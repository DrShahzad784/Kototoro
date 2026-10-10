import Combine
import KototoroShared
import SwiftUI

/// Both child screens borrow the browsing session; leaving a screen must not close the process VM.
@MainActor
final class SourceDetailsModel: ObservableObject {
    @Published private(set) var details: IosSourceContent?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published var selectedLine: String?
    let runtime: IosSources
    let content: IosSourceContent
    private var detailsRequest: UUID?

    init(runtime: IosSources, content: IosSourceContent) {
        self.runtime = runtime
        self.content = content
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
        error = nil
        defer { if detailsRequest == request { isLoading = false } }
        do {
            let result = try await runtime.details(content: content)
            try Task.checkCancellation()
            if detailsRequest == request {
                details = result
                selectedLine = lines.first ?? nil
            }
        } catch {
            if !Task.isCancelled, detailsRequest == request {
                self.error = error.localizedDescription
                // Saved full details retain their chapters for offline reading.
                if !content.chapters.isEmpty { details = content; selectedLine = lines.first ?? nil }
            }
        }
    }
}

struct SourceDetailsView: View {
    @EnvironmentObject private var library: LibraryModel
    @StateObject private var model: SourceDetailsModel
    @State private var playing: PlaybackRequest?
    @State private var isSummaryExpanded = false
    @State private var isFavourite = false
    @State private var recordError: String?
    @State private var resume: IosReadingPosition?
    @State private var editingCategories = false
    @State private var savingFavourite = false

    init(runtime: IosSources, content: IosSourceContent) {
        _model = StateObject(wrappedValue: SourceDetailsModel(runtime: runtime, content: content))
    }

    var body: some View {
        List {
            header
            if let recordError { Text(recordError).foregroundStyle(.red) }
            if model.isLoading { ProgressView("正在读取详情…").frame(maxWidth: .infinity) }
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).accessibilityIdentifier("details.error")
                    Button("重试") { Task { await model.load() } }.disabled(model.isLoading)
                }
            }
            if let details = model.details {
                if model.isVideo { episodes(details) } else { chapters(details) }
            }
        }
        .navigationTitle(model.content.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await model.load()
            await refreshRecords()
        }
        .toolbar {
            Button(isFavourite ? "取消收藏" : "收藏", systemImage: isFavourite ? "heart.fill" : "heart") {
                Task {
                    savingFavourite = true
                    defer { savingFavourite = false }
                    do {
                        try await library.setFavourite(model.details ?? model.content, favourite: !isFavourite)
                        isFavourite.toggle()
                    } catch { recordError = error.localizedDescription }
                }
            }
            .accessibilityIdentifier("details.favourite")
            .disabled(model.details == nil || savingFavourite)
            if isFavourite {
                Button("整理分类", systemImage: "folder") { editingCategories = true }
            }
        }
        .sheet(isPresented: $editingCategories) { FavouriteCategoriesView(content: model.details ?? model.content) }
        .fullScreenCover(item: $playing, onDismiss: { Task { await refreshRecords() } }) { request in
            VideoPlayerView(runtime: model.runtime, content: request.content, chapter: request.chapter)
        }
    }

    private func refreshRecords() async {
        guard let content = model.details, let database = library.library else { return }
        do {
            try await database.saveDetails(content: content)
            isFavourite = try await database.isFavourite(content: content).boolValue
            resume = try await database.position(content: content)
        } catch { recordError = error.localizedDescription }
    }

    private var header: some View {
        let shown = model.details ?? model.content
        return Section {
            HStack(alignment: .top, spacing: 14) {
                CoverImage(runtime: model.runtime, content: shown)
                    .frame(width: 112, height: 168)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel("封面")
                    .accessibilityIdentifier("details.cover")
                VStack(alignment: .leading, spacing: 6) {
                    Text(shown.title).font(.title3.weight(.semibold)).accessibilityIdentifier("details.title")
                    if !shown.authors.isEmpty {
                        Text(shown.authors.joined(separator: "、")).font(.subheadline).foregroundStyle(.secondary)
                    }
                    if !shown.tags.isEmpty {
                        Text(shown.tags.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                    if let details = model.details {
                        Text(model.isVideo ? "\(details.chapters.count) 个剧集" : "\(details.chapters.count) 个章节")
                            .font(.caption).foregroundStyle(.tertiary)
                    }
                }
            }
            .listRowBackground(Color.clear)
            if let summary = shown.summary.map(Self.plainText), !summary.isEmpty {
                Text(summary)
                    .font(.callout)
                    .lineLimit(isSummaryExpanded ? nil : 4)
                    .onTapGesture { withAnimation { isSummaryExpanded.toggle() } }
                    .accessibilityIdentifier("details.summary")
            }
            if let resume, let details = model.details,
               let chapter = details.chapters.first(where: { $0.id == resume.chapterId }) {
                if model.isVideo {
                    Button("继续播放", systemImage: "play.fill") { playing = PlaybackRequest(content: details, chapter: chapter) }
                        .accessibilityIdentifier("details.resume")
                } else {
                    NavigationLink {
                        if model.isNovel { NovelReaderView(runtime: model.runtime, content: details, chapter: chapter) }
                        else { SourceReaderView(runtime: model.runtime, content: details, chapter: chapter) }
                    } label: { Label("继续阅读", systemImage: "book.fill") }
                    .accessibilityIdentifier("details.resume")
                }
            }
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
        Section("章节（\(details.chapters.count)）") {
            if details.chapters.isEmpty { Text("暂无章节").accessibilityIdentifier("details.empty") }
            ForEach(details.chapters, id: \.id) { chapter in
                NavigationLink {
                    if model.isNovel {
                        NovelReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    } else {
                        SourceReaderView(runtime: model.runtime, content: details, chapter: chapter)
                    }
                } label: {
                    Text(chapter.title)
                }
                .accessibilityIdentifier("chapter.\(chapter.id)")
            }
        }
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

struct PlaybackRequest: Identifiable {
    let content: IosSourceContent
    let chapter: IosSourceChapter
    var id: String { chapter.id }
}
