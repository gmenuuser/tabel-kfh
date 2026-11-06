import XCTest

/// The coal module in the simulator the way the owner would use it on the first day: two drivers, a hired and
/// an own-vehicle sale, a payment, closing the day. Screenshots go to `SAMPLES_DIR/Screens/3N-*.png`.
final class CoalWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testCoalDay() throws {
        launch(dark: false)
        openModule()
        shot("30-coal-today-empty")

        tab("Водители")
        addDriver("Мунгалов", "Сергей")
        addDriver("Кайдолов", "Пётр")
        XCTAssertTrue(element(containing: "Мунгалов Сергей").waitForExistence(timeout: 10))
        shot("31-drivers")

        tab("Продажи")
        newSale(tons: "2 т", place: "Новоцурухайтуй", driver: "М Мунгалов", own: false, expectTotal: "14", shotName: "32-new-sale")
        newSale(tons: "1 т", place: "Приаргунск", driver: nil, own: true, expectTotal: "7", shotName: "33-new-sale-own")
        XCTAssertTrue(element(containing: "Наёмная: Мунгалов").waitForExistence(timeout: 10))
        XCTAssertTrue(element(containing: "Собственная машина КФХ").exists)
        shot("34-coal-today")

        tab("Итоги дня")
        XCTAssertTrue(element(containing: "Финансовые итоги").waitForExistence(timeout: 10))
        shot("35-day-summary")
        let pay = app.buttons["Выплатить водителю"].firstMatch
        scroll(to: pay)
        shot("36-day-summary-drivers")
        pay.tap()
        let amount = app.textFields["paymentAmount"]
        XCTAssertTrue(amount.waitForExistence(timeout: 10))
        // The cursor goes to the end of «1000», then the text is replaced.
        amount.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        amount.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 10) + "500")
        shot("37-payment")
        app.navigationBars.buttons["Выплатить"].tap()
        XCTAssertTrue(element(containing: "Выплаты водителям").waitForExistence(timeout: 10))
        let export = app.buttons["Поделиться"].firstMatch
        scroll(to: export)
        shot("38-day-summary-export")

        tab("Продажи")
        let close = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ")).firstMatch
        scroll(to: close)
        close.tap()
        let confirm = app.buttons["Подтвердить завершение дня"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        shot("39-close-day")
        confirm.tap()
        let finish = app.buttons["Завершить день"]
        XCTAssertTrue(finish.waitForExistence(timeout: 5))
        finish.tap()
        XCTAssertTrue(element(containing: "Рабочий день завершён").waitForExistence(timeout: 10))
        shot("40-day-closed")

        tab("История")
        XCTAssertTrue(app.navigationBars["История"].waitForExistence(timeout: 10))
        shot("41-history")
        app.buttons["Дни"].firstMatch.tap()
        XCTAssertTrue(element(containing: "06.11.2026").waitForExistence(timeout: 10))
        shot("42-history-days")

        tab("Водители")
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Мунгалов Сергей")).firstMatch.tap()
        XCTAssertTrue(element(containing: "Текущий долг").waitForExistence(timeout: 10))
        shot("43-driver")

        tab("Цены")
        XCTAssertTrue(element(containing: "Приаргунск").waitForExistence(timeout: 10))
        shot("44-prices")

        app.navigationBars.buttons["Табель"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Добавить сотрудника"].firstMatch.waitForExistence(timeout: 10),
                      "«Табель» goes back to the timesheet")
    }

    func testCoalDark() throws {
        launch(dark: true)
        openModule()
        tab("Водители")
        addDriver("Мунгалов", "Сергей")
        tab("Продажи")
        newSale(tons: "2 т", place: "Новоцурухайтуй", driver: "М Мунгалов", own: false, expectTotal: "14",
                shotName: "50-dark-new-sale")
        shot("51-dark-coal-today")
        tab("Итоги дня")
        XCTAssertTrue(element(containing: "Финансовые итоги").waitForExistence(timeout: 10))
        shot("52-dark-day-summary")
    }

    // MARK: - Steps

    private func launch(dark: Bool) {
        app = XCUIApplication()
        app.launchArguments = ["-uitest"] + (dark ? ["-uitest-dark"] : [])
        app.launch()
    }

    private func openModule() {
        let entry = app.buttons["openCoalModule"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "Главная offers «Продажа угля»")
        shot("29-home-coal-entry")
        entry.tap()
        XCTAssertTrue(app.navigationBars["Продажи за сегодня"].waitForExistence(timeout: 10))
    }

    private func addDriver(_ lastName: String, _ firstName: String) {
        app.navigationBars.buttons["Добавить водителя"].firstMatch.tap()
        let last = app.textFields["Фамилия"]
        XCTAssertTrue(last.waitForExistence(timeout: 10))
        last.tap()
        last.typeText(lastName)
        let first = app.textFields["Имя"]
        first.tap()
        first.typeText(firstName)
        app.navigationBars.buttons["Сохранить"].tap()
        XCTAssertTrue(element(containing: "\(lastName) \(firstName)").waitForExistence(timeout: 10))
    }

    private func newSale(tons: String, place: String, driver: String?, own: Bool, expectTotal: String, shotName: String) {
        let new = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Новая продажа")).firstMatch
        XCTAssertTrue(new.waitForExistence(timeout: 10))
        new.tap()
        XCTAssertTrue(app.navigationBars["Новая продажа"].waitForExistence(timeout: 10))
        app.buttons[tons].firstMatch.tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", place)).firstMatch.tap()
        if own {
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Собственная КФХ")).firstMatch.tap()
        }
        if let driver {
            let button = app.buttons[driver].firstMatch
            scroll(to: button)
            button.tap()
        }
        let total = app.staticTexts["saleTotal"]
        scroll(to: total)
        XCTAssertTrue(total.label.hasPrefix(expectTotal), "total \(total.label)")
        shot(shotName)
        let save = app.buttons["СОХРАНИТЬ ПРОДАЖУ"]
        scroll(to: save)
        save.tap()
        XCTAssertTrue(app.navigationBars["Продажи за сегодня"].waitForExistence(timeout: 10), "the form closes")
    }

    private func tab(_ title: String) {
        let button = app.tabBars.buttons[title]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "tab «\(title)»")
        button.tap()
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
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try screenshot.pngRepresentation.write(to: directory.appendingPathComponent("\(name).png"))
        } catch {
            XCTFail("could not write \(name).png into \(directory.path): \(error)")
        }
    }
}
