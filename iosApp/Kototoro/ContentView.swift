import SwiftUI
import KototoroShared

struct ContentView: View {
    @ObservedObject var library: LibraryModel
    @StateObject private var sources = SourcesModel()
    @State private var isAddingCategory = false
    @State private var categoryTitle = ""
    @State private var incomingLink: IncomingRepositoryLink?
    @State private var selectedCategory: Int32?
    @State private var categoryContent: CategoryContent?

    var body: some View {
        TabView {
            NavigationStack {
                List {
                    if let runtime = sources.runtime, !library.favourites.isEmpty {
                        Section("收藏") {
                            ForEach(library.favourites.filter { selectedCategory == nil || $0.categoryIds.contains { $0.int32Value == selectedCategory } }, id: \.content.id) { entry in
                                NavigationLink {
                                    SourceDetailsView(runtime: runtime, content: entry.content)
                                } label: {
                                    LibraryContentRow(content: entry.content, runtime: runtime,
                                                      progress: entry.hasProgress ? entry.progress : nil)
                                }
                                .accessibilityIdentifier("favourite.\(entry.content.id)")
                                .contextMenu {
                                    Button("整理分类", systemImage: "folder") { categoryContent = CategoryContent(content: entry.content) }
                                    Button("取消收藏", systemImage: "heart.slash", role: .destructive) {
                                        Task { try? await library.setFavourite(entry.content, favourite: false) }
                                    }
                                }
                            }
                        }
                    }
                    if library.isLoading {
                        ProgressView("正在读取漫画库…")
                    } else if let error = library.error {
                        ContentUnavailableView {
                            Label("漫画库暂时不可用", systemImage: "exclamationmark.triangle")
                        } description: {
                            Text(error)
                        } actions: {
                            Button("重试") { Task { await library.load() } }
                        }
                    } else if library.categories.isEmpty {
                        ContentUnavailableView(
                            "开始整理漫画库",
                            systemImage: "books.vertical",
                            description: Text("创建分类，整理你接下来想读的漫画。")
                        )
                        .accessibilityIdentifier("library.empty")
                    } else {
                        Section("分类") {
                            ForEach(library.categories, id: \.id) { category in
                                Button {
                                    selectedCategory = selectedCategory == category.id ? nil : category.id
                                } label: {
                                    Label(category.title, systemImage: selectedCategory == category.id ? "folder.fill" : "folder")
                                }
                                    .accessibilityIdentifier("category.\(category.id)")
                            }
                        }
                    }
                }
                .navigationTitle("漫画库")
                .refreshable { await library.refresh() }
                .task { await library.refresh() }
                .toolbar {
                    Button("添加分类", systemImage: "folder.badge.plus") {
                        categoryTitle = ""
                        isAddingCategory = true
                    }
                    .disabled(library.isLoading || library.schemaVersion == nil)
                    .accessibilityIdentifier("category.add")
                }
                .sheet(isPresented: $isAddingCategory) {
                    NavigationStack {
                        Form {
                            TextField("分类名称", text: $categoryTitle)
                                .accessibilityIdentifier("category.title")
                            if let error = library.error {
                                Text(error).foregroundStyle(.red)
                            }
                        }
                        .navigationTitle("新建分类")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("取消") { isAddingCategory = false }
                            }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("保存") {
                                    Task {
                                        if await library.addCategory(categoryTitle) {
                                            isAddingCategory = false
                                        }
                                    }
                                }
                                .disabled(categoryTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                          || library.isLoading)
                                .accessibilityIdentifier("category.save")
                            }
                        }
                        .interactiveDismissDisabled(library.isLoading)
                    }
                    .presentationDetents([.medium])
                }
                .sheet(item: $categoryContent) { request in FavouriteCategoriesView(content: request.content) }
            }
            .tabItem { Label("漫画库", systemImage: "books.vertical") }

            SourcesView(library: library, model: sources)
            .tabItem { Label("浏览", systemImage: "safari") }

            LibraryRecordsView()
                .tabItem { Label("记录", systemImage: "clock.arrow.circlepath") }

            NavigationStack {
                List {
                    Section("本地存储") {
                        if let version = library.schemaVersion {
                            LabeledContent("数据库版本", value: "\(version)")
                                .accessibilityIdentifier("database.ready")
                        } else {
                            Text("正在打开数据库…")
                        }
                    }
                    Section("关于") {
                        LabeledContent("应用", value: "Kototoro")
                        LabeledContent("版本", value: "iOS 开发版")
                    }
                    NavigationLink { RepositoriesView() } label: { Label("仓库与扩展", systemImage: "shippingbox") }
                }
                .navigationTitle("设置")
            }
            .tabItem { Label("设置", systemImage: "gearshape") }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .environmentObject(library)
        .environmentObject(sources)
        .onOpenURL { url in incomingLink = IncomingRepositoryLink(address: url.absoluteString) }
        .sheet(item: $incomingLink) { link in
            NavigationStack {
                RepositoriesView(incoming: link.address)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { incomingLink = nil } } }
            }
        }
        .task(id: library.library == nil) {
            guard let database = library.library else { return }
            sources.attach(database)
            await sources.load()
            await library.refresh()
        }
    }
}

private struct CategoryContent: Identifiable {
    let content: IosSourceContent
    var id: String { content.id }
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
