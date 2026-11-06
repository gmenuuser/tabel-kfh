import XCTest

/// Walks the whole app in the simulator the way the owner would on the first day and keeps a screenshot
/// of every screen: as an attachment of the test result and as `SAMPLES_DIR/Screens/NN-name.png`
/// (CI artifact «review-samples»).
final class AppWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // Spec 13: 1 добавление сотрудника, 4 приход, 5 уход, табель.
    func testWalkthroughLight() throws {
        launch(dark: false)

        let add = app.buttons["Добавить сотрудника"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 15), "empty Home offers «Добавить сотрудника»")
        XCTAssertTrue(app.staticTexts["Сегодня нет работающих сотрудников."].exists)
        shot("01-home-empty")

        addEmployee(screenshot: "02-employee-form")
        XCTAssertTrue(element(containing: "Зубахин Иван Петрович").waitForExistence(timeout: 10))
        shot("03-home-employee")

        markArrivalAndDeparture(screenshots: true)

        tab("Сотрудники")
        XCTAssertTrue(element(containing: "Зубахин Иван Петрович").waitForExistence(timeout: 10))
        XCTAssertTrue(element(containing: "Тракторист").exists)
        shot("07-employees")

        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Зубахин Иван Петрович")).firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Зубахин Иван Петрович"].waitForExistence(timeout: 10), "the history opens")
        XCTAssertTrue(element(containing: "Дней явки").waitForExistence(timeout: 10))
        shot("07a-employee-history")
        let archive = app.buttons["В архив"].firstMatch
        scroll(to: archive)
        XCTAssertTrue(archive.exists, "the history offers «В архив»")
        shot("07b-employee-history-archive")

        tab("Календарь")
        XCTAssertTrue(app.navigationBars["Календарь"].waitForExistence(timeout: 10))
        shot("08-calendar")

        generateTimesheet()
        shot("09-timesheet")
        for title in ["Скачать PDF", "Экспорт в Excel", "Распечатать", "Поделиться"] {
            XCTAssertTrue(app.buttons[title].firstMatch.exists, "export bar has «\(title)»")
        }
        scroll(to: app.buttons["Поделиться"].firstMatch)
        shot("10-timesheet-export")

        tab("Настройки")
        for title in ["Название КФХ", "ФИО руководителя", "Ответственное лицо", "Адрес", "Телефон"] {
            XCTAssertTrue(textField(title).waitForExistence(timeout: 10), "settings field «\(title)»")
        }
        shot("11-settings")
        let backup = app.buttons["Создать резервную копию"].firstMatch
        scroll(to: backup)
        XCTAssertTrue(backup.exists)
        shot("12-settings-backup")
    }

    func testWalkthroughDark() throws {
        launch(dark: true)

        XCTAssertTrue(app.buttons["Добавить сотрудника"].firstMatch.waitForExistence(timeout: 15))
        shot("21-dark-home-empty")
        addEmployee(screenshot: nil)
        XCTAssertTrue(element(containing: "Зубахин Иван Петрович").waitForExistence(timeout: 10))
        markArrivalAndDeparture(screenshots: false)
        shot("22-dark-home")

        generateTimesheet()
        shot("23-dark-timesheet")
        scroll(to: app.buttons["Поделиться"].firstMatch)
        shot("24-dark-timesheet-export")
    }

    // MARK: - Steps

    private func launch(dark: Bool) {
        app = XCUIApplication()
        app.launchArguments = ["-uitest", "-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU"]
        if dark { app.launchArguments.append("-uitest-dark") }
        app.launch()
    }

    private func addEmployee(screenshot: String?) {
        app.buttons["Добавить сотрудника"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Новый сотрудник"].waitForExistence(timeout: 10))
        type("Зубахин", into: "Фамилия")
        type("Иван", into: "Имя")
        type("Петрович", into: "Отчество")
        type("Тракторист", into: "Должность")
        if let screenshot { shot(screenshot) }
        app.navigationBars["Новый сотрудник"].buttons["Сохранить"].tap()
        XCTAssertTrue(app.navigationBars["Новый сотрудник"].waitForNonExistence(timeout: 10), "the form closes after saving")
    }

    /// «Пришёл», then «Ушёл» a few seconds later: the app asks to confirm such a short shift.
    private func markArrivalAndDeparture(screenshots: Bool) {
        let arrived = app.buttons["Пришёл"].firstMatch
        XCTAssertTrue(arrived.waitForExistence(timeout: 10))
        arrived.tap()
        dismissAlertIfShown()

        let left = app.buttons["Ушёл"].firstMatch
        XCTAssertTrue(left.waitForExistence(timeout: 10), "after «Пришёл» the card offers «Ушёл»")
        waitUntilEnabled(left)
        if screenshots { shot("04-home-at-work") }
        left.tap()

        let confirm = app.buttons["Отметить уход"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "a departure right after the arrival is confirmed first")
        if screenshots { shot("05-short-shift-confirmation") }
        confirm.tap()
        dismissAlertIfShown()

        XCTAssertTrue(element(containing: "Изменить").waitForExistence(timeout: 10), "the day is closed")
        XCTAssertFalse(app.buttons["Ушёл"].exists)
        if screenshots { shot("06-home-left") }
    }

    private func generateTimesheet() {
        tab("Табель")
        let generate = app.buttons["Сформировать табель"].firstMatch
        XCTAssertTrue(generate.waitForExistence(timeout: 10))
        generate.tap()
        dismissAlertIfShown()
        XCTAssertTrue(app.buttons["Скачать PDF"].firstMatch.waitForExistence(timeout: 15), "a version is shown")
        XCTAssertTrue(element(containing: "Зубахин").waitForExistence(timeout: 10), "the grid shows the person")
    }

    // MARK: - Helpers

    private func tab(_ title: String) {
        let button = app.tabBars.buttons[title]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "tab «\(title)»")
        button.tap()
    }

    private func type(_ text: String, into field: String) {
        let input = textField(field)
        XCTAssertTrue(input.waitForExistence(timeout: 10), "field «\(field)»")
        input.tap()
        input.typeText(text)
        XCTAssertEqual(input.value as? String, text, "typed into «\(field)»")
    }

    /// A text field by its title (SwiftUI shows it as the label and the placeholder).
    private func textField(_ title: String) -> XCUIElement {
        app.textFields.matching(NSPredicate(format: "label == %@ OR placeholderValue == %@ OR identifier == %@",
                                            title, title, title)).firstMatch
    }

    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// The card's button stays disabled for a second after a tap (double-tap guard).
    private func waitUntilEnabled(_ element: XCUIElement) {
        let deadline = Date().addingTimeInterval(15)
        while !element.isEnabled && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        if !element.isEnabled { shot("still-disabled-\(element.label)") }
        XCTAssertTrue(element.isEnabled, "«\(element.label)» is enabled again")
    }

    /// Swipes up until `element` is on screen (at most 6 times).
    private func scroll(to element: XCUIElement) {
        for _ in 0..<6 where !(element.exists && element.isHittable) {
            app.swipeUp()
        }
    }

    /// An unexpected alert would block the flow: keep its picture, then close it.
    private func dismissAlertIfShown() {
        let alert = app.alerts.firstMatch
        guard alert.waitForExistence(timeout: 1) else { return }
        shot("alert-\(alert.label)")
        alert.buttons.firstMatch.tap()
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
