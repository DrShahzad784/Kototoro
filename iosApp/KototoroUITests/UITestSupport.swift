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

    /// The page's own system search field. Hidden tabs keep their views (and fields) alive, so only a hittable
    /// field belongs to this page; a field tucked under the title is pulled back into view, and a page that keeps
    /// search in its toolbar opens it from there.
    func pageSearchField(timeout: TimeInterval = 10) -> XCUIElement {
        func visible() -> XCUIElement? { searchFields.allElementsBoundByIndex.first { $0.exists && $0.isHittable } }
        _ = searchFields.firstMatch.waitForExistence(timeout: 2)
        if let field = visible() { return field }
        let scroll = scrollViews.allElementsBoundByIndex.first { $0.isHittable } ?? collectionViews.firstMatch
        if scroll.exists { scroll.swipeDown() }
        if let field = visible() { return field }
        let button = navigationBars.buttons["搜索"].firstMatch
        if button.waitForExistence(timeout: timeout) { button.tap() }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let field = visible() { return field }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return searchFields.firstMatch
    }
}

