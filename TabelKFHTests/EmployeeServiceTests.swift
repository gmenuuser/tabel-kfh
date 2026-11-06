import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class EmployeeServiceTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    private var service: EmployeeService!

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        service = EmployeeService(context: context)
    }

    private func day(_ d: Int, _ m: Int, _ y: Int) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func draft(_ lastName: String = "Зубахин", firstName: String = "Иван",
                       hire: Date? = nil, dismissal: Date? = nil) -> EmployeeDraft {
        var d = EmployeeDraft()
        d.lastName = lastName
        d.firstName = firstName
        d.hireDate = hire ?? day(1, 3, 2025)
        d.dismissalDate = dismissal
        return d
    }

    private func employeeCount() throws -> Int {
        try context.fetchCount(FetchDescriptor<Employee>())
    }

    private func recordCount() throws -> Int {
        try context.fetchCount(FetchDescriptor<AttendanceRecord>())
    }

    // Spec 1.
    func testAddStoresTrimmedFieldsInNameOrder() throws {
        var d = draft("  Зубахин ", firstName: " Иван")
        d.patronymic = "Петрович  "
        d.position = " Тракторист "
        d.hireDate = day(1, 3, 2025).addingTimeInterval(13 * 3600)
        d.workSchedule = .twoTwo
        d.standardWorkHours = 11
        d.defaultBreakMinutes = 45

        let e = try service.add(d)

        XCTAssertEqual(try employeeCount(), 1)
        XCTAssertEqual(e.lastName, "Зубахин")
        XCTAssertEqual(e.firstName, "Иван")
        XCTAssertEqual(e.patronymic, "Петрович")
        XCTAssertEqual(e.position, "Тракторист")
        XCTAssertEqual(e.fullName, "Зубахин Иван Петрович")
        XCTAssertEqual(e.hireDate, day(1, 3, 2025))
        XCTAssertNil(e.dismissalDate)
        XCTAssertFalse(e.isArchived)
        XCTAssertEqual(e.workSchedule, .twoTwo)
        XCTAssertEqual(e.standardWorkHours, 11)
        XCTAssertEqual(e.defaultBreakMinutes, 45)
    }

    func testEmptyLastNameIsRejected() throws {
        let d = draft("   ")

        XCTAssertEqual(d.validationErrors, ["Укажи фамилию"])
        XCTAssertThrowsError(try service.add(d)) { error in
            XCTAssertEqual(error as? EmployeeError, .invalid(["Укажи фамилию"]))
            XCTAssertEqual(error.localizedDescription, "Укажи фамилию")
        }
        XCTAssertEqual(try employeeCount(), 0)
    }

    func testEmptyFirstNameIsRejected() {
        XCTAssertEqual(draft(firstName: "").validationErrors, ["Укажи имя"])
    }

    func testDismissalBeforeHireIsRejected() throws {
        let d = draft(hire: day(10, 3, 2025), dismissal: day(9, 3, 2025))

        XCTAssertEqual(d.validationErrors, ["Дата увольнения раньше даты приёма"])
        XCTAssertThrowsError(try service.add(d))
        XCTAssertEqual(try employeeCount(), 0)
        XCTAssertTrue(draft(hire: day(10, 3, 2025), dismissal: day(10, 3, 2025)).validationErrors.isEmpty)
    }

    func testNormAndBreakLimits() {
        var d = draft()
        d.standardWorkHours = 0.5
        d.defaultBreakMinutes = 241
        XCTAssertEqual(d.validationErrors, ["Норма — от 1 до 24 часов в день", "Перерыв — от 0 до 240 минут"])
        d.standardWorkHours = 24
        d.defaultBreakMinutes = 0
        XCTAssertTrue(d.validationErrors.isEmpty)
        d.standardWorkHours = 24.5
        d.defaultBreakMinutes = -5
        XCTAssertEqual(d.validationErrors.count, 2)
    }

    // Spec 2.
    func testUpdateChangesPositionAndKeepsRecordBreaks() throws {
        var d = draft()
        d.position = "Тракторист"
        d.defaultBreakMinutes = 60
        let e = try service.add(d)
        let record = try AttendanceService(context: context).save(
            employee: e, day: day(5, 11, 2026), status: .present,
            arrival: day(5, 11, 2026).addingTimeInterval(8 * 3600),
            departure: day(5, 11, 2026).addingTimeInterval(17 * 3600),
            breakMinutes: 60, manualMinutes: nil, comment: "")

        var edit = EmployeeDraft(e)
        XCTAssertEqual(edit.position, "Тракторист")
        XCTAssertEqual(edit.defaultBreakMinutes, 60)
        edit.position = " Механик "
        edit.defaultBreakMinutes = 30
        try service.update(e, from: edit)

        XCTAssertEqual(e.position, "Механик")
        XCTAssertEqual(e.defaultBreakMinutes, 30)
        XCTAssertEqual(record.breakMinutes, 60)
        XCTAssertEqual(record.workedMinutes, 480)
        XCTAssertEqual(try recordCount(), 1)
        XCTAssertEqual(try employeeCount(), 1)
    }

    func testInvalidUpdateChangesNothing() throws {
        let e = try service.add(draft())
        var edit = EmployeeDraft(e)
        edit.lastName = ""
        edit.position = "Механик"

        XCTAssertThrowsError(try service.update(e, from: edit))
        XCTAssertEqual(e.lastName, "Зубахин")
        XCTAssertEqual(e.position, "")
    }

    // Spec 3.
    func testArchiveKeepsRecordsAndRemovesFromActive() throws {
        let e = try service.add(draft())
        try AttendanceService(context: context).save(
            employee: e, day: day(5, 11, 2026), status: .sick, arrival: nil, departure: nil,
            breakMinutes: 0, manualMinutes: nil, comment: "")
        let today = day(6, 11, 2026)
        XCTAssertEqual(service.active(on: today).map(\.id), [e.id])

        try service.archive(e)

        XCTAssertTrue(e.isArchived)
        XCTAssertEqual(try recordCount(), 1)
        XCTAssertEqual(e.records.count, 1)
        XCTAssertTrue(service.active(on: today).isEmpty)
        XCTAssertEqual(service.archived().map(\.id), [e.id])
    }

    func testRestoreReturnsToActive() throws {
        let e = try service.add(draft())
        try service.archive(e)

        try service.restore(e)

        XCTAssertFalse(e.isArchived)
        XCTAssertEqual(service.active(on: day(6, 11, 2026)).map(\.id), [e.id])
        XCTAssertTrue(service.archived().isEmpty)
    }

    func testActiveExcludesNotYetHiredAndDismissedAndSortsByName() throws {
        let today = day(6, 11, 2026)
        try service.add(draft("Яковлев"))
        try service.add(draft("борисов"))
        try service.add(draft("Алексеев", hire: today))
        try service.add(draft("Ёлкин", dismissal: today))
        try service.add(draft("Будущий", hire: day(7, 11, 2026)))
        try service.add(draft("Уволенный", dismissal: day(5, 11, 2026)))

        let names = service.active(on: today.addingTimeInterval(15 * 3600)).map(\.lastName)

        XCTAssertEqual(names, ["Алексеев", "борисов", "Ёлкин", "Яковлев"])
    }

    func testDraftFromEmployeeCopiesAllFields() throws {
        var d = draft(dismissal: day(1, 12, 2026))
        d.patronymic = "Петрович"
        d.position = "Агроном"
        d.workSchedule = .sixOne
        d.standardWorkHours = 7.5
        d.defaultBreakMinutes = 30
        d.photo = Data([1, 2, 3])
        let e = try service.add(d)

        XCTAssertEqual(EmployeeDraft(e), d)
    }
}
