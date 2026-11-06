import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class TimesheetServiceTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    /// 1 December 2026, 10:00: every November day is in the past.
    private var clock = Date()

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        clock = at(1, 10, 0, month: 12)
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func at(_ d: Int, _ hour: Int, _ minute: Int = 0, month: Int = 11) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: d, hour: hour, minute: minute))!
    }

    private func service() -> TimesheetService {
        TimesheetService(context: context, now: { [unowned self] in self.clock })
    }

    private func attendance() -> AttendanceService {
        AttendanceService(context: context, now: { [unowned self] in self.clock })
    }

    @discardableResult
    private func addEmployee(_ lastName: String, hire: Date? = nil, dismissal: Date? = nil,
                             archived: Bool = false) -> Employee {
        let e = Employee(lastName: lastName, firstName: "Иван", patronymic: "Петрович", position: "Тракторист",
                         hireDate: hire ?? day(1, 1), dismissalDate: dismissal, isArchived: archived)
        context.insert(e)
        try? context.save()
        return e
    }

    private func work(_ e: Employee, _ d: Int, from: Int, to: Int?, breakMinutes: Int = 60,
                      manual: Int? = nil) throws {
        try attendance().save(employee: e, day: day(d), status: .present, arrival: at(d, from),
                              departure: to.map { at(d, $0) }, breakMinutes: breakMinutes,
                              manualMinutes: manual, comment: "")
    }

    private func status(_ e: Employee, _ d: Int, _ s: DayStatus) throws {
        try attendance().save(employee: e, day: day(d), status: s, arrival: nil, departure: nil,
                              breakMinutes: 0, manualMinutes: nil, comment: "")
    }

    func testGeneratesFromRecordsWithDefaultFarmWhenSettingsWereNeverSaved() throws {
        let e = addEmployee("Зубахин")
        try work(e, 2, from: 8, to: 17)
        try work(e, 3, from: 8, to: 16, breakMinutes: 30)
        try status(e, 4, .sick)
        try work(e, 5, from: 8, to: nil)
        try work(e, 6, from: 8, to: nil, manual: 240)

        let snapshot = try service().generate(month: 11, year: 2026, employee: nil, format: .codesAndHours)
        let doc = try service().document(snapshot)

        XCTAssertEqual(snapshot.version, 1)
        XCTAssertEqual(snapshot.month, 11)
        XCTAssertEqual(snapshot.year, 2026)
        XCTAssertNil(snapshot.employeeId)
        XCTAssertEqual(snapshot.createdAt, clock)
        XCTAssertEqual(doc.version, 1)
        XCTAssertEqual(doc.createdAt, clock)
        XCTAssertEqual(doc.farmName, "КФХ Зубахин")
        XCTAssertEqual(doc.managerName, "")
        XCTAssertEqual(doc.responsiblePerson, "")
        XCTAssertEqual(doc.rows.count, 1)
        let cells = doc.rows[0].cells
        XCTAssertEqual(cells.count, 30)
        XCTAssertEqual(cells[1].text, "Я\n8")
        XCTAssertEqual(cells[2].text, "Я\n7,5")
        XCTAssertEqual(cells[3].text, "Б")
        XCTAssertEqual(cells[4].text, "Я?")
        XCTAssertEqual(cells[5].text, "Я\n4")
        XCTAssertEqual(cells[0].text, "")
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 4, minutes: 480 + 450 + 240, sick: 1))
        XCTAssertEqual(doc.unfinished, ["Зубахин И. П. — 5 ноября"])
        XCTAssertEqual(doc.farmTotals, doc.rows[0].totals)
    }

    func testUsesSavedFarmSettings() throws {
        context.insert(FarmSettings(farmName: "КФХ Луговое", managerName: "Зубахин И. П.",
                                    responsiblePerson: "Иванова А. А.", address: "с. Луговое", phone: "123"))
        try context.save()
        addEmployee("Зубахин")

        let doc = try service().document(try service().generate(month: 11, year: 2026, employee: nil, format: .codes))

        XCTAssertEqual(doc.farmName, "КФХ Луговое")
        XCTAssertEqual(doc.managerName, "Зубахин И. П.")
        XCTAssertEqual(doc.responsiblePerson, "Иванова А. А.")
        XCTAssertEqual(doc.address, "с. Луговое")
        XCTAssertEqual(doc.phone, "123")
    }

    func testRegeneratingMakesANewVersionAndOldVersionsStayFrozen() throws {
        let e = addEmployee("Зубахин")
        try work(e, 2, from: 8, to: 17)
        let v1 = try service().generate(month: 11, year: 2026, employee: nil, format: .codesAndHours)
        let v1Data = v1.documentData

        // Later edits: rename, change position, fix a record, add a mark, change the farm.
        e.lastName = "Петров"
        e.position = "Механик"
        try context.save()
        try work(e, 2, from: 9, to: 17)
        try status(e, 3, .sick)
        context.insert(FarmSettings(farmName: "КФХ Новое"))
        try context.save()
        clock = at(2, 9, 0, month: 12)

        let v2 = try service().generate(month: 11, year: 2026, employee: nil, format: .codesAndHours)

        XCTAssertEqual(v2.version, 2)
        XCTAssertEqual(service().versions(month: 11, year: 2026, employee: nil).map(\.version), [2, 1])
        XCTAssertEqual(v1.documentData, v1Data)

        let old = try service().document(v1)
        XCTAssertEqual(old.version, 1)
        XCTAssertEqual(old.rows[0].employee.fullName, "Зубахин Иван Петрович")
        XCTAssertEqual(old.rows[0].employee.position, "Тракторист")
        XCTAssertEqual(old.rows[0].cells[1].text, "Я\n8")
        XCTAssertEqual(old.rows[0].cells[2].text, "")
        XCTAssertEqual(old.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 480))
        XCTAssertEqual(old.farmName, "КФХ Зубахин")
        let oldEmployees = try JSONDecoder().decode([EmployeeSnapshot].self, from: v1.employeeData)
        XCTAssertEqual(oldEmployees.map(\.fullName), ["Зубахин Иван Петрович"])

        let new = try service().document(v2)
        XCTAssertEqual(new.version, 2)
        XCTAssertEqual(new.rows[0].employee.fullName, "Петров Иван Петрович")
        XCTAssertEqual(new.rows[0].cells[1].text, "Я\n7")
        XCTAssertEqual(new.rows[0].cells[2].text, "Б")
        XCTAssertEqual(new.farmName, "КФХ Новое")
        XCTAssertEqual(new.createdAt, at(2, 9, 0, month: 12))
    }

    func testOneEmployeeTimesheetHasOneRowAndItsOwnVersions() throws {
        let a = addEmployee("Андреев")
        let b = addEmployee("Борисов")
        try work(a, 2, from: 8, to: 17)
        try work(b, 2, from: 8, to: 12)
        try service().generate(month: 11, year: 2026, employee: nil, format: .codes)

        let single = try service().generate(month: 11, year: 2026, employee: b, format: .codes)
        let doc = try service().document(single)

        XCTAssertEqual(single.version, 1)
        XCTAssertEqual(single.employeeId, b.id)
        XCTAssertEqual(doc.rows.count, 1)
        XCTAssertEqual(doc.rows[0].number, 1)
        XCTAssertEqual(doc.rows[0].employee.id, b.id)
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 180))
        XCTAssertEqual(doc.farmTotals, doc.rows[0].totals)

        XCTAssertEqual(try service().generate(month: 11, year: 2026, employee: b, format: .codes).version, 2)
        XCTAssertEqual(service().versions(month: 11, year: 2026, employee: b).map(\.version), [2, 1])
        XCTAssertEqual(service().versions(month: 11, year: 2026, employee: a).count, 0)
        XCTAssertEqual(service().versions(month: 11, year: 2026, employee: nil).map(\.version), [1])
        XCTAssertEqual(service().versions(month: 10, year: 2026, employee: nil).count, 0)
    }

    func testRowsAreEmployeesOfTheMonthIncludingArchived() throws {
        let archived = addEmployee("Архипов", dismissal: day(15), archived: true)
        try work(archived, 3, from: 8, to: 17)
        addEmployee("Будущий", hire: day(1, 12))
        addEmployee("Ушедший", dismissal: day(31, 10))
        let hired = addEmployee("Васильев", hire: day(10))

        let doc = try service().document(try service().generate(month: 11, year: 2026, employee: nil, format: .codes))

        XCTAssertEqual(doc.rows.map(\.employee.fullName), ["Архипов Иван Петрович", "Васильев Иван Петрович"])
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 480))
        XCTAssertEqual(doc.rows[0].cells.filter(\.employed).count, 15)
        XCTAssertEqual(doc.rows[1].employee.id, hired.id)
        XCTAssertEqual(doc.rows[1].cells.filter(\.employed).count, 21)
    }

    func testInvalidMonthThrows() {
        XCTAssertThrowsError(try service().generate(month: 13, year: 2026, employee: nil, format: .codes)) { error in
            XCTAssertEqual(error as? TimesheetError, .invalidMonth)
        }
    }
}
