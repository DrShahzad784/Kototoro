import SwiftUI

struct ContentView: View {
    @ObservedObject var library: LibraryModel
    @State private var isAddingCategory = false
    @State private var categoryTitle = ""

    var body: some View {
        TabView {
            NavigationStack {
                List {
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
                                Label(category.title, systemImage: "folder")
                                    .accessibilityIdentifier("category.\(category.id)")
                            }
                        }
                    }
                }
                .navigationTitle("漫画库")
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
            }
            .tabItem { Label("漫画库", systemImage: "books.vertical") }

            SourcesView()
            .tabItem { Label("浏览", systemImage: "safari") }

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
                }
                .navigationTitle("设置")
            }
            .tabItem { Label("设置", systemImage: "gearshape") }
        }
    }
}
