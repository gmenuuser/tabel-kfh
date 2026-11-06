import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class CalendarViewModelTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func at(_ d: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: d, hour: hour, minute: minute))!
    }

    /// Now = 6 November 2026, 12:00 unless given.
    private func makeModel(now: Date? = nil) -> CalendarViewModel {
        let moment = now ?? at(6, 12)
        return CalendarViewModel(context: context, now: { moment })
    }

    private func attendance() -> AttendanceService {
        let moment = at(6, 12)
        return AttendanceService(context: context, now: { moment })
    }

    @discardableResult
    private func addEmployee(_ lastName: String, hire: Date? = nil, dismissal: Date? = nil,
                             archived: Bool = false) -> Employee {
        let e = Employee(lastName: lastName, firstName: "Иван", hireDate: hire ?? day(1, 1),
                         dismissalDate: dismissal, isArchived: archived)
        context.insert(e)
        try? context.save()
        return e
    }

    private func saveStatus(_ e: Employee, _ d: Date, _ status: DayStatus) throws {
        try attendance().save(employee: e, day: d, status: status, arrival: nil, departure: nil,
                              breakMinutes: 0, manualMinutes: nil, comment: "")
    }

    private func saveTimes(_ e: Employee, _ d: Int, from: Int, to: Int?) throws {
        try attendance().save(employee: e, day: day(d), status: .present, arrival: at(d, from),
                              departure: to.map { at(d, $0) }, breakMinutes: 60, manualMinutes: nil, comment: "")
    }

    func testStartsOnTheMonthOfNow() {
        let model = makeModel()
        XCTAssertEqual(model.month, 11)
        XCTAssertEqual(model.year, 2026)
        XCTAssertEqual(model.title, "Ноябрь 2026")
    }

    // 1 November 2026 is a Sunday.
    func testNovember2026HasSixLeadingBlanksAndThirtyDays() {
        let cells = makeModel().cells()

        XCTAssertEqual(cells.prefix(6).filter(\.isBlank).count, 6)
        XCTAssertEqual(cells.filter(\.isBlank).count, 6)
        XCTAssertFalse(cells[6].isBlank)
        let days = cells.filter { !$0.isBlank }
        XCTAssertEqual(days.count, 30)
        XCTAssertEqual(days.first?.day, day(1))
        XCTAssertEqual(days.first?.number, 1)
        XCTAssertEqual(days.last?.day, day(30))
        XCTAssertEqual(Set(cells.map(\.id)).count, cells.count)
    }

    // 1 June 2026 is a Monday.
    func testMondayFirstMonthHasNoLeadingBlanks() {
        let model = makeModel()
        model.month = 6
        let cells = model.cells()
        XCTAssertEqual(cells.filter(\.isBlank).count, 0)
        XCTAssertEqual(cells.count, 30)
    }

    func testTodayAndFutureFlags() {
        let days = makeModel().cells().filter { !$0.isBlank }
        XCTAssertTrue(days[5].isToday)
        XCTAssertFalse(days[5].isFuture)
        XCTAssertFalse(days[4].isFuture)
        XCTAssertFalse(days[4].isToday)
        XCTAssertTrue(days[6].isFuture)
        XCTAssertFalse(days[6].isToday)
    }

    func testPreviousFromJanuaryGoesToDecemberOfThePreviousYear() {
        let model = makeModel()
        model.month = 1
        model.year = 2027
        model.previous()
        XCTAssertEqual(model.month, 12)
        XCTAssertEqual(model.year, 2026)
    }

    func testNextFromDecemberGoesToJanuaryOfTheNextYear() {
        let model = makeModel()
        model.month = 12
        model.next()
        XCTAssertEqual(model.month, 1)
        XCTAssertEqual(model.year, 2027)
    }

    func testAllEmployeesCellsCountStatuses() throws {
        let a = addEmployee("Иванов")
        let b = addEmployee("Петров")
        let c = addEmployee("Сидоров")
        try saveTimes(a, 2, from: 8, to: 17)
        try saveTimes(b, 2, from: 9, to: 18)
        try saveStatus(c, day(2), .sick)
        try saveStatus(a, day(1, 12), .dayOff)

        let days = makeModel().cells().filter { !$0.isBlank }

        XCTAssertEqual(days[1].statusCounts, [.present: 2, .sick: 1])
        XCTAssertEqual(days[0].statusCounts, [:])
        XCTAssertNil(days[1].status)
        XCTAssertTrue(days.allSatisfy { $0.statusCounts[.dayOff] == nil }, "December marks stay in December")
    }

    func testFilterShowsThatEmployeesStatusAndMinutesForEachDay() throws {
        let a = addEmployee("Иванов")
        let b = addEmployee("Петров")
        try saveTimes(a, 2, from: 8, to: 17)
        try saveStatus(a, day(3), .sick)
        try saveTimes(a, 4, from: 8, to: nil)
        try saveStatus(b, day(5), .vacation)

        let model = makeModel()
        model.employeeFilter = a
        let days = model.cells().filter { !$0.isBlank }

        XCTAssertEqual(days.count, 30)
        XCTAssertNil(days[0].status)
        XCTAssertEqual(days[1].status, .present)
        XCTAssertEqual(days[1].minutes, 480)
        XCTAssertEqual(days[2].status, .sick)
        XCTAssertNil(days[2].minutes)
        XCTAssertEqual(days[3].status, .present)
        XCTAssertNil(days[3].minutes, "an unfinished day has no total")
        XCTAssertNil(days[4].status, "another employee's mark is not shown")
        XCTAssertTrue(days.allSatisfy { $0.statusCounts.isEmpty })
    }

    func testFilterMarksDaysOutsideEmployment() {
        let a = addEmployee("Иванов", hire: day(10), dismissal: day(20))
        let model = makeModel()
        model.employeeFilter = a
        let days = model.cells().filter { !$0.isBlank }

        XCTAssertFalse(days[8].isEmployed)
        XCTAssertTrue(days[9].isEmployed)
        XCTAssertTrue(days[19].isEmployed)
        XCTAssertFalse(days[20].isEmployed)
    }

    func testDayEntriesListEmployeesEmployedThatDayIncludingArchived() throws {
        let worker = addEmployee("Иванов")
        addEmployee("Архипов", hire: day(1, 1), dismissal: day(10), archived: true)
        addEmployee("Новиков", hire: day(20))
        addEmployee("Уволенов", hire: day(1, 1), dismissal: day(3))
        try saveStatus(worker, day(5), .sick)

        let entries = makeModel().entries(on: day(5))

        XCTAssertEqual(entries.map(\.employee.lastName), ["Архипов", "Иванов"])
        XCTAssertNil(entries[0].record)
        XCTAssertEqual(entries[1].record?.status, .sick)
    }

    func testEmployeeChoicesAreThoseEmployedDuringTheMonth() {
        addEmployee("Иванов")
        addEmployee("Архипов", hire: day(1, 1), dismissal: day(10), archived: true)
        addEmployee("Старов", hire: day(1, 1), dismissal: day(31, 10))
        addEmployee("Будущев", hire: day(1, 12))

        XCTAssertEqual(makeModel().employees().map(\.lastName), ["Архипов", "Иванов"])
    }

    func testFutureDaysAllowOnlyPlannedAbsences() {
        XCTAssertEqual(DayStatus.statuses(on: day(7), today: day(6)),
                       [.dayOff, .sick, .vacation, .timeOff, .businessTrip])
        XCTAssertEqual(DayStatus.statuses(on: day(6), today: day(6)), DayStatus.allCases)
        XCTAssertEqual(DayStatus.statuses(on: day(5), today: day(6)), DayStatus.allCases)
    }
}
