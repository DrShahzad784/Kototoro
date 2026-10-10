import CryptoKit
import Foundation
import XCTest

final class SourceImageFileTests: XCTestCase {
    func testDecodeAndRejectTamperedArtifacts() throws {
        let files = FileManager.default
        let directory = files.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? files.removeItem(at: directory) }
        let png = Data(base64Encoded:
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        )!
        let hash = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
        let name = "\(hash).img"
        let file = directory.appendingPathComponent(name)
        try png.write(to: file)
        let decoded = try SourceImageFile.decode(directory: directory, relativePath: name,
                                                sha256: hash, byteSize: Int64(png.count))
        XCTAssertEqual(decoded.size.width, 1)
        XCTAssertEqual(decoded.size.height, 1)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: "../\(name)",
                                                       sha256: hash, byteSize: Int64(png.count)))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count + 1)))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: 64 * 1024 * 1024 + 1))
        let html = Data("<html>error</html>".utf8)
        let htmlHash = SHA256.hash(data: html).map { String(format: "%02x", $0) }.joined()
        try html.write(to: directory.appendingPathComponent("\(htmlHash).img"))
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: "\(htmlHash).img",
                                                       sha256: htmlHash, byteSize: Int64(html.count)))
        var tampered = png
        tampered[tampered.count - 1] ^= 1
        try tampered.write(to: file)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count)))
        try files.removeItem(at: file)
        let outside = directory.appendingPathComponent("outside.png")
        try png.write(to: outside)
        try files.createSymbolicLink(at: file, withDestinationURL: outside)
        XCTAssertThrowsError(try SourceImageFile.decode(directory: directory, relativePath: name,
                                                       sha256: hash, byteSize: Int64(png.count)))
    }
}
