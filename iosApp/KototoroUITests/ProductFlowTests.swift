import XCTest

final class ProductFlowTests: XCTestCase {
    /// A repo hub "导入" link opens the app from outside, imports the repository and installs an APK whose manifest
    /// has no library marker (third-party repositories; Mihon reads the library from the version name).
    /// VomicMH is used because CopyManga is NSFW-flagged and hidden by default.
    @MainActor
    func testExternalRepositoryLinkImportsAndInstallsMarkerlessApk() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.vomicmh")
    }

    @MainActor
    func testCopyMangaInitializesAndPersistsInstallation() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.copymanga")
    }

    @MainActor
    func testMh1234InitializesAndPersistsInstallation() throws {
        try installRepositoryExtension(packageName: "eu.kanade.tachiyomi.extension.zh.mh1234",
            index: "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb", repositoryQuery: "Keiyoushi")
    }

    @MainActor
    private func installRepositoryExtension(packageName: String,
        index: String = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/index.min.json",
        repositoryQuery: String = "copymanga-copy20") throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90) else {
            throw XCTSkip("Requires the real API JVM profile (online)")
        }
        let encoded = index.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        app.open(URL(string: "kototoro://add-repo?url=\(encoded)&kind=MIHON")!)
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), "The link should open repositories: \(app.debugDescription)")
        let imported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已添加")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [imported], timeout: 90)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        tabs.buttons["仓库"].tap()
        let repositorySearch = app.pageSearchField()
        XCTAssertTrue(repositorySearch.waitForExistence(timeout: 10))
        repositorySearch.tap(); repositorySearch.typeText(repositoryQuery + "\n")
        let repository = app.buttons["repository.row.MIHON.\(index)"]
        XCTAssertTrue(repository.waitForExistence(timeout: 30), app.debugDescription)
        repository.tap()
        let extensionSearch = app.searchFields["搜索此仓库的扩展"]
        for _ in 0..<3 where !extensionSearch.exists { app.collectionViews.firstMatch.swipeDown() }
        XCTAssertTrue(extensionSearch.waitForExistence(timeout: 10))
        extensionSearch.tap(); extensionSearch.typeText(packageName + "\n")
        let install = app.buttons["extension.install.\(packageName)"]
        let showAdult = app.buttons["repository.showAdult"]
        if !install.waitForExistence(timeout: 5), showAdult.exists { showAdult.tap() }
        XCTAssertTrue(install.waitForExistence(timeout: 30), app.debugDescription)
        install.tap()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [installed], timeout: 300)
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["repository.status"].label.contains("已安装"))
        XCTAssertTrue(app.buttons["extension.details.MIHON:\(packageName)"].exists, app.debugDescription)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90))
        app.buttons["sources.repositories"].tap()
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap(); search.typeText(packageName + "\n")
        XCTAssertTrue(app.buttons["extension.details.MIHON:\(packageName)"].waitForExistence(timeout: 30),
                      "Installed extension should survive relaunch: \(app.debugDescription)")
    }

    /// JAR parser repositories from the repo hub: legacy JSON indexes with a plugin.jar entry, and a GitHub
    /// release (UMA). Each shows a readable name and installs from the repository page.
    @MainActor
    func testJarRepositoryShowsNameAndInstallsPlugin() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        let started = Date()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 300) else {
            let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Extension runtime not ready"
            shot.lifetime = .keepAlways; add(shot)
            throw XCTSkip("Requires the real API JVM profile (online)")
        }
        print("Extension runtime ready after \(Int(Date().timeIntervalSince(started))) s")
        for (index, name) in [
            ("https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json", "skepsun/kototoro-parsers"),
            ("https://raw.githubusercontent.com/skepsun/k-parsers-r/repo/index.min.json", "skepsun/k-parsers-r"),
            ("https://github.com/InvalidDavid/UMA", nil),
        ] {
            installJarRepository(app, index: index, name: name)
            app.terminate(); app.launch()
        }
    }

    private func installJarRepository(_ app: XCUIApplication, index: String, name: String?) {
        let encoded = index.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        app.open(URL(string: "kototoro://add-repo?url=\(encoded)&kind=JAR")!)
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), app.debugDescription)
        let imported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已添加")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [imported], timeout: 90)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, "\(index): \(app.debugDescription)")
        if let name { XCTAssertTrue(app.staticTexts["repository.status"].label.contains(name), app.staticTexts["repository.status"].label) }
        tabs.buttons["仓库"].tap()
        let repository = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "repository.row.JAR.\(index)")).firstMatch
        XCTAssertTrue(repository.waitForExistence(timeout: 30), "\(index): \(app.debugDescription)")
        repository.tap()
        let install = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "extension.install.")).firstMatch
        // Adult plugins (k-parsers-r) are hidden by default; the repository page offers to show them.
        let showAdult = app.buttons["repository.showAdult"]
        if !install.waitForExistence(timeout: 15), showAdult.exists { showAdult.tap() }
        XCTAssertTrue(install.waitForExistence(timeout: 30), "\(index) should be installable: \(app.debugDescription)")
        install.tap()
        let installStarted = Date()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        // DEX-only plugins are converted by the interpreter-only embedded JVM (download, conversion and loading).
        wait(for: [installed], timeout: 900)
        print("JAR install \(index) finished after \(Int(Date().timeIntervalSince(installStarted))) s")
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "JAR install \(index)"
        shot.lifetime = .keepAlways; add(shot)
        let error = app.staticTexts["repository.error"]
        XCTAssertFalse(error.exists, "\(index) install failed: \(error.exists ? error.label : "")")
    }

    @MainActor
    func testRepositoryLinkConvertsApkOnDeviceAndPersistsInstallation() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90) else {
            throw XCTSkip("Requires the real API JVM profile and the local repository fixture")
        }
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(app.segmentedControls["repository.tabs"].waitForExistence(timeout: 30))
        let refreshing = app.descendants(matching: .any)["repository.refreshing"]
        let refreshed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !refreshing.exists }, object: nil)
        wait(for: [refreshed], timeout: 90)
        app.buttons["repository.actions"].tap()
        app.buttons["repository.add"].tap()
        let address = app.textFields["repository.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 30))
        address.tap()
        address.typeText("kototoro://add-repo?url=http%3A%2F%2F127.0.0.1%3A8766%2Findex.min.json&kind=MIHON")
        app.buttons["repository.import"].tap()
        let added = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !address.exists }, object: nil)
        wait(for: [added], timeout: 30)
        app.segmentedControls["repository.tabs"].buttons["仓库"].tap()
        let repository = app.buttons["repository.row.MIHON.http://127.0.0.1:8766/index.min.json"]
        XCTAssertTrue(repository.waitForExistence(timeout: 30), app.debugDescription)
        repository.tap()
        let install = app.buttons["extension.install.eu.kanade.tachiyomi.extension.zh.zaimanhua"]
        XCTAssertTrue(install.waitForExistence(timeout: 30), app.debugDescription)
        install.tap()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.descendants(matching: .any)["repository.error"].exists
        }, object: nil)
        wait(for: [installed], timeout: 300)
        XCTAssertFalse(app.descendants(matching: .any)["repository.error"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["repository.status"].label.contains("已安装"))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90), app.debugDescription)
        app.buttons["sources.repositories"].tap()
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap()
        search.typeText("eu.kanade.tachiyomi.extension.zh.zaimanhua\n")
        XCTAssertTrue(app.buttons["extension.details.MIHON:eu.kanade.tachiyomi.extension.zh.zaimanhua"]
            .waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["extension.details.MIHON:eu.kanade.tachiyomi.extension.zh.zaimanhua"].tap()
        XCTAssertTrue(app.staticTexts["再漫画"].firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["extension.close"].exists)
    }

    @MainActor
    func testCmsFavouritesHistoryAndPlaybackResume() throws {
        let app = XCUIApplication(); app.launch()
        importVideoSite(app, url: "http://127.0.0.1:8765/api.php/provide/vod/", title: "记录闭环测试")
        // The browse list is long with the real extensions installed; find the new site like a user would.
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap(); search.typeText("记录闭环测试")
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "记录闭环测试")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription); source.tap()
        let item = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 30)); item.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let favourite = app.buttons["details.favourite"]
        XCTAssertTrue(favourite.waitForExistence(timeout: 10))
        if favourite.label == "收藏" { favourite.tap() }
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists); chapter.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            Int(position.label.components(separatedBy: " ").dropFirst().first ?? "") ?? 0 >= 5
        }, object: nil)
        wait(for: [advanced], timeout: 30)
        playerControl(app, "player.close").tap()
        app.terminate(); app.launch()
        app.tabBars.buttons["作品库"].tap()
        // Other tests favourite the same fixture title on other sites; this run's copy is the one with progress.
        let card = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@ AND label CONTAINS %@", "favourite.", "测试动画", "已读"))
            .firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30)); card.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let resume = app.buttons["details.resume"]
        XCTAssertTrue(resume.waitForExistence(timeout: 30), app.debugDescription)
        resume.tap()
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let resumed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            position.exists && position.label != "已播放 0 秒"
        }, object: nil)
        wait(for: [resumed], timeout: 15)
        playerControl(app, "player.close").tap()
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["记录"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "history.")).firstMatch.waitForExistence(timeout: 30))
    }
}
