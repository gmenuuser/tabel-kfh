import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class TimesheetViewModelTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    /// 6 November 2026, 14:05 unless a test moves it.
    private var clock = Date()

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        clock = at(6, 14, 5)
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func at(_ d: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: d, hour: hour, minute: minute))!
    }

    private func makeModel() -> TimesheetViewModel {
        TimesheetViewModel(context: context, now: { [unowned self] in self.clock })
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

    func testGeneratingTwiceMakesTwoVersionsAndSelectsTheNewest() {
        addEmployee("Зубахин")
        let model = makeModel()

        model.generate()
        clock = at(6, 15, 30)
        model.generate()

        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.versions.map(\.version), [2, 1])
        XCTAssertEqual(model.selected?.version, 2)
        XCTAssertEqual(model.document?.version, 2)
        XCTAssertEqual(model.document?.month, 11)
        XCTAssertEqual(model.document?.rows.count, 1)
    }

    func testEmptyStateBeforeTheFirstVersion() {
        addEmployee("Зубахин")
        let model = makeModel()

        XCTAssertEqual(model.month, 11)
        XCTAssertEqual(model.year, 2026)
        XCTAssertTrue(model.versions.isEmpty)
        XCTAssertNil(model.selected)
        XCTAssertNil(model.document)
        XCTAssertEqual(model.emptyText, "Табель за Ноябрь 2026 ещё не сформирован")
    }

    func testVersionTitle() {
        addEmployee("Зубахин")
        let model = makeModel()
        model.generate()
        model.generate()

        XCTAssertEqual(model.versions.map { model.versionTitle($0) },
                       ["Версия 2 · 06.11.2026 14:05", "Версия 1 · 06.11.2026 14:05"])
    }

    func testSelectingAnOlderVersionShowsItsDocument() throws {
        addEmployee("Зубахин")
        let model = makeModel()
        model.format = .codes
        model.generate()
        model.format = .codesAndHours
        model.generate()

        let old = try XCTUnwrap(model.versions.last)
        model.select(old)

        XCTAssertEqual(model.selected?.version, 1)
        XCTAssertEqual(model.document?.version, 1)
        XCTAssertEqual(model.document?.format, .codes)
    }

    func testMonthAndEmployeeHaveTheirOwnVersions() throws {
        let a = addEmployee("Андреев")
        addEmployee("Борисов")
        let model = makeModel()
        model.generate()

        model.choose(a)
        XCTAssertTrue(model.versions.isEmpty)
        XCTAssertNil(model.document)
        model.generate()
        XCTAssertEqual(model.document?.rows.map(\.employee.id), [a.id])

        model.previous()
        XCTAssertEqual(model.month, 10)
        XCTAssertTrue(model.versions.isEmpty)
        XCTAssertEqual(model.emptyText, "Табель за Октябрь 2026 ещё не сформирован")

        model.next()
        XCTAssertEqual(model.versions.map(\.version), [1])
        XCTAssertEqual(model.document?.rows.count, 1)

        model.choose(nil)
        XCTAssertEqual(model.document?.rows.count, 2)
    }

    func testExportSurnameOnlyForOneEmployeeTimesheet() {
        let a = addEmployee("Зубахин")
        let model = makeModel()
        XCTAssertNil(model.exportSurname, "nothing selected")

        model.generate()
        XCTAssertEqual(model.document?.rows.count, 1)
        XCTAssertNil(model.exportSurname, "whole farm, even with one person")

        model.choose(a)
        model.generate()
        XCTAssertEqual(model.exportSurname, "Зубахин")
    }

    func testEmployeeChoicesAreThoseEmployedInTheMonthIncludingArchived() {
        addEmployee("Архипов", dismissal: day(15), archived: true)
        addEmployee("Будущий", hire: day(1, 12))
        addEmployee("Ушедший", dismissal: day(31, 10))
        addEmployee("Васильев", hire: day(30))
        let model = makeModel()

        XCTAssertEqual(model.employees().map(\.lastName), ["Архипов", "Васильев"])
    }

    func testAnEmployeeOutsideTheChosenMonthFallsBackToEveryone() {
        let future = addEmployee("Будущий", hire: day(1, 12))
        let model = makeModel()
        model.show(month: 12, year: 2026)
        model.choose(future)
        XCTAssertEqual(model.employee?.id, future.id)

        model.show(month: 11, year: 2026)

        XCTAssertNil(model.employee)
    }

    func testTotalsColumns() {
        XCTAssertEqual(TimesheetViewModel.totalsTitles, ["Явки", "Часы", "В", "Б", "О", "ОТ", "ПР", "К"])
        let totals = TimesheetTotals(presentDays: 2, minutes: 930, dayOff: 3, sick: 1, vacation: 4, timeOff: 5,
                                     absent: 6, businessTrip: 7)
        XCTAssertEqual(TimesheetViewModel.totalsValues(totals), ["2", "15,5", "3", "1", "4", "5", "6", "7"])
    }
}
