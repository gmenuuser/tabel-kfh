import XCTest

/// «Скот и забой» the way the owner starts: 20 коров, a slaughter of 2 with the confirmation, the history and
/// the reports. Screenshots go to `SAMPLES_DIR/Screens/6N-*.png`.
final class LivestockWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testHerdAndSlaughter() throws {
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        app.launch()
        let entry = app.buttons["openLivestock"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the main screen offers «СКОТ И ЗАБОЙ»")
        entry.tap()
        XCTAssertTrue(app.navigationBars["Скот и забой"].waitForExistence(timeout: 10))
        shot("60-livestock-empty")

        app.buttons["Поголовье"].firstMatch.tap()
        let add = app.buttons["Добавить Коровы"]
        XCTAssertTrue(add.waitForExistence(timeout: 10))
        shot("61-herd")
        add.tap()
        let heads = app.textFields["headsField"]
        XCTAssertTrue(heads.waitForExistence(timeout: 10))
        heads.tap()
        heads.typeText("20")
        shot("62-add")
        app.navigationBars["Добавить"].buttons["Сохранить"].tap()
        XCTAssertTrue(app.staticTexts["heads-Коровы"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["heads-Коровы"].label, "20")

        // «Уменьшить» needs a reason first.
        app.buttons["Уменьшить Коровы"].tap()
        let decreaseHeads = app.textFields["headsField"]
        XCTAssertTrue(decreaseHeads.waitForExistence(timeout: 10))
        decreaseHeads.tap()
        decreaseHeads.typeText("1")
        app.navigationBars["Уменьшить"].buttons["Сохранить"].tap()
        XCTAssertTrue(element(containing: "Выберите причину уменьшения").waitForExistence(timeout: 10))
        shot("63-decrease-reason")
        app.navigationBars["Уменьшить"].buttons["Отмена"].tap()
        XCTAssertEqual(app.staticTexts["heads-Коровы"].label, "20", "nothing was written off")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Записать забой"].firstMatch.tap()
        let cows = app.buttons["Категория Коровы"]
        XCTAssertTrue(cows.waitForExistence(timeout: 10))
        cows.tap()
        app.buttons["2"].firstMatch.tap()
        shot("64-slaughter")
        let save = app.buttons["СОХРАНИТЬ ЗАБОЙ"]
        scroll(to: save)
        save.tap()
        let proceed = app.buttons["Продолжить"]
        XCTAssertTrue(proceed.waitForExistence(timeout: 10), "«Будет списано: 2 головы КРС. Продолжить?»")
        XCTAssertTrue(element(containing: "Будет списано: 2 головы КРС").exists)
        shot("65-slaughter-confirm")
        proceed.tap()
        XCTAssertTrue(element(containing: "Записано: забой 2 головы КРС").waitForExistence(timeout: 10))
        shot("66-slaughter-saved")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(element(containing: "18").waitForExistence(timeout: 10))
        shot("67-livestock-after")
        app.buttons["История операций"].firstMatch.tap()
        XCTAssertTrue(element(containing: "Забой").waitForExistence(timeout: 10))
        XCTAssertTrue(element(containing: "Поступление").exists)
        shot("68-history")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Отчёты по скоту"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Скачать PDF"].firstMatch.waitForExistence(timeout: 10))
        shot("69-reports")
    }

    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func scroll(to element: XCUIElement) {
        var tries = 0
        while (!element.exists || !element.isHittable) && tries < 8 {
            app.swipeUp()
            tries += 1
        }
    }

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let root = ProcessInfo.processInfo.environment["SAMPLES_DIR"], !root.isEmpty else { return }
        let directory = URL(fileURLWithPath: root).appendingPathComponent("Screens", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? screenshot.pngRepresentation.write(to: directory.appendingPathComponent("\(name).png"))
    }
}
