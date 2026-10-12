import XCTest

final class BackupFlowTests: XCTestCase {
    /// Full round trip through the system document picker: export → save → pick the same file → preview → merge.
    @MainActor
    func testExportSaveAndRestoreThroughDocumentPicker() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["设置"].tap()
        let entry = app.buttons["settings.backup"]
        XCTAssertTrue(entry.waitForExistence(timeout: 30))
        entry.tap()
        let export = app.buttons["backup.export"]
        XCTAssertTrue(export.waitForExistence(timeout: 30))
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: export)], timeout: 30)
        export.tap()
        let save = app.buttons["DOCPicker.actionButton"]
        XCTAssertTrue(save.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "导出备份")
        save.tap()
        let message = app.staticTexts["backup.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertEqual(message.label, "备份已保存")

        app.buttons["backup.import"].tap()
        let file = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH 'kototoro_'")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "选择备份")
        file.tap()
        let restore = app.buttons["backup.restore"]
        XCTAssertTrue(restore.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.descendants(matching: .any)["backup.count.categories"].exists)
        attach(app, "备份预览")
        restore.tap()
        let merged = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH '已合并'"), object: message)
        wait(for: [merged], timeout: 30)
        XCTAssertFalse(app.descendants(matching: .any)["backup.error"].exists)
        attach(app, "合并完成")
    }

    /// Another app's backup through the same entry: `Scripts/stage-picker-fixtures.py` places a Mihon
    /// `.tachibk` in "On My iPhone"; it is recognised, previewed and imported with the uninstalled extension reported.
    @MainActor
    func testImportMihonBackupThroughDocumentPicker() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["设置"].tap()
        let entry = app.buttons["settings.backup"]
        XCTAssertTrue(entry.waitForExistence(timeout: 30))
        entry.tap()
        let open = app.buttons["backup.import"]
        XCTAssertTrue(open.waitForExistence(timeout: 30))
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: open)], timeout: 30)
        open.tap()
        let file = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH 'fixture_mihon'")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 30), "Stage the fixture first: \(app.debugDescription)")
        file.tap()
        let importButton = app.buttons["backup.importExternal"]
        XCTAssertTrue(importButton.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.descendants(matching: .any)["backup.externalApp"].label.contains("Mihon"))
        XCTAssertTrue(app.descendants(matching: .any)["backup.externalFavourites"].label.contains("1"))
        attach(app, "Mihon 备份预览")
        importButton.tap()
        let message = app.staticTexts["backup.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertEqual(message.label, "已导入 Mihon 备份")
        let uninstalled = app.descendants(matching: .any)["backup.uninstalled"]
        XCTAssertTrue(uninstalled.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(uninstalled.label.contains("Fixture Source"), uninstalled.label)
        attach(app, "Mihon 导入结果")
        app.tabBars.buttons["作品库"].tap()
        let card = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "favourite.", "iOS 导入测试作品")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "导入后的作品库")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
