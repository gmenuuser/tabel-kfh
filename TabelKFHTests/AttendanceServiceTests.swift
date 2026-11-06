import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class AttendanceServiceTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    /// The service's clock; tests move it.
    private var clock = Date()

    private func at(_ day: Int, _ hour: Int, _ minute: Int, month: Int = 11) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }

    private func makeService(now: Date) throws -> AttendanceService {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        clock = now
        return AttendanceService(context: context, now: { [unowned self] in self.clock })
    }

    private func addEmployee(hire: Date? = nil, dismissal: Date? = nil, breakMinutes: Int = 60) -> Employee {
        let employee = Employee(lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович",
                                position: "Тракторист", hireDate: hire ?? calendar.date(day: 1, month: 3, year: 2025),
                                dismissalDate: dismissal, defaultBreakMinutes: breakMinutes)
        context.insert(employee)
        try? context.save()
        return employee
    }

    private func recordCount() throws -> Int {
        try context.fetchCount(FetchDescriptor<AttendanceRecord>())
    }

    // Spec 4.
    func testArrivalCreatesPresentRecordWithNowAndDefaultBreak() throws {
        let service = try makeService(now: at(6, 8, 2))
        let employee = addEmployee(breakMinutes: 45)

        let record = try service.markArrival(employee)

        XCTAssertEqual(record.status, .present)
        XCTAssertEqual(record.arrivalTime, at(6, 8, 2))
        XCTAssertNil(record.departureTime)
        XCTAssertNil(record.workedMinutes)
        XCTAssertEqual(record.breakMinutes, 45)
        XCTAssertEqual(record.day, calendar.date(day: 6, month: 11, year: 2026))
        XCTAssertEqual(record.employeeId, employee.id)
        XCTAssertEqual(record.employee?.id, employee.id)
        XCTAssertEqual(employee.records.count, 1)
        XCTAssertEqual(service.todayAction(for: employee), .markDeparture(openSince: at(6, 8, 2)))
    }

    func testSecondArrivalThrowsAlreadyArrived() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee()
        try service.markArrival(employee)
        clock = at(6, 8, 1)

        XCTAssertThrowsError(try service.markArrival(employee)) { error in
            XCTAssertEqual(error as? AttendanceError, .alreadyArrived)
        }
        XCTAssertEqual(try recordCount(), 1)
        XCTAssertEqual(service.record(for: employee, day: at(6, 15, 0))?.arrivalTime, at(6, 8, 0))
    }

    func testForcedSecondArrivalRestartsTheDay() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee()
        try service.markArrival(employee)
        clock = at(6, 9, 30)

        let record = try service.markArrival(employee, force: true)

        XCTAssertEqual(record.arrivalTime, at(6, 9, 30))
        XCTAssertEqual(try recordCount(), 1)
    }

    // Spec 5, 7.
    func testDepartureComputesMinutesWithBreak() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee(breakMinutes: 60)
        try service.markArrival(employee)
        clock = at(6, 17, 0)

        let record = try service.markDeparture(employee)

        XCTAssertEqual(record.departureTime, at(6, 17, 0))
        XCTAssertEqual(record.workedMinutes, 480)
        XCTAssertEqual(service.todayAction(for: employee), .closed(minutes: 480))
    }

    func testSecondDepartureThrowsAlreadyLeftUnlessForced() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee(breakMinutes: 60)
        try service.markArrival(employee)
        clock = at(6, 17, 0)
        try service.markDeparture(employee)
        clock = at(6, 18, 0)

        XCTAssertThrowsError(try service.markDeparture(employee)) { error in
            XCTAssertEqual(error as? AttendanceError, .alreadyLeft)
        }
        let record = try service.markDeparture(employee, force: true)
        XCTAssertEqual(record.workedMinutes, 540)
        XCTAssertEqual(try recordCount(), 1)
    }

    func testDepartureWithoutArrivalThrowsNotArrived() throws {
        let service = try makeService(now: at(6, 17, 0))
        let employee = addEmployee()

        XCTAssertThrowsError(try service.markDeparture(employee)) { error in
            XCTAssertEqual(error as? AttendanceError, .notArrived)
        }
        XCTAssertEqual(try recordCount(), 0)
    }

    // Review focus: night shift and the app left open past midnight.
    func testNightShiftBelongsToArrivalDay() throws {
        let service = try makeService(now: at(5, 22, 0))
        let employee = addEmployee(breakMinutes: 30)
        try service.markArrival(employee)
        clock = at(6, 6, 0)

        XCTAssertEqual(service.todayAction(for: employee), .markDeparture(openSince: at(5, 22, 0)))
        let record = try service.markDeparture(employee)

        XCTAssertEqual(record.workedMinutes, 450)
        XCTAssertEqual(record.day, calendar.date(day: 5, month: 11, year: 2026))
        XCTAssertEqual(try recordCount(), 1)
        XCTAssertEqual(service.todayAction(for: employee), .markArrival)
    }

    func testOpenRecordOlderThanYesterdayIsNotOpen() throws {
        let service = try makeService(now: at(3, 8, 0))
        let employee = addEmployee()
        try service.markArrival(employee)
        clock = at(6, 8, 0)

        XCTAssertNil(service.openRecord(for: employee))
        XCTAssertEqual(service.todayAction(for: employee), .markArrival)
    }

    // Spec 8.
    func testSaveUpsertsPastDay() throws {
        let service = try makeService(now: at(6, 12, 0))
        let employee = addEmployee()
        let day = calendar.date(day: 2, month: 11, year: 2026)

        let first = try service.save(employee: employee, day: at(2, 15, 0), status: .present,
                                     arrival: at(2, 8, 0), departure: at(2, 17, 0), breakMinutes: 60,
                                     manualMinutes: nil, comment: "")
        XCTAssertEqual(first.workedMinutes, 480)
        XCTAssertEqual(first.day, day)

        let second = try service.save(employee: employee, day: day, status: .present,
                                      arrival: at(2, 9, 0), departure: at(2, 17, 0), breakMinutes: 60,
                                      manualMinutes: nil, comment: "Опоздал")
        XCTAssertEqual(second.id, first.id)
        XCTAssertEqual(second.workedMinutes, 420)
        XCTAssertEqual(second.comment, "Опоздал")
        XCTAssertEqual(try recordCount(), 1)
    }

    func testSaveWithManualMinutesWins() throws {
        let service = try makeService(now: at(6, 18, 0))
        let employee = addEmployee()
        try service.save(employee: employee, day: at(6, 0, 0), status: .present,
                         arrival: at(6, 8, 0), departure: at(6, 17, 0), breakMinutes: 60,
                         manualMinutes: 300, comment: "")
        XCTAssertEqual(service.todayAction(for: employee), .closed(minutes: 300))
    }

    // Spec 9.
    func testNonPresentStatusesAreSavedWithoutTimes() throws {
        let service = try makeService(now: at(6, 12, 0))
        let employee = addEmployee()

        let sick = try service.save(employee: employee, day: at(6, 0, 0), status: .sick,
                                    arrival: at(6, 8, 0), departure: at(6, 17, 0), breakMinutes: 60,
                                    manualMinutes: 100, comment: "Справка")
        XCTAssertEqual(sick.status, .sick)
        XCTAssertNil(sick.arrivalTime)
        XCTAssertNil(sick.departureTime)
        XCTAssertNil(sick.workedMinutes)
        XCTAssertNil(sick.manualWorkedMinutes)
        XCTAssertEqual(service.todayAction(for: employee), .status(.sick))

        let dayOff = try service.save(employee: employee, day: at(1, 0, 0), status: .dayOff,
                                      arrival: nil, departure: nil, breakMinutes: 0,
                                      manualMinutes: nil, comment: "")
        XCTAssertEqual(dayOff.status, .dayOff)
        XCTAssertNil(dayOff.arrivalTime)
        XCTAssertEqual(try recordCount(), 2)
    }

    func testArrivalOnDayWithOtherStatusNeedsForce() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee()
        try service.save(employee: employee, day: at(6, 0, 0), status: .sick, arrival: nil, departure: nil,
                         breakMinutes: 0, manualMinutes: nil, comment: "")

        XCTAssertThrowsError(try service.markArrival(employee)) { error in
            XCTAssertEqual(error as? AttendanceError, .dayHasOtherStatus(.sick))
        }
        let record = try service.markArrival(employee, force: true)
        XCTAssertEqual(record.status, .present)
        XCTAssertEqual(record.arrivalTime, at(6, 8, 0))
        XCTAssertEqual(try recordCount(), 1)
    }

    func testSaveOutsideEmploymentThrows() throws {
        let service = try makeService(now: at(20, 12, 0))
        let employee = addEmployee(hire: calendar.date(day: 10, month: 11, year: 2026),
                                   dismissal: calendar.date(day: 15, month: 11, year: 2026))

        for day in [at(9, 0, 0), at(16, 0, 0)] {
            XCTAssertThrowsError(try service.save(employee: employee, day: day, status: .present,
                                                  arrival: nil, departure: nil, breakMinutes: 0,
                                                  manualMinutes: nil, comment: "")) { error in
                XCTAssertEqual(error as? AttendanceError, .outsideEmployment)
            }
        }
        XCTAssertNoThrow(try service.save(employee: employee, day: at(10, 0, 0), status: .present,
                                          arrival: nil, departure: nil, breakMinutes: 0,
                                          manualMinutes: nil, comment: ""))
        XCTAssertNoThrow(try service.save(employee: employee, day: at(15, 0, 0), status: .dayOff,
                                          arrival: nil, departure: nil, breakMinutes: 0,
                                          manualMinutes: nil, comment: ""))
        XCTAssertThrowsError(try service.markArrival(employee)) { error in
            XCTAssertEqual(error as? AttendanceError, .outsideEmployment)
        }
        XCTAssertEqual(try recordCount(), 2)
    }

    // A missing mark is never turned into ПР.
    func testNoRecordMeansMarkArrivalNeverAbsent() throws {
        let service = try makeService(now: at(6, 18, 0))
        let employee = addEmployee()

        XCTAssertEqual(service.todayAction(for: employee), .markArrival)
        XCTAssertNil(service.record(for: employee, day: at(6, 0, 0)))
        XCTAssertEqual(try recordCount(), 0)
    }

    func testDelete() throws {
        let service = try makeService(now: at(6, 8, 0))
        let employee = addEmployee()
        let record = try service.markArrival(employee)

        try service.delete(record)

        XCTAssertEqual(try recordCount(), 0)
        XCTAssertEqual(service.todayAction(for: employee), .markArrival)
    }

    func testErrorsHaveRussianDescriptions() {
        XCTAssertEqual(AttendanceError.alreadyArrived.errorDescription, "Приход уже отмечен")
        XCTAssertEqual(AttendanceError.notArrived.errorDescription, "Сначала отметьте приход")
        XCTAssertEqual(AttendanceError.dayHasOtherStatus(.sick).errorDescription,
                       "На этот день уже стоит статус «Больничный»")
        XCTAssertNotNil(AttendanceError.outsideEmployment.errorDescription)
        XCTAssertNotNil(AttendanceError.alreadyLeft.errorDescription)
    }
}
