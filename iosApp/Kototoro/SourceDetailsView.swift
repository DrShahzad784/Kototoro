import Combine
import KototoroShared
import SwiftUI

/// Both child screens borrow the browsing session; leaving a screen must not close the process VM.
@MainActor
final class SourceDetailsModel: ObservableObject {
    @Published private(set) var details: IosSourceContent?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published private(set) var cover: UIImage?
    @Published private(set) var coverError: String?
    let runtime: IosSources
    let content: IosSourceContent
    private var detailsRequest: UUID?

    init(runtime: IosSources, content: IosSourceContent) {
        self.runtime = runtime
        self.content = content
    }

    func load() async {
        guard details == nil else { return }
        let request = UUID()
        detailsRequest = request
        isLoading = true
        error = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let result = try await runtime.details(content: content)
            try Task.checkCancellation()
            if detailsRequest == request { details = result }
        } catch {
            if !Task.isCancelled, detailsRequest == request { self.error = error.localizedDescription }
        }
    }

    func loadCover() async {
        guard let details, details.hasCover, cover == nil else { return }
        coverError = nil
        do {
            let artifact = try await runtime.cover(content: details)
            cover = try await artifact.decodedImage()
        } catch {
            if !Task.isCancelled { coverError = error.localizedDescription }
        }
    }
}

struct SourceDetailsView: View {
    @StateObject private var model: SourceDetailsModel

    init(runtime: IosSources, content: IosSourceContent) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: content))
    }

    var body: some View {
        List {
            if model.isLoading { ProgressView("正在读取详情…") }
            if let error = model.error {
                Text(error).foregroundStyle(.red).accessibilityIdentifier("details.error")
                Button("重试") { Task { await model.load() } }
                    .disabled(model.isLoading)
            }
            if let details = model.details {
                Section {
                    if let cover = model.cover {
                        Image(uiImage: cover).resizable().scaledToFit().frame(maxHeight: 240)
                            .accessibilityLabel("漫画封面").accessibilityIdentifier("details.cover")
                    }
                    Text(details.title).font(.title2).accessibilityIdentifier("details.title")
                    if !details.authors.isEmpty { Text(details.authors.joined(separator: "、")) }
                    if !details.tags.isEmpty {
                        Text(details.tags.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                    if let error = model.coverError {
                        Text("封面加载失败：\(error)").font(.caption).foregroundStyle(.secondary)
                        Button("重试封面") { Task { await model.loadCover() } }
                    }
                }
                .task { await model.loadCover() }
                Section("章节（\(details.chapters.count)）") {
                    if details.chapters.isEmpty { Text("暂无章节").accessibilityIdentifier("details.empty") }
                    ForEach(details.chapters, id: \.id) { chapter in
                        NavigationLink {
                            SourceReaderView(runtime: model.runtime, content: details, chapter: chapter)
                        } label: {
                            Text(chapter.title)
                        }
                        .accessibilityIdentifier("chapter.\(chapter.id)")
                    }
                }
            }
        }
        .navigationTitle(model.content.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }
}
