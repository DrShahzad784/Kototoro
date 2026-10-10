import CryptoKit
import Foundation
import KototoroShared
import UIKit

enum ReaderDownloads {
    private struct ImageRecord: Codable {
        let path: String
        let hash: String
        let bytes: Int64
    }
    private struct Manifest: Codable {
        let pages: String
        let images: [ImageRecord]
    }
    static func directory(content: IosSourceContent, chapter: IosSourceChapter) throws -> URL {
        let root = try NativeSourceTransport.dataDirectory.appendingPathComponent("downloads", isDirectory: true)
        let identity = content.id + ":" + chapter.id
        let hash = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(hash, isDirectory: true)
    }
    static func isDownloaded(content: IosSourceContent, chapter: IosSourceChapter) -> Bool {
        guard let dir = try? directory(content: content, chapter: chapter) else { return false }
        return FileManager.default.fileExists(atPath: dir.appendingPathComponent("chapter.json").path)
    }
    static func pages(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) async throws -> [IosSourcePage] {
        if let manifest = try manifest(content: content, chapter: chapter) {
            return try IosPageArchive().decode(json: manifest.pages)
        }
        return try await runtime.pages(chapter: chapter)
    }
    static func loadedPage(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                           page: IosSourcePage, index: Int, cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        if let manifest = try manifest(content: content, chapter: chapter), manifest.images.indices.contains(index) {
            let record = manifest.images[index]
            let dir = try directory(content: content, chapter: chapter)
            return try await ReaderImageArtifact.load(directory: dir, relativePath: record.path,
                                                      sha256: record.hash, byteSize: record.bytes, cropMargins: cropMargins)
        }
        return try await runtime.image(page: page).loadedPage(cropMargins: cropMargins)
    }
    static func image(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter,
                      page: IosSourcePage, index: Int, cropMargins: Bool = false) async throws -> UIImage {
        try await loadedPage(runtime: runtime, content: content, chapter: chapter, page: page,
                             index: index, cropMargins: cropMargins).image
    }
    static func download(runtime: IosSources, content: IosSourceContent, chapter: IosSourceChapter) async throws {
        let pages = try await runtime.pages(chapter: chapter)
        guard !pages.isEmpty else { throw URLError(.zeroByteResource) }
        let dir = try directory(content: content, chapter: chapter)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        var records: [ImageRecord] = []
        // Sequential requests bound decode and extension memory on the interpreter-backed JVM.
        for page in pages {
            try Task.checkCancellation()
            let artifact = try await runtime.image(page: page)
            _ = try await artifact.decodedImage()
            let target = dir.appendingPathComponent(artifact.relativePath)
            if !FileManager.default.fileExists(atPath: target.path) {
                try FileManager.default.copyItem(at: store.appendingPathComponent(artifact.relativePath), to: target)
            }
            records.append(ImageRecord(path: artifact.relativePath, hash: artifact.sha256, bytes: artifact.byteSize))
        }
        let value = Manifest(pages: IosPageArchive().encode(pages: pages), images: records)
        try JSONEncoder().encode(value).write(to: dir.appendingPathComponent("chapter.json"), options: .atomic)
    }
    static func remove(content: IosSourceContent, chapter: IosSourceChapter) throws {
        let dir = try directory(content: content, chapter: chapter)
        if FileManager.default.fileExists(atPath: dir.path) { try FileManager.default.removeItem(at: dir) }
    }
    private static func manifest(content: IosSourceContent, chapter: IosSourceChapter) throws -> Manifest? {
        let file = try directory(content: content, chapter: chapter).appendingPathComponent("chapter.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: file))
    }
}
