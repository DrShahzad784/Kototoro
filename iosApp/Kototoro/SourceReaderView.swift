import Combine
import KototoroShared
import SwiftUI

@MainActor
final class SourceReaderModel: ObservableObject {
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var pages: [IosSourcePage] = []
    @Published private(set) var index = 0
    @Published private(set) var image: UIImage?
    @Published private(set) var error: String?
    @Published private(set) var isLoadingPages = false
    @Published private(set) var isLoadingImage = false
    private let runtime: IosSources
    private let content: IosSourceContent
    @Published private(set) var loaded = false
    private var pagesRequest: UUID?
    private var imageRequest: UUID?

    var selectedPage: IosSourcePage? { pages.indices.contains(index) ? pages[index] : nil }
    var previousChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var nextChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
    }

    func load() async {
        guard !loaded else { return }
        let request = UUID()
        pagesRequest = request
        let selectedChapter = chapter
        isLoadingPages = true
        error = nil
        defer { if pagesRequest == request { isLoadingPages = false } }
        do {
            let result = try await runtime.pages(chapter: selectedChapter)
            try Task.checkCancellation()
            guard pagesRequest == request, chapter.id == selectedChapter.id else { return }
            pages = result
            loaded = true
        } catch {
            if !Task.isCancelled, pagesRequest == request { self.error = error.localizedDescription }
        }
    }

    func loadImage() async {
        guard let page = selectedPage, image == nil else { return }
        let request = UUID()
        imageRequest = request
        isLoadingImage = true
        error = nil
        defer { if imageRequest == request { isLoadingImage = false } }
        do {
            let artifact = try await runtime.image(page: page)
            let decoded = try await artifact.decodedImage()
            if imageRequest == request, selectedPage?.id == page.id { image = decoded }
        } catch {
            if !Task.isCancelled, imageRequest == request, selectedPage?.id == page.id {
                self.error = error.localizedDescription
            }
        }
    }

    func movePage(forward: Bool) {
        let target = index + (forward ? 1 : -1)
        guard pages.indices.contains(target), !isLoadingImage else { return }
        image = nil
        index = target
    }

    func moveChapter(_ chapter: IosSourceChapter) async {
        guard !isLoadingPages, !isLoadingImage else { return }
        self.chapter = chapter
        image = nil
        pages = []
        index = 0
        loaded = false
        await load()
    }

    func retry() async {
        if loaded { await loadImage() } else { await load() }
    }
}

struct SourceReaderView: View {
    @StateObject private var model: SourceReaderModel

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: SourceReaderModel(runtime: runtime, content: content, chapter: chapter))
    }

    var body: some View {
        VStack(spacing: 12) {
            if model.isLoadingPages || model.isLoadingImage { ProgressView("正在读取漫画页…") }
            if let error = model.error {
                Text(error).foregroundStyle(.red).accessibilityIdentifier("reader.error")
                Button("重试") { Task { await model.retry() } }
                    .disabled(model.isLoadingPages || model.isLoadingImage)
            }
            if let image = model.image {
                ScrollView {
                    Image(uiImage: image).resizable().scaledToFit()
                        .accessibilityLabel("漫画页图片").accessibilityIdentifier("reader.image")
                }
            } else if model.loaded && model.error == nil && model.pages.isEmpty {
                ContentUnavailableView("暂无漫画页", systemImage: "doc")
            }
            Spacer(minLength: 0)
            if !model.pages.isEmpty {
                HStack {
                    Button("上一页") { model.movePage(forward: false) }
                        .disabled(model.index == 0 || model.isLoadingImage)
                        .accessibilityIdentifier("reader.previousPage")
                    Spacer()
                    Text("第 \(model.index + 1) / \(model.pages.count) 页")
                        .accessibilityIdentifier("reader.position")
                    Spacer()
                    Button("下一页") { model.movePage(forward: true) }
                        .disabled(model.index + 1 >= model.pages.count || model.isLoadingImage)
                        .accessibilityIdentifier("reader.nextPage")
                }
            }
            HStack {
                if let chapter = model.previousChapter {
                    Button("上一章") { Task { await model.moveChapter(chapter) } }
                        .accessibilityIdentifier("reader.previousChapter")
                }
                Spacer()
                if let chapter = model.nextChapter {
                    Button("下一章") { Task { await model.moveChapter(chapter) } }
                        .accessibilityIdentifier("reader.nextChapter")
                }
            }
            .disabled(model.isLoadingPages || model.isLoadingImage)
        }
        .padding()
        .navigationTitle(model.chapter.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task { await model.load() }
        .task(id: model.selectedPage?.id) { await model.loadImage() }
    }
}
