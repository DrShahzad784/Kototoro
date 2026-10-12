import XCTest

final class ExtensionPresentationTests: XCTestCase {
    private func catalog(_ package: String = "test.extension", ecosystem: String = "MIHON",
                         code: Int64 = 2, repository: String = "repo-a", adult: Bool = false,
                         languages: [String] = ["zh", "en"]) -> CatalogExtension {
        CatalogExtension(packageName: package, ecosystem: ecosystem, title: "书源组合", version: "1.2",
                         versionCode: code, languages: languages, sourceNames: ["中文漫画", "English Comics"],
                         isAdult: adult, repositoryID: repository, repositoryTitle: "测试仓库", iconURL: nil)
    }

    private func installed(ecosystem: String = "MIHON", code: Int64 = 1,
                           adult: Bool? = nil) -> InstalledExtension {
        InstalledExtension(packageName: "test.extension", versionCode: code, sha256: "hash", ecosystem: ecosystem,
                           bundled: false, displayName: "已安装书源", versionName: "1.1", isAdult: adult)
    }

    func testOlderHostReplyRemainsReadableWithoutDisplayMetadata() throws {
        let json = #"{"packageName":"test.extension","versionCode":1,"sha256":"hash","ecosystem":"MIHON","bundled":false}"#
        let local = try JSONDecoder().decode(InstalledExtension.self, from: Data(json.utf8))
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [catalog()], activeID: nil).first)
        XCTAssertEqual(row.title, "书源组合")
        XCTAssertEqual(row.installedVersion, "构建 1")
        XCTAssertEqual(row.sourceNames, ["中文漫画", "English Comics"])
        XCTAssertEqual(row.state, .update)
    }

    func testSamePackageInDifferentEcosystemsHasIndependentState() throws {
        let rows = ExtensionIndex.summaries(installed: [installed()],
            available: [catalog(), catalog(ecosystem: "ANIYOMI")], activeID: "ANIYOMI:test.extension")
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows.first { $0.ecosystem == "MIHON" }?.state, .update)
        XCTAssertEqual(rows.first { $0.ecosystem == "ANIYOMI" }?.state, .installing)
        XCTAssertNil(rows.first { $0.ecosystem == "ANIYOMI" }?.installed)
    }

    func testNewestRepositoryWinsAndEqualVersionsHaveStableSelection() throws {
        let candidates = [catalog(code: 2, repository: "repo-z"), catalog(code: 3, repository: "repo-b"),
                          catalog(code: 3, repository: "repo-a")]
        for values in [candidates, Array(candidates.reversed())] {
            let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [], available: values, activeID: nil).first)
            XCTAssertEqual(row.available?.repositoryID, "repo-a")
            XCTAssertEqual(row.available?.versionCode, 3)
        }
    }

    func testOlderCatalogStillOffersInstallationThroughExistingRuntime() throws {
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [installed(code: 5)],
            available: [catalog(code: 2)], activeID: nil).first)
        XCTAssertEqual(row.state, .installed)
        XCTAssertTrue(row.permitsInstallation)
        XCTAssertEqual(row.title, "已安装书源")
    }

    func testReadableSourceNameReplacesTechnicalPackageNameWithoutRepository() throws {
        var local = installed()
        local.displayName = local.packageName
        local.sourceNames = ["再漫画"]
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [], activeID: nil).first)
        XCTAssertEqual(row.title, "再漫画")
    }

    func testPackageStemAliasUsesReadableSourceName() throws {
        var local = installed()
        local.displayName = "Tachiyomi: Extension"
        local.sourceNames = ["再漫画"]
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [], activeID: nil).first)
        XCTAssertEqual(row.title, "再漫画")
        local.displayName = "作者命名"
        let named = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [], activeID: nil).first)
        XCTAssertEqual(named.title, "作者命名")
    }

    func testInstalledNameSourcesAndIconSurviveMissingRepositoryWithPersistedMetadata() throws {
        let icon = try XCTUnwrap(URL(string: "https://repo.example/icon/test.png"))
        let metadata = ExtensionDisplayMetadata(title: "Mihon: 再漫画", languages: ["zh"],
                                                sourceNames: ["再漫画"], iconURL: icon)
        let restored = try JSONDecoder().decode([String: ExtensionDisplayMetadata].self,
            from: JSONEncoder().encode(["MIHON:test.extension": metadata]))
        let json = #"{"packageName":"test.extension","versionCode":1,"sha256":"hash","ecosystem":"MIHON","bundled":false}"#
        let local = try JSONDecoder().decode(InstalledExtension.self, from: Data(json.utf8))
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [],
                                                        activeID: nil, metadata: restored).first)
        XCTAssertEqual(row.title, "再漫画")
        XCTAssertEqual(row.iconURL, icon)
        XCTAssertEqual(row.sourceNames, ["再漫画"])
        XCTAssertEqual(row.languages, ["zh"])
        XCTAssertEqual(row.state, .installed)
        XCTAssertFalse(row.permitsInstallation)
    }

    func testInstalledAdultExtensionRemainsManageableWhenDiscoveryIsHidden() throws {
        let available = try XCTUnwrap(ExtensionIndex.summaries(installed: [],
            available: [catalog(adult: true)], activeID: nil).first)
        let local = try XCTUnwrap(ExtensionIndex.summaries(installed: [installed(adult: true)],
            available: [catalog(adult: true)], activeID: nil).first)
        XCTAssertFalse(ExtensionFilter().matches(available))
        XCTAssertTrue(ExtensionFilter(showAdult: true).matches(available))
        XCTAssertTrue(ExtensionFilter().matches(local))
        XCTAssertTrue(ExtensionFilter(scope: .installed).matches(local))
    }

    func testSearchMatchesContainedSourceRepositoryAndLocalizedLanguage() throws {
        let row = try XCTUnwrap(ExtensionIndex.summaries(installed: [], available: [catalog()], activeID: nil).first)
        for query in ["English Comics", "测试仓库", ExtensionLanguage.title("zh"), "多语言", " TEST.EXTENSION "] {
            XCTAssertTrue(ExtensionFilter(query: query).matches(row), query)
        }
        XCTAssertTrue(ExtensionFilter(ecosystem: "MIHON", languages: ["zh"], scope: .available).matches(row))
        XCTAssertTrue(ExtensionFilter(languages: ["all"]).matches(row))
        XCTAssertFalse(ExtensionFilter(ecosystem: "ANIYOMI").matches(row))
        XCTAssertFalse(ExtensionFilter(languages: ["ja"]).matches(row))
        XCTAssertFalse(ExtensionFilter(scope: .installed).matches(row))
    }

    func testLanguageSelectionMatchesAnySelectedLanguageAndClearingRestoresAll() {
        let rows = ExtensionIndex.summaries(installed: [], available: [
            catalog("zh", languages: ["zh"]), catalog("en", languages: ["en"]),
            catalog("ja", languages: ["ja"]), catalog("both"), catalog("unspecified", languages: []),
            catalog("multi", languages: ["multi"]),
        ], activeID: nil)
        func packages(_ filter: ExtensionFilter) -> Set<String> {
            Set(rows.filter(filter.matches).map(\.packageName))
        }
        // Language-agnostic ("multi"/"all") packages stay visible under any language filter.
        var filter = ExtensionFilter(languages: ["zh", "en"])
        XCTAssertEqual(packages(filter), ["zh", "en", "both", "multi"])
        filter.languages.remove("zh")
        XCTAssertEqual(packages(filter), ["en", "both", "multi"])
        filter.languages.removeAll()
        XCTAssertEqual(packages(filter), ["zh", "en", "ja", "both", "unspecified", "multi"])
        filter.languages = ["all", "ja"]
        XCTAssertEqual(packages(filter), ["both", "multi", "ja"])
        filter.ecosystem = "ANIYOMI"
        XCTAssertTrue(packages(filter).isEmpty)
        filter.ecosystem = "MIHON"
        filter.scope = .installed
        XCTAssertTrue(packages(filter).isEmpty)
    }

    func testUpdateIsIncludedInInstalledScopeAndSourcesSurviveRepositoryRemoval() throws {
        let local = installed()
        let update = try XCTUnwrap(ExtensionIndex.summaries(installed: [local], available: [catalog()], activeID: nil).first)
        XCTAssertTrue(ExtensionFilter(scope: .installed).matches(update))
        XCTAssertTrue(ExtensionFilter(scope: .updates).matches(update))
        XCTAssertFalse(ExtensionFilter(scope: .available).matches(update))
        var metadata = local
        metadata.sourceNames = ["原有来源"]
        metadata.languages = ["zh"]
        let offline = try XCTUnwrap(ExtensionIndex.summaries(installed: [metadata], available: [], activeID: nil).first)
        XCTAssertEqual(offline.title, "已安装书源")
        XCTAssertEqual(offline.sourceNames, ["原有来源"])
        XCTAssertEqual(offline.state, .installed)
        XCTAssertNil(offline.available)
    }
}
