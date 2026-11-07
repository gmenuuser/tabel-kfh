import XCTest

/// Coal sales the way the owner works: everything on one screen — drivers added from the truck buttons, two
/// sales, the day closed; then the reports and the settings. Screenshots go to `SAMPLES_DIR/Screens/3N-*.png`.
final class CoalWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testCoalDay() throws {
        launch(dark: false)
        openCoal()
        shot("30-coal-empty")

        addDriver("Мунгалов", "Сергей")
        addDriver("Кайдолов", "Пётр")
        XCTAssertTrue(app.buttons["М Мунгалов"].waitForExistence(timeout: 10), "the new driver is a button")
        XCTAssertTrue(app.buttons["К Кайдолов"].exists)
        shot("31-coal-drivers")

        sell(tons: "2 т", place: "Новоцурухайтуй", vehicle: "М Мунгалов", expectTotal: "14", shotName: "32-coal-sale")
        XCTAssertTrue(element(containing: "Наёмная: Мунгалов").waitForExistence(timeout: 10), "the sale is listed")
        XCTAssertTrue(app.staticTexts["saleTotal"].label.hasPrefix("0"), "the form is clean for the next sale")
        sell(tons: "1 т", place: "Приаргунск", vehicle: "vehicle-own", expectTotal: "7", shotName: "33-coal-sale-own")
        XCTAssertTrue(element(containing: "Собственная машина КФХ").waitForExistence(timeout: 10))
        shot("34-coal-today")

        let close = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ")).firstMatch
        scroll(to: close)
        close.tap()
        let confirm = app.buttons["ПОДТВЕРДИТЬ ЗАВЕРШЕНИЕ ДНЯ"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        XCTAssertTrue(element(containing: "Мунгалов Сергей: к выплате").exists, "what to pay Мунгалов")
        XCTAssertTrue(element(containing: "Доход от собственной машины").exists)
        shot("35-close-day")
        confirm.tap()
        XCTAssertTrue(element(containing: "Рабочий день завершён").waitForExistence(timeout: 10))
        app.swipeDown()
        shot("36-day-closed")

        app.navigationBars["Продажа угля"].buttons["Ещё"].tap()
        app.buttons["Итоги дня и выплаты"].firstMatch.tap()
        XCTAssertTrue(element(containing: "Финансовые итоги").waitForExistence(timeout: 10))
        shot("37-day-summary")

        backToMain()
        shot("38-main-after-sales")
        app.buttons["openReports"].tap()
        XCTAssertTrue(app.navigationBars["Отчёты"].waitForExistence(timeout: 10))
        shot("39-reports")
        app.buttons["Выплаты водителям"].firstMatch.tap()
        let pay = app.buttons["Выплатить Мунгалов"].firstMatch
        XCTAssertTrue(pay.waitForExistence(timeout: 10))
        shot("40-payments")
        pay.tap()
        let amount = app.textFields["paymentAmount"]
        XCTAssertTrue(amount.waitForExistence(timeout: 10))
        amount.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        amount.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 10) + "500")
        shot("41-payment")
        app.navigationBars.buttons["Выплатить"].tap()
        XCTAssertTrue(element(containing: "получил 500").waitForExistence(timeout: 10), "the payment is counted")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Продажи угля"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Экспорт в Excel"].firstMatch.waitForExistence(timeout: 10))
        shot("42-sales-report")

        backToMain()
        app.buttons["openSettings"].tap()
        XCTAssertTrue(app.navigationBars["Настройки"].waitForExistence(timeout: 10))
        // Below the farm and the sync sections.
        let prices = app.buttons["Цена угля и населённые пункты"].firstMatch
        scroll(to: prices)
        XCTAssertTrue(prices.waitForExistence(timeout: 10))
        prices.tap()
        XCTAssertTrue(element(containing: "Приаргунск").waitForExistence(timeout: 10))
        shot("43-prices")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Водители"].firstMatch.tap()
        XCTAssertTrue(element(containing: "Мунгалов Сергей").waitForExistence(timeout: 10))
        shot("44-drivers")
    }

    func testCoalDark() throws {
        launch(dark: true)
        openCoal()
        addDriver("Мунгалов", "Сергей")
        sell(tons: "2 т", place: "Новоцурухайтуй", vehicle: "М Мунгалов", expectTotal: "14", shotName: "50-dark-coal-sale")
        shot("51-dark-coal")
        backToMain()
        shot("52-dark-main")
    }

    // MARK: - Steps

    private func launch(dark: Bool) {
        app = XCUIApplication()
        app.launchArguments = ["-uitest"] + (dark ? ["-uitest-dark"] : [])
        app.launch()
    }

    private func openCoal() {
        let entry = app.buttons["openCoal"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the main screen offers «ПРОДАЖА УГЛЯ»")
        entry.tap()
        XCTAssertTrue(app.navigationBars["Продажа угля"].waitForExistence(timeout: 10))
    }

    /// From the «+ Водитель» button among the trucks.
    private func addDriver(_ lastName: String, _ firstName: String) {
        let add = app.buttons["Добавить водителя"].firstMatch
        scroll(to: add)
        add.tap()
        let last = app.textFields["Фамилия"]
        XCTAssertTrue(last.waitForExistence(timeout: 10))
        last.tap()
        last.typeText(lastName)
        let first = app.textFields["Имя"]
        first.tap()
        first.typeText(firstName)
        app.navigationBars.buttons["Сохранить"].tap()
        XCTAssertTrue(app.navigationBars["Новый водитель"].waitForNonExistence(timeout: 10))
    }

    /// Tons, village, truck, then «СОХРАНИТЬ ПРОДАЖУ» — all on the one screen.
    private func sell(tons: String, place: String, vehicle: String, expectTotal: String, shotName: String) {
        let chip = app.buttons[tons].firstMatch
        scrollBack(to: chip)
        chip.tap()
        let location = app.buttons["location-\(place)"]
        scroll(to: location)
        location.tap()
        let truck = app.buttons[vehicle].firstMatch
        scroll(to: truck)
        truck.tap()
        let total = app.staticTexts["saleTotal"]
        scroll(to: total)
        XCTAssertTrue(total.label.hasPrefix(expectTotal), "total \(total.label)")
        shot(shotName)
        let save = app.buttons["СОХРАНИТЬ ПРОДАЖУ"]
        scroll(to: save)
        save.tap()
        XCTAssertTrue(element(containing: "Сохранено:").waitForExistence(timeout: 10), "the sale is saved")
    }

    private func backToMain() {
        for _ in 0..<5 where !app.buttons["openCoal"].exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            _ = app.buttons["openCoal"].waitForExistence(timeout: 2)
        }
        XCTAssertTrue(app.buttons["openCoal"].exists, "back on the main screen")
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

    private func scrollBack(to element: XCUIElement) {
        var tries = 0
        while (!element.exists || !element.isHittable) && tries < 8 {
            app.swipeDown()
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
