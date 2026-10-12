import XCTest

final class RepositoryManagementTests: XCTestCase {
    @MainActor
    func testNativeRepositoryFlowSearchFiltersAndExtensionDetails() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["sources.repositories"].waitForExistence(timeout: 30))
        app.buttons["sources.repositories"].tap()
        let tabs = app.segmentedControls["repository.tabs"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        clearFilters(app)
        let refreshing = app.descendants(matching: .any)["repository.refreshing"]
        let refreshed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !refreshing.exists }, object: nil)
        wait(for: [refreshed], timeout: 90)
        app.buttons["repository.actions"].tap()
        app.buttons["repository.add"].tap()
        let address = app.textFields["repository.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        attach(app, "添加仓库")
        address.tap()
        address.typeText("not-a-repository")
        app.buttons["repository.import"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["repository.error"].waitForExistence(timeout: 10))
        address.tap()
        address.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "not-a-repository".count))
        address.typeText("http://127.0.0.1:8766/ui/repo.json")
        app.buttons["repository.import"].tap()
        let added = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !address.exists }, object: nil)
        wait(for: [added], timeout: 30)
        app.buttons["repository.actions"].tap()
        XCTAssertTrue(app.buttons["repository.importFile"].isEnabled)
        app.buttons["repository.importFile"].tap()
        let cancelFileImport = app.buttons["取消"]
        XCTAssertTrue(cancelFileImport.waitForExistence(timeout: 10))
        attach(app, "文件导入")
        cancelFileImport.tap()
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("fixture.reader\n")
        let details = app.buttons["extension.details.MIHON:fixture.reader.multi"]
        XCTAssertTrue(details.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.buttons["extension.install.fixture.reader.multi"].isEnabled)
        XCTAssertFalse(app.staticTexts["此版本可浏览仓库，暂不支持扩展安装。"].exists)
        let chinese = app.buttons["extension.details.MIHON:fixture.reader.zh"]
        let english = app.buttons["extension.details.MIHON:fixture.reader.en"]
        let language = app.buttons["extension.language"]
        XCTAssertTrue(language.exists)
        language.tap()
        app.buttons["中文"].tap()
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(language.label.contains("中文"))
        XCTAssertTrue(chinese.exists)
        XCTAssertTrue(details.exists)
        XCTAssertFalse(english.exists)
        attach(app, "中文语言过滤")
        language.tap()
        app.buttons["英语"].tap()
        attach(app, "语言多选菜单")
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(language.label.contains("中文"))
        XCTAssertTrue(language.label.contains("英语"))
        XCTAssertTrue(details.exists)
        XCTAssertTrue(chinese.exists)
        XCTAssertTrue(english.exists)
        attach(app, "中英语言多选过滤")
        language.tap()
        app.buttons["中文"].tap()
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(details.exists)
        XCTAssertTrue(english.exists)
        XCTAssertFalse(chinese.exists)
        language.tap()
        app.buttons["英语"].tap()
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(language.label.contains("全部语言"))
        XCTAssertFalse(app.buttons["extension.language.reset"].exists)
        XCTAssertTrue(chinese.exists)
        XCTAssertTrue(english.exists)
        language.tap()
        app.buttons["多语言"].tap()
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(details.exists)
        XCTAssertFalse(chinese.exists)
        XCTAssertFalse(english.exists)
        app.buttons["extension.language.reset"].tap()
        XCTAssertTrue(language.label.contains("全部语言"))
        XCTAssertTrue(chinese.exists)
        language.tap()
        app.buttons["中文"].tap()
        app.buttons["英语"].tap()
        app.buttons["extension.language.all"].tap()
        app.navigationBars["扩展管理"].tap()
        XCTAssertTrue(language.label.contains("全部语言"))
        XCTAssertTrue(english.exists)
        attach(app, "扩展列表")
        details.tap()
        XCTAssertTrue(app.staticTexts["包含的来源 · 2"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["English Comics"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["extension.repository"].label.contains("SwiftUI 体验仓库"))
        attach(app, "扩展详情")
        app.buttons["extension.close"].tap()

        XCTAssertTrue(search.exists)
        search.tap()
        search.buttons.firstMatch.tap()
        search.typeText("English Comics")
        XCTAssertTrue(details.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["extension.details.MIHON:fixture.reader.zh"].exists)
        search.buttons.firstMatch.tap()
        search.typeText("fixture.reader\n")
        // Dismiss the keyboard before interacting with the pinned native segmented control.
        app.segmentedControls["extension.scope"].buttons["可安装"].tap()
        XCTAssertTrue(details.waitForExistence(timeout: 10))
        app.segmentedControls["extension.scope"].buttons["更新"].tap()
        XCTAssertFalse(details.exists)
        app.segmentedControls["extension.scope"].buttons["全部"].tap()
        tabs.buttons["仓库"].tap()
        let repository = app.buttons["repository.row.MIHON.http://127.0.0.1:8766/ui/repo.json"]
        XCTAssertTrue(repository.waitForExistence(timeout: 10), app.debugDescription)
        attach(app, "仓库列表")
        repository.tap()
        XCTAssertTrue(details.waitForExistence(timeout: 10))
        attach(app, "仓库详情")
        details.tap()
        XCTAssertTrue(app.staticTexts["English Comics"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testFiltersPersistAcrossNavigationRelaunchAndReset() throws {
        let app = XCUIApplication()
        app.launch()
        openRepositories(app)
        clearFilters(app)
        let refreshing = app.descendants(matching: .any)["repository.refreshing"]
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !refreshing.exists },
                                             object: nil)], timeout: 90)
        app.buttons["repository.actions"].tap()
        app.buttons["repository.add"].tap()
        let address = app.textFields["repository.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        address.tap()
        address.typeText("http://127.0.0.1:8766/ui/repo.json")
        app.buttons["repository.import"].tap()
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !address.exists },
                                             object: nil)], timeout: 30)
        searchFixtures(app)
        let chinese = app.buttons["extension.details.MIHON:fixture.reader.zh"]
        let english = app.buttons["extension.details.MIHON:fixture.reader.en"]
        XCTAssertTrue(chinese.waitForExistence(timeout: 30))
        app.buttons["extension.language"].tap()
        app.buttons["中文"].tap()
        app.buttons["英语"].tap()
        app.navigationBars["扩展管理"].tap()
        app.segmentedControls["extension.scope"].buttons["可安装"].tap()
        app.buttons["关闭"].tap()
        selectEcosystem("Aniyomi", in: app)
        searchFixtures(app)
        XCTAssertFalse(chinese.exists)
        XCTAssertFalse(english.exists)

        app.buttons["关闭"].tap()
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["sources.repositories"].waitForExistence(timeout: 10))
        app.buttons["sources.repositories"].tap()
        assertSavedSelection(app)
        attach(app, "返回页面保留筛选")

        app.terminate()
        app.launch()
        openRepositories(app)
        assertSavedSelection(app)
        searchFixtures(app)
        wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !refreshing.exists },
                                             object: nil)], timeout: 90)
        XCTAssertFalse(chinese.exists)
        XCTAssertFalse(english.exists)
        attach(app, "重启恢复筛选")
        // Changing only the ecosystem proves the restored filter was hiding the Mihon fixture.
        app.buttons["关闭"].tap()
        selectEcosystem("Mihon", in: app)
        searchFixtures(app)
        XCTAssertTrue(chinese.waitForExistence(timeout: 10))
        XCTAssertTrue(english.exists)
        clearFilters(app)

        app.terminate()
        app.launch()
        openRepositories(app)
        XCTAssertTrue(app.buttons["extension.language"].label.contains("全部语言"))
        XCTAssertTrue(app.segmentedControls["extension.scope"].buttons["全部"].isSelected)
        app.buttons["extension.filters"].tap()
        XCTAssertFalse(app.buttons["extension.filters.reset"].exists)
        app.navigationBars["扩展管理"].tap()
        searchFixtures(app)
        XCTAssertTrue(chinese.waitForExistence(timeout: 30))
        XCTAssertTrue(english.exists)
    }

    @MainActor
    private func openRepositories(_ app: XCUIApplication) {
        app.tabBars.buttons["浏览"].tap()
        XCTAssertTrue(app.buttons["sources.repositories"].waitForExistence(timeout: 30))
        app.buttons["sources.repositories"].tap()
        XCTAssertTrue(app.segmentedControls["repository.tabs"].waitForExistence(timeout: 30))
    }

    @MainActor
    private func searchFixtures(_ app: XCUIApplication) {
        let search = app.pageSearchField()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("fixture.reader\n")
    }

    @MainActor
    private func selectEcosystem(_ title: String, in app: XCUIApplication) {
        app.buttons["extension.filters"].tap()
        if app.buttons["生态"].exists { app.buttons["生态"].tap() }
        app.buttons[title].tap()
    }

    @MainActor
    private func clearFilters(_ app: XCUIApplication) {
        if app.buttons["关闭"].exists { app.buttons["关闭"].tap() }
        app.buttons["extension.filters"].tap()
        let reset = app.buttons["extension.filters.reset"]
        if reset.exists { reset.tap() }
        else { app.navigationBars["扩展管理"].tap() }
    }

    @MainActor
    private func assertSavedSelection(_ app: XCUIApplication) {
        let language = app.buttons["extension.language"]
        XCTAssertTrue(language.label.contains("中文"))
        XCTAssertTrue(language.label.contains("英语"))
        XCTAssertTrue(app.segmentedControls["extension.scope"].buttons["可安装"].isSelected)
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
