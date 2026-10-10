import KototoroShared
import UIKit

extension IosSourceImage {
    /// Hashing and bounded ImageIO decoding must not run on the main actor.
    func decodedImage() async throws -> UIImage {
        try Task.checkCancellation()
        let directory = try NativeSourceTransport.dataDirectory.appendingPathComponent("images", isDirectory: true)
        let path = relativePath
        let hash = sha256
        let length = byteSize
        let image = try await Task.detached(priority: .userInitiated) {
            try SourceImageFile.decode(directory: directory, relativePath: path, sha256: hash, byteSize: length)
        }.value
        try Task.checkCancellation()
        return image
    }
}
