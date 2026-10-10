import XCTest

final class LibraryPersistenceTests: XCTestCase {
    @MainActor
    func testCategorySurvivesAppRestart() {
        let app = XCUIApplication()
        app.launch()
        let add = app.buttons["category.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        let ready = NSPredicate(format: "enabled == true")
        expectation(for: ready, evaluatedWith: add)
        waitForExpectations(timeout: 30)
        add.tap()
        let field = app.textFields["category.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["category.save"].isEnabled)
        let title = "iOS Persistence \(UUID().uuidString.prefix(8))"
        field.tap()
        field.typeText(title)
        app.buttons["category.save"].tap()
        XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 10))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 30))
    }
}
