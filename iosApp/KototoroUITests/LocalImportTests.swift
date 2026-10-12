import XCTest

/// Local works through the document picker: `Scripts/stage-picker-fixtures.py` places a two-page CBZ and a GB18030
/// TXT novel in "On My iPhone"; both are imported from the 本地作品 sources and open in the readers.
final class LocalImportTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    @MainActor
    func testImportedComicOpensInTheReader() throws {
        importFile(source: "LOCAL", file: "fixture_comic")
        openFirstChapter(title: "fixture_comic")
        XCTAssertTrue(app.images["reader.image"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        attach("本地漫画阅读")
    }

    @MainActor
    func testImportedTextNovelSplitsChapters() throws {
        importFile(source: "LOCAL_NOVEL", file: "fixture_novel")
        openFirstChapter(title: "fixture_novel")
        let paragraph = app.staticTexts["novel.firstParagraph"]
        XCTAssertTrue(paragraph.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(paragraph.label.contains("简介"), paragraph.label)
        attach("本地小说阅读")
    }

    private func importFile(source name: String, file: String) {
        app.tabBars.buttons["浏览"].tap()
        let field = app.pageSearchField()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("本地")
        let source = app.buttons["source.\(name)"]
        XCTAssertTrue(source.waitForExistence(timeout: 30), app.debugDescription)
        source.tap()
        let add = app.buttons["browse.importLocal"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), app.debugDescription)
        add.tap()
        let picked = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", file)).firstMatch
        XCTAssertTrue(picked.waitForExistence(timeout: 30), "Stage the fixtures first: \(app.debugDescription)")
        picked.tap()
        // Multiple selection keeps the picker open until 打开 is pressed.
        for label in ["打开", "Open"] where app.buttons[label].waitForExistence(timeout: 3) {
            app.buttons[label].tap(); break
        }
        let item = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "source.content.", file)).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 30), app.debugDescription)
        attach("\(name) 导入后")
        item.tap()
    }

    private func openFirstChapter(title: String) {
        XCTAssertTrue(app.staticTexts["details.title"].waitForExistence(timeout: 30), app.debugDescription)
        let chapter = revealChapter(in: app, timeout: 30)
        XCTAssertTrue(chapter.exists, app.debugDescription)
        chapter.tap()
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
