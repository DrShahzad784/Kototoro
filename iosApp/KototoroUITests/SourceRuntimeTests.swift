import XCTest

final class SourceRuntimeTests: XCTestCase {
    func testIndependentSourceRunsThroughEmbeddedJVM() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if app.staticTexts["扩展支持正在接入"].waitForExistence(timeout: 2) {
            throw XCTSkip("Enable the optional simulator JVM preview to run this test")
        }
        if app.staticTexts["再漫画 APK 兼容性验证"].exists {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_9007199254740993"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Embedded VM failed: \(app.debugDescription)")
        source.tap()
        XCTAssertTrue(app.staticTexts["popular 1"].waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 90))
        source.tap()
        XCTAssertTrue(app.staticTexts["popular 1"].waitForExistence(timeout: 30))
        app.staticTexts["popular 1"].tap()
        let firstChapter = chapterButton(in: app)
        XCTAssertTrue(firstChapter.waitForExistence(timeout: 30), "Details failed: \(app.debugDescription)")
        firstChapter.tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30), "Image failed: \(app.debugDescription)")
        XCTAssertEqual(app.staticTexts["reader.position"].label, "第 1 / 2 页")
        XCTAssertFalse(app.buttons["reader.previousPage"].isEnabled)
        app.buttons["reader.nextPage"].tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["reader.position"].label, "第 2 / 2 页")
        XCTAssertFalse(app.buttons["reader.nextPage"].isEnabled)
        app.buttons["reader.previousPage"].tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["reader.position"].label, "第 1 / 2 页")
        app.buttons["reader.nextChapter"].tap()
        XCTAssertTrue(app.navigationBars["new"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["reader.position"].label, "第 1 / 2 页")
        XCTAssertFalse(app.buttons["reader.nextChapter"].exists)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(chapterButton(in: app).waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["popular 1"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testConvertedRealMihonApkRunsOnSimulator() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if app.staticTexts["扩展支持正在接入"].waitForExistence(timeout: 2) ||
            app.staticTexts["独立测试源，仅用于运行时验证"].exists {
            throw XCTSkip("Select the real APK preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_524579092615598717"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Real APK failed: \(app.debugDescription)")
        source.tap()
        let firstContent = app.staticTexts.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")
        ).firstMatch
        let completed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            firstContent.exists || app.staticTexts["sources.error"].exists
        }, object: nil)
        wait(for: [completed], timeout: 60)
        XCTAssertTrue(firstContent.exists, "Browse failed: \(app.debugDescription)")
        firstContent.tap()
        let firstChapter = chapterButton(in: app)
        let detailsCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            firstChapter.exists || app.staticTexts["details.error"].exists
        }, object: nil)
        wait(for: [detailsCompleted], timeout: 60)
        XCTAssertTrue(firstChapter.exists, "Details failed: \(app.debugDescription)")
        let detailsScreenshot = XCTAttachment(screenshot: app.screenshot())
        detailsScreenshot.name = "Real APK details and chapters"
        detailsScreenshot.lifetime = .keepAlways
        add(detailsScreenshot)
        firstChapter.tap()
        let image = app.images["reader.image"]
        let imageCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            image.exists || app.staticTexts["reader.error"].exists
        }, object: nil)
        wait(for: [imageCompleted], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        XCTAssertFalse(app.buttons["reader.previousPage"].isEnabled)
        let readerScreenshot = XCTAttachment(screenshot: app.screenshot())
        readerScreenshot.name = "Real APK decoded first manga page"
        readerScreenshot.lifetime = .keepAlways
        add(readerScreenshot)
        if app.buttons["reader.nextPage"].isEnabled {
            app.buttons["reader.nextPage"].tap()
            let nextImageCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                image.exists || app.staticTexts["reader.error"].exists
            }, object: nil)
            wait(for: [nextImageCompleted], timeout: 90)
            XCTAssertTrue(image.exists, "Second page failed: \(app.debugDescription)")
            XCTAssertTrue(app.staticTexts["reader.position"].label.hasPrefix("第 2 /"))
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    private func chapterButton(in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
    }
}
