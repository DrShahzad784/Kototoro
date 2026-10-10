import Combine
import KototoroShared
import SwiftUI

@MainActor
final class SourcesModel: ObservableObject {
    @Published private(set) var sources: [IosSource] = []
    @Published private(set) var content: [IosSourceContent] = []
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    let runtime = IosSources(transport: NativeSourceTransport())
    let previewLabel: String? = {
        guard let url = Bundle.main.resourceURL?.appendingPathComponent("jvm/sources/preview.json"),
              let data = try? Data(contentsOf: url),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return nil }
        return value["label"]
    }()

    deinit { runtime.close() }

    func load() async {
        guard !isLoading else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do { sources = try await runtime.sources() }
        catch { self.error = error.localizedDescription }
    }

    func browse(_ source: IosSource) async {
        guard !isLoading else { return }
        isLoading = true
        error = nil
        content = []
        defer { isLoading = false }
        do { content = try await runtime.list(sourceName: source.name, offset: 0) }
        catch { self.error = error.localizedDescription }
    }
}

struct SourcesView: View {
    @StateObject private var model = SourcesModel()

    var body: some View {
        NavigationStack {
            if NativeSourceTransport.isAvailable {
                List {
                    if model.isLoading { ProgressView("正在读取扩展…") }
                    if let error = model.error {
                        Text(error).foregroundStyle(.red).accessibilityIdentifier("sources.error")
                        Button("重试") { Task { await model.load() } }
                            .disabled(model.isLoading)
                    }
                    Section {
                        ForEach(model.sources, id: \.name) { source in
                            Button {
                                Task { await model.browse(source) }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(source.name)
                                    Text(source.locale).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .disabled(model.isLoading)
                            .accessibilityIdentifier("source.\(source.name)")
                        }
                    } header: {
                        Text("已加载的源")
                    } footer: {
                        if let label = model.previewLabel { Text(label) }
                    }
                    if !model.content.isEmpty {
                        Section("浏览结果") {
                            ForEach(model.content, id: \.id) { item in
                                NavigationLink {
                                    SourceDetailsView(runtime: model.runtime, content: item)
                                } label: {
                                    Text(item.title).accessibilityIdentifier("source.content.\(item.id)")
                                }
                            }
                        }
                    }
                }
                .task { await model.load() }
                .navigationTitle("浏览")
            } else {
                ContentUnavailableView("扩展支持正在接入", systemImage: "puzzlepiece.extension",
                    description: Text("Mihon 扩展将在接入源运行时后显示于此。"))
                    .navigationTitle("浏览")
            }
        }
    }
}
