import XCTest

final class RepositoryLinkTests: XCTestCase {
    func testSupportedHubLinksPreserveEncodedRepositoryAddress() throws {
        let addresses = [
            "MIHON": "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
            "ANIYOMI": "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
            "TSUNDOKU": "https://github.com/NovelSourcery/extensions/raw/repo/index.pb",
            "JAR": "https://github.com/InvalidDavid/UMA",
            "TVBOX": "https://cj.rycjapi.com/api.php/provide/vod/at/xml/",
        ]
        for (kind, address) in addresses {
            var link = URLComponents(string: "kototoro://add-repo")!
            link.queryItems = [URLQueryItem(name: "url", value: address), URLQueryItem(name: "kind", value: kind)]
            let parsed = try RepositoryLink.parse(link.string!, defaultKind: "MIHON")
            XCTAssertEqual(parsed.kind, kind)
            XCTAssertEqual(parsed.url.absoluteString, address)
        }
        let parsed = try RepositoryLink.parse(
            "kototoro://add-repo?url=https%3A%2F%2Fexample.org%2Ffeed%3Fa%3D1%26b%3D%252F&kind=JAR", defaultKind: "MIHON")
        XCTAssertEqual(parsed.url.absoluteString, "https://example.org/feed?a=1&b=%2F")
    }

    func testMalformedAndUnsupportedLinksFailBeforeImport() {
        for link in [
            "kototoro://add-repo?url=https://example.org&kind=MIHON&kind=JAR",
            "kototoro://add-repo?url=https://example.org&url=https://other.org&kind=MIHON",
            "kototoro://add-repo?url=https://example.org&kind=CLOUDSTREAM",
            "kototoro://other?url=https://example.org&kind=MIHON",
            "kototoro://add-repo?url=file:///private/data&kind=MIHON",
            "https://user:secret@example.org/index.json",
        ] { XCTAssertThrowsError(try RepositoryLink.parse(link, defaultKind: "MIHON"), link) }
    }
}
