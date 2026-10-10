import KototoroShared
import SwiftUI

struct LibraryContentRow: View {
    let content: IosSourceContent
    let runtime: IosSources
    var progress: Float?
    var subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            CoverImage(runtime: runtime, content: content)
                .frame(width: 48, height: 70)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 4) {
                Text(content.title).lineLimit(2)
                Text(subtitle ?? SourcesView.kindName(content.mediaKind))
                    .font(.caption).foregroundStyle(.secondary)
                if let progress {
                    ProgressView(value: Double(progress), total: 1)
                    Text("已读 \(Int(progress * 100))%")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct LibraryRecordsView: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @State private var selection = 0

    var body: some View {
        NavigationStack {
            List {
                if let error = library.error { Text(error).foregroundStyle(.red) }
                Picker("记录", selection: $selection) {
                    Text("历史").tag(0)
                    Text("更新").tag(1)
                }
                .pickerStyle(.segmented)
                if let runtime = sources.runtime {
                    if selection == 0 {
                        ForEach(library.history, id: \.content.id) { entry in
                            NavigationLink {
                                SourceDetailsView(runtime: runtime, content: entry.content)
                            } label: {
                                LibraryContentRow(content: entry.content, runtime: runtime,
                                                  progress: entry.hasProgress ? entry.progress : nil)
                            }
                            .accessibilityIdentifier("history.\(entry.content.id)")
                            .swipeActions {
                                Button("删除记录", systemImage: "trash", role: .destructive) {
                                    Task { await library.removeHistory(entry.content) }
                                }
                            }
                        }
                        if library.history.isEmpty {
                            ContentUnavailableView("暂无阅读记录", systemImage: "clock",
                                                   description: Text("阅读漫画、小说或播放视频后会自动记录进度。"))
                        }
                    } else {
                        if let summary = library.updateSummary { Text(summary).font(.footnote).foregroundStyle(.secondary) }
                        ForEach(library.updates, id: \.content.id) { update in
                            NavigationLink {
                                SourceDetailsView(runtime: runtime, content: update.content)
                                    .task {
                                        try? await library.library?.markUpdateRead(content: update.content)
                                        await library.refresh()
                                    }
                            } label: {
                                LibraryContentRow(content: update.content, runtime: runtime,
                                                  subtitle: "新增 \(update.newChapters) 章")
                            }
                        }
                        if library.updates.isEmpty {
                            ContentUnavailableView("暂无新章节", systemImage: "arrow.triangle.2.circlepath",
                                                   description: Text("检查收藏作品，获取最新章节。"))
                        }
                    }
                }
            }
            .navigationTitle(selection == 0 ? "历史" : "更新")
            .toolbar {
                if selection == 1, let runtime = sources.runtime {
                    Button("检查更新", systemImage: "arrow.clockwise") { Task { await library.checkUpdates(runtime) } }
                        .disabled(library.isCheckingUpdates)
                        .accessibilityIdentifier("updates.check")
                }
            }
            .overlay { if library.isCheckingUpdates { ProgressView("正在检查收藏更新…") } }
            .refreshable {
                if selection == 1, let runtime = sources.runtime { await library.checkUpdates(runtime) }
                else { await library.refresh() }
            }
            .task { await library.refresh() }
        }
    }
}
