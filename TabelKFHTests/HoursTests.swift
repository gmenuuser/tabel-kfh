import XCTest
import SwiftData
@testable import TabelKFH

/// The timesheet of hours: 0, 4, 8, 12 per person and day; an empty day is not 0 hours.
@MainActor
final class HoursTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!
    private var hours: HoursService!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 20, month: 11, year: 2026).addingTimeInterval(9 * 3600)
        hours = HoursService(context: context, calendar: calendar, now: { [unowned self] in self.clock })
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date { calendar.date(day: d, month: m, year: y) }

    private func employee(_ lastName: String, hired: Date? = nil) throws -> Employee {
        let e = Employee(lastName: lastName, firstName: "Иван", hireDate: hired ?? day(1, 1))
        context.insert(e)
        try context.save()
        return e
    }

    private func marks() throws -> Int { try context.fetchCount(FetchDescriptor<WorkHoursMark>()) }

    // 1–2.
    func testEightIsSavedAndFourReplacesIt() throws {
        let e = try employee("Иванов")
        try hours.set(8, for: e, on: day(5))
        XCTAssertEqual(hours.hours(of: e, on: day(5)), 8)
        try hours.set(4, for: e, on: day(5))
        XCTAssertEqual(hours.hours(of: e, on: day(5)), 4)
        XCTAssertEqual(try marks(), 1, "one mark per person and day")
        XCTAssertThrowsError(try hours.set(6, for: e, on: day(5))) { XCTAssertEqual($0 as? HoursError, .invalidHours) }
    }

    func testTheButtonsReplaceAndTheSelectedOneRemoves() throws {
        let e = try employee("Петров")
        XCTAssertEqual(try hours.tap(8, for: e, on: day(5)), 8)
        XCTAssertEqual(try hours.tap(12, for: e, on: day(5)), 12)
        XCTAssertEqual(try hours.tap(12, for: e, on: day(5)), nil)
        XCTAssertNil(hours.hours(of: e, on: day(5)))
        XCTAssertEqual(try marks(), 0)
    }

    // 3.
    func testHoursSurviveRestart() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HoursTests-\(UUID())")
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
            try HoursService(context: ctx, calendar: calendar, now: { [unowned self] in self.clock }).set(12, for: e, on: day(3))
        }
        let ctx = ModelContext(try makeModelContainer(url: url))
        let e = try XCTUnwrap(try ctx.fetch(FetchDescriptor<Employee>()).first { $0.id == id })
        XCTAssertEqual(HoursService(context: ctx, calendar: calendar).hours(of: e, on: day(3)), 12)
    }

    // 4–7.
    func testMonthTotalsAndEmptyIsNotZero() throws {
        let e = try employee("Сидоров")
        try hours.set(8, for: e, on: day(2))
        try hours.set(8, for: e, on: day(3))
        try hours.set(4, for: e, on: day(4))
        try hours.set(12, for: e, on: day(5))
        try hours.set(0, for: e, on: day(6))
        let doc = try hours.month(11, 2026)
        let t = try XCTUnwrap(doc.rows.first?.hoursTotals)
        XCTAssertEqual(t.hours, 32, "8 + 8 + 4 + 12")
        XCTAssertEqual(t.workedDays, 4, "days with non-zero hours")
        XCTAssertEqual(t.zero, 1, "0 hours counted on its own")
        XCTAssertEqual(t.four, 1)
        XCTAssertEqual(t.eight, 2)
        XCTAssertEqual(t.twelve, 1)
        XCTAssertEqual(doc.rows[0].cells[5].text, "0", "a marked 0")
        XCTAssertEqual(doc.rows[0].cells[6].text, "", "no mark is empty, not 0")
        XCTAssertNil(doc.rows[0].cells[6].hours)
        XCTAssertEqual(doc.farmHoursTotals?.hours, 32)
        XCTAssertEqual(doc.totalsTitles, ["Часов", "Раб. дней", "0 ч", "4 ч", "8 ч", "12 ч"])
        XCTAssertEqual(doc.totalsValues(doc.rows[0]), [32, 4, 1, 1, 2, 1])
    }

    // 8.
    func testEditingAPastDayRecountsTheMonth() throws {
        let e = try employee("Козлов")
        try hours.set(8, for: e, on: day(2))
        try hours.set(8, for: e, on: day(3))
        XCTAssertEqual(try hours.month(11, 2026).rows[0].hoursTotals?.hours, 16)
        try hours.set(12, for: e, on: day(2))
        XCTAssertEqual(try hours.month(11, 2026).rows[0].hoursTotals?.hours, 20)
        try hours.set(nil, for: e, on: day(3))
        XCTAssertEqual(try hours.month(11, 2026).rows[0].hoursTotals?.workedDays, 1)
    }

    // 9.
    func testArchivedEmployeeKeepsTheTimesheetAndNewOneStartsAtHire() throws {
        let old = try employee("Архипов")
        try hours.set(8, for: old, on: day(3))
        try EmployeeService(context: context).archive(old)
        let new = try employee("Новиков", hired: day(10))
        XCTAssertThrowsError(try hours.set(8, for: new, on: day(9))) { XCTAssertEqual($0 as? HoursError, .outsideEmployment) }
        let doc = try hours.month(11, 2026)
        XCTAssertEqual(doc.rows.map { $0.employee.fullName }, ["Архипов Иван", "Новиков Иван"])
        XCTAssertEqual(doc.rows[0].hoursTotals?.hours, 8, "the archived one's hours stay")
        XCTAssertFalse(doc.rows[1].cells[8].employed, "before hire")
        XCTAssertTrue(doc.rows[1].cells[9].employed)
    }

    // 10. Old status records are kept and never become hours.
    func testOldStatusRecordsStayAndAreNotHours() throws {
        let e = try employee("Старый")
        let r = AttendanceRecord(employeeId: e.id, day: day(4), arrivalTime: day(4).addingTimeInterval(8 * 3600),
                                 departureTime: day(4).addingTimeInterval(17 * 3600), breakMinutes: 60,
                                 workedMinutes: 480, status: .present)
        context.insert(r)
        r.employee = e
        context.insert(AttendanceRecord(employeeId: e.id, day: day(5), status: .sick))
        try context.save()
        let doc = try hours.month(11, 2026)
        XCTAssertEqual(doc.rows[0].cells[3].text, "", "an old Я is not 8 hours")
        XCTAssertEqual(doc.rows[0].cells[4].text, "", "an old Б is not 0 hours")
        XCTAssertEqual(doc.rows[0].hoursTotals?.hours, 0)
        try hours.set(8, for: e, on: day(4))
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<AttendanceRecord>()), 2, "old records stay")
        XCTAssertEqual(r.workedMinutes, 480)
    }

    /// The phone's version 4 store opens with the timesheet of hours and keeps the old records.
    func testVersion4StoreOpensWithHours() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HoursMigration-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("tabel.store")
        let id = UUID()
        do {
            let schema = Schema(versionedSchema: SchemaV4.self)
            let container = try ModelContainer(for: schema, migrationPlan: nil,
                                               configurations: [ModelConfiguration(schema: schema, url: url,
                                                                                   cloudKitDatabase: .none)])
            let ctx = ModelContext(container)
            let e = Employee(id: id, lastName: "Зубахин", firstName: "Иван", hireDate: day(1, 1))
            ctx.insert(e)
            let r = AttendanceRecord(employeeId: id, day: day(3), status: .vacation)
            ctx.insert(r)
            r.employee = e
            try ctx.save()
        }
        let ctx = ModelContext(try makeModelContainer(url: url))
        XCTAssertEqual(try ctx.fetch(FetchDescriptor<AttendanceRecord>()).first?.status, .vacation)
        let e = try XCTUnwrap(try ctx.fetch(FetchDescriptor<Employee>()).first)
        try HoursService(context: ctx, calendar: calendar).set(8, for: e, on: day(4))
        XCTAssertEqual(HoursService(context: ctx, calendar: calendar).hours(of: e, on: day(4)), 8)
    }

    func testExportHasHoursAndNoCodes() throws {
        let e = try employee("Иванов")
        try hours.set(8, for: e, on: day(2))
        try hours.set(12, for: e, on: day(3))
        try hours.set(0, for: e, on: day(4))
        let doc = try hours.month(11, 2026)
        let zip = try MiniZipReader(XLSXWriter.make(doc))
        let sheet = String(decoding: try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml")), as: UTF8.self)
        XCTAssertTrue(sheet.contains(">Часов<"))
        XCTAssertTrue(sheet.contains("<v>20</v>"), "the month's hours as a number")
        for code in [">Я<", ">В<", ">Б<", ">ОТ<", ">ПР<"] { XCTAssertFalse(sheet.contains(code), code) }
        XCTAssertFalse(TimesheetPDFRenderer.render(doc).isEmpty)
    }

    func testMarkUnmarkedAndBackup() throws {
        let a = try employee("Алексеев"), b = try employee("Борисов")
        try hours.set(4, for: a, on: day(6))
        XCTAssertEqual(try hours.markUnmarked([a, b], hours: 8, on: day(6)), 1)
        XCTAssertEqual(hours.hours(of: a, on: day(6)), 4)
        XCTAssertEqual(hours.hours(of: b, on: day(6)), 8)
        let backup = BackupService(context: context, now: { [unowned self] in self.clock }, adoptTimeZone: { _ in })
        let data = try backup.makeBackup()
        try hours.set(nil, for: b, on: day(6))
        try backup.restore(data)
        XCTAssertEqual(hours.hours(of: b, on: day(6)), 8)
        XCTAssertEqual(try marks(), 2)
    }
}
