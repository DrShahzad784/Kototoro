import AVFoundation
import AVKit
import Combine
import KototoroShared
import SwiftUI

/// Resolves an episode through the shared runtime and plays it with the system player (AVPlayerViewController:
/// native controls, AirPlay, Picture in Picture). Advances to the next episode of the same line at the end.
@MainActor
final class VideoPlayerModel: ObservableObject {
    @Published private(set) var chapter: IosSourceChapter
    @Published private(set) var player: AVPlayer?
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false
    @Published private(set) var position: Double = 0
    /// Every playable stream of the episode (Aniyomi qualities/hosters, CMS single stream); the preferred one first.
    @Published private(set) var streams: [IosSourcePage] = []
    @Published private(set) var streamIndex = 0
    let runtime: IosSources
    let content: IosSourceContent
    private var request: UUID?
    private var timeObserver: Any?
    private var observers: [AnyCancellable] = []
    var library: IosLibrary?
    private var lastSavedAt = 0.0

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        self.runtime = runtime
        self.content = content
        self.chapter = chapter
    }

    var previous: IosSourceChapter? { adjacent(forward: false) }
    var next: IosSourceChapter? { adjacent(forward: true) }

    /// Shared navigation keeps the source/branch rules; episodes additionally stay on the current playback line.
    private func adjacent(forward: Bool) -> IosSourceChapter? {
        var candidate = content.adjacentChapter(chapter: chapter, forward: forward)
        while let current = candidate, current.line != chapter.line {
            candidate = content.adjacentChapter(chapter: current, forward: forward)
        }
        return candidate
    }

    func load() async {
        let request = UUID()
        self.request = request
        let selected = chapter
        isLoading = true
        error = nil
        defer { if self.request == request { isLoading = false } }
        do {
            let pages = try await runtime.pages(chapter: selected)
            try Task.checkCancellation()
            guard self.request == request else { return }
            guard !pages.isEmpty else {
                throw NSError(domain: "org.skepsun.kototoro.video", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "此剧集没有可播放的地址"])
            }
            streams = pages
            if position == 0, let saved = try await library?.position(content: content), saved.chapterId == selected.id {
                position = Double(saved.scroll)
            }
            select(stream: 0)
        } catch {
            if !Task.isCancelled, self.request == request { self.error = error.localizedDescription }
        }
    }

    /// Switches stream and resumes from the current position.
    func select(stream index: Int) {
        guard streams.indices.contains(index) else { return }
        let resume = position
        stop(keepStreams: true)
        streamIndex = index
        guard let url = URL(string: streams[index].url) else {
            error = "无效的播放地址"
            return
        }
        error = nil
        play(url: url, headers: streams[index].headers, from: resume)
    }

    func move(to target: IosSourceChapter) async {
        await persist()
        stop()
        chapter = target
        await load()
    }

    func stop(keepStreams: Bool = false) {
        if !keepStreams {
            streams = []
            streamIndex = 0
        }
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observers.removeAll()
        player?.pause()
        player = nil
        position = 0
        lastSavedAt = 0
    }

    func persist() async {
        guard position.isFinite, position > 0, let library else { return }
        let duration = player?.currentItem?.duration.seconds ?? 0
        let progress = duration.isFinite && duration > 0 ? min(999, Int(position / duration * 1000)) : 0
        do { try await library.recordPosition(content: content, chapter: chapter, index: Int32(progress),
                                              count: 1000, scroll: Float(position)) }
        catch { self.error = error.localizedDescription }
    }

    private func play(url: URL, headers: [String: String], from start: Double = 0) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        // Sites commonly require their Referer / User-Agent on every playlist and segment request.
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
                                                      queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, time.seconds.isFinite else { return }
                self.position = time.seconds
                if abs(self.position - self.lastSavedAt) >= 5 {
                    self.lastSavedAt = self.position
                    Task { await self.persist() }
                }
            }
        }
        NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification, object: item)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self, let next = self.next else { return }
                Task { await self.move(to: next) }
            }
            .store(in: &observers)
        item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                guard let self, status == .failed else { return }
                // Hosters go stale independently; try the episode's next stream before reporting a failure.
                if self.streamIndex + 1 < self.streams.count {
                    self.select(stream: self.streamIndex + 1)
                } else {
                    self.error = item.error?.localizedDescription ?? "无法播放此剧集"
                }
            }
            .store(in: &observers)
        self.player = player
        if start > 1 { player.seek(to: CMTime(seconds: start, preferredTimescale: 600)) }
        player.play()
    }
}

struct VideoPlayerView: View {
    @EnvironmentObject private var library: LibraryModel
    @StateObject private var model: VideoPlayerModel
    @Environment(\.dismiss) private var dismiss

    init(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) {
        _model = StateObject(wrappedValue: VideoPlayerModel(runtime: runtime, content: content, chapter: chapter))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let player = model.player {
                SystemPlayer(player: player)
                    .ignoresSafeArea()
                    .accessibilityIdentifier("player.view")
            }
            if model.isLoading { ProgressView().tint(.white).controlSize(.large) }
            if let error = model.error {
                VStack(spacing: 12) {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("player.error")
                    Button("重试") { Task { await model.move(to: model.chapter) } }.buttonStyle(.glass)
                }
                .padding()
            }
        }
        .safeAreaInset(edge: .top) { chrome }
        .statusBarHidden()
        .task { model.library = library.library; await model.load() }
        .onDisappear { Task { await model.persist(); model.stop(); await library.refresh() } }
    }

    private var chrome: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                Button("关闭", systemImage: "xmark") { dismiss() }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("player.close")
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.content.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(SourceDetailsView.episodeTitle(model.chapter)).font(.caption).lineLimit(1)
                        .accessibilityIdentifier("player.title")
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .glassEffect(.regular, in: .capsule)
                Spacer(minLength: 0)
                // Exposed for UI tests and VoiceOver: playback actually advancing, not just a player existing.
                Text("已播放 \(Int(model.position)) 秒")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.8))
                    .accessibilityIdentifier("player.position")
                if model.streams.count > 1 {
                    Menu {
                        ForEach(Array(model.streams.enumerated()), id: \.offset) { index, stream in
                            Button {
                                model.select(stream: index)
                            } label: {
                                if index == model.streamIndex {
                                    Label(Self.streamName(stream, index), systemImage: "checkmark")
                                } else {
                                    Text(Self.streamName(stream, index))
                                }
                            }
                        }
                    } label: {
                        Label("线路", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glass)
                    .accessibilityIdentifier("player.streams")
                }
                if let previous = model.previous {
                    Button("上一集", systemImage: "backward.end.fill") { Task { await model.move(to: previous) } }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("player.previous")
                }
                if let next = model.next {
                    Button("下一集", systemImage: "forward.end.fill") { Task { await model.move(to: next) } }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .accessibilityIdentifier("player.next")
                }
            }
            .padding(.horizontal)
        }
    }
}

extension VideoPlayerView {
    static func streamName(_ stream: IosSourcePage, _ index: Int) -> String {
        stream.label?.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? "线路 \(index + 1)"
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// The system player controller keeps native controls, AirPlay and Picture in Picture.
private struct SystemPlayer: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
    }
}
