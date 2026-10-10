import Foundation
import KototoroShared
import UIKit

struct ReaderImageArtifact: Equatable, Sendable {
    let directory: URL
    let relativePath: String
    let sha256: String
    let byteSize: Int64
    let encodedWidth: Int
    let encodedHeight: Int
    let tiled: Bool

    func decodeTile(_ tile: IosReaderTile) async throws -> UIImage {
        try Task.checkCancellation()
        let dir = directory; let path = relativePath; let hash = sha256; let length = byteSize
        let rect = CGRect(x: Int(tile.decodeLeft), y: Int(tile.decodeTop),
                          width: Int(tile.decodeWidth), height: Int(tile.decodeHeight))
        let sample = max(1, Int(tile.sampleSize))
        let image = try await Task.detached(priority: .userInitiated) {
            try SourceImageFile.decodeRegion(directory: dir, relativePath: path, sha256: hash,
                                             byteSize: length, rect: rect, sampleSize: sample)
        }.value
        try Task.checkCancellation()
        return image
    }

    static func load(directory: URL, relativePath: String, sha256: String,
                     byteSize: Int64, cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        try Task.checkCancellation()
        let loaded = try await Task.detached(priority: .userInitiated) { () throws -> ReaderLoadedPage in
            let dims = try SourceImageFile.dimensions(directory: directory, relativePath: relativePath,
                                                      sha256: sha256, byteSize: byteSize)
            let reader = IosReader()
            let resolved = reader.decodeSize(width: Int32(dims.width), height: Int32(dims.height))
            let maxPixelSize = Int(max(resolved.width, resolved.height))
            var image = try SourceImageFile.decode(directory: directory, relativePath: relativePath,
                                                   sha256: sha256, byteSize: byteSize, maxPixelSize: maxPixelSize)
            let tiled = reader.isTiled(width: Int32(dims.width), height: Int32(dims.height))
            if cropMargins && !tiled, let cgImage = image.cgImage, cgImage.width >= 100, cgImage.height >= 100 {
                let sample = Int(reader.edgeSampleSize(width: Int32(cgImage.width), height: Int32(cgImage.height)))
                if let sampled = SourceImageFile.samplePixels(image, sampleSize: sample) {
                    let kotlinPixels = KotlinIntArray(size: Int32(sampled.pixels.count))
                    for (index, pixel) in sampled.pixels.enumerated() {
                        kotlinPixels.set(index: Int32(index), value: pixel)
                    }
                    if let bounds = reader.contentBounds(
                        width: Int32(cgImage.width),
                        height: Int32(cgImage.height),
                        sampleSize: Int32(sample),
                        bitmapWidth: Int32(sampled.width),
                        bitmapHeight: Int32(sampled.height),
                        pixels: kotlinPixels
                    ) {
                        image = SourceImageFile.crop(image, rect: CGRect(
                            x: Int(bounds.left), y: Int(bounds.top),
                            width: Int(bounds.width), height: Int(bounds.height)
                        ))
                    }
                }
            }
            let width = tiled ? dims.width : Int(image.size.width)
            let height = tiled ? dims.height : Int(image.size.height)
            return ReaderLoadedPage(
                image: image,
                artifact: ReaderImageArtifact(
                    directory: directory,
                    relativePath: relativePath,
                    sha256: sha256,
                    byteSize: byteSize,
                    encodedWidth: max(1, width),
                    encodedHeight: max(1, height),
                    tiled: tiled
                )
            )
        }.value
        try Task.checkCancellation()
        return loaded
    }
}

struct ReaderLoadedPage {
    let image: UIImage
    let artifact: ReaderImageArtifact
}

extension IosSourceImage {
    /// Hashing and bounded ImageIO decoding must not run on the main actor.
    func decodedImage(cropMargins: Bool = false) async throws -> UIImage {
        try await loadedPage(cropMargins: cropMargins).image
    }

    func loadedPage(cropMargins: Bool = false) async throws -> ReaderLoadedPage {
        let directory = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        return try await ReaderImageArtifact.load(directory: directory, relativePath: relativePath,
                                                  sha256: sha256, byteSize: byteSize, cropMargins: cropMargins)
    }
}
