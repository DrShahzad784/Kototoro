import XCTest

/// Runs against `Scripts/serve-cms-fixture.py` on the host (the simulator shares its network). Default build, no JVM.
final class VideoSourceTests: XCTestCase {
    private let api = "http://127.0.0.1:8765/api.php/provide/vod/"

    @MainActor
    func testAppleCmsSourceImportsBrowsesAndPlaysNatively() throws {
        try requireFixture()
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()

        let add = app.buttons["sources.addVideo"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["addVideo.url"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(api)
        app.textFields["addVideo.title"].tap()
        app.textFields["addVideo.title"].typeText("本地测试影视")
        app.buttons["addVideo.import"].tap()
        XCTAssertTrue(element(app, "addVideo.result").waitForExistence(timeout: 30), "Import failed: \(app.debugDescription)")
        app.buttons["addVideo.close"].tap()

        // Other imported sites may already exist on the device; pick the fixture by name.
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", "本地测试影视")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        let sourceName = String(source.identifier.dropFirst("source.".count))
        controlFixture("/fail-page-2")
        source.tap()
        let first = element(app, "source.content.\(contentId(101, sourceName))")
        let second = element(app, "source.content.\(contentId(102, sourceName))")
        let third = element(app, "source.content.\(contentId(103, sourceName))")
        XCTAssertTrue(first.waitForExistence(timeout: 30), "Browse failed: \(app.debugDescription)")
        XCTAssertTrue(second.exists)
        let retry = app.buttons["browse.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 15), "Transient page-2 error should surface retry: \(app.debugDescription)")
        XCTAssertTrue(first.exists && second.exists, "Page-1 items must remain visible when page-2 fails")
        controlFixture("/recover-page-2")
        retry.tap()
        XCTAssertTrue(third.waitForExistence(timeout: 15), "Retry should append page-2 items without clearing page 1: \(app.debugDescription)")
        XCTAssertTrue(first.exists && second.exists)
        // Categories come from the CMS `class` list and filter on the server.
        let animation = app.buttons["category.动画"]
        XCTAssertTrue(animation.waitForExistence(timeout: 10))
        animation.tap()
        let hiddenMovie = NSPredicate(format: "exists == false")
        expectation(for: hiddenMovie, evaluatedWith: second)
        waitForExpectations(timeout: 15)
        attach(app, "Apple CMS browse grid")
        first.tap()

        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30))
        let episode = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        XCTAssertTrue(episode.waitForExistence(timeout: 30), "Details failed: \(app.debugDescription)")
        XCTAssertTrue(element(app, "details.line").exists, "Two named lines should offer a picker")
        attach(app, "Apple CMS details and episodes")
        episode.tap()

        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 20), "Player failed: \(app.debugDescription)")
        let advanced = NSPredicate(format: "label != %@ AND label != %@", "已播放 0 秒", "")
        expectation(for: advanced, evaluatedWith: position)
        waitForExpectations(timeout: 30)
        XCTAssertFalse(app.staticTexts["player.error"].exists)
        XCTAssertEqual(app.staticTexts["player.title"].label, "第1集")
        attach(app, "Native HLS playback")

        app.buttons["player.next"].tap()
        let secondTitle = NSPredicate(format: "label == %@", "第2集")
        expectation(for: secondTitle, evaluatedWith: app.staticTexts["player.title"])
        waitForExpectations(timeout: 15)
        // The second episode is the last on its line: no cross-line "next".
        XCTAssertFalse(app.buttons["player.next"].exists)
        app.buttons["player.close"].tap()
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 10))

        app.terminate()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 30), "Imported source did not persist")
    }

    /// Opt-in real site: `TEST_RUNNER_KOTOTORO_REAL_CMS=<api url>` (e.g. from the Kototoro repo hub TVBox list).
    @MainActor
    func testRealAppleCmsSitePlaysOnSimulator() throws {
        guard let api = ProcessInfo.processInfo.environment["KOTOTORO_REAL_CMS"], !api.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_KOTOTORO_REAL_CMS to a real Apple CMS API URL")
        }
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        let add = app.buttons["sources.addVideo"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["addVideo.url"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(api)
        app.buttons["addVideo.import"].tap()
        XCTAssertTrue(element(app, "addVideo.result").waitForExistence(timeout: 60), "Import failed: \(app.debugDescription)")
        app.buttons["addVideo.close"].tap()

        let host = URL(string: api)?.host ?? api
        let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
                                                      "source.JSON_TVBOX_", host)).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        let listed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            first.exists || app.descendants(matching: .any)["sources.error"].exists
        }, object: nil)
        wait(for: [listed], timeout: 90)
        XCTAssertTrue(first.exists, "Browse failed: \(app.debugDescription)")
        sleep(3) // let poster images arrive for the screenshot
        attach(app, "Real CMS grid")
        first.tap()

        let episode = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        let detailed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            episode.exists || app.staticTexts["details.error"].exists
        }, object: nil)
        wait(for: [detailed], timeout: 90)
        XCTAssertTrue(episode.exists, "Details failed: \(app.debugDescription)")
        attach(app, "Real CMS details")
        episode.tap()
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 30))
        let started = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (position.exists && position.label != "已播放 0 秒") || app.staticTexts["player.error"].exists
        }, object: nil)
        wait(for: [started], timeout: 90)
        XCTAssertFalse(app.staticTexts["player.error"].exists, "Playback failed: \(app.debugDescription)")
        attach(app, "Real CMS playback")
    }

    /// Same Java String hash rule as the shared runtime: positiveHash("item|cms:<id>").
    private func contentId(_ vodId: Int, _ sourceName: String) -> String {
        var hash: Int64 = 1125899906842597
        for unit in "\(sourceName)|item|cms:\(vodId)".utf16 { hash = hash &* 31 &+ Int64(unit) }
        return String(hash & Int64.max)
    }

    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func controlFixture(_ path: String) {
        let done = expectation(description: "fixture \(path)")
        var ok = false
        URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:8765\(path)")!) { _, response, _ in
            ok = (response as? HTTPURLResponse)?.statusCode == 200
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        XCTAssertTrue(ok, "Fixture control \(path) failed")
    }

    private func requireFixture() throws {
        let done = expectation(description: "fixture health")
        var reachable = false
        URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:8765/health")!) { _, response, _ in
            reachable = (response as? HTTPURLResponse)?.statusCode == 200
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 5)
        if !reachable { throw XCTSkip("Start iosApp/Scripts/serve-cms-fixture.py to run this test") }
    }
}
