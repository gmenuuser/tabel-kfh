import XCTest

/// The calculators as the owner uses them: seeding saved to the history, spraying with the tank, the history.
/// Screenshots go to `SAMPLES_DIR/Screens/7N-*.png`.
final class AgroWalkthroughTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testCalculators() throws {
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        app.launch()
        let entry = app.buttons["openAgro"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the main screen offers «АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ»")
        scroll(to: entry)
        entry.tap()
        XCTAssertTrue(app.buttons["agro-seeding"].waitForExistence(timeout: 10))
        shot("70-agro")

        app.buttons["agro-seeding"].tap()
        type("10", into: "field-area")
        type("200", into: "field-rate")
        XCTAssertTrue(element(containing: "2 000 кг".replacingOccurrences(of: " ", with: "\u{00A0}"))
            .waitForExistence(timeout: 10), "the result is shown at once")
        shot("71-seeding")
        let save = app.buttons["Сохранить"].firstMatch
        scroll(to: save)
        save.tap()
        XCTAssertTrue(element(containing: "Расчёт сохранён в истории").waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["agro-spraying"].tap()
        type("10", into: "field-area")
        type("2", into: "field-rate")
        type("200", into: "field-water")
        type("1000", into: "field-tank")
        XCTAssertTrue(element(containing: "Полных заправок").waitForExistence(timeout: 10))
        scroll(to: element(containing: "Полных заправок"))
        shot("72-spraying")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let history = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "История расчётов")).firstMatch
        scroll(to: history)
        history.tap()
        XCTAssertTrue(element(containing: "Посев семян").waitForExistence(timeout: 10))
        shot("73-agro-history")
    }

    private func type(_ text: String, into id: String) {
        let field = app.textFields[id]
        scroll(to: field)
        XCTAssertTrue(field.waitForExistence(timeout: 10), id)
        field.tap()
        field.typeText(text)
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
