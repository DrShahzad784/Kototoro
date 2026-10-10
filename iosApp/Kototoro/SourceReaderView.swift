import Combine
import KototoroShared
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class SourceReaderModel: ObservableObject {
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var pages: [IosSourcePage] = []
    @Published private(set) var index = 0
    @Published private(set) var images: [Int: UIImage] = [:]
    @Published private(set) var artifacts: [Int: ReaderImageArtifact] = [:]
    @Published private(set) var error: String?
    @Published private(set) var isLoadingPages = false
    @Published private(set) var bookmarks: [IosBookmark] = []
    @Published private(set) var slots: [IosReaderSlot] = []
    @Published private(set) var loaded = false
    @Published private(set) var pageRevision = 0
    @Published var slotIndex: Int = 0
    @Published var autoScroll = false
    let runtime: IosSources
    let content: IosSourceContent
    let engine = IosReader()
    var library: IosLibrary?
    private var request: UUID?
    private var loading: Set<Int> = []
    private var viewport = CGSize(width: 1, height: 1)
    private var preferences = ReaderPreferences()
    private var offset: Float = 0
    private var savedPosition: IosReadingPosition?
    private(set) var restoreOrigin: CGFloat = 0
    private var awaitingRestore = false

    var selectedPage: IosSourcePage? { pages.indices.contains(index) ? pages[index] : nil }
    var image: UIImage? { images[index] }
    var previousChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: false) }
    var nextChapter: IosSourceChapter? { content.adjacentChapter(chapter: chapter, forward: true) }
    var isBookmarked: Bool { bookmarks.contains { $0.chapterId == chapter.id && Int($0.index) == index } }

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime; self.content = content; self.chapter = chapter
    }

    func configure(size: CGSize, preferences: ReaderPreferences) {
        guard size.width > 1 && size.height > 1 else { return }
        let cropChanged = loaded && self.preferences.cropMargins != preferences.cropMargins
        viewport = size; self.preferences = preferences
        engine.configure(count: Int32(pages.count), width: Int32(size.width), height: Int32(size.height),
                         mode: Int32(preferences.mode), doublePage: preferences.doublePage,
                         cover: preferences.cover, fit: Int32(preferences.fit),
                         splitWide: preferences.splitWidePages)
        slots = engine.slots()
        if let current = slots.first(where: { Int($0.index) == slotIndex }),
           current.placements.contains(where: { Int($0.index) == index }) {
            slotIndex = Int(current.index)
        } else {
            slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == index } }?.index ?? 0)
        }
        if cropChanged {
            images = [:]; artifacts = [:]; loading = []; pageRevision += 1
        }
    }

    func load() async {
        guard !loaded else { return }
        let token = UUID(); request = token
        let selected = chapter
        isLoadingPages = true; error = nil
        defer { if request == token { isLoadingPages = false } }
        do {
            let result = try await ReaderDownloads.pages(runtime: runtime, content: content, chapter: selected)
            let saved = try await library?.position(content: content)
            try Task.checkCancellation()
            guard request == token else { return }
            pages = result
            savedPosition = saved
            if let savedPosition, savedPosition.chapterId == selected.id {
                index = min(max(0, Int(savedPosition.index)), max(0, pages.count - 1))
                offset = savedPosition.scroll
            }
            configure(size: viewport, preferences: preferences)
            restoreOrigin = CGFloat(engine.scrollOrigin(index: Int32(index), offset: offset))
            awaitingRestore = preferences.mode >= 3
            loaded = true; pageRevision += 1
            await refreshBookmarks()
        } catch { if !Task.isCancelled && request == token { self.error = error.localizedDescription } }
    }

    func loadImage(at target: Int) async {
        guard loaded, pages.indices.contains(target), images[target] == nil, !loading.contains(target) else { return }
        let token = request
        let page = pages[target]
        let cropMargins = preferences.cropMargins
        loading.insert(target)
        defer { if request == token { loading.remove(target) } }
        do {
            let loadedPage = try await ReaderDownloads.loadedPage(
                runtime: runtime, content: content, chapter: chapter,
                page: page, index: target, cropMargins: cropMargins
            )
            guard request == token else { return }
            images[target] = loadedPage.image
            artifacts[target] = loadedPage.artifact
            engine.setImageSize(
                index: Int32(target),
                width: Int32(loadedPage.artifact.encodedWidth),
                height: Int32(loadedPage.artifact.encodedHeight)
            )
            slots = engine.slots()
            if !slots.contains(where: { Int($0.index) == slotIndex && $0.placements.contains { Int($0.index) == index } }) {
                slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == index } }?.index ?? 0)
            }
            // Decoded images stay bounded. Immutable on-disk artifacts survive eviction.
            let keep = Set((max(0, index - 2)...min(max(0, pages.count - 1), index + 3)))
            images = images.filter { keep.contains($0.key) }
            artifacts = artifacts.filter { keep.contains($0.key) }
            if slots.first(where: { $0.placements.contains { Int($0.index) == index } })?
                .placements.contains(where: { Int($0.index) == target }) == true { await persist() }
        } catch { if !Task.isCancelled && request == token { self.error = error.localizedDescription } }
    }

    func displayImage(for target: Int, segment: Int) -> UIImage? {
        guard let base = images[target] else { return nil }
        return segment == 1 || segment == 2 ? SourceImageFile.splitPage(base, segment: segment) : base
    }

    func selectSlot(_ selected: Int) {
        guard let slot = slots.first(where: { Int($0.index) == selected }) else { return }
        index = Int(slot.anchor); offset = 0
        Task { await persist() }
    }

    func continuousPosition(x: CGFloat, y: CGFloat) {
        guard loaded, !pages.isEmpty else { return }
        if awaitingRestore {
            let current = preferences.mode == 3 ? y : x
            guard abs(current - restoreOrigin) < 2 else { return }
            awaitingRestore = false
        }
        let progress = engine.progress(x: Float(x), y: Float(y))
        index = min(max(0, Int(progress.index)), max(0, pages.count - 1)); offset = progress.offset
        slotIndex = Int(slots.first { $0.anchor == Int32(index) }?.index ?? 0)
    }

    func userScrolled() { awaitingRestore = false; autoScroll = false }

    func jump(to page: Int) {
        guard pages.indices.contains(page) else { return }
        index = page; offset = 0
        slotIndex = Int(slots.first { $0.placements.contains { Int($0.index) == page } }?.index ?? 0)
    }

    func movePage(forward: Bool) {
        let next = slotIndex + (forward ? 1 : -1)
        guard slots.indices.contains(next) else { return }
        slotIndex = next; selectSlot(next)
    }

    func moveChapter(_ target: IosSourceChapter) async {
        guard !isLoadingPages else { return }
        await persist()
        request = UUID(); chapter = target; loaded = false
        pages = []; slots = []; images = [:]; artifacts = [:]; loading = []; index = 0; slotIndex = 0; offset = 0; engine.reset()
        await load()
    }

    func persist() async {
        guard pages.indices.contains(index), images[index] != nil, let library else { return }
        let lastVisible = slots.first { $0.placements.contains { Int($0.index) == index } }?
            .placements.map { Int($0.index) }.max() ?? index
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(index),
                                              count: Int32(pages.count), lastVisible: Int32(max(index, lastVisible)), scroll: offset) }
        catch { self.error = error.localizedDescription }
    }

    func refreshBookmarks() async {
        do { bookmarks = try await library?.bookmarks(content: content) ?? [] }
        catch { self.error = error.localizedDescription }
    }

    func toggleBookmark() async {
        guard let library, let page = selectedPage else { return }
        do {
            _ = try await library.toggleBookmark(content: content, chapter: chapter, page: page,
                                                  index: Int32(index), count: Int32(pages.count), scroll: offset)
            await refreshBookmarks()
        } catch { self.error = error.localizedDescription }
    }

    func removeBookmark(_ bookmark: IosBookmark) async {
        do { try await library?.removeBookmark(content: content, bookmark: bookmark); await refreshBookmarks() }
        catch { self.error = error.localizedDescription }
    }

    func retry() async {
        error = nil
        if loaded { await loadImage(at: index) } else { await load() }
    }
}

struct ReaderPNG: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data
    init(image: UIImage) { data = image.pngData() ?? Data() }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct SourceReaderView: View {
    private let controlDiameter: CGFloat = 44
    @StateObject private var model: SourceReaderModel
    @StateObject private var settings: ReaderSettings
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var chrome
    @State private var panel: ReaderPanel?
    @State private var controls = true
    @State private var export: ReaderPNG?
    @State private var exporting = false
    @State private var cropping = false
    @State private var croppedImage: UIImage?
    @State private var continuousPage: Int?
    @State private var continuousPosition = ScrollPosition(idType: Int.self)
    @State private var scrollOffset = CGPoint.zero
    @State private var downloadStatus: String?
    @State private var downloading = false

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: SourceReaderModel(runtime: runtime, content: content, chapter: chapter))
        _settings = StateObject(wrappedValue: ReaderSettings(contentID: content.id))
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                settings.background.ignoresSafeArea()
                readingSurface(size: geometry.size)
                    .onTapGesture { withAnimation(chromeAnimation) { controls.toggle(); panel = nil } }
                if model.isLoadingPages { ProgressView("正在读取漫画页…").tint(.gray) }
                if let error = model.error {
                    VStack {
                        Text(error).foregroundStyle(.red).accessibilityIdentifier("reader.error")
                        Button("重试") { Task { await model.retry() } }.buttonStyle(.glass)
                    }.padding().background(.regularMaterial, in: .rect(cornerRadius: 16))
                }
                if model.loaded && model.pages.isEmpty && model.error == nil {
                    ContentUnavailableView("暂无漫画页", systemImage: "doc").foregroundStyle(.gray)
                }
                if controls { chromeView(height: geometry.size.height) }
            }
            .clipped()
            .onChange(of: geometry.size, initial: true) { _, size in model.configure(size: size, preferences: settings.value) }
            .onChange(of: settings.value) { _, value in
                model.configure(size: geometry.size, preferences: value)
                if value.mode >= 3 { scrollContinuous(to: CGFloat(model.engine.scrollOrigin(index: Int32(model.index), offset: 0))) }
            }
        }
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!controls)
        .task {
            model.library = library.library; await model.load()
            if settings.value.mode >= 3 {
                await Task.yield()
                scrollContinuous(to: model.restoreOrigin)
            }
        }
        .task(id: model.index) { await model.loadImage(at: model.index); await model.persist() }
        .task(id: model.autoScroll) {
            var elapsed = 0
            while model.autoScroll && !Task.isCancelled {
                if settings.value.mode >= 3 {
                    try? await Task.sleep(for: .milliseconds(16))
                    guard !Task.isCancelled, model.autoScroll else { break }
                    let current = settings.value.mode == 3 ? scrollOffset.y : scrollOffset.x
                    let direction: CGFloat = settings.value.mode == 4 ? -1 : 1
                    let next = current + direction * CGFloat(model.engine.autoScrollDistance(speed: Float(settings.value.autoSpeed), elapsedMillis: 16))
                    if next < 0 || next > CGFloat(model.engine.scrollLimit()) { model.autoScroll = false; await model.persist(); break }
                    scrollContinuous(to: next)
                    elapsed += 16
                    if elapsed >= 5000 { elapsed = 0; await model.persist() }
                    continue
                }
                try? await Task.sleep(for: .milliseconds(Int64(model.engine.autoScrollDelay(speed: Float(settings.value.autoSpeed)))))
                guard !Task.isCancelled && model.autoScroll else { break }
                if model.slotIndex + 1 < model.slots.count {
                    withAnimation(pageAnimation) { model.movePage(forward: true); continuousPage = model.slotIndex }
                } else if let next = model.nextChapter { await model.moveChapter(next) }
                else { model.autoScroll = false }
            }
        }
        .onDisappear { model.autoScroll = false; Task { await model.persist(); await library.refresh() } }
        .fileExporter(isPresented: $exporting, document: export, contentType: .png,
                      defaultFilename: "\(model.content.title)-\(model.index + 1)") { result in
            if case .failure(let error) = result { downloadStatus = error.localizedDescription }
        }
        .sheet(isPresented: $cropping, onDismiss: {
            if let image = croppedImage { croppedImage = nil; save(image) }
        }) {
            if let image = model.image { ReaderCropView(image: image) { croppedImage = $0 } }
        }
    }

    @ViewBuilder
    private func readingSurface(size: CGSize) -> some View {
        let vertical = settings.value.mode == 2 || settings.value.mode == 3
        let continuous = settings.value.mode >= 3
        ScrollView(vertical ? .vertical : .horizontal) {
            Group {
                if vertical {
                    LazyVStack(spacing: 0) { slots(size: size, continuous: continuous) }
                } else {
                    LazyHStack(spacing: 0) { slots(size: size, continuous: continuous) }
                }
            }
            .scrollTargetLayout()
        }
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(ReaderScrollBehavior(paged: !continuous))
        .modifier(ReaderScrollPosition(continuous: continuous, position: $continuousPosition,
                                      slot: Binding<Int?>(get: { model.slotIndex },
                                          set: { if let value = $0 { model.slotIndex = value; model.selectSlot(value) } })))
        .environment(\.layoutDirection, settings.value.mode == 1 || settings.value.mode == 4 ? .rightToLeft : .leftToRight)
        .onScrollGeometryChange(for: CGPoint.self) { $0.contentOffset } action: { _, point in
            if continuous { scrollOffset = point; model.continuousPosition(x: point.x, y: point.y) }
        }
        .onScrollPhaseChange { _, phase in
            if phase == .interacting { model.userScrolled() }
            if phase == .idle && !model.autoScroll { Task { await model.persist() } }
        }
        .onChange(of: continuousPage) { _, page in
            if continuous, let page { scrollContinuous(to: CGFloat(model.engine.scrollOrigin(index: Int32(page), offset: 0))) }
        }
        .onChange(of: model.chapter.id) { _, _ in continuousPage = nil }
        .onChange(of: model.loaded) { _, loaded in
            if continuous && loaded { scrollContinuous(to: model.restoreOrigin) }
        }
    }

    @ViewBuilder private func slots(size: CGSize, continuous: Bool) -> some View {
        ForEach(model.slots, id: \.index) { slot in
            if continuous, let placement = slot.placements.first {
                ReaderPageTile(
                    model: model,
                    settings: settings,
                    index: Int(placement.index),
                    segment: Int(placement.segment),
                    displaySize: CGSize(width: CGFloat(placement.width), height: CGFloat(placement.height)),
                    viewportOffset: scrollOffset,
                    zoomScale: 1
                )
                    .frame(width: CGFloat(placement.width), height: CGFloat(placement.height))
                    .id(Int(slot.index))
            } else {
                ReaderSpread(model: model, settings: settings, slot: slot, size: size).id(Int(slot.index))
                    .scrollTransition(.interactive, axis: settings.value.mode == 2 ? .vertical : .horizontal) { content, phase in
                        content.opacity(!reduceMotion && settings.value.animation == 2 && !phase.isIdentity ? 0 : 1)
                            .scaleEffect(!reduceMotion && settings.value.animation == 3 && !phase.isIdentity ? 0.85 : 1)
                    }
            }
        }
    }

    private func chromeView(height: CGFloat) -> some View {
        GlassEffectContainer(spacing: 8) {
            VStack(spacing: 12) {
                if !settings.value.bottomPanels { topChrome }
                Spacer(minLength: 0)
                if settings.value.bottomPanels { topChrome }
                if !floatingActions.isEmpty {
                    HStack(spacing: 12) {
                        Spacer()
                        ForEach(floatingActions, id: \.self) { action in
                            Button {
                                if action == "auto" { model.autoScroll.toggle() } else { moreAction(action) }
                            } label: {
                                Image(systemName: action == "bookmark" && model.isBookmarked ? "bookmark.fill" : ReaderMorePanel.actionSymbol(action))
                                    .frame(width: controlDiameter, height: controlDiameter)
                            }
                                .buttonStyle(.plain)
                                .glassEffect(.regular.tint(.accentColor).interactive(), in: .circle)
                                .accessibilityLabel(ReaderMorePanel.actionTitle(action))
                                .accessibilityIdentifier("reader.\(action)")
                                .accessibilityValue(action == "bookmark" ? (model.isBookmarked ? "已添加" : "未添加") : "")
                        }
                    }
                }
                progressBar
            }
            .padding(12)
            .foregroundStyle(.primary)
        }
    }

    private var progressBar: some View {
        HStack(spacing: 20) {
            Button("上一章", systemImage: "backward.end.fill") {
                if let chapter = model.previousChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.previousChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.previousChapter")
            HStack(spacing: 8) {
                Text("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("当前页码")
                    .accessibilityValue("\(model.pages.isEmpty ? 0 : model.index + 1)")
                    .accessibilityIdentifier("reader.currentPage")
                Slider(value: Binding(
                    get: { Double(model.index) },
                    set: { value in
                        model.userScrolled()
                        model.jump(to: Int(value.rounded()))
                        continuousPage = model.slotIndex
                    }), in: 0...Double(max(1, model.pages.count - 1)), step: 1,
                    onEditingChanged: { editing in if !editing { Task { await model.persist() } } })
                    .disabled(model.pages.count <= 1 || model.isLoadingPages)
                    .accessibilityLabel("阅读进度")
                    .accessibilityValue("第 \(model.pages.isEmpty ? 0 : model.index + 1) / \(model.pages.count) 页")
                    .accessibilityIdentifier("reader.progress")
                Text("\(model.pages.count)")
                    .font(.caption.monospacedDigit()).fixedSize()
                    .accessibilityLabel("总页码")
                    .accessibilityValue("\(model.pages.count)")
                    .accessibilityIdentifier("reader.totalPages")
            }
            .padding(.horizontal, 14)
            .frame(height: controlDiameter)
            .glassEffect(.regular, in: .capsule)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("reader.progressControl")
            Button("下一章", systemImage: "forward.end.fill") {
                if let chapter = model.nextChapter { Task { await model.moveChapter(chapter) } }
            }
            .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
            .frame(width: controlDiameter, height: controlDiameter)
            .disabled(model.nextChapter == nil || model.isLoadingPages)
            .accessibilityIdentifier("reader.nextChapter")
        }
        .font(.subheadline)
    }

    @ViewBuilder private var topChrome: some View {
        if let panel {
            VStack(spacing: 0) {
                HStack {
                    Text(panel == .chapters ? model.chapter.title : "更多").font(.headline).lineLimit(1)
                    Spacer()
                    Button("收起", systemImage: "xmark") { withAnimation(chromeAnimation) { self.panel = nil } }
                        .labelStyle(.iconOnly).buttonStyle(.borderless).accessibilityIdentifier("reader.panel.close")
                }.padding(16)
                Divider()
                if panel == .chapters {
                    ReaderChapterPanel(model: model, onJump: { page in model.jump(to: page); continuousPage = model.slotIndex; closePanel() },
                                       onChapter: { chapter in Task { await model.moveChapter(chapter); continuousPage = model.slotIndex; closePanel() } })
                } else {
                    ReaderMorePanel(settings: settings, autoScroll: $model.autoScroll,
                                    isBookmarked: model.isBookmarked,
                                    status: downloadStatus, downloading: downloading,
                                    onAction: moreAction)
                }
            }
            .frame(maxHeight: 540)
            .glassEffect(.regular, in: .rect(cornerRadius: 28))
            .glassEffectID(panel == .chapters ? "chapters" : "more", in: chrome)
            .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top)))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(panel == .chapters ? "reader.chapterPanel" : "reader.morePanel")
        } else {
            HStack {
                Button("返回", systemImage: "chevron.left") { dismiss() }
                    .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                    .frame(width: controlDiameter, height: controlDiameter).accessibilityIdentifier("reader.back")
                Spacer(minLength: 8)
                Button { withAnimation(chromeAnimation) { panel = .chapters } } label: {
                    Label(model.chapter.title, systemImage: "list.bullet").font(.subheadline).lineLimit(1)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                }
                .buttonStyle(.plain).glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("chapters", in: chrome).accessibilityIdentifier("reader.chapters")
                Spacer(minLength: 8)
                Button { withAnimation(chromeAnimation) { panel = .more } } label: {
                    Image(systemName: "ellipsis").frame(width: controlDiameter, height: controlDiameter)
                }.buttonStyle(.plain).glassEffect(.regular.interactive(), in: .circle)
                    .glassEffectID("more", in: chrome).accessibilityIdentifier("reader.more")
            }
        }
    }

    private func closePanel() { withAnimation(chromeAnimation) { panel = nil } }
    private func scrollContinuous(to origin: CGFloat) {
        if settings.value.mode == 3 { continuousPosition.scrollTo(y: origin) }
        else { continuousPosition.scrollTo(x: origin) }
    }
    private var chromeAnimation: Animation? { reduceMotion ? nil : .spring }
    private var pageAnimation: Animation? {
        reduceMotion || settings.value.animation == 0 ? nil : .easeInOut(duration: 0.25)
    }
    private var floatingActions: [String] {
        var actions = settings.value.floatingActions.filter { $0 != "bookmark" }
        if settings.value.bookmarkButton { actions.append("bookmark") }
        return Array(actions.suffix(4))
    }
    private func save(_ image: UIImage) { export = ReaderPNG(image: image); exporting = true }
    private func moreAction(_ action: String) {
        switch action {
        case "chapters": withAnimation(chromeAnimation) { panel = .chapters }
        case "bookmark": Task { await model.toggleBookmark() }
        case "save": if let image = model.image { closePanel(); save(image) }
        case "crop": if model.image != nil { closePanel(); cropping = true }
        case "direction": settings.value.mode = settings.value.mode == 1 ? 0 : 1
        case "browser": if let url = URL(string: model.content.publicUrl) { UIApplication.shared.open(url) }
        case "download":
            guard !downloading else { return }
            downloading = true; downloadStatus = "正在保存本章…"
            Task {
                do {
                    try await ReaderDownloads.download(runtime: model.runtime, content: model.content, chapter: model.chapter)
                    downloadStatus = "本章已下载，可离线阅读"
                } catch { downloadStatus = error.localizedDescription }
                downloading = false
            }
        default: break
        }
    }
}

enum ReaderPanel { case chapters, more }

private struct ReaderScrollBehavior: ScrollTargetBehavior {
    let paged: Bool
    func updateTarget(_ target: inout ScrollTarget, context: TargetContext) {
        if paged { PagingScrollTargetBehavior().updateTarget(&target, context: context) }
    }
}

private struct ReaderScrollPosition: ViewModifier {
    let continuous: Bool
    @Binding var position: ScrollPosition
    let slot: Binding<Int?>
    func body(content: Content) -> some View {
        if continuous { content.scrollPosition($position) }
        else { content.scrollPosition(id: slot) }
    }
}

private struct ReaderPageTile: View {
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    let index: Int
    var segment = 0
    var displaySize = CGSize.zero
    var viewportOffset = CGPoint.zero
    var zoomScale: CGFloat = 1
    var body: some View {
        Group {
            if let image = model.displayImage(for: index, segment: segment) {
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: displaySize.width > 0 ? displaySize.width : nil,
                               height: displaySize.height > 0 ? displaySize.height : nil)
                    if segment == 0, let artifact = model.artifacts[index], artifact.tiled,
                       displaySize.width > 1, displaySize.height > 1 {
                        let tiles = model.engine.visibleTiles(
                            x: Float(viewportOffset.x),
                            y: Float(viewportOffset.y),
                            index: Int32(index),
                            encodedWidth: Int32(artifact.encodedWidth),
                            encodedHeight: Int32(artifact.encodedHeight),
                            displayScale: Float(max(1, zoomScale))
                        )
                        if !tiles.isEmpty {
                            ReaderTiledOverlay(artifact: artifact, tiles: tiles)
                        }
                    }
                }
                .brightness(settings.value.brightness).contrast(settings.value.contrast)
                .saturation(settings.value.grayscale ? 0 : 1)
                .modifier(ReaderInvert(enabled: settings.value.inverted))
                .shadow(color: .black.opacity(settings.value.bookEffect ? 0.4 : 0), radius: 8)
                .accessibilityIdentifier("reader.image")
            } else { ProgressView().tint(.gray) }
        }
        .task(id: "\(model.pageRevision)-\(index)") { await model.loadImage(at: index) }
    }
}

private struct ReaderTiledOverlay: View {
    let artifact: ReaderImageArtifact
    let tiles: [IosReaderTile]
    @State private var decodedTiles: [String: UIImage] = [:]

    private static func key(_ tile: IosReaderTile) -> String {
        "\(tile.decodeLeft):\(tile.decodeTop):\(tile.decodeWidth):\(tile.decodeHeight):\(tile.sampleSize)"
    }

    private var tileSignature: String {
        tiles.map(Self.key).joined(separator: "|")
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { _, tile in
                if let image = decodedTiles[Self.key(tile)] {
                    Image(uiImage: image).resizable()
                        .frame(width: CGFloat(tile.logicalWidth), height: CGFloat(tile.logicalHeight))
                        .offset(x: CGFloat(tile.logicalLeft), y: CGFloat(tile.logicalTop))
                }
            }
        }
        .allowsHitTesting(false)
        .task(id: tileSignature) {
            let activeKeys = Set(tiles.map(Self.key))
            decodedTiles = decodedTiles.filter { activeKeys.contains($0.key) }
            for tile in tiles {
                guard !Task.isCancelled else { break }
                let key = Self.key(tile)
                guard decodedTiles[key] == nil else { continue }
                if let image = try? await artifact.decodeTile(tile) {
                    decodedTiles[key] = image
                }
            }
        }
    }
}

private struct ReaderInvert: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View { if enabled { content.colorInvert() } else { content } }
}

private struct ReaderSpread: View {
    @ObservedObject var model: SourceReaderModel
    @ObservedObject var settings: ReaderSettings
    let slot: IosReaderSlot
    let size: CGSize
    @State private var scale = 1.0
    @State private var baseScale = 1.0
    @State private var pan = CGSize.zero
    @State private var basePan = CGSize.zero
    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(slot.placements.enumerated()), id: \.offset) { _, placement in
                ReaderPageTile(
                    model: model,
                    settings: settings,
                    index: Int(placement.index),
                    segment: Int(placement.segment),
                    displaySize: CGSize(width: CGFloat(placement.width), height: CGFloat(placement.height)),
                    viewportOffset: CGPoint(x: pan.width, y: pan.height),
                    zoomScale: scale
                )
                    .frame(width: CGFloat(placement.width), height: CGFloat(placement.height))
                    .offset(x: CGFloat(placement.x), y: CGFloat(placement.y))
            }
        }
        .frame(width: size.width, height: size.height)
        .scaleEffect(scale).offset(pan)
        .frame(width: size.width, height: size.height).clipped()
        .simultaneousGesture(MagnifyGesture().onChanged { scale = min(6, max(1, baseScale * $0.magnification)) }
            .onEnded { _ in baseScale = scale; clamp() })
        .highPriorityGesture(DragGesture().onChanged {
            if canPan { pan = CGSize(width: basePan.width + $0.translation.width, height: basePan.height + $0.translation.height) }
        }.onEnded { _ in if canPan { clamp() } }, including: canPan ? .all : .subviews)
        .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2; baseScale = scale; clamp() } }
    }
    private var canPan: Bool {
        scale > 1 || (slot.placements.map { $0.x + $0.width }.max() ?? 0) > Float(size.width) ||
            (slot.placements.map { $0.y + $0.height }.max() ?? 0) > Float(size.height)
    }
    private func clamp() {
        let left = slot.placements.map { $0.x }.min() ?? 0
        let right = slot.placements.map { $0.x + $0.width }.max() ?? Float(size.width)
        let top = slot.placements.map { $0.y }.min() ?? 0
        let bottom = slot.placements.map { $0.y + $0.height }.max() ?? Float(size.height)
        pan = CGSize(width: CGFloat(model.engine.clampPan(value: Float(pan.width), contentMin: left, contentMax: right,
                                                           viewport: Float(size.width), scale: Float(scale))),
                     height: CGFloat(model.engine.clampPan(value: Float(pan.height), contentMin: top, contentMax: bottom,
                                                            viewport: Float(size.height), scale: Float(scale))))
        basePan = pan
    }
}
