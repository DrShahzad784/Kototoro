import Combine
import KototoroShared
import SwiftUI

/// Owns the routed source runtime for the app's lifetime: Apple CMS sites run natively, extensions use the JVM.
@MainActor
final class SourcesModel: ObservableObject {
    @Published private(set) var sources: [IosSource] = []
    @Published private(set) var extensionError: String?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published private(set) var runtime: IosSources?
    nonisolated(unsafe) private var ownedRuntime: IosSources?
    private var videoSources: IosVideoSources?
    private var library: IosLibrary?
    let previewLabel: String? = {
        guard let url = Bundle.main.resourceURL?.appendingPathComponent("jvm/sources/preview.json"),
              let data = try? Data(contentsOf: url),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return nil }
        return value["label"]
    }()

    var videoSourceList: [IosSource] { sources.filter { $0.kind == "video" } }
    var extensionSourceList: [IosSource] { sources.filter { $0.kind == "extension" } }

    static let ecosystems: [(id: String, title: String, icon: String)] = [
        ("MIHON", "Mihon 漫画", "book.closed"),
        ("TSUNDOKU", "Tsundoku 小说", "text.book.closed"),
        ("ANIYOMI", "Aniyomi 动漫", "play.rectangle"),
        ("JAR", "解析器插件（kototoro / kotatsu / UMA）", "shippingbox"),
    ]

    /// Extension sources of one ecosystem, filtered by the search text and the adult-content setting.
    func extensions(_ ecosystem: String, query: String, showAdult: Bool) -> [IosSource] {
        let text = query.trimmingCharacters(in: .whitespaces)
        return extensionSourceList.filter { source in
            source.ecosystem == ecosystem && (showAdult || !source.isAdult) &&
                (text.isEmpty || source.title.localizedCaseInsensitiveContains(text) ||
                 source.name.localizedCaseInsensitiveContains(text))
        }
    }

    deinit { ownedRuntime?.close() }

    func attach(_ library: IosLibrary) {
        guard runtime == nil else { return }
        self.library = library
        let store = IosVideoSources(library: library)
        videoSources = store
        runtime = IosSources(transport: NativeSourceTransport.isAvailable ? NativeSourceTransport() : nil,
                             videoSources: store)
        ownedRuntime = runtime
    }

    func load() async {
        guard let runtime, !isLoading else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let catalog = try await runtime.catalog()
            sources = catalog.sources
            library?.updateSources(sources: catalog.sources)
            extensionError = catalog.extensionError
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Returns a short summary for the import sheet, or throws with the reason nothing was imported.
    func importSource(url: String, title: String) async throws -> String {
        guard let videoSources, let runtime else { throw CancellationError() }
        let summary = try await videoSources.importFrom(url: url.trimmingCharacters(in: .whitespacesAndNewlines),
                                                       title: title)
        try await runtime.sourcesChanged()
        await load()
        var text = "已导入 \(summary.imported) 个站点"
        if summary.skippedSpiderSites > 0 { text += "，跳过 \(summary.skippedSpiderSites) 个需要爬虫运行时的站点" }
        return text
    }

    func delete(_ source: IosSource) async {
        guard let videoSources, let runtime else { return }
        do {
            try await videoSources.delete(id: source.name)
            try await runtime.sourcesChanged()
            sources.removeAll { $0.name == source.name }
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct SourcesView: View {
    @ObservedObject var library: LibraryModel
    @ObservedObject var model: SourcesModel
    @State private var isAdding = false
    @State private var query = ""
    @AppStorage("sources.showAdult") private var showAdult = false

    var body: some View {
        NavigationStack {
            List {
                if let error = model.error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("sources.error")
                        Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                    }
                }
                Section {
                    if model.videoSourceList.isEmpty && !model.isLoading {
                        Button {
                            isAdding = true
                        } label: {
                            Label("添加苹果 CMS / TVBox 源", systemImage: "plus.circle")
                        }
                        .accessibilityIdentifier("sources.addVideo.inline")
                    }
                    ForEach(model.videoSourceList.filter { matches($0) }, id: \.name) { source in
                        sourceLink(source, systemImage: "play.tv")
                            .swipeActions {
                                Button("删除", systemImage: "trash", role: .destructive) {
                                    Task { await model.delete(source) }
                                }
                            }
                    }
                } header: {
                    Text("影视")
                } footer: {
                    Text("支持苹果 CMS（JSON/XML）接口，以及 TVBox 配置中的 type 0/1 站点。")
                }
                if NativeSourceTransport.isAvailable {
                    Section {
                        if let error = model.extensionError {
                            Text(error).foregroundStyle(.red).accessibilityIdentifier("sources.extensionError")
                        }
                    } header: {
                        Text("扩展")
                    } footer: {
                        if let label = model.previewLabel { Text(label) }
                    }
                    ForEach(SourcesModel.ecosystems, id: \.id) { ecosystem in
                        let sources = model.extensions(ecosystem.id, query: query, showAdult: showAdult)
                        if !sources.isEmpty {
                            Section("\(ecosystem.title) · \(sources.count)") {
                                ForEach(sources, id: \.name) { source in
                                    sourceLink(source, systemImage: ecosystem.icon)
                                }
                            }
                        }
                    }
                }
            }
            .overlay {
                if model.isLoading && model.sources.isEmpty { ProgressView("正在读取源…") }
            }
            .navigationTitle("浏览")
            .navigationDestination(for: SourceRoute.self) { route in
                if let runtime = model.runtime {
                    SourceBrowseView(runtime: runtime, source: route.source)
                }
            }
            .searchable(text: $query, prompt: "搜索源")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink { RepositoriesView() } label: {
                        Label("仓库与扩展", systemImage: "shippingbox")
                    }.accessibilityIdentifier("sources.repositories")
                    Menu("显示", systemImage: "line.3.horizontal.decrease") {
                        Toggle("显示成人内容源", isOn: $showAdult)
                    }
                    .accessibilityIdentifier("sources.filter")
                    Button("添加源", systemImage: "plus") { isAdding = true }
                        .disabled(model.runtime == nil)
                        .accessibilityIdentifier("sources.addVideo")
                }
            }
            .refreshable { await model.load() }
            .sheet(isPresented: $isAdding) {
                AddVideoSourceView { url, title in try await model.importSource(url: url, title: title) }
            }
            .task(id: library.library == nil) {
                guard let database = library.library else { return }
                model.attach(database)
                await model.load()
            }
        }
    }

    private func matches(_ source: IosSource) -> Bool {
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || source.title.localizedCaseInsensitiveContains(text) ||
            source.name.localizedCaseInsensitiveContains(text)
    }

    private func sourceLink(_ source: IosSource, systemImage: String) -> some View {
        NavigationLink(value: SourceRoute(source: source)) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.title)
                    if source.kind == "extension" {
                        Text([source.locale, Self.kindName(source.mediaKind)].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            } icon: {
                Image(systemName: systemImage)
            }
        }
        .accessibilityIdentifier("source.\(source.name)")
    }
}

extension SourcesView {
    static func kindName(_ mediaKind: String) -> String {
        switch mediaKind {
        case "VIDEO": "视频"
        case "NOVEL": "小说"
        default: "漫画"
        }
    }
}

struct SourceRoute: Hashable {
    let source: IosSource
    static func == (lhs: SourceRoute, rhs: SourceRoute) -> Bool { lhs.source.name == rhs.source.name }
    func hash(into hasher: inout Hasher) { hasher.combine(source.name) }
}

/// Import from a TVBox configuration / multi-repository URL or a MacCMS API URL.
struct AddVideoSourceView: View {
    let onImport: (String, String) async throws -> String
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var title = ""
    @State private var isImporting = false
    @State private var error: String?
    @State private var result: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…/api.php/provide/vod/", text: $url)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("addVideo.url")
                    TextField("名称（可选）", text: $title)
                        .accessibilityIdentifier("addVideo.title")
                } footer: {
                    Text("粘贴苹果 CMS 接口地址，或 TVBox 配置/多仓地址。需要爬虫运行时的站点（type 3/4）会被跳过。")
                }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                        .accessibilityIdentifier("addVideo.error")
                }
                if let result {
                    Section { Label(result, systemImage: "checkmark.circle").foregroundStyle(.green) }
                        .accessibilityIdentifier("addVideo.result")
                }
            }
            .navigationTitle("添加影视源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(result == nil ? "取消" : "完成") { dismiss() }
                        .accessibilityIdentifier("addVideo.close")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("导入") {
                        Task {
                            isImporting = true
                            error = nil
                            defer { isImporting = false }
                            do { result = try await onImport(url, title) }
                            catch { self.error = error.localizedDescription }
                        }
                    }
                    .disabled(url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isImporting)
                    .accessibilityIdentifier("addVideo.import")
                }
            }
            .overlay { if isImporting { ProgressView() } }
            .interactiveDismissDisabled(isImporting)
        }
        .presentationDetents([.medium, .large])
    }
}
