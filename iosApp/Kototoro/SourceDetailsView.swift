import Combine
import KototoroShared
import SwiftUI

/// Both child screens borrow the browsing session; leaving a screen must not close the process VM.
@MainActor
final class SourceDetailsModel: ObservableObject {
    @Published private(set) var details: IosSourceContent?
    @Published private(set) var error: String?
    /// The last failure was a browser challenge (Cloudflare): the error offers the in-app browser.
    @Published private(set) var challenge: IosChallenge?
    @Published private(set) var isLoading = false
    @Published var selectedLine: String?
    let runtime: IosSources
    let content: IosSourceContent
    private var detailsRequest: UUID?

    init(runtime: IosSources, content: IosSourceContent) {
        self.runtime = runtime
        self.content = content
    }

    @Published var selectedBranch: String?

    /// Translation branches in source order (manga/novels); a single unnamed branch is one nil entry.
    var branches: [String?] {
        var seen = Set<String?>()
        return (details?.chapters ?? []).map(\.branch).filter { seen.insert($0).inserted }
    }

    func chapterList(query: String, newestFirst: Bool) -> [IosSourceChapter] {
        var chapters = details?.chapters ?? []
        if branches.count > 1 { chapters = chapters.filter { $0.branch == selectedBranch } }
        let text = query.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { chapters = chapters.filter { $0.title.localizedCaseInsensitiveContains(text) } }
        return newestFirst ? chapters.reversed() : chapters
    }

    func refresh() async {
        do {
            let result = try await runtime.refreshDetails(content: details ?? content)
            details = result; error = nil; challenge = nil
            if !branches.contains(selectedBranch) { selectedBranch = branches.first ?? nil }
        } catch { if !Task.isCancelled { fail(error) } }
    }

    /// Readable text, and whether the site asked for browser verification.
    private func fail(_ error: Error) {
        self.error = SourceErrorText.describe(error)
        challenge = SourceErrorText.challenge(error)
    }

    var isVideo: Bool { content.mediaKind == "VIDEO" }
    var isNovel: Bool { content.mediaKind == "NOVEL" }

    /// Playback lines in source order; a single unnamed line is represented by one nil entry.
    var lines: [String?] {
        var seen = Set<String?>()
        return (details?.chapters ?? []).map(\.line).filter { seen.insert($0).inserted }
    }

    var visibleChapters: [IosSourceChapter] {
        let chapters = details?.chapters ?? []
        guard isVideo, lines.count > 1 else { return chapters }
        let line = selectedLine ?? lines.first ?? nil
        return chapters.filter { $0.line == line }
    }

    func load() async {
        guard !isLoading, details == nil || error != nil else { return }
        let request = UUID()
        detailsRequest = request
        isLoading = true
        error = nil; challenge = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let result = try await runtime.details(content: content)
            try Task.checkCancellation()
            if detailsRequest == request {
                details = result
                selectedLine = lines.first ?? nil
                selectedBranch = branches.first ?? nil
            }
        } catch {
            if !Task.isCancelled, detailsRequest == request {
                fail(error)
                // Saved full details retain their chapters for offline reading.
                if !content.chapters.isEmpty {
                    details = content; selectedLine = lines.first ?? nil; selectedBranch = branches.first ?? nil
                }
            }
        }
    }
}

struct SourceDetailsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @StateObject private var model: SourceDetailsModel
    @State private var playing: PlaybackRequest?
    @State private var reading: PlaybackRequest?
    @State private var isSummaryExpanded = false
    @State private var isFavourite = false
    @State private var recordError: String?
    @State private var resume: IosReadingPosition?
    @State private var editingCategories = false
    @State private var savingFavourite = false
    @State private var chapterQuery = ""
    @AppStorage("details.newestFirst") private var newestFirst = false
    @AppStorage(PanoramaKeys.enabled) private var panorama = true
    @AppStorage(PanoramaKeys.layout) private var panoramaLayout = PanoramaLayout.top
    @State private var scrollOffset: CGFloat = 0
    /// Opened from "continue reading": go straight on with the chapter being read, once.
    private let autoResume: Bool
    @State private var resumed = false

    init(runtime: IosSources, content: IosSourceContent, autoResume: Bool = false) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: content))
        self.autoResume = autoResume
    }

    var body: some View {
        List {
            header
            if let recordError { Text(recordError).foregroundStyle(.red) }
            if model.isLoading && model.details == nil {
                ProgressView("正在读取详情…").frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("details.error")
                    if let challenge = model.challenge {
                        ChallengeVerifyButton(challenge: challenge, runtime: model.runtime,
                                              sourceName: model.content.sourceName) { await model.load() }
                    }
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                }
            }
            if let details = model.details {
                if model.isVideo { episodes(details) } else { chapters(details) }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .background { backdrop }
        .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, offset in
            scrollOffset = offset
        }
        // Over the panorama the bar stays clear; the title appears once the header has scrolled away.
        .navigationTitle(panorama && scrollOffset < 220 ? "" : model.content.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await sources.recordUsage(sourceName: model.content.sourceName)
            await model.load()
            await refreshRecords()
            await sources.refreshSettings()
            if autoResume, !resumed, let details = model.details,
               let target = resumeChapter(details) ?? details.chapters.first {
                resumed = true
                if model.isVideo { playing = PlaybackRequest(content: details, chapter: target) }
                else { reading = PlaybackRequest(content: details, chapter: target) }
            }
        }
        .refreshable { await model.refresh(); await refreshRecords(); await sources.refreshSettings() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let url = URL(string: model.content.publicUrl), url.scheme?.hasPrefix("http") == true {
                    ShareLink(item: url) { Label("分享", systemImage: "square.and.arrow.up") }
                    Menu("更多", systemImage: "ellipsis") {
                        Button("在浏览器中打开", systemImage: "safari") { UIApplication.shared.open(url) }
                        if isFavourite { Button("整理分类", systemImage: "folder") { editingCategories = true } }
                    }
                }
            }
        }
        .sheet(isPresented: $editingCategories) { FavouriteCategoriesView(content: model.details ?? model.content) }
        .onChange(of: reading == nil) { _, closed in if closed { Task { await refreshRecords() } } }
        .navigationDestination(item: $reading) { request in
            if model.isNovel { NovelReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter) }
            else { SourceReaderView(runtime: model.runtime, content: request.content, chapter: request.chapter) }
        }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) { request in
            VideoPlayerView(runtime: model.runtime, content: request.content, chapter: request.chapter)
        }
    }

    private func toggleFavourite() {
        Task {
            savingFavourite = true
            defer { savingFavourite = false }
            do {
                try await library.setFavourite(model.details ?? model.content, favourite: !isFavourite)
                isFavourite.toggle()
            } catch { recordError = error.localizedDescription }
        }
    }

    private func refreshRecords() async {
        guard let content = model.details, let database = library.library else { return }
        do {
            try await database.saveDetails(content: content)
            isFavourite = try await database.isFavourite(content: content).boolValue
            resume = try await database.position(content: content)
            // Open the chapter list on the branch being read.
            if let chapter = resumeChapter(content), model.branches.contains(chapter.branch) { model.selectedBranch = chapter.branch }
        } catch { recordError = error.localizedDescription }
    }

    private var header: some View {
        let shown = model.details ?? model.content
        return Section {
            VStack(alignment: .leading, spacing: 16) {
                // Leave the upper part of the panorama banner uncovered, as Android's header does.
                if panorama && panoramaLayout == .top { Color.clear.frame(height: 48) }
                HStack(alignment: .top, spacing: 16) {
                    CoverImage(runtime: model.runtime, content: shown)
                        .frame(width: 118, height: 177)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
                        .accessibilityLabel("封面")
                        .accessibilityIdentifier("details.cover")
                    VStack(alignment: .leading, spacing: 6) {
                        Text(shown.title).font(.title3.weight(.bold)).lineLimit(4)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("details.title")
                        if let alt = shown.altTitles.first {
                            Text(alt).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if !shown.authors.isEmpty {
                            Label(shown.authors.joined(separator: "、"), systemImage: "person")
                                .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Label(sources.sources.first { $0.name == shown.sourceName }?.title ?? shown.sourceName, systemImage: "globe").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        HStack(spacing: 6) {
                            if let state = shown.state { pill(Self.stateTitle(state), tint: Self.stateTint(state)) }
                            if shown.rating > 0 {
                                pill(String(format: "★ %.1f", Double(shown.rating) * 5), tint: .orange)
                            }
                            if model.details != nil {
                                // Video lines repeat the same episodes; count the shown line only.
                                let count = model.isVideo ? model.visibleChapters.count : model.details?.chapters.count ?? 0
                                pill(model.isVideo ? "\(count) 集" : "\(count) 章", tint: .secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                actions(shown)
                if !shown.tags.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(shown.tags, id: \.self) { tag in
                            Text(tag).font(.caption).padding(.horizontal, 10).padding(.vertical, 5)
                                .background(.quaternary, in: .capsule)
                        }
                    }
                }
                if let summary = shown.summary.map(Self.plainText), !summary.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(summary)
                            .font(.callout)
                            .lineLimit(isSummaryExpanded ? nil : 4)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("details.summary")
                        if summary.count > 120 || summary.filter({ $0 == "\n" }).count >= 4 {
                            Button(isSummaryExpanded ? "收起" : "展开") { withAnimation { isSummaryExpanded.toggle() } }
                                .font(.footnote.weight(.semibold))
                        }
                    }
                }
            }
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
        }
        .listRowSeparator(.hidden)
    }

    /// Android's panorama cover at the top (or the softly blurred cover when it is turned off), faded into the
    /// grouped background.
    private var backdrop: some View {
        ZStack(alignment: .top) {
            Color(uiColor: .systemGroupedBackground)
            if panorama {
                DetailsPanorama(runtime: model.runtime, content: model.details ?? model.content, scrollOffset: scrollOffset)
            } else {
                CoverImage(runtime: model.runtime, content: model.details ?? model.content, plain: true)
                    .frame(height: 420).frame(maxWidth: .infinity)
                    .blur(radius: 50).opacity(0.5)
                    .mask(LinearGradient(colors: [.black, .black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom))
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// Primary read/continue action plus favourite, mirroring Android's details action bar.
    @ViewBuilder
    private func actions(_ shown: IosSourceContent) -> some View {
        HStack(spacing: 12) {
            if let details = model.details, let target = resumeChapter(details) ?? details.chapters.first {
                let resuming = resumeChapter(details) != nil
                let title = resuming ? (model.isVideo ? "继续播放" : "继续阅读") : (model.isVideo ? "开始播放" : "开始阅读")
                Group {
                    if model.isVideo {
                        Button { playing = PlaybackRequest(content: details, chapter: target) } label: {
                            primaryLabel(title, subtitle: target.title, icon: "play.fill")
                        }
                    } else {
                        // A NavigationLink inside a List row would turn the whole header into a row link.
                        Button { reading = PlaybackRequest(content: details, chapter: target) } label: {
                            primaryLabel(title, subtitle: target.title, icon: "book.fill")
                        }
                    }
                }
                .buttonStyle(.glassProminent)
                .accessibilityIdentifier(resuming ? "details.resume" : "details.start")
            } else {
                Button {} label: { primaryLabel(model.isVideo ? "开始播放" : "开始阅读", subtitle: nil, icon: "book.fill") }
                    .buttonStyle(.glassProminent).disabled(true)
            }
            Button(isFavourite ? "取消收藏" : "收藏", systemImage: isFavourite ? "heart.fill" : "heart", action: toggleFavourite)
                .labelStyle(.iconOnly)
                .font(.title3)
                .foregroundStyle(isFavourite ? Color.pink : Color.primary)
                .frame(width: 52, height: 52)
                .buttonStyle(.glass).buttonBorderShape(.circle)
                .accessibilityIdentifier("details.favourite")
                .disabled(model.details == nil || savingFavourite)
                .contextMenu {
                    if isFavourite { Button("整理分类", systemImage: "folder") { editingCategories = true } }
                }
        }
    }

    private func primaryLabel(_ title: String, subtitle: String?, icon: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.headline)
                if let subtitle { Text(subtitle).font(.caption).opacity(0.85).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .padding(.horizontal, 6)
    }

    private func pill(_ text: String, tint: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .foregroundStyle(tint)
            .background(tint.opacity(0.15), in: .capsule)
    }

    private func resumeChapter(_ details: IosSourceContent) -> IosSourceChapter? {
        guard let resume else { return nil }
        return details.chapters.first { $0.id == resume.chapterId }
    }

    static func stateTitle(_ state: String) -> String {
        switch state {
        case "ONGOING": "连载中"
        case "FINISHED": "已完结"
        case "ABANDONED": "已放弃"
        case "PAUSED": "暂停更新"
        case "UPCOMING": "即将推出"
        case "RESTRICTED": "受限"
        default: state
        }
    }

    static func stateTint(_ state: String) -> Color {
        switch state {
        case "ONGOING": .green
        case "FINISHED": .blue
        case "ABANDONED", "RESTRICTED": .red
        default: .orange
        }
    }

    @ViewBuilder
    private func episodes(_ details: IosSourceContent) -> some View {
        Section {
            if details.chapters.isEmpty { Text("暂无可播放的剧集").accessibilityIdentifier("details.empty") }
            if model.lines.count > 1 {
                if model.lines.count <= 3 {
                    linePicker.pickerStyle(.segmented)
                } else {
                    linePicker.pickerStyle(.menu)
                }
            }
            if Self.hasLongTitles(model.visibleChapters) {
                // Long episode names (e.g. "Episode 1 - I'm Luffy!") read better one per row.
                ForEach(model.visibleChapters, id: \.id) { chapter in
                    Button {
                        playing = PlaybackRequest(content: details, chapter: chapter)
                    } label: {
                        Label(Self.episodeTitle(chapter), systemImage: "play.circle")
                    }
                    .accessibilityIdentifier("chapter.\(chapter.id)")
                }
            } else {
            // Rows of four keep long series (1000+ episodes) lazy: a grid inside one List row would build every button.
            ForEach(Self.rows(model.visibleChapters), id: \.first?.id) { row in
                HStack(spacing: 10) {
                    ForEach(row, id: \.id) { chapter in
                        Button {
                            playing = PlaybackRequest(content: details, chapter: chapter)
                        } label: {
                            Text(Self.episodeTitle(chapter))
                                .font(.subheadline)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("chapter.\(chapter.id)")
                    }
                    ForEach(row.count..<Self.rowSize, id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
                .listRowSeparator(.hidden)
            }
            }
        } header: {
            Text("剧集")
        }
    }

    private var linePicker: some View {
        Picker("线路", selection: $model.selectedLine) {
            ForEach(model.lines, id: \.self) { line in
                Text(line ?? "默认").tag(line)
            }
        }
        .accessibilityIdentifier("details.line")
    }

    @ViewBuilder
    private func chapters(_ details: IosSourceContent) -> some View {
        let branches = model.branches
        let visible = model.chapterList(query: chapterQuery, newestFirst: newestFirst)
        let current = resumeChapter(details)
        let currentIndex = current.flatMap { chapter in details.chapters.firstIndex { $0.id == chapter.id } }
        Section {
            if details.chapters.count > 20 {
                TextField("搜索章节", text: $chapterQuery).textFieldStyle(.roundedBorder)
                    .listRowSeparator(.hidden)
            }
            if branches.count > 1 {
                Picker("分支", selection: $model.selectedBranch) {
                    ForEach(branches, id: \.self) { Text($0 ?? "默认").tag($0) }
                }
                .pickerStyle(.menu)
            }
            if visible.isEmpty { Text("暂无章节").foregroundStyle(.secondary).accessibilityIdentifier("details.empty") }
            ForEach(visible, id: \.id) { chapter in
                let index = details.chapters.firstIndex { $0.id == chapter.id }
                let isCurrent = chapter.id == current?.id
                let isRead = !isCurrent && index != nil && currentIndex != nil && index! < currentIndex!
                NavigationLink {
                    if model.isNovel {
                        NovelReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    } else {
                        SourceReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    }
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chapter.title).font(isCurrent ? .body.weight(.semibold) : .body)
                                .foregroundStyle(isRead ? .secondary : .primary)
                                .lineLimit(2)
                            if let subtitle = Self.chapterSubtitle(chapter) {
                                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                        if isCurrent, let resume {
                            Text("\(Int(resume.progress * 100))%").font(.caption.monospacedDigit())
                                .foregroundStyle(.tint)
                        }
                    }
                }
                .listRowBackground(isCurrent ? Color.accentColor.opacity(0.12) : nil)
                .accessibilityIdentifier("chapter.\(chapter.id)")
            }
        } header: {
            HStack {
                Text("章节（\(details.chapters.count)）")
                Spacer()
                Button(newestFirst ? "最新在前" : "最早在前", systemImage: "arrow.up.arrow.down") {
                    withAnimation { newestFirst.toggle() }
                }
                .labelStyle(.titleAndIcon).font(.caption).textCase(nil)
                .accessibilityIdentifier("details.sort")
            }
        }
    }

    static func chapterSubtitle(_ chapter: IosSourceChapter) -> String? {
        var parts: [String] = []
        if let scanlator = chapter.scanlator { parts.append(scanlator) }
        if chapter.uploadDate > 0 {
            parts.append(Date(timeIntervalSince1970: Double(chapter.uploadDate) / 1000).formatted(date: .abbreviated, time: .omitted))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    static let rowSize = 4

    static func hasLongTitles(_ chapters: [IosSourceChapter]) -> Bool {
        chapters.prefix(50).contains { episodeTitle($0).count > 7 }
    }

    static func rows(_ chapters: [IosSourceChapter]) -> [[IosSourceChapter]] {
        stride(from: 0, to: chapters.count, by: rowSize).map { Array(chapters[$0..<min($0 + rowSize, chapters.count)]) }
    }

    /// Episode titles repeat the line name ("线路A - 第1集"); the picker already shows it.
    static func episodeTitle(_ chapter: IosSourceChapter) -> String {
        guard let line = chapter.line, chapter.title.hasPrefix("\(line) - ") else { return chapter.title }
        return String(chapter.title.dropFirst(line.count + 3))
    }

    /// CMS descriptions are often HTML fragments.
    static func plainText(_ html: String) -> String {
        html.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct PlaybackRequest: Identifiable, Hashable {
    let content: IosSourceContent
    let chapter: IosSourceChapter
    var id: String { chapter.id }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.content.id == rhs.content.id && lhs.chapter.id == rhs.chapter.id }
    func hash(into hasher: inout Hasher) { hasher.combine(content.id); hasher.combine(chapter.id) }
}
