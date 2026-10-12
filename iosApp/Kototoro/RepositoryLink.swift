import Foundation

struct RepositoryLink {
    let url: URL
    let kind: String
    static let kinds = ["MIHON", "ANIYOMI", "TSUNDOKU", "JAR", "TVBOX"]
    static func parse(_ text: String, defaultKind: String) throws -> RepositoryLink {
        guard let input = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) else { throw URLError(.badURL) }
        var address = input.string ?? ""
        var kind = defaultKind
        // Same deep links as Android: `kind` is authoritative; legacy Tachiyomi/Aniyomi schemes imply the kind.
        let schemeKinds = ["kototoro": "MIHON", "tachiyomi": "MIHON", "aniyomi": "ANIYOMI", "anikku": "ANIYOMI"]
        if let scheme = input.scheme?.lowercased(), let implied = schemeKinds[scheme] {
            guard input.host == "add-repo" else { throw URLError(.unsupportedURL) }
            let params = input.queryItems ?? []
            let kinds = params.filter { $0.name == "kind" }
            guard params.filter({ $0.name == "url" }).count == 1, kinds.count <= 1,
                  let value = params.first(where: { $0.name == "url" })?.value else { throw URLError(.badURL) }
            address = value
            kind = kinds.first?.value?.uppercased() ?? implied
        }
        guard kinds.contains(kind), let url = URL(string: address), ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil, url.user == nil, url.password == nil else { throw URLError(.badURL) }
        return RepositoryLink(url: url, kind: kind)
    }
}

