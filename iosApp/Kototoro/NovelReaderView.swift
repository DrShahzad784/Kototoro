import Combine
import ImageIO
import KototoroShared
import SwiftUI

/// One block of a chapter: a paragraph of text or an illustration.
enum NovelBlock: Hashable {
    case text(String)
    case image(URL)
}

/// Turns source chapter HTML into readable blocks without a web view: paragraphs and line breaks become blocks,
/// `<img>` becomes an illustration, remaining tags are dropped and entities decoded.
enum NovelText {
    static func blocks(from html: String) -> [NovelBlock] {
        var blocks: [NovelBlock] = []
        let marked = html
            .replacingOccurrences(of: "(?is)<(script|style)[^>]*>.*?</\\1>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "(?i)<img[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", with: "\n\u{1}$1\n",
                                  options: .regularExpression)
            .replacingOccurrences(of: "(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for line in marked.components(separatedBy: "\n") {
            if line.hasPrefix("\u{1}") {
                if let url = URL(string: decode(String(line.dropFirst()))) { blocks.append(.image(url)) }
                continue
            }
            let text = decode(line).trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { blocks.append(.text(text)) }
        }
        return blocks
    }

    private static func decode(_ text: String) -> String {
        var result = text
        for (entity, value) in [("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"),
                                ("&apos;", "'"), ("&hellip;", "…"), ("&mdash;", "—"), ("&ldquo;", "“"),
                                ("&rdquo;", "”"), ("&lsquo;", "‘"), ("&rsquo;", "’"), ("&amp;", "&")] {
            result = result.replacingOccurrences(of: entity, with: value)
        }
        // Numeric entities (&#12354; / &#x3042;).
        let pattern = try! NSRegularExpression(pattern: "&#(x?)([0-9a-fA-F]+);")
        for match in pattern.matches(in: result, range: NSRange(result.startIndex..., in: result)).reversed() {
            guard let whole = Range(match.range, in: result), let digits = Range(match.range(at: 2), in: result) else { continue }
            let hex = match.range(at: 1).length > 0
            if let code = UInt32(result[digits], radix: hex ? 16 : 10), let scalar = Unicode.Scalar(code) {
                result.replaceSubrange(whole, with: String(Character(scalar)))
            }
        }
        return result
    }
}

@MainActor
final class NovelReaderModel: ObservableObject {
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var blocks: [NovelBlock] = []
    @Published private(set) var bookmarks: [IosBookmark] = []
    private(set) var text: IosChapterText?
    /// Downloaded pictures of the current chapter, by their original URL.
    private(set) var localImages: [String: URL] = [:]
    /// The chapter on screen was read from its download.
    @Published private(set) var isOffline = false
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    let runtime: IosSources
    let content: IosSourceContent
    private var request: UUID?
    var library: IosLibrary?
    @Published private(set) var resumeIndex = 0
    @Published private(set) var visibleIndex = 0
    private var visibleIndices: Set<Int> = []
    private var saveTask: Task<Void, Never>?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
    }

    var previous: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var next: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }
    var isBookmarked: Bool { bookmarks.contains { $0.chapterId == chapter.id && Int($0.index) == visibleIndex } }

    func load() async {
        let request = UUID()
        self.request = request
        let selected = chapter
        isLoading = true
        visibleIndices = []
        error = nil; challenge = nil
        defer { if self.request == request { isLoading = false } }
        do {
            // A downloaded chapter reads offline; a damaged download falls back to the source.
            let offline = try? NovelDownloads.offline(content: content, chapter: selected)
            let text = if let offline { offline.0 } else { try await runtime.chapterText(chapter: selected) }
            try Task.checkCancellation()
            guard self.request == request else { return }
            // Parsing large chapters off the main actor keeps scrolling responsive.
            self.text = text
            localImages = offline?.1 ?? [:]
            isOffline = offline != nil
            let html = text?.html ?? ""
            blocks = await Task.detached(priority: .userInitiated) { NovelText.blocks(from: html) }.value
            guard self.request == request else { return }
            if let library, let position = try await library.position(content: content), position.chapterId == selected.id {
                resumeIndex = min(max(0, Int(position.index)), max(0, blocks.count - 1))
            } else { resumeIndex = 0 }
            visibleIndex = resumeIndex
            await persist()
            await refreshBookmarks()
        } catch {
            if !Task.isCancelled, self.request == request {
                self.error = SourceErrorText.describe(error)
                challenge = SourceErrorText.challenge(error)
            }
        }
    }

    func move(to target: IosSourceChapter) async {
        await persist()
        chapter = target
        blocks = []
        await load()
    }

    func jump(to target: Int) async {
        guard blocks.indices.contains(target) else { return }
        resumeIndex = target
        visibleIndex = target
        await persist()
    }

    func visible(_ index: Int, isVisible: Bool) {
        guard blocks.indices.contains(index) else { return }
        if isVisible { visibleIndices.insert(index) } else { visibleIndices.remove(index) }
        guard !isLoading, let first = visibleIndices.min() else { return }
        visibleIndex = first
        saveTask?.cancel()
        saveTask = Task { try? await Task.sleep(for: .milliseconds(600)); if !Task.isCancelled { await persist() } }
    }

    func persist() async {
        guard !blocks.isEmpty, let library else { return }
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(visibleIndex),
                                              count: Int32(blocks.count), scroll: 0) }
        catch { self.error = error.localizedDescription }
    }

    func refreshBookmarks() async {
        do { bookmarks = try await library?.bookmarks(content: content) ?? [] }
        catch { self.error = error.localizedDescription }
    }

    func toggleBookmark() async {
        guard let library, blocks.indices.contains(visibleIndex) else { return }
        let preview: String
        switch blocks[visibleIndex] {
        case .text(let value): preview = value
        case .image(let url): preview = url.lastPathComponent.isEmpty ? chapter.title : url.lastPathComponent
        }
        do {
            try await library.toggleNovelBookmark(content: content, chapter: chapter,
                                                  index: Int32(visibleIndex), count: Int32(blocks.count), preview: preview)
            await refreshBookmarks()
        } catch { self.error = error.localizedDescription }
    }

    func removeBookmark(_ bookmark: IosBookmark) async {
        do { try await library?.removeBookmark(content: content, bookmark: bookmark); await refreshBookmarks() }
        catch { self.error = error.localizedDescription }
    }
}

struct NovelReaderView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue
    @StateObject private var model: NovelReaderModel
    @AppStorage("novel.fontSize") private var fontSize = 18.0
    @AppStorage("novel.lineSpacing") private var lineSpacing = 8.0
    @State private var showingSheet = false
    @State private var sheetTab = 0
    @State private var showingDownloads = false
    @State private var downloadError: String?

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: NovelReaderModel(runtime: runtime, content: content, chapter: chapter))
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: lineSpacing * 1.5) {
                    Text(model.chapter.title)
                        .font(.title2.weight(.semibold))
                        .padding(.bottom, model.isOffline ? 0 : 8)
                        .id("top")
                    if model.isOffline {
                        Label("已下载 · 离线阅读", systemImage: "arrow.down.circle.fill")
                            .font(.caption).foregroundStyle(.secondary)
                            .padding(.bottom, 8)
                            .accessibilityIdentifier("novel.offline")
                    }
                    if model.isLoading { ProgressView().frame(maxWidth: .infinity).padding(.top, 40) }
                    if let error = model.error {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                            .accessibilityIdentifier("novel.error")
                        if let challenge = model.challenge {
                            ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                                  sourceName: model.content.sourceName) { await model.load() }
                        }
                        Button("重试") { Task { await model.load() } }.buttonStyle(.glass)
                    }
                    if !model.isLoading && model.error == nil && model.blocks.isEmpty {
                        ContentUnavailableView("本章没有正文", systemImage: "text.page.slash")
                    }
                    ForEach(Array(model.blocks.enumerated()), id: \.offset) { index, block in
                        Group {
                        switch block {
                        case .text(let text):
                            Text(text)
                                .font(.system(size: fontSize))
                                .lineSpacing(lineSpacing)
                                .textSelection(.enabled)
                                .accessibilityIdentifier(index == 0 ? "novel.firstParagraph" : "novel.paragraph")
                        case .image(let url):
                            HeaderImage(url: model.localImages[url.absoluteString] ?? url,
                                        headers: model.text?.headers(imageUrl: url.absoluteString) ?? [:])
                                .frame(maxWidth: .infinity)
                        }
                        }
                        .id(index)
                        .onScrollVisibilityChange(threshold: 0.5) { visible in
                            model.visible(index, isVisible: visible)
                        }
                    }
                    if !model.blocks.isEmpty { chapterButtons(proxy) }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .navigationTitle(model.content.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .tabBar)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button(model.isBookmarked ? "取消书签" : "添加书签",
                           systemImage: model.isBookmarked ? "bookmark.fill" : "bookmark") {
                        Task { await model.toggleBookmark() }
                    }
                    .disabled(model.blocks.isEmpty)
                    .accessibilityIdentifier("novel.bookmark")
                    Button("目录与书签", systemImage: "list.bullet") { showingSheet = true }
                        .accessibilityIdentifier("novel.chapters")
                    // Imported files are already on the device.
                    if !model.runtime.isLocal(content: model.content) { downloadMenu }
                    Menu("排版", systemImage: "textformat.size") {
                        Stepper("字号 \(Int(fontSize))", value: $fontSize, in: 13...32, step: 1)
                        Stepper("行距 \(Int(lineSpacing))", value: $lineSpacing, in: 2...20, step: 2)
                    }
                    .accessibilityIdentifier("novel.typography")
                }
            }
            .sheet(isPresented: $showingSheet) { navigationSheet(proxy) }
            .alert("无法下载", isPresented: Binding(get: { downloadError != nil }, set: { if !$0 { downloadError = nil } })) {
                Button("好") { downloadError = nil }
            } message: { Text(downloadError ?? "") }
            .task {
                model.library = library.library
                await model.load()
                proxy.scrollTo(model.resumeIndex, anchor: .top)
            }
            .onDisappear { Task { await model.persist(); await library.refresh() } }
            .readerPresence()
        }
    }

    /// Android's novel download choices: this chapter, the next ones, or every chapter not yet saved.
    private var downloadMenu: some View {
        Menu {
            Section(downloadStatus ?? "") {
                Button("下载本章", systemImage: "arrow.down.circle") { enqueue([model.chapter]) }
                    .disabled(ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter))
                    .accessibilityIdentifier("novel.download.current")
                Button("下载后续 10 章", systemImage: "arrow.down.to.line") { enqueue(followingChapters(10)) }
                    .disabled(followingChapters(10).isEmpty)
                    .accessibilityIdentifier("novel.download.next")
                Button("下载全部未下载章节", systemImage: "square.and.arrow.down.on.square") {
                    enqueue(model.content.chapters.filter { !ReaderDownloads.isDownloaded(content: model.content, chapter: $0) })
                }
                .accessibilityIdentifier("novel.download.all")
            }
            Button("下载队列", systemImage: "list.bullet.rectangle") { showingDownloads = true }
                .accessibilityIdentifier("novel.downloads")
        } label: {
            Label("下载", systemImage: "arrow.down.circle")
        }
        .accessibilityIdentifier("novel.download")
        .sheet(isPresented: $showingDownloads) { ReaderDownloadsSheet() }
    }

    private var downloadStatus: String? {
        if ReaderDownloads.isDownloaded(content: model.content, chapter: model.chapter) { return "本章已下载，可离线阅读" }
        return downloads.entry(content: model.content, chapter: model.chapter)?.status
    }

    private func followingChapters(_ count: Int) -> [IosSourceChapter] {
        var result: [IosSourceChapter] = []
        var current = model.chapter
        while result.count < count, let next = model.content.adjacentChapter(chapter: current, forward: true) {
            if !ReaderDownloads.isDownloaded(content: model.content, chapter: next) { result.append(next) }
            current = next
        }
        return result
    }

    private func enqueue(_ chapters: [IosSourceChapter]) {
        guard !chapters.isEmpty else { return }
        do { try downloads.enqueue(content: model.content, chapters: chapters) }
        catch { downloadError = error.localizedDescription }
    }

    private func navigationSheet(_ proxy: ScrollViewProxy) -> some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("内容", selection: $sheetTab) {
                    Text("章节").tag(0)
                    Text("书签").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(12)
                if sheetTab == 0 {
                    List(model.content.chapters, id: \.id) { item in
                        Button {
                            showingSheet = false
                            Task { await model.move(to: item); proxy.scrollTo("top", anchor: .top) }
                        } label: {
                            HStack {
                                Text(item.title)
                                Spacer()
                                if ReaderDownloads.isDownloaded(content: model.content, chapter: item) {
                                    Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary)
                                        .accessibilityLabel("已下载")
                                }
                                if item.id == model.chapter.id { Image(systemName: "book.fill") }
                            }
                        }
                        .accessibilityIdentifier("novel.chapter.\(item.id)")
                        .swipeActions {
                            if !model.runtime.isLocal(content: model.content),
                               !ReaderDownloads.isDownloaded(content: model.content, chapter: item) {
                                Button("下载", systemImage: "arrow.down.circle") { enqueue([item]) }.tint(.accentColor)
                            }
                        }
                    }
                } else if model.bookmarks.isEmpty {
                    ContentUnavailableView("暂无书签", systemImage: "bookmark")
                } else {
                    List {
                        ForEach(model.bookmarks, id: \.id) { bookmark in
                            Button {
                                showingSheet = false
                                Task {
                                    if bookmark.chapterId != model.chapter.id,
                                       let target = model.content.chapters.first(where: { $0.id == bookmark.chapterId }) {
                                        await model.move(to: target)
                                    }
                                    let index = min(max(0, Int(bookmark.index)), max(0, model.blocks.count - 1))
                                    await model.jump(to: index)
                                    proxy.scrollTo(index, anchor: .top)
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(model.content.chapters.first { $0.id == bookmark.chapterId }?.title ?? "章节")
                                        .font(.subheadline.weight(.medium))
                                    Text("第 \(bookmark.index + 1) 段 · \(bookmark.preview)")
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                            .swipeActions {
                                Button("删除", role: .destructive) { Task { await model.removeBookmark(bookmark) } }
                            }
                        }
                    }
                }
            }
            .navigationTitle("目录与书签")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showingSheet = false } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func chapterButtons(_ proxy: ScrollViewProxy) -> some View {
        GlassEffectContainer(spacing: 12) {
            HStack {
                if let previous = model.previous {
                    Button("上一章", systemImage: "chevron.left") {
                        Task { await model.move(to: previous); proxy.scrollTo("top") }
                    }
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("novel.previousChapter")
                }
                Spacer()
                if let next = model.next {
                    Button("下一章", systemImage: "chevron.right") {
                        Task { await model.move(to: next); proxy.scrollTo("top") }
                    }
                    .buttonStyle(.glassProminent)
                    .accessibilityIdentifier("novel.nextChapter")
                }
            }
        }
        .padding(.vertical, 24)
    }
}

/// An illustration loaded with the headers the source requires (most novel sites check the Referer).
struct HeaderImage: View {
    let url: URL
    let headers: [String: String]
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary).frame(height: 160)
                    .overlay { if failed { Image(systemName: "photo.badge.exclamationmark").foregroundStyle(.secondary) } }
            }
        }
        .task(id: url) {
            var request = URLRequest(url: url)
            headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true,
                      data.count <= 32 * 1024 * 1024 else { throw URLError(.badServerResponse) }
                // Downsample off the main actor; illustrations can be very large.
                image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                          let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                              kCGImageSourceCreateThumbnailFromImageAlways: true,
                              kCGImageSourceThumbnailMaxPixelSize: 2048,
                              kCGImageSourceCreateThumbnailWithTransform: true,
                          ] as CFDictionary) else { return nil }
                    return UIImage(cgImage: cgImage)
                }.value
                failed = image == nil
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }
}
