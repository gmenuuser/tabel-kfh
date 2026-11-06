import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class HomeViewModelTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    private var clock = Date()

    private func at(_ day: Int, _ hour: Int, _ minute: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private func setUpStore(now: Date) throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        clock = now
    }

    private func makeModel() -> HomeViewModel {
        let model = HomeViewModel(context: context, now: { [unowned self] in self.clock })
        model.reload()
        return model
    }

    private func service() -> AttendanceService {
        AttendanceService(context: context, now: { [unowned self] in self.clock })
    }

    @discardableResult
    private func addEmployee(_ lastName: String, hire: Date? = nil, archived: Bool = false) -> Employee {
        let employee = Employee(lastName: lastName, firstName: "Иван", position: "Рабочий",
                                hireDate: hire ?? calendar.date(day: 1, month: 1, year: 2026),
                                isArchived: archived, defaultBreakMinutes: 30)
        context.insert(employee)
        try? context.save()
        return employee
    }

    func testCountsArrivedSickAndUnmarked() throws {
        try setUpStore(now: at(6, 10, 0))
        let arrived = addEmployee("Алексеев")
        let sick = addEmployee("Борисов")
        addEmployee("Васильев")
        addEmployee("Архивный", archived: true)
        addEmployee("Будущий", hire: calendar.date(day: 10, month: 11, year: 2026))
        clock = at(6, 8, 2)
        try service().markArrival(arrived)
        try service().save(employee: sick, day: at(6, 0, 0), status: .sick, arrival: nil, departure: nil,
                           breakMinutes: 0, manualMinutes: nil, comment: "")
        clock = at(6, 10, 0)

        let model = makeModel()

        XCTAssertEqual(model.rows.map(\.employee.lastName), ["Алексеев", "Борисов", "Васильев"])
        XCTAssertEqual(model.totalCount, 3)
        XCTAssertEqual(model.atWorkCount, 1)
        XCTAssertEqual(model.absentCount, 2)
        XCTAssertEqual(model.employeesTitle, "Сотрудников: 3")
        XCTAssertEqual(model.atWorkTitle, "На работе: 1")
        XCTAssertEqual(model.absentTitle, "Отсутствуют: 2")

        XCTAssertEqual(model.rows.map(\.statusText), ["На работе", "Больничный", "Не отмечен"])
        XCTAssertEqual(model.rows[0].arrivalText, "08:02")
        XCTAssertEqual(model.rows[0].departureText, "—")
        XCTAssertEqual(model.rows[0].action, .markDeparture(openSince: at(6, 8, 2)))
        XCTAssertEqual(model.rows[1].action, .status(.sick))
        XCTAssertEqual(model.rows[2].action, .markArrival)
    }

    func testClosedDayShowsHours() throws {
        try setUpStore(now: at(6, 8, 0))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 16, 0)
        try service().markDeparture(employee)

        let model = makeModel()

        let row = try XCTUnwrap(model.rows.first)
        XCTAssertEqual(row.statusText, "Ушёл")
        XCTAssertEqual(row.departureText, "16:00")
        XCTAssertEqual(row.hoursText, "7 ч 30 мин")
        XCTAssertEqual(row.action, .closed(minutes: 450))
        XCTAssertEqual(model.atWorkCount, 1)
    }

    // Review focus: the app left open past midnight keeps «Ушёл» for yesterday's arrival.
    func testNightShiftStillAtWorkAfterMidnight() throws {
        try setUpStore(now: at(5, 22, 0))
        let employee = addEmployee("Алексеев")
        let model = makeModel()
        model.tapPrimary(employee)
        clock = at(6, 0, 30)

        model.reload()

        let row = try XCTUnwrap(model.rows.first)
        XCTAssertEqual(row.action, .markDeparture(openSince: at(5, 22, 0)))
        XCTAssertEqual(row.statusText, "На работе")
        XCTAssertEqual(row.editorDay, calendar.date(day: 5, month: 11, year: 2026))
        XCTAssertEqual(model.atWorkCount, 1)
        XCTAssertEqual(model.dateTitle, "пятница, 6 ноября 2026")
    }

    func testForgottenDepartureShowsNoteAndAllowsArrival() throws {
        try setUpStore(now: at(5, 8, 0))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 9, 0)

        let model = makeModel()

        let row = try XCTUnwrap(model.rows.first)
        XCTAssertEqual(row.action, .markArrival)
        XCTAssertEqual(row.statusText, "Не отмечен")
        XCTAssertEqual(row.staleOpenDay, calendar.date(day: 5, month: 11, year: 2026))
        XCTAssertEqual(row.staleNoteText, "Вчера не отмечен уход")
        XCTAssertEqual(model.staleOpenRecord(for: employee)?.day, calendar.date(day: 5, month: 11, year: 2026))
        XCTAssertEqual(model.atWorkCount, 0)

        model.tapPrimary(employee)

        XCTAssertNil(model.confirmation)
        XCTAssertEqual(model.rows.first?.action, .markDeparture(openSince: at(6, 9, 0)))
        XCTAssertEqual(model.rows.first?.staleOpenDay, calendar.date(day: 5, month: 11, year: 2026))
    }

    func testTodaysForgottenDepartureShowsNote() throws {
        try setUpStore(now: at(6, 0, 10))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 23, 0)

        let row = try XCTUnwrap(makeModel().rows.first)

        XCTAssertEqual(row.staleOpenDay, calendar.date(day: 6, month: 11, year: 2026))
        XCTAssertEqual(row.staleNoteText, "Не отмечен уход")
    }

    func testDepartureSoonAfterArrivalAsksForConfirmation() throws {
        try setUpStore(now: at(6, 8, 0))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 8, 3)
        let model = makeModel()

        model.tapPrimary(employee)

        let confirmation = try XCTUnwrap(model.confirmation)
        XCTAssertEqual(confirmation.message, "Уход через 3 мин после прихода. Отметить уход?")
        XCTAssertNil(service().record(for: employee, day: at(6, 0, 0))?.departureTime)
        model.confirm(confirmation)
        XCTAssertEqual(service().record(for: employee, day: at(6, 0, 0))?.departureTime, at(6, 8, 3))
    }

    func testDepartureAfterFiveMinutesNeedsNoConfirmation() throws {
        try setUpStore(now: at(6, 8, 0))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 8, 5)
        let model = makeModel()

        model.tapPrimary(employee)

        XCTAssertNil(model.confirmation)
        XCTAssertEqual(service().record(for: employee, day: at(6, 0, 0))?.departureTime, at(6, 8, 5))
    }

    func testNoStaleNoteForNightShift() throws {
        try setUpStore(now: at(5, 22, 0))
        let employee = addEmployee("Алексеев")
        try service().markArrival(employee)
        clock = at(6, 6, 0)

        let model = makeModel()

        XCTAssertNil(model.rows.first?.staleOpenDay)
        XCTAssertNil(model.staleOpenRecord(for: employee))
    }

    func testDoubleTapIsIgnoredWhileBusy() throws {
        try setUpStore(now: at(6, 8, 0))
        let employee = addEmployee("Алексеев")
        let model = makeModel()

        model.tapPrimary(employee)
        model.tapPrimary(employee)

        XCTAssertTrue(model.isBusy(employee))
        XCTAssertNil(model.confirmation)
        let record = try XCTUnwrap(service().record(for: employee, day: at(6, 0, 0)))
        XCTAssertNil(record.departureTime)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<AttendanceRecord>()), 1)
    }

    func testArrivalOnSickDayAsksForConfirmation() throws {
        try setUpStore(now: at(6, 8, 0))
        let employee = addEmployee("Алексеев")
        try service().save(employee: employee, day: at(6, 0, 0), status: .sick, arrival: nil, departure: nil,
                           breakMinutes: 0, manualMinutes: nil, comment: "")
        let model = makeModel()

        model.tapPrimary(employee)

        let confirmation = try XCTUnwrap(model.confirmation)
        XCTAssertEqual(confirmation.message, "У сотрудника сегодня «Больничный». Отметить приход?")
        model.confirm(confirmation)
        XCTAssertNil(model.confirmation)
        XCTAssertEqual(model.rows.first?.action, .markDeparture(openSince: at(6, 8, 0)))
    }

    func testStaleArrivalAsksForConfirmationWithTime() throws {
        try setUpStore(now: at(6, 8, 2))
        let employee = addEmployee("Алексеев")
        let model = makeModel()
        try service().markArrival(employee)

        model.tapArrival(employee)

        XCTAssertEqual(model.confirmation?.message, "Приход уже отмечен в 08:02. Отметить заново?")
    }

    func testFarmNameAndDateTitle() throws {
        try setUpStore(now: at(6, 9, 0))
        XCTAssertEqual(makeModel().farmName, "КФХ Зубахин")
        context.insert(FarmSettings(farmName: "КФХ Иванов"))
        try context.save()

        let model = makeModel()

        XCTAssertEqual(model.farmName, "КФХ Иванов")
        XCTAssertEqual(model.dateTitle, "пятница, 6 ноября 2026")
        XCTAssertEqual(model.employeesTitle, "Сотрудников: 0")
    }
}
