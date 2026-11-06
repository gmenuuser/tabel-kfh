import XCTest

/// Walks the whole app in the simulator the way the owner would on the first day and keeps a screenshot
/// of every screen: as an attachment of the test result and as `SAMPLES_DIR/Screens/NN-name.png`
/// (CI artifact «review-samples»).
final class AppWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // Добавление сотрудника, явка одной кнопкой, статус из меню, вчерашний день, табель за месяц.
    func testWalkthroughLight() throws {
        launch(dark: false)

        let add = app.buttons["Добавить сотрудника"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 15), "empty Home offers «Добавить сотрудника»")
        XCTAssertTrue(app.staticTexts["В этот день нет работающих сотрудников."].exists)
        shot("01-home-empty")

        addEmployee(screenshot: "02-employee-form")
        XCTAssertTrue(element(containing: "Зубахин Иван Петрович").waitForExistence(timeout: 10))
        shot("03-home-employee")

        markPresence(screenshots: true)

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
        markPresence(screenshots: false)
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
        // The main screen: three large buttons.
        let timesheet = app.buttons["openTimesheet"]
        XCTAssertTrue(timesheet.waitForExistence(timeout: 15), "the main screen offers «ТАБЕЛЬ РАБОТНИКОВ»")
        XCTAssertTrue(app.buttons["openCoal"].exists, "and «ПРОДАЖА УГЛЯ»")
        XCTAssertTrue(app.buttons["openReports"].exists, "and «ОТЧЁТЫ»")
        shot(dark ? "20-dark-main" : "00-main")
        timesheet.tap()
        XCTAssertTrue(app.navigationBars["Табель работников"].waitForExistence(timeout: 10))
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

    /// «Отметить: Я», a second tap cancels it, a status from the menu, then yesterday.
    private func markPresence(screenshots: Bool) {
        XCTAssertFalse(app.buttons["Пришёл"].exists, "no arrival button any more")
        XCTAssertFalse(app.buttons["Ушёл"].exists)
        let mark = app.buttons["mark-Зубахин"]
        XCTAssertTrue(mark.waitForExistence(timeout: 10))
        XCTAssertTrue(element(containing: "Не отмечен").exists)
        mark.tap()
        dismissAlertIfShown()
        XCTAssertTrue(element(containing: "Был на работе").waitForExistence(timeout: 10), "one tap marks Я")
        if screenshots { shot("04-home-present") }
        mark.tap()
        XCTAssertTrue(element(containing: "Не отмечен").waitForExistence(timeout: 10), "a second tap cancels it")

        let menu = app.buttons["Статус Зубахин Иван Петрович"]
        menu.tap()
        let sick = app.buttons["Б — Больничный"]
        XCTAssertTrue(sick.waitForExistence(timeout: 10), "the menu offers the statuses")
        if screenshots { shot("05-status-menu") }
        sick.tap()
        XCTAssertTrue(element(containing: "Больничный").waitForExistence(timeout: 10))

        app.buttons["Вчера"].firstMatch.tap()
        XCTAssertTrue(element(containing: "Четверг, 5 ноября 2026").waitForExistence(timeout: 10), "yesterday is shown")
        // Hired today: yesterday the person was not in the farm yet.
        XCTAssertTrue(element(containing: "нет работающих сотрудников").waitForExistence(timeout: 10))
        if screenshots { shot("06-home-yesterday") }
        app.buttons["Сегодня"].firstMatch.tap()
        XCTAssertTrue(element(containing: "Больничный").waitForExistence(timeout: 10), "today kept «Б»")
    }

    /// The month table: Б on the 6th, then a tap on the 7th makes it a day off.
    private func generateTimesheet() {
        tab("Табель")
        XCTAssertTrue(app.buttons["monthTitle"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["monthTitle"].label.contains("НОЯБРЬ 2026"))
        XCTAssertTrue(element(containing: "Зубахин И. П.").waitForExistence(timeout: 10), "the grid shows the person")
        let cell = app.buttons["Зубахин Иван Петрович, 7: не отмечен"]
        XCTAssertTrue(cell.waitForExistence(timeout: 10))
        cell.tap()
        let dayOff = app.buttons["В — Выходной"]
        XCTAssertTrue(dayOff.waitForExistence(timeout: 10))
        dayOff.tap()
        XCTAssertTrue(app.buttons["Зубахин Иван Петрович, 7: Выходной"].waitForExistence(timeout: 10), "the cell changed")
        XCTAssertTrue(app.buttons["Скачать PDF"].firstMatch.waitForExistence(timeout: 15))
    }

    // MARK: - Helpers

    /// The old tabs, now: «Настройки» behind the gear of the main screen; «Сотрудники», «Календарь» and
    /// «Табель» in «Ещё» of «Табель работников».
    private func tab(_ title: String) {
        backToMain()
        if title == "Настройки" {
            app.buttons["openSettings"].tap()
            XCTAssertTrue(app.navigationBars["Настройки"].waitForExistence(timeout: 10))
            return
        }
        app.buttons["openTimesheet"].tap()
        let more = app.navigationBars["Табель работников"].buttons["Ещё"]
        XCTAssertTrue(more.waitForExistence(timeout: 10), "«Ещё» on «Табель работников»")
        more.tap()
        let item = ["Сотрудники": "Сотрудники и история", "Календарь": "Календарь", "Табель": "Табель за месяц"][title] ?? title
        let button = app.buttons[item].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 10), "menu item «\(item)»")
        button.tap()
    }

    /// «Назад» until the main screen shows.
    private func backToMain() {
        for _ in 0..<5 where !app.buttons["openTimesheet"].exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            _ = app.buttons["openTimesheet"].waitForExistence(timeout: 2)
        }
        XCTAssertTrue(app.buttons["openTimesheet"].exists, "back on the main screen")
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
