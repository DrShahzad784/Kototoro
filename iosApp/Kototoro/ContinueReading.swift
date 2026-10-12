import KototoroShared
import SwiftUI

/// Android's "continue reading" button, as the tab bar's bottom accessory (the system place for an ongoing activity,
/// like Music's now playing): the latest work of the active space from the history, opened straight at the chapter
/// being read.
struct ContinueReadingAccessory: View {
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @AppStorage(ContentSpace.key) private var space = ""
    @AppStorage(ContentSpace.enabledKey) private var spacesEnabled = true
    @Environment(\.openContinueReading) private var open

    private var latest: IosLibraryEntry? {
        ContentSpace.latest(in: library.history, enabled: spacesEnabled, space: space)
    }

    var body: some View {
        Button {
            if let latest { open(CategoryContent(content: latest.content)) }
        } label: {
            HStack(spacing: 10) {
                if let latest, sources.runtime != nil {
                    // Loaded by the tab container: the system-hosted accessory does not run its views' tasks.
                    Group {
                        if let cover = ContinueReadingCover.shared.image { Image(uiImage: cover).renderingMode(.original).resizable().scaledToFill() }
                        else { Rectangle().fill(.quaternary) }
                    }
                    .frame(width: 24, height: 34)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(latest.content.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        if placement != .inline {
                            Text(ContinueReadingDetail.text(latest))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 4)
                    Image(systemName: latest.content.mediaKind == "VIDEO" ? "play.fill" : "book.fill")
                        .font(.body.weight(.semibold))
                } else {
                    Image(systemName: "book")
                    Text("开始阅读后可在这里继续").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
            }
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(latest == nil || sources.runtime == nil)
        .accessibilityLabel(latest.map { "继续阅读：\($0.content.title)" } ?? "继续阅读")
        .accessibilityIdentifier("continueReading")
    }
}

enum ContinueReadingDetail {
    /// "继续阅读 · 第 12 话 · 35%": the chapter read last, then the overall progress when known.
    static func text(_ entry: IosLibraryEntry) -> String {
        let action = entry.content.mediaKind == "VIDEO" ? "继续播放" : "继续阅读"
        var parts = [action]
        if let chapter = entry.lastChapterTitle { parts.append(chapter) }
        if entry.hasProgress && entry.progress >= 0.01 { parts.append("\(Int(entry.progress * 100))%") }
        return parts.joined(separator: " · ")
    }
}

private struct ContinueReadingPresentation: ViewModifier {
    @Binding var opened: CategoryContent?
    @EnvironmentObject private var library: LibraryModel
    @EnvironmentObject private var sources: SourcesModel
    @EnvironmentObject private var downloads: ReaderDownloadQueue

    func body(content: Content) -> some View {
        content.fullScreenCover(item: $opened) { item in
            if let runtime = sources.runtime {
                NavigationStack {
                    SourceDetailsView(runtime: runtime, content: item.content, autoResume: true)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button("关闭", systemImage: "xmark") { opened = nil }
                                    .accessibilityIdentifier("continueReading.close")
                            }
                        }
                }
                // A cover sits outside the injected environment; pass the models explicitly.
                .environmentObject(library)
                .environmentObject(sources)
                .environmentObject(downloads)
            }
        }
    }
}

/// Pushed readers cover the screen, so the tab bar's continue-reading accessory steps aside while one is shown.
@MainActor @Observable
final class ReaderPresence {
    static let shared = ReaderPresence()
    private(set) var visibleReaders = 0
    var isReading: Bool { visibleReaders > 0 }
    func appear() { visibleReaders += 1 }
    func disappear() { visibleReaders = max(0, visibleReaders - 1) }
}

private struct ReaderPresenceModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .onAppear { ReaderPresence.shared.appear() }
            .onDisappear { ReaderPresence.shared.disappear() }
    }
}

extension View {
    /// Marks a full-screen reading surface (manga, novel or video) while it is on screen.
    func readerPresence() -> some View { modifier(ReaderPresenceModifier()) }

    /// One resume presentation shared by the tab accessory and the space panel.
    func continueReadingPresentation(opened: Binding<CategoryContent?>) -> some View {
        modifier(ContinueReadingPresentation(opened: opened))
    }

    /// The continue-reading accessory, shown only while there is something to continue where the system allows it.
    func continueReadingAccessory(enabled: Bool) -> some View {
        modifier(ContinueReadingAccessoryModifier(enabled: enabled))
    }
}

private struct ContinueReadingAccessoryModifier: ViewModifier {
    let enabled: Bool
    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: enabled) { ContinueReadingAccessory() }
        } else {
            content.tabViewBottomAccessory { ContinueReadingAccessory() }
        }
    }
}

/// Presents a resumed work from the tab container rather than the accessory, which hides while a reader is shown
/// and must not take its own presentation down with it.
/// The accessory's cover, loaded by the tab container. Observable rather than an environment value: the
/// system-hosted accessory keeps the environment it was created with, but follows observation.
@MainActor @Observable
final class ContinueReadingCover {
    static let shared = ContinueReadingCover()
    var image: UIImage?
}

private struct OpenContinueReadingKey: EnvironmentKey {
    static let defaultValue: (CategoryContent) -> Void = { _ in }
}

extension EnvironmentValues {
    var openContinueReading: (CategoryContent) -> Void {
        get { self[OpenContinueReadingKey.self] }
        set { self[OpenContinueReadingKey.self] = newValue }
    }
}

/// Opens the app-wide search (the search tab) from a page's toolbar.
private struct OpenGlobalSearchKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var openGlobalSearch: () -> Void {
        get { self[OpenGlobalSearchKey.self] }
        set { self[OpenGlobalSearchKey.self] = newValue }
    }
}

/// In-place page search (library, browse, records). The system's minimized toolbar search cannot be closed inside
/// this TabView (its close button only drops the keyboard and the field refocuses), so the page owns the whole
/// interaction: a toolbar button opens a compact field at the top; submitting collapses it to a keyword capsule
/// that reopens the field in one tap and clears the search in another; cancel closes and clears.
private struct PageSearch: ViewModifier {
    @Binding var text: String
    let prompt: String
    @State private var editing = false
    @FocusState private var focused: Bool

    private var keyword: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("搜索", systemImage: "magnifyingglass") { open() }
                        .accessibilityIdentifier("pageSearch.open")
                }
            }
            .safeAreaBar(edge: .top, spacing: 0) {
                Group {
                    if editing { field } else if !keyword.isEmpty { capsule }
                }
                .padding(.horizontal)
                .padding(.bottom, 6)
                .animation(.snappy, value: editing)
            }
            .onChange(of: focused) { _, isFocused in
                // Leaving the field (scrolling, another control) keeps a typed keyword as the capsule.
                if !isFocused && !keyword.isEmpty { editing = false }
            }
    }

    private var field: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField(prompt, text: $text)
                    .focused($focused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { focused = false; editing = keyword.isEmpty ? editing : false }
                    .accessibilityIdentifier("pageSearch.field")
                if !text.isEmpty {
                    Button("清除文本", systemImage: "xmark.circle.fill") { text = "" }
                        .labelStyle(.iconOnly).foregroundStyle(.secondary).buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .glassEffect(.regular.interactive(), in: .capsule)
            Button("取消") { close() }
                .accessibilityIdentifier("pageSearch.cancel")
        }
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private var capsule: some View {
        HStack(spacing: 6) {
            Button { open() } label: {
                Label(keyword, systemImage: "magnifyingglass").lineLimit(1)
            }
            .accessibilityLabel("正在搜索：\(keyword)")
            .accessibilityHint("编辑搜索词")
            .accessibilityIdentifier("pageSearch.keyword")
            Button("清除搜索", systemImage: "xmark.circle.fill") { withAnimation(.snappy) { text = "" } }
                .labelStyle(.iconOnly)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("pageSearch.clear")
        }
        .font(.footnote.weight(.medium))
        .buttonStyle(.plain)
        .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 6)
        .glassEffect(.regular.interactive(), in: .capsule)
        .frame(maxWidth: .infinity, alignment: .leading)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private func open() {
        editing = true
        Task { @MainActor in focused = true }
    }

    private func close() {
        focused = false
        editing = false
        text = ""
    }
}

extension View {
    func pageSearch(text: Binding<String>, prompt: String) -> some View {
        modifier(PageSearch(text: text, prompt: prompt))
    }
}
