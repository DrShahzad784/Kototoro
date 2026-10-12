import SwiftUI
import KototoroShared

struct ContentView: View {
    private enum MainTab: Hashable { case library, browse, records, settings, search }
    @ObservedObject var library: LibraryModel
    @StateObject private var sources = SourcesModel()
    @StateObject private var downloads = ReaderDownloadQueue(
        file: NativeSourceTransport.downloadQueueFile
    )
    @Environment(\.scenePhase) private var scenePhase
    @State private var incomingLink: IncomingRepositoryLink?
    @State private var openedFileMessage: String?
    @State private var globalSearchQuery = ""
    @State private var selectedTab = MainTab.library
    @State private var searchSubmission = 0
    @State private var resumed: CategoryContent?
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true

    private var resumeLatest: IosLibraryEntry? {
        ContentSpace.latest(in: library.history, enabled: spacesEnabled, space: space)
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("作品库", systemImage: "books.vertical", value: MainTab.library) {
                LibraryView(library: library, sources: sources)
            }
            Tab("浏览", systemImage: "safari", value: MainTab.browse) {
                SourcesView(library: library, model: sources)
            }
            Tab("记录", systemImage: "clock.arrow.circlepath", value: MainTab.records) {
                LibraryRecordsView()
            }
            Tab("设置", systemImage: "gearshape", value: MainTab.settings) {
            NavigationStack {
                List {
                    Section {
                        NavigationLink { RepositoriesView() } label: { Label("仓库与扩展", systemImage: "shippingbox") }
                        NavigationLink { ReaderDownloadsView() } label: { Label("下载", systemImage: "arrow.down.circle") }
                            .accessibilityIdentifier("settings.downloads")
                        NavigationLink { BackupView() } label: { Label("备份与恢复", systemImage: "externaldrive") }
                            .accessibilityIdentifier("settings.backup")
                    }
                    Section("本地存储") {
                        if let version = library.schemaVersion {
                            LabeledContent("数据库版本", value: "\(version)")
                                .accessibilityIdentifier("database.ready")
                        } else {
                            Text("正在打开数据库…")
                        }
                    }
                    ContentSpaceSettingsSection()
                    PanoramaSettingsSection()
                    ReaderLoadingSettingsSection()
                    Section("关于") {
                        LabeledContent("应用", value: "Kototoro")
                        LabeledContent("版本", value: "iOS 开发版")
                    }
                }
                .navigationTitle("设置")
            }
            }
            // The system's separate search button at the trailing end of the tab bar (iOS 26).
            Tab(value: MainTab.search, role: .search) {
                GlobalSearchView(query: $globalSearchQuery, submission: searchSubmission)
            }
        }
        .searchable(text: $globalSearchQuery, prompt: "搜索全部来源中的作品")
        .tabViewSearchActivation(.searchTabSelection)
        .onSubmit(of: .search) {
            if selectedTab == .search { searchSubmission += 1 }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .continueReadingAccessory(
            enabled: !ReaderPresence.shared.isReading &&
                ContentSpace.latest(in: library.history, enabled: spacesEnabled, space: space) != nil
        )
        .continueReadingPresentation(opened: $resumed)
        .environment(\.openContinueReading) { resumed = $0 }
        .task(id: "\(resumeLatest?.content.sourceName ?? "")|\(resumeLatest?.content.id ?? "")|\(sources.runtime != nil)") {
            ContinueReadingCover.shared.image = nil
            guard let latest = resumeLatest, let runtime = sources.runtime else { return }
            ContinueReadingCover.shared.image = try? await CoverImage.image(runtime: runtime, content: latest.content)
        }
        .environment(\.openGlobalSearch) { selectedTab = .search }
        .environmentObject(library)
        .environmentObject(sources)
        .environmentObject(downloads)
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { downloads.setForeground(false) }
            else if phase == .active { downloads.setForeground(true) }
        }
        .onOpenURL { url in
            // Files shared to the app ("用 Kototoro 打开") become local works; links add repositories.
            guard url.isFileURL else { incomingLink = IncomingRepositoryLink(address: url.absoluteString); return }
            guard let runtime = sources.runtime else { return }
            Task {
                openedFileMessage = await LocalImport.run([url], runtime: runtime)
                    ?? "已导入“\(url.deletingPathExtension().lastPathComponent)”，可在“浏览 · 本地作品”中查看"
            }
        }
        .alert("导入本地文件", isPresented: Binding(get: { openedFileMessage != nil },
                                                   set: { if !$0 { openedFileMessage = nil } })) {
            Button("好") { openedFileMessage = nil }
        } message: {
            Text(openedFileMessage ?? "")
        }
        .sheet(item: $incomingLink) { link in
            NavigationStack {
                RepositoriesView(incoming: link.address)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { incomingLink = nil } } }
            }
            // A sheet on this modifier chain sits outside the injected environment; pass the models explicitly.
            .environmentObject(library)
            .environmentObject(sources)
            .environmentObject(downloads)
        }
        .task(id: library.library == nil) {
            guard let database = library.library else { return }
            sources.attach(database)
            if let runtime = sources.runtime { downloads.attach(runtime: runtime) }
            await sources.load()
            await library.refresh()
        }
    }
}

struct FavouriteCategoriesView: View {
    let content: IosSourceContent
    @EnvironmentObject private var library: LibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<Int32> = []
    @State private var error: String?
    @State private var saving = false
    var body: some View {
        NavigationStack {
            List {
                ForEach(library.categories, id: \.id) { category in
                    Toggle(category.title, isOn: Binding(get: { selected.contains(category.id) }, set: {
                        if $0 { selected.insert(category.id) } else { selected.remove(category.id) }
                    }))
                }
                if let error { Text(error).foregroundStyle(.red) }
                Text("移除全部分类会取消收藏。").font(.caption).foregroundStyle(.secondary)
            }
            .navigationTitle("整理分类")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saving = true
                        Task {
                            do {
                                try await library.library?.setCategories(content: content, categoryIds: selected.map { KotlinInt(value: $0) })
                                await library.refresh(); dismiss()
                            } catch { self.error = error.localizedDescription }
                            saving = false
                        }
                    }.disabled(saving)
                }
            }
            .task {
                do { selected = Set(try await library.library?.favouriteCategories(content: content).map(\.int32Value) ?? []) }
                catch { self.error = error.localizedDescription }
            }
        }
    }
}

private struct IncomingRepositoryLink: Identifiable {
    let id = UUID()
    let address: String
}
