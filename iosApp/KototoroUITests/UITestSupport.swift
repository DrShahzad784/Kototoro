import XCTest

extension XCUIApplication {
    /// iOS can collapse the tab bar while scrolling; tapping the remaining selected tab expands it again.
    func mainTab(_ title: String) -> XCUIElement {
        let tab = tabBars.buttons[title]
        if !tab.exists {
            let collapsed = tabBars.buttons.matching(NSPredicate(format: "value == %@", "已折叠")).firstMatch
            if collapsed.exists {
                collapsed.tap()
                _ = tab.waitForExistence(timeout: 5)
            }
        }
        return tab
    }

    /// The page's own search field: the root pages' compact field (`pageSearch`), or a system search field on
    /// other pages. Opens it from the toolbar when it is not shown yet.
    func pageSearchField(timeout: TimeInterval = 10) -> XCUIElement {
        // Hidden tabs keep their views alive: only the visible (hittable) field or button belongs to this page.
        let own = textFields["pageSearch.field"]
        if own.exists && own.isHittable { return own }
        // The page's own entry wins over a system search field, which may be the global search left open.
        _ = buttons["pageSearch.open"].waitForExistence(timeout: 2)
        let open = buttons.matching(identifier: "pageSearch.open").allElementsBoundByIndex.first { $0.isHittable }
        if let open {
            open.tap()
            _ = own.waitForExistence(timeout: timeout)
            return own
        }
        let field = searchFields.firstMatch
        if field.waitForExistence(timeout: 2) && field.isHittable { return field }
        let button = navigationBars.buttons["搜索"].firstMatch
        if button.waitForExistence(timeout: timeout) { button.tap() }
        _ = field.waitForExistence(timeout: timeout)
        return field
    }
}

