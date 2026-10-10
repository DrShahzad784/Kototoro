import Combine
import CryptoKit
import Foundation
import KototoroShared
import SwiftUI
import UniformTypeIdentifiers

struct InstalledExtension: Decodable {
    let packageName: String
    let versionCode: Int64
    let sha256: String
    let ecosystem: String
    let bundled: Bool
}
private struct ManagementResult: Decodable { let managementVersion: Int; let installed: [InstalledExtension] }

@MainActor
final class RepositoriesModel: ObservableObject {
    @Published private(set) var saved: [IosRepository] = []
    @Published private(set) var catalog: IosRepositoryCatalog?
    @Published private(set) var error: String?
    @Published private(set) var status: String?
    @Published private(set) var busy = false
    @Published private(set) var installed: [InstalledExtension] = []
    private var store: IosRepositories?
    private weak var sources: SourcesModel?

    func attach(library: IosLibrary?, sources: SourcesModel) { if let library { store = IosRepositories(library: library) }; self.sources = sources }
    func reportError(_ message: String) { error = message }
    func load() async {
        do {
            saved = try await store?.saved() ?? []
            if NativeSourceTransport.isAvailable { try await manage(["managementVersion": 1, "operation": "list"]) }
        } catch { self.error = error.localizedDescription }
    }
    func importLink(_ text: String, kind: String) async {
        guard !busy else { return }
        busy = true; error = nil; status = nil
        defer { busy = false }
        do {
            let link = try RepositoryLink.parse(text, defaultKind: kind)
            if link.kind == "TVBOX" {
                status = try await sources?.importSource(url: link.url.absoluteString, title: "")
                return
            }
            let fetched = try await fetch(link)
            try await store?.save(catalog: fetched, fingerprint: fingerprint(fetched))
            catalog = fetched; saved = try await store?.saved() ?? []
            status = "已添加 \(fetched.title)，共 \(fetched.extensions.count) 个扩展"
        } catch { self.error = error.localizedDescription }
    }
    func open(_ repository: IosRepository) async { await importLink(repository.url, kind: repository.kind) }
    func remove(_ repository: IosRepository) async {
        do { try await store?.remove(repository: repository); saved = try await store?.saved() ?? [] }
        catch { self.error = error.localizedDescription }
    }
    func state(_ extensionItem: IosExtension) -> String {
        guard let installed = installed.first(where: { $0.packageName == extensionItem.id }) else { return "安装" }
        return installed.versionCode < extensionItem.versionCode ? "更新" : "已安装"
    }
    func install(_ extensionItem: IosExtension) async {
        guard !busy, let catalog else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            guard NativeSourceTransport.isAvailable else { throw NSError(domain: "extensions", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "此构建未包含扩展运行时；请使用包含 JVM 的构建安装扩展"]) }
            let base = URL(string: catalog.resourceBaseUrl)!
            guard let url = URL(string: extensionItem.url, relativeTo: base)?.absoluteURL,
                  ["http", "https"].contains(url.scheme ?? "") else { throw URLError(.badURL) }
            status = "正在下载 \(extensionItem.title)…"
            // URLSession downloads to disk; do not buffer large APKs on the UI thread.
            let (temp, response) = try await URLSession.shared.download(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
            let size = try temp.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0 && size <= 128 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
            let incoming = try NativeSourceTransport.dataDirectory.appendingPathComponent("incoming", isDirectory: true)
            try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
            let file = incoming.appendingPathComponent(UUID().uuidString + ".archive")
            try FileManager.default.moveItem(at: temp, to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            let hash = try await Task.detached { () throws -> String in
                SHA256.hash(data: try Data(contentsOf: file, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
            }.value
            status = "正在转换、校验并安装 \(extensionItem.title)…"
            try await manage(["managementVersion": 1, "operation": "install", "path": file.path,
                              "sha256": hash, "packageName": extensionItem.id, "versionCode": extensionItem.versionCode,
                              "ecosystem": catalog.kind])
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "\(extensionItem.title) 已安装"
        } catch { self.error = error.localizedDescription }
    }
    func installFile(_ url: URL) async {
        guard !busy else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            guard NativeSourceTransport.isAvailable else { throw NSError(domain: "extensions", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "此构建未包含扩展运行时；请使用包含 JVM 的构建安装扩展"]) }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0 && size <= 128 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
            let incoming = try NativeSourceTransport.dataDirectory.appendingPathComponent("incoming", isDirectory: true)
            try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
            let file = incoming.appendingPathComponent(UUID().uuidString + ".archive")
            try FileManager.default.copyItem(at: url, to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            let hash = try await Task.detached { () throws -> String in
                SHA256.hash(data: try Data(contentsOf: file, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
            }.value
            let stem = url.deletingPathExtension().lastPathComponent
                .replacingOccurrences(of: "[^A-Za-z0-9_.-]", with: "_", options: .regularExpression)
            let fallbackName = stem.isEmpty ? "local.plugin" : String(stem.prefix(200))
            status = "正在转换、校验并安装 \(url.lastPathComponent)…"
            try await manage(["managementVersion": 1, "operation": "install", "path": file.path,
                              "sha256": hash, "packageName": fallbackName, "versionCode": 0, "ecosystem": ""])
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "\(url.lastPathComponent) 已安装"
        } catch { self.error = error.localizedDescription }
    }
    func uninstall(_ item: InstalledExtension) async {
        guard !busy, !item.bundled else { return }
        busy = true; defer { busy = false }
        do {
            try await manage(["managementVersion": 1, "operation": "uninstall",
                              "packageName": item.packageName, "ecosystem": item.ecosystem])
            try await sources?.runtime?.sourcesChanged(); await sources?.load()
            status = "已卸载扩展"
        } catch { self.error = error.localizedDescription }
    }
    private func manage(_ request: [String: Any]) async throws {
        guard let runtime = sources?.runtime else { throw CancellationError() }
        let json = String(decoding: try JSONSerialization.data(withJSONObject: request), as: UTF8.self)
        let reply = try await runtime.manage(requestJson: json)
        let result = try JSONDecoder().decode(ManagementResult.self, from: Data(reply.utf8))
        guard result.managementVersion == 1 else { throw URLError(.cannotParseResponse) }
        installed = result.installed
    }

    private func fetch(_ link: RepositoryLink) async throws -> IosRepositoryCatalog {
        guard let store else { throw CancellationError() }
        let path = link.url.pathComponents.filter { $0 != "/" }
        if link.kind == "JAR" && link.url.host == "github.com" && path.count == 2 {
            let api = URL(string: "https://api.github.com/repos/\(path[0])/\(path[1])/releases/latest")!
            let (bytes, _) = try await indexData(api)
            return try store.release(json: String(decoding: bytes, as: UTF8.self), url: link.url.absoluteString)
        }
        let name = link.url.lastPathComponent.lowercased()
        let candidates = name.hasSuffix(".pb") || name.hasSuffix(".json") ? [link.url] :
            ["index.pb", "repo.json", "index.json", "index.min.json"].map { link.url.appendingPathComponent($0) }
        var last: Error = URLError(.fileDoesNotExist)
        for url in candidates {
            do {
                let (bytes, actual) = try await indexData(url)
                var result = try store.decode(bytes: kotlinBytes(bytes), protobuf: url.pathExtension.lowercased() == "pb",
                                              url: actual.absoluteString, kind: link.kind)
                if let external = result.externalList, let target = URL(string: external, relativeTo: actual)?.absoluteURL {
                    let (list, listURL) = try await indexData(target)
                    result = try store.external(catalog: result, bytes: kotlinBytes(list), protobuf: target.pathExtension.lowercased() == "pb",
                                                url: listURL.absoluteString)
                }
                return result
            } catch let error as RepositoryHTTPError where error.status == 404 { last = error }
        }
        throw last
    }
    private func indexData(_ url: URL) async throws -> (Data, URL) {
        guard ["https", "http"].contains(url.scheme ?? ""), url.user == nil, url.password == nil else { throw URLError(.badURL) }
        var request = URLRequest(url: url); request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else { throw RepositoryHTTPError(status: http.statusCode) }
        guard data.count <= 20 * 1024 * 1024, let actual = response.url else { throw URLError(.dataLengthExceedsMaximum) }
        return (data, actual)
    }
    private func fingerprint(_ catalog: IosRepositoryCatalog) -> String {
        SHA256.hash(data: Data((catalog.signingKey.isEmpty ? catalog.url : catalog.signingKey).utf8))
            .map { String(format: "%02x", $0) }.joined()
    }
    private func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return bytes
    }
}
private struct RepositoryHTTPError: LocalizedError {
    let status: Int
    var errorDescription: String? { "仓库返回 HTTP \(status)" }
}

struct RepositoriesView: View {
    @StateObject private var model = RepositoriesModel()
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var address = ""
    @State private var kind = "MIHON"
    @State private var query = ""
    @State private var importingFile = false
    @AppStorage("sources.showAdult") private var showAdult = false
    let incoming: String?

    init(incoming: String? = nil) { self.incoming = incoming }
    var body: some View {
        List {
            Section("添加仓库") {
                TextField("仓库地址或 kototoro:// 添加链接", text: $address)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("repository.address")
                Picker("类型", selection: $kind) {
                    ForEach(RepositoryLink.kinds, id: \.self) { Text($0).tag($0) }
                }
                Button("导入仓库") { Task { await model.importLink(address, kind: kind) } }
                    .disabled(model.busy || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("repository.import")
                Button("从文件安装 APK / JAR…") { importingFile = true }
                    .disabled(model.busy)
                    .accessibilityIdentifier("repository.importFile")
                Link("在 Repo Hub 查找仓库", destination: URL(string: "https://skepsun.github.io/kototoro-repo-hub/")!)
            }
            if model.busy { ProgressView(model.status ?? "正在读取…") }
            if let error = model.error { Text(error).foregroundStyle(.red).accessibilityIdentifier("repository.error") }
            if !model.busy, let status = model.status { Text(status).foregroundStyle(.secondary).accessibilityIdentifier("repository.status") }
            if let catalog = model.catalog {
                Section(catalog.title) {
                    TextField("搜索扩展", text: $query).accessibilityIdentifier("repository.search")
                    ForEach(catalog.extensions.filter {
                        (showAdult || !$0.isAdult) && (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) ||
                                                     $0.languages.contains { $0.localizedCaseInsensitiveContains(query) })
                    }, id: \.id) { item in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(item.title)
                                Text(([item.version] + item.languages).joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.state(item) == "已安装" {
                                Menu("已安装") { Button("重新安装") { Task { await model.install(item) } } }
                                    .buttonStyle(.glass).disabled(model.busy)
                                    .accessibilityIdentifier("extension.install.\(item.id)")
                            } else {
                                Button(model.state(item)) { Task { await model.install(item) } }
                                    .buttonStyle(.glass).disabled(model.busy)
                                    .accessibilityIdentifier("extension.install.\(item.id)")
                            }
                        }
                    }
                }
            }
            Section("已添加的仓库") {
                ForEach(model.saved, id: \.url) { repository in
                    Button { Task { await model.open(repository) } } label: {
                        VStack(alignment: .leading) {
                            Text(repository.title)
                            Text(repository.kind + " · " + repository.url).font(.caption).foregroundStyle(.secondary)
                        }
                    }.swipeActions {
                        Button("移除仓库", role: .destructive) { Task { await model.remove(repository) } }
                    }
                }
            }
            if !model.installed.isEmpty {
                Section("已安装扩展") {
                    ForEach(model.installed, id: \.packageName) { item in
                        VStack(alignment: .leading) {
                            Text(item.packageName).font(.subheadline)
                            Text(item.ecosystem + (item.bundled ? " · 随构建提供" : " · 已安装"))
                                .font(.caption).foregroundStyle(.secondary)
                        }.swipeActions {
                            if !item.bundled {
                                Button("卸载", role: .destructive) { Task { await model.uninstall(item) } }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("仓库与扩展")
        .fileImporter(isPresented: $importingFile,
                      allowedContentTypes: [UTType(filenameExtension: "apk") ?? .data,
                                            UTType(filenameExtension: "jar") ?? .archive, .archive, .data]) { result in
            switch result {
            case .success(let url): Task { await model.installFile(url) }
            case .failure(let error): model.reportError(error.localizedDescription)
            }
        }
        .task(id: "\(library.library == nil)-\(incoming ?? "")") {
            guard library.library != nil else { return }
            model.attach(library: library.library, sources: sources)
            await model.load()
            if let incoming { address = incoming; await model.importLink(incoming, kind: kind) }
        }
        .refreshable { await model.load() }
    }
}
