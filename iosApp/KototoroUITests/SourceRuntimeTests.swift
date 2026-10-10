import XCTest

final class SourceRuntimeTests: XCTestCase {
    func testIndependentSourceRunsThroughEmbeddedJVM() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["扩展"].waitForExistence(timeout: 10) {
            throw XCTSkip("Enable the optional simulator JVM preview to run this test")
        }
        if app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "真实扩展兼容性验证")).firstMatch.exists {
            throw XCTSkip("Select the independent fixture preview profile to run this test")
        }
        let source = app.buttons["source.MIHON_9007199254740993"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Embedded VM failed: \(app.debugDescription)")
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 90))
        source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30))
        item(app, "popular 1").tap()
        let firstChapter = chapterButton(in: app)
        XCTAssertTrue(firstChapter.waitForExistence(timeout: 30), "Details failed: \(app.debugDescription)")
        firstChapter.tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30), "Image failed: \(app.debugDescription)")
        let progress = app.sliders["reader.progress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 5))
        progress.adjust(toNormalizedSliderPosition: 0)
        assertPosition(app, page: 1, total: 2)
        XCTAssertFalse(app.buttons["reader.previousPage"].exists)
        XCTAssertFalse(app.buttons["reader.nextPage"].exists)
        XCTAssertLessThan(app.buttons["reader.bookmark"].frame.maxY, app.buttons["reader.nextChapter"].frame.minY)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.midX,
                       app.buttons["reader.nextChapter"].frame.midX, accuracy: 2)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.width,
                       app.buttons["reader.nextChapter"].frame.width, accuracy: 1)
        XCTAssertEqual(app.buttons["reader.bookmark"].frame.height,
                       app.buttons["reader.nextChapter"].frame.height, accuracy: 1)
        XCTAssertEqual(app.descendants(matching: .any)["reader.progressControl"].frame.height,
                       app.buttons["reader.nextChapter"].frame.height, accuracy: 1)
        XCTAssertEqual(app.buttons["reader.back"].frame.width, app.buttons["reader.back"].frame.height, accuracy: 1)
        let footerScreenshot = XCTAttachment(screenshot: app.screenshot())
        footerScreenshot.name = "Native chapter progress bar and floating bookmark"
        footerScreenshot.lifetime = .keepAlways; add(footerScreenshot)
        progress.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 2, total: 2)
        if app.buttons["reader.bookmark"].value as? String != "已添加" { app.buttons["reader.bookmark"].tap() }
        app.buttons["reader.chapters"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["reader.chapterPanel"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reader.more"].exists)
        app.segmentedControls.buttons["书签"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "第 2 页")).firstMatch.waitForExistence(timeout: 10))
        let chaptersScreenshot = XCTAttachment(screenshot: app.screenshot())
        chaptersScreenshot.name = "Native chapter panel and bookmarks"; chaptersScreenshot.lifetime = .keepAlways; add(chaptersScreenshot)
        app.buttons["reader.panel.close"].tap()
        app.buttons["reader.more"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["reader.morePanel"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["reader.chapters"].exists)
        app.buttons["下载本章"].tap()
        XCTAssertTrue(app.staticTexts["本章已下载，可离线阅读"].waitForExistence(timeout: 30), app.debugDescription)
        let moreScreenshot = XCTAttachment(screenshot: app.screenshot())
        moreScreenshot.name = "Native more panel and chapter download"; moreScreenshot.lifetime = .keepAlways; add(moreScreenshot)
        app.buttons["reader.panel.close"].tap()
        progress.adjust(toNormalizedSliderPosition: 0)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 1, total: 2)
        app.buttons["reader.nextChapter"].tap()
        XCTAssertTrue(app.buttons["reader.chapters"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 1, total: 2)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
        progress.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30))
        assertPosition(app, page: 2, total: 2)
        app.buttons["reader.back"].tap()
        XCTAssertTrue(chapterButton(in: app).waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate(); app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(source.waitForExistence(timeout: 90)); source.tap()
        XCTAssertTrue(item(app, "popular 1").waitForExistence(timeout: 30)); item(app, "popular 1").tap()
        XCTAssertTrue(app.buttons["details.resume"].waitForExistence(timeout: 30)); app.buttons["details.resume"].tap()
        XCTAssertTrue(app.images["reader.image"].waitForExistence(timeout: 30), app.debugDescription)
        assertPosition(app, page: 2, total: 2)
        XCTAssertFalse(app.buttons["reader.nextChapter"].isEnabled)
    }

    func testConvertedRealMihonApkRunsOnSimulator() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        if !app.staticTexts["扩展"].waitForExistence(timeout: 10) ||
            app.staticTexts["独立测试源，仅用于运行时验证"].exists {
            throw XCTSkip("Select the real APK preview profile to run this test")
        }
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 30))
        search.tap()
        search.typeText("MIHON_524579092615598717")
        let source = app.buttons["source.MIHON_524579092615598717"]
        XCTAssertTrue(source.waitForExistence(timeout: 90), "Real APK failed: \(app.debugDescription)")
        source.tap()
        let firstContent = app.descendants(matching: .any).matching(
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
        let progress = app.sliders["reader.progress"]
        XCTAssertTrue(progress.exists)
        progress.adjust(toNormalizedSliderPosition: 0)
        let readerScreenshot = XCTAttachment(screenshot: app.screenshot())
        readerScreenshot.name = "Real APK decoded first manga page"
        readerScreenshot.lifetime = .keepAlways
        add(readerScreenshot)
        let pageCount = Int(app.staticTexts["reader.totalPages"].value as? String ?? "0") ?? 0
        if pageCount > 1 {
            progress.adjust(toNormalizedSliderPosition: 1 / CGFloat(pageCount - 1))
            let nextImageCompleted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                image.exists || app.staticTexts["reader.error"].exists
            }, object: nil)
            wait(for: [nextImageCompleted], timeout: 90)
            XCTAssertTrue(image.exists, "Second page failed: \(app.debugDescription)")
            assertPosition(app, page: 2, total: pageCount)
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    /// Browse results are poster cards (buttons); match by label regardless of element type.
    private func item(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
    }

    private func chapterButton(in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
    }

    private func assertPosition(_ app: XCUIApplication, page: Int, total: Int,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(app.staticTexts["reader.currentPage"].value as? String, String(page), file: file, line: line)
        XCTAssertEqual(app.staticTexts["reader.totalPages"].value as? String, String(total), file: file, line: line)
    }
}
