import CryptoKit
import Foundation
import ImageIO
import UIKit

/// Decode only the immutable host artifact; networking and source interceptors stay in source-host.
enum SourceImageFile {
    static func decode(directory: URL, relativePath: String, sha256: String, byteSize: Int64) throws -> UIImage {
        guard sha256.count == 64, sha256.allSatisfy({ "0123456789abcdef".contains($0) }),
              relativePath == "\(sha256).img", byteSize > 0, byteSize <= 64 * 1024 * 1024 else {
            throw failure("图片记录无效")
        }
        let file = directory.appendingPathComponent(relativePath)
        let attributes = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard attributes.isRegularFile == true, attributes.isSymbolicLink != true,
              attributes.fileSize.map(Int64.init) == byteSize else { throw failure("图片文件缺失或长度不符") }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        guard Int64(data.count) == byteSize,
              SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == sha256 else {
            throw failure("图片文件校验失败")
        }
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: 4096,
              ] as CFDictionary) else { throw failure("无法解码此图片格式") }
        return UIImage(cgImage: image)
    }

    private static func failure(_ message: String) -> NSError {
        NSError(domain: "org.skepsun.kototoro.image", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
