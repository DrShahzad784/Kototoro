import XCTest

/// Real extensions through the embedded JVM (`stage-mihon-preview.py` profile): Tsundoku novels, Aniyomi video and
/// kototoro-parsers JAR sources. Online; site availability is reported, not hidden.
final class ExtensionEcosystemTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        // Builds without the embedded JVM have no extension section at all: skip at once instead of waiting.
        if !app.staticTexts["扩展"].waitForExistence(timeout: 15) {
            throw XCTSkip("This build has no embedded JVM")
        }
        let profile = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "真实扩展兼容性验证")).firstMatch
        if !profile.waitForExistence(timeout: 90) {
            throw XCTSkip("Select the real extension preview profile (stage-mihon-preview.py) to run these tests")
        }
    }

    @MainActor
    func testTsundokuNovelChapterText() throws {
        openFirstItem(source: "TSUNDOKU_3273374795580060030")
        openFirstChapter()
        let paragraph = element("novel.firstParagraph")
        waitForEither(paragraph, element("novel.error"), timeout: 90)
        XCTAssertTrue(paragraph.exists, "Novel text failed: \(app.debugDescription)")
        attach("Tsundoku novel reader")
    }

    @MainActor
    func testAniyomiEpisodePlays() throws {
        openFirstItem(source: "ANIYOMI_2247987965040486818")
        openFirstChapter()
        assertPlaybackAdvances()
        attach("Aniyomi playback")
    }

    @MainActor
    func testJarMangaPagesRender() throws {
        openFirstItem(source: "MANGADEX", search: "MANGADEX")
        openFirstChapter()
        let image = app.images["reader.image"]
        waitForEither(image, app.staticTexts["reader.error"], timeout: 90)
        XCTAssertTrue(image.exists, "Reader failed: \(app.debugDescription)")
        attach("JAR manga reader")
    }

    @MainActor
    func testJarNovelChapterText() throws {
        openFirstItem(source: "BILINOVEL", search: "BILINOVEL")
        openFirstChapter()
        let paragraph = element("novel.firstParagraph")
        waitForEither(paragraph, element("novel.error"), timeout: 90)
        XCTAssertTrue(paragraph.exists, "Novel text failed: \(app.debugDescription)")
        attach("JAR novel reader")
    }

    @MainActor
    func testJarVideoPlays() throws {
        // AGEDM's default line needs a VIP account; FANTUAN serves plain HLS.
        openFirstItem(source: "FANTUAN", search: "FANTUAN")
        openFirstChapter()
        assertPlaybackAdvances()
        attach("JAR video playback")
    }

    // MARK: - Helpers

    /// Filters the source list first: other sources (e.g. imported CMS sites) may push the row off screen, and
    /// lazy list rows that were never on screen do not exist for XCUITest.
    private func openFirstItem(source name: String, search: String? = nil) {
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText(search ?? name)
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), "Source \(name) missing: \(app.debugDescription)")
        source.tap()
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "source.content.")).firstMatch
        waitForEither(first, element("sources.error"), timeout: 90)
        XCTAssertTrue(first.exists, "Browse failed: \(app.debugDescription)")
        sleep(2) // let covers arrive for the screenshot
        attach("\(name) browse")
        first.tap()
    }

    private func openFirstChapter() {
        let chapter = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "chapter.")).firstMatch
        waitForEither(chapter, app.staticTexts["details.error"], timeout: 90)
        XCTAssertTrue(chapter.exists, "Details failed: \(app.debugDescription)")
        attach("details")
        chapter.tap()
    }

    private func assertPlaybackAdvances() {
        let position = app.staticTexts["player.position"]
        XCTAssertTrue(position.waitForExistence(timeout: 60), "Player failed: \(app.debugDescription)")
        let started = XCTNSPredicateExpectation(predicate: NSPredicate { [app] _, _ in
            guard let app else { return false }
            return (position.exists && position.label != "已播放 0 秒") || app.staticTexts["player.error"].exists
        }, object: nil)
        wait(for: [started], timeout: 120)
        XCTAssertFalse(app.staticTexts["player.error"].exists, "Playback failed: \(app.debugDescription)")
    }

    private func element(_ identifier: String) -> XCUIElement { app.descendants(matching: .any)[identifier] }

    private func waitForEither(_ first: XCUIElement, _ second: XCUIElement, timeout: TimeInterval) {
        let done = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in first.exists || second.exists }, object: nil)
        wait(for: [done], timeout: timeout)
    }

    private func attach(_ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
