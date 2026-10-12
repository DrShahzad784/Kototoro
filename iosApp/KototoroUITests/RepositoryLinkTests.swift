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

    /// Repositories' own pages still link with the Tachiyomi/Aniyomi schemes; Android infers the kind from them.
    func testLegacySchemesImplyTheirKind() throws {
        let address = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/index.min.json"
        let encoded = address.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        for (scheme, kind) in [("tachiyomi", "MIHON"), ("aniyomi", "ANIYOMI"), ("anikku", "ANIYOMI"), ("kototoro", "MIHON")] {
            let parsed = try RepositoryLink.parse("\(scheme)://add-repo?url=\(encoded)", defaultKind: "JAR")
            XCTAssertEqual(parsed.kind, kind, scheme)
            XCTAssertEqual(parsed.url.absoluteString, address)
        }
        // An explicit kind still wins over the scheme.
        XCTAssertEqual(try RepositoryLink.parse("tachiyomi://add-repo?url=\(encoded)&kind=tsundoku", defaultKind: "MIHON").kind,
                       "TSUNDOKU")
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
