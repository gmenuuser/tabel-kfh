import XCTest
import SwiftData
@testable import TabelKFH

/// The timesheet of statuses: one tap for Я, a menu for the other statuses, no times.
@MainActor
final class DayMarkTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!
    private var marks: AttendanceService!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(9 * 3600)
        marks = AttendanceService(context: context, now: { [unowned self] in self.clock }, calendar: calendar)
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date { calendar.date(day: d, month: m, year: y) }

    @discardableResult
    private func employee(_ lastName: String, hired: Date? = nil, dismissed: Date? = nil) throws -> Employee {
        let e = Employee(lastName: lastName, firstName: "Иван", hireDate: hired ?? day(1, 1), dismissalDate: dismissed)
        context.insert(e)
        try context.save()
        return e
    }

    private func records() throws -> Int { try context.fetchCount(FetchDescriptor<AttendanceRecord>()) }

    // Проверка 1–2.
    func testOneTapMarksPresenceAndASecondTapCancelsIt() throws {
        let e = try employee("Иванов")
        XCTAssertNil(marks.status(of: e, on: day(6)), "не отмечен by default")
        XCTAssertEqual(try marks.togglePresence(e, on: day(6)), .present)
        XCTAssertEqual(marks.status(of: e, on: day(6)), .present)
        let r = try XCTUnwrap(marks.record(for: e, day: day(6)))
        XCTAssertNil(r.arrivalTime, "no times")
        XCTAssertNil(r.departureTime)
        XCTAssertEqual(try marks.togglePresence(e, on: day(6)), nil)
        XCTAssertNil(marks.status(of: e, on: day(6)))
        XCTAssertEqual(try records(), 0, "cancelling removes the mark")
    }

    // Проверка 3–5.
    func testStatusesFromTheMenu() throws {
        let e = try employee("Петров")
        for (d, status) in [(2, DayStatus.dayOff), (3, .sick), (4, .vacation), (5, .timeOff), (9, .businessTrip)] {
            try marks.setStatus(status, for: e, on: day(d))
            XCTAssertEqual(marks.status(of: e, on: day(d)), status)
        }
        // Another status over Я, and the big button over another status.
        try marks.setStatus(.present, for: e, on: day(6))
        try marks.setStatus(.sick, for: e, on: day(6))
        XCTAssertEqual(marks.status(of: e, on: day(6)), .sick)
        XCTAssertEqual(try marks.togglePresence(e, on: day(6)), .present)
        XCTAssertEqual(try records(), 6, "one record per day")
        try marks.setStatus(nil, for: e, on: day(6))
        XCTAssertNil(marks.status(of: e, on: day(6)))
    }

    // Проверка 6: past days, and one day never changes another.
    func testPastDaysAndOnlyThatDayChanges() throws {
        let e = try employee("Сидоров")
        try marks.setStatus(.present, for: e, on: day(5))
        try marks.setStatus(.present, for: e, on: day(6))
        try marks.setStatus(.sick, for: e, on: day(20, 10))
        try marks.setStatus(.dayOff, for: e, on: day(6))
        XCTAssertEqual(marks.status(of: e, on: day(5)), .present)
        XCTAssertEqual(marks.status(of: e, on: day(20, 10)), .sick)
        XCTAssertEqual(marks.status(of: e, on: day(6)), .dayOff)
    }

    func testFutureDayTakesStatusesButNotPresence() throws {
        let e = try employee("Козлов")
        XCTAssertThrowsError(try marks.togglePresence(e, on: day(7))) {
            XCTAssertEqual($0 as? AttendanceError, .futurePresence)
        }
        try marks.setStatus(.vacation, for: e, on: day(7))
        XCTAssertEqual(marks.status(of: e, on: day(7)), .vacation)
        XCTAssertThrowsError(try marks.markAllPresent([e], on: day(7)))
    }

    func testOutsideEmploymentIsRefused() throws {
        let e = try employee("Новиков", hired: day(10))
        XCTAssertThrowsError(try marks.setStatus(.present, for: e, on: day(9))) {
            XCTAssertEqual($0 as? AttendanceError, .outsideEmployment)
        }
    }

    func testMarkAllPresentMarksOnlyTheUnmarked() throws {
        let a = try employee("Алексеев"), b = try employee("Борисов"), c = try employee("Васильев")
        try marks.setStatus(.sick, for: b, on: day(6))
        XCTAssertEqual(try marks.markAllPresent([a, b, c], on: day(6)), 2)
        XCTAssertEqual(marks.status(of: a, on: day(6)), .present)
        XCTAssertEqual(marks.status(of: b, on: day(6)), .sick, "a sick day stays")
        XCTAssertEqual(marks.status(of: c, on: day(6)), .present)
        XCTAssertEqual(try marks.markAllPresent([a, b, c], on: day(6)), 0)
    }

    /// An old record with arrival and departure keeps them while it stays Я; the timesheet shows only «Я».
    func testOldTimedRecordsShowAsPresence() throws {
        let e = try employee("Старый")
        let r = AttendanceRecord(employeeId: e.id, day: day(3), arrivalTime: day(3).addingTimeInterval(8 * 3600),
                                 departureTime: day(3).addingTimeInterval(17 * 3600), breakMinutes: 60,
                                 workedMinutes: 480, status: .present)
        context.insert(r)
        r.employee = e
        try context.save()
        try marks.setStatus(.present, for: e, on: day(3))
        XCTAssertNotNil(r.arrivalTime, "history is kept")
        let doc = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .liveDocument(month: 11, year: 2026)
        XCTAssertEqual(doc.rows[0].cells[2].text, "Я")
        XCTAssertEqual(doc.rows[0].totals.presentDays, 1)
        XCTAssertEqual(TimesheetTotals.columnTitles, ["Я", "В", "Б", "О", "ОТ", "ПР", "К"])
        try marks.setStatus(.dayOff, for: e, on: day(3))
        XCTAssertNil(r.arrivalTime, "another status keeps no times")
    }

    // Проверка 7–8: months of 28, 29, 30 and 31 days and the totals.
    func testMonthTimesheetOfEveryLength() throws {
        let e = try employee("Иванов", hired: day(1, 1, 2026))
        clock = day(1, 3, 2029)
        for (month, year, days) in [(2, 2026, 28), (2, 2028, 29), (11, 2026, 30), (12, 2026, 31)] {
            try marks.setStatus(.present, for: e, on: day(1, month, year))
            try marks.setStatus(.dayOff, for: e, on: day(2, month, year))
            try marks.setStatus(.sick, for: e, on: day(days, month, year))
            let doc = try TimesheetService(context: context, now: { [unowned self] in self.clock })
                .liveDocument(month: month, year: year)
            XCTAssertEqual(doc.daysInMonth, days)
            XCTAssertEqual(doc.rows[0].cells.count, days)
            XCTAssertEqual(doc.rows[0].cells[days - 1].text, "Б")
            XCTAssertEqual(doc.rows[0].totals.columnValues, [1, 1, 1, 0, 0, 0, 0], "\(month).\(year)")
            XCTAssertEqual(doc.farmTotals.days(.present), 1)
        }
    }

    func testArchivedEmployeeStaysInOldTimesheetsAndNewOneFromHireDate() throws {
        let old = try employee("Архипов", dismissed: day(15))
        try marks.setStatus(.present, for: old, on: day(3))
        old.isArchived = true
        let new = try employee("Новенький", hired: day(20))
        try context.save()
        let doc = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .liveDocument(month: 11, year: 2026)
        XCTAssertEqual(doc.rows.map { $0.employee.fullName }, ["Архипов Иван", "Новенький Иван"])
        XCTAssertEqual(doc.rows[0].cells[2].text, "Я")
        XCTAssertFalse(doc.rows[0].cells[20].employed, "after dismissal")
        XCTAssertFalse(doc.rows[1].cells[18].employed, "before hire")
        XCTAssertTrue(doc.rows[1].cells[19].employed)
        let december = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .liveDocument(month: 12, year: 2026)
        XCTAssertEqual(december.rows.map { $0.employee.fullName }, ["Новенький Иван"])
    }

    // Проверка 9–10: the export has the codes and the day counts, no hours.
    func testExportHasCodesAndDayCountsOnly() throws {
        let e = try employee("Иванов")
        try marks.setStatus(.present, for: e, on: day(2))
        try marks.setStatus(.vacation, for: e, on: day(3))
        let doc = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .liveDocument(month: 11, year: 2026)
        XCTAssertEqual(exportFileName(doc, ext: "pdf"), "Табель_Ноябрь_2026.pdf")
        let zip = try MiniZipReader(XLSXWriter.make(doc))
        let sheet = String(decoding: try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml")), as: UTF8.self)
        XCTAssertFalse(sheet.contains("Часы"))
        XCTAssertFalse(sheet.contains("Версия"))
        XCTAssertTrue(sheet.contains(">О<"))
        XCTAssertFalse(TimesheetPDFRenderer.render(doc).isEmpty)
    }

    // Проверка 11.
    func testMarksSurviveRestart() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("DayMarkTests-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("tabel.store")
        let id: UUID
        do {
            let ctx = ModelContext(try makeModelContainer(url: url))
            let e = Employee(lastName: "Иванов", firstName: "Иван", hireDate: day(1, 1))
            ctx.insert(e)
            try ctx.save()
            id = e.id
            let service = AttendanceService(context: ctx, now: { [unowned self] in self.clock }, calendar: calendar)
            try service.setStatus(.present, for: e, on: day(5))
            try service.setStatus(.sick, for: e, on: day(6))
        }
        let ctx = ModelContext(try makeModelContainer(url: url))
        let e = try XCTUnwrap(try ctx.fetch(FetchDescriptor<Employee>()).first { $0.id == id })
        let service = AttendanceService(context: ctx, now: { [unowned self] in self.clock }, calendar: calendar)
        XCTAssertEqual(service.status(of: e, on: day(5)), .present)
        XCTAssertEqual(service.status(of: e, on: day(6)), .sick)
    }
}
