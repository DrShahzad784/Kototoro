import Combine
import KototoroShared
import SwiftUI

/// One source's listing: paged poster grid, categories and search. Paging follows the source's own mode.
@MainActor
final class SourceBrowseModel: ObservableObject {
    @Published private(set) var items: [IosSourceContent] = []
    @Published private(set) var categories: [IosSourceTag] = []
    @Published private(set) var selectedCategory: IosSourceTag?
    @Published private(set) var isLoading = false
    @Published private(set) var hasMore = true
    @Published private(set) var error: String?
    @Published var query = ""
    let runtime: IosSources
    let source: IosSource
    private var page: Int32 = 0
    private var activeQuery: String?
    private var generation = UUID()

    init(runtime: IosSources, source: IosSource) {
        self.runtime = runtime
        self.source = source
    }

    func start() async {
        guard items.isEmpty, !isLoading else { return }
        async let tags: Void = loadCategories()
        await loadMore()
        await tags
    }

    func select(_ category: IosSourceTag?) async {
        guard category?.key != selectedCategory?.key else { return }
        selectedCategory = category
        await reload()
    }

    func submitSearch() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        activeQuery = text.isEmpty ? nil : text
        await reload()
    }

    func clearSearch() async {
        guard activeQuery != nil else { return }
        activeQuery = nil
        await reload()
    }

    func reload() async {
        generation = UUID()
        items = []
        page = 0
        hasMore = true
        error = nil
        isLoading = false
        await loadMore()
    }

    func retry() async {
        if categories.isEmpty {
            async let tags: Void = loadCategories()
            await loadMore()
            await tags
        } else {
            await loadMore()
        }
    }

    func loadMore() async {
        guard hasMore, !isLoading else { return }
        let request = generation
        isLoading = true
        error = nil
        defer { if generation == request { isLoading = false } }
        do {
            let result = try await runtime.page(sourceName: source.name, page: page, loadedCount: Int32(items.count),
                                                query: activeQuery, tag: selectedCategory)
            guard generation == request else { return }
            let known = Set(items.map(\.id))
            let fresh = result.filter { !known.contains($0.id) }
            items.append(contentsOf: fresh)
            page += 1
            hasMore = !fresh.isEmpty
        } catch {
            if !Task.isCancelled, generation == request {
                self.error = error.localizedDescription
            }
        }
    }

    private func loadCategories() async {
        categories = (try? await runtime.categories(sourceName: source.name)) ?? []
    }
}

struct SourceBrowseView: View {
    @StateObject private var model: SourceBrowseModel
    @Environment(\.isSearching) private var isSearching

    init(runtime: IosSources, source: IosSource) {
        _model = StateObject(wrappedValue: SourceBrowseModel(runtime: runtime, source: source))
    }

    private let columns = [GridItem(.adaptive(minimum: 108, maximum: 180), spacing: 14, alignment: .top)]

    var body: some View {
        ScrollView {
            if !model.categories.isEmpty { categoryBar }
            LazyVGrid(columns: columns, spacing: 18) {
                ForEach(model.items, id: \.id) { item in
                    NavigationLink {
                        SourceDetailsView(runtime: model.runtime, content: item)
                    } label: {
                        PosterCard(runtime: model.runtime, content: item)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("source.content.\(item.id)")
                    .task { if item.id == model.items.last?.id, model.error == nil { await model.loadMore() } }
                }
            }
            .padding(.horizontal)
            footer
        }
        .navigationTitle(model.source.title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "搜索")
        .onSubmit(of: .search) { Task { await model.submitSearch() } }
        .onChange(of: model.query) { _, value in
            if value.isEmpty { Task { await model.clearSearch() } }
        }
        .refreshable { await model.reload() }
        .task { await model.start() }
    }

    private var categoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    chip("全部", selected: model.selectedCategory == nil) { await model.select(nil) }
                    ForEach(model.categories, id: \.key) { tag in
                        chip(tag.title, selected: model.selectedCategory?.key == tag.key) { await model.select(tag) }
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 6)
            }
        }
    }

    @ViewBuilder
    private func chip(_ title: String, selected: Bool, action: @escaping () async -> Void) -> some View {
        let button = Button(title) { Task { await action() } }.accessibilityIdentifier("category.\(title)")
        if selected {
            button.buttonStyle(.glassProminent)
        } else {
            button.buttonStyle(.glass)
        }
    }

    @ViewBuilder
    private var footer: some View {
        if let error = model.error {
            VStack(spacing: 8) {
                Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    .accessibilityIdentifier("sources.error")
                Button("重试") { Task { await model.retry() } }
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("browse.retry")
            }
            .padding()
        } else if model.isLoading {
            ProgressView().padding()
        } else if model.items.isEmpty {
            ContentUnavailableView("没有内容", systemImage: "square.grid.2x2",
                                   description: Text(model.query.isEmpty ? "此源暂未返回内容。" : "没有找到相关结果。"))
                .accessibilityIdentifier("browse.empty")
        }
    }
}

/// 2:3 poster with the title underneath. Extension covers go through the source's client; others are plain URLs.
struct PosterCard: View {
    let runtime: IosSources
    let content: IosSourceContent

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoverImage(runtime: runtime, content: content)
                .aspectRatio(2 / 3, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text(content.title)
                .font(.footnote.weight(.medium))
                .lineLimit(2, reservesSpace: true)
            if let summary = content.summary {
                Text(summary).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .contentShape(Rectangle())
    }
}

struct CoverImage: View {
    let runtime: IosSources
    let content: IosSourceContent
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            if runtime.fetchesCovers(content: content) {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if failed {
                    placeholder
                }
            } else if let url = content.coverUrl.flatMap(URL.init(string:)) {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                    switch phase {
                    case .success(let image): image.resizable().scaledToFill()
                    case .failure: placeholder
                    default: EmptyView()
                    }
                }
            } else {
                placeholder
            }
        }
        .clipped()
        .task(id: content.id) {
            guard runtime.fetchesCovers(content: content), content.hasCover, image == nil else { return }
            // Extension covers go through the source's own client in the JVM, which serializes per source: keep at
            // most two in flight so opening details is not queued behind a whole grid of covers.
            guard await CoverGate.shared.enter() else { return }
            defer { Task { await CoverGate.shared.leave() } }
            do { image = try await runtime.cover(content: content).decodedImage() } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }

    private var placeholder: some View {
        Image(systemName: content.mediaKind == "VIDEO" ? "film" : content.mediaKind == "NOVEL" ? "text.book.closed" : "book.closed")
            .font(.title)
            .foregroundStyle(.secondary)
    }
}

/// Bounded concurrency for extension cover requests. Waiters whose view disappeared (task cancelled) never start.
actor CoverGate {
    static let shared = CoverGate(limit: 2)
    private let limit: Int
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) { self.limit = limit }

    /// Returns false when the calling task was cancelled while waiting; the slot is not held then.
    func enter() async -> Bool {
        if running < limit {
            running += 1
        } else {
            await withCheckedContinuation { waiters.append($0) }
        }
        if Task.isCancelled {
            leave()
            return false
        }
        return true
    }

    func leave() {
        if waiters.isEmpty {
            running -= 1
        } else {
            waiters.removeFirst().resume()  // hand the slot over directly
        }
    }
}
