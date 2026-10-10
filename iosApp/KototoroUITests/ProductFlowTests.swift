import XCTest

final class ProductFlowTests: XCTestCase {
    @MainActor
    func testRepositoryLinkConvertsApkOnDeviceAndPersistsInstallation() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        guard app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90) else {
            throw XCTSkip("Requires the real API JVM profile and the local repository fixture")
        }
        app.buttons["sources.repositories"].tap()
        let address = app.textFields["repository.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 30))
        address.tap()
        address.typeText("kototoro://add-repo?url=http%3A%2F%2F127.0.0.1%3A8766%2Findex.min.json&kind=MIHON")
        app.buttons["repository.import"].tap()
        let install = app.buttons["extension.install.eu.kanade.tachiyomi.extension.zh.zaimanhua"]
        XCTAssertTrue(install.waitForExistence(timeout: 30), app.debugDescription)
        install.tap()
        let reinstall = app.buttons["重新安装"]
        if reinstall.waitForExistence(timeout: 3) { reinstall.tap() }
        let installed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let status = app.staticTexts["repository.status"]
            return (status.exists && status.label.contains("已安装")) || app.staticTexts["repository.error"].exists
        }, object: nil)
        wait(for: [installed], timeout: 300)
        XCTAssertFalse(app.staticTexts["repository.error"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["repository.status"].label.contains("已安装"))
        let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["source.MIHON_524579092615598717"].waitForExistence(timeout: 90), app.debugDescription)
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "MIHON")).firstMatch.waitForExistence(timeout: 30))
    }

    @MainActor
    func testCmsFavouritesHistoryAndPlaybackResume() throws {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        app.buttons["sources.addVideo"].tap()
        let url = app.textFields["addVideo.url"]
        XCTAssertTrue(url.waitForExistence(timeout: 10))
        url.tap(); url.typeText("http://127.0.0.1:8765/api.php/provide/vod/")
        app.textFields["addVideo.title"].tap(); app.textFields["addVideo.title"].typeText("记录闭环测试")
        app.buttons["addVideo.import"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["addVideo.result"].waitForExistence(timeout: 30))
        app.buttons["addVideo.close"].tap()
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "记录闭环测试")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 30)); source.tap()
        let item = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 30)); item.tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let favourite = app.buttons["details.favourite"]
        XCTAssertTrue(favourite.waitForExistence(timeout: 10))
        if favourite.label == "收藏" { favourite.tap() }
        let chapter = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        XCTAssertTrue(chapter.waitForExistence(timeout: 30)); chapter.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            Int(position.label.components(separatedBy: " ").dropFirst().first ?? "") ?? 0 >= 5
        }, object: nil)
        wait(for: [advanced], timeout: 30)
        app.buttons["player.close"].tap()
        app.terminate(); app.launch()
        app.tabBars.buttons["漫画库"].tap()
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "favourite.")).firstMatch
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
        app.buttons["player.close"].tap()
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["记录"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "history.")).firstMatch.waitForExistence(timeout: 30))
    }
}
