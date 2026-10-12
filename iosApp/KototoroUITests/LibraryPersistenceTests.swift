import XCTest

final class LibraryPersistenceTests: XCTestCase {
    @MainActor
    func testCategoryOrderAndVisibilitySurviveRestartAndCancelDoesNotWrite() {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        let suffix = UUID().uuidString.prefix(6)
        let first = "待读 \(suffix)", second = "在读 \(suffix)"
        for title in [first, second] {
            let add = app.buttons["category.add"]
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: add)
            waitForExpectations(timeout: 30)
            add.tap()
            let field = app.textFields["category.title"]
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap(); field.typeText(title)
            app.buttons["category.save"].tap()
            XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        }

        func visibility(_ title: String) -> XCUIElement {
            app.switches.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@",
                                              "category.visibility.", title)).firstMatch
        }
        func openManager() {
            app.buttons["category.manage"].tap()
            XCTAssertTrue(app.navigationBars["管理分类"].waitForExistence(timeout: 10))
            for _ in 0..<20 {
                if visibility(second).isHittable { break }
                app.collectionViews.firstMatch.swipeUp()
            }
            XCTAssertTrue(visibility(first).isHittable && visibility(second).isHittable, app.debugDescription)
        }
        func toggleVisibility(_ title: String) {
            // The editable row's accessibility frame includes the label and reorder handle; tap the switch thumb.
            visibility(title).coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).tap()
        }

        openManager()
        toggleVisibility(first)
        XCTAssertEqual(visibility(first).value as? String, "0")
        app.buttons["category.manage.cancel"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(chip(first, in: app).exists)
        openManager()
        XCTAssertEqual(visibility(first).value as? String, "1", "Cancel must not write the hidden flag")
        let firstId = String(visibility(first).identifier.dropFirst("category.visibility.".count))
        let secondId = String(visibility(second).identifier.dropFirst("category.visibility.".count))
        let firstCell = app.cells.containing(.switch, identifier: "category.visibility.\(firstId)").firstMatch
        let secondCell = app.cells.containing(.switch, identifier: "category.visibility.\(secondId)").firstMatch
        XCTAssertTrue(firstCell.exists && secondCell.exists, app.debugDescription)
        secondCell.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).press(
            forDuration: 0.5, thenDragTo: firstCell.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.1)),
            withVelocity: .slow, thenHoldForDuration: 0.3)
        XCTAssertLessThan(visibility(second).frame.minY, visibility(first).frame.minY, "Dragging must reorder the rows")
        toggleVisibility(first)
        let edited = XCTAttachment(screenshot: app.screenshot()); edited.name = "Category order and shelf visibility"
        edited.lifetime = .keepAlways; add(edited)
        app.buttons["category.manage.save"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertFalse(chip(first, in: app).exists)
        XCTAssertTrue(chip(second, in: app).exists)

        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["category.manage"].waitForExistence(timeout: 30))
        XCTAssertFalse(chip(first, in: app).exists)
        openManager()
        XCTAssertLessThan(visibility(second).frame.minY, visibility(first).frame.minY)
        XCTAssertEqual(visibility(first).value as? String, "0")
        XCTAssertEqual(visibility(second).value as? String, "1")
        // Restore the hidden category through the same UI; both chips should become available again.
        toggleVisibility(first)
        app.buttons["category.manage.save"].tap()
        XCTAssertTrue(app.navigationBars["管理分类"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(chip(first, in: app).exists)
    }

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
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        XCTAssertTrue(reveal(chip(title, in: app), in: app), app.debugDescription)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["category.add"].waitForExistence(timeout: 30))
        XCTAssertTrue(reveal(chip(title, in: app), in: app), app.debugDescription)
    }

    /// Category chips are labelled "title count".
    private func chip(_ title: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "category.", title)).firstMatch
    }

    /// Chips are not lazy: every category exists in the bar even when scrolled out of view.
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        element.waitForExistence(timeout: 15)
    }
}
