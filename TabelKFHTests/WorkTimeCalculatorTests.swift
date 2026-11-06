import XCTest
@testable import TabelKFH

final class WorkTimeCalculatorTests: XCTestCase {
    /// Fixture calendar: the farm calendar pinned to Moscow time, so dates do not depend on the CI machine.
    private let moscow: Calendar = {
        var calendar = Calendar.farm
        calendar.timeZone = TimeZone(identifier: "Europe/Moscow")!
        return calendar
    }()

    private func at(_ day: Int, _ hour: Int, _ minute: Int, month: Int = 11, calendar: Calendar? = nil) -> Date {
        let cal = calendar ?? moscow
        return cal.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }

    // MARK: workedMinutes (spec 6, 7)

    func testFullDayWithBreak() {
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: at(6, 8, 0), departure: at(6, 17, 0), breakMinutes: 60), 480)
    }

    func testFullDayWithoutBreak() {
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: at(6, 8, 0), departure: at(6, 17, 0), breakMinutes: 0), 540)
    }

    func testPartDay() {
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: at(6, 8, 0), departure: at(6, 12, 0), breakMinutes: 0), 240)
    }

    func testPartialMinutesAreFloored() {
        let arrival = at(6, 8, 0)
        let departure = arrival.addingTimeInterval(60 * 60 + 59)
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: 0), 60)
    }

    func testNightShiftViaDepartureDate() {
        let arrivalDay = moscow.date(day: 5, month: 11, year: 2026)
        let arrival = at(5, 22, 0)
        let departure = WorkTimeCalculator.departureDate(arrivalDay: arrivalDay, arrival: arrival,
                                                         departureClock: DateComponents(hour: 6, minute: 0),
                                                         calendar: moscow)
        XCTAssertEqual(departure, at(6, 6, 0))
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: 30), 450)
    }

    func testDepartureDateSameDay() {
        let arrivalDay = moscow.date(day: 6, month: 11, year: 2026)
        let departure = WorkTimeCalculator.departureDate(arrivalDay: arrivalDay, arrival: at(6, 8, 0),
                                                         departureClock: DateComponents(hour: 17, minute: 30),
                                                         calendar: moscow)
        XCTAssertEqual(departure, at(6, 17, 30))
    }

    /// Clocks go back 03:00 → 02:00 in Berlin on 2026-10-25: a 22:00–06:00 night lasts 9 real hours.
    func testNightShiftAcrossDSTUsesRealInterval() {
        var berlin = Calendar.farm
        berlin.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let arrivalDay = berlin.date(day: 24, month: 10, year: 2026)
        let arrival = at(24, 22, 0, month: 10, calendar: berlin)
        let departure = WorkTimeCalculator.departureDate(arrivalDay: arrivalDay, arrival: arrival,
                                                         departureClock: DateComponents(hour: 6, minute: 0),
                                                         calendar: berlin)
        XCTAssertEqual(departure, at(25, 6, 0, month: 10, calendar: berlin))
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: 30), 510)
    }

    func testBreakLongerThanShift() {
        let arrival = at(6, 8, 0), departure = at(6, 12, 0)
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: 600), 0)
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: arrival, departure: departure,
                                                   breakMinutes: 600, now: at(6, 20, 0))
        XCTAssertEqual(warnings, [.breakLongerThanShift])
    }

    // MARK: warnings

    func testNormalDayHasNoWarnings() {
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: at(6, 8, 0), departure: at(6, 17, 0),
                                                   breakMinutes: 60, now: at(6, 18, 0))
        XCTAssertEqual(warnings, [])
    }

    func testDepartureBeforeArrival() {
        let arrival = at(6, 17, 0), departure = at(6, 8, 0)
        XCTAssertEqual(WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: 0), 0)
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: arrival, departure: departure,
                                                   breakMinutes: 0, now: at(6, 18, 0))
        XCTAssertEqual(warnings, [.departureBeforeArrival])
    }

    func testOverSixteenHours() {
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: at(6, 5, 0), departure: at(6, 22, 0),
                                                   breakMinutes: 60, now: at(6, 23, 0))
        XCTAssertEqual(warnings, [.overSixteenHours])
    }

    func testTimesWithNonPresentStatus() {
        let warnings = WorkTimeCalculator.warnings(status: .dayOff, arrival: at(6, 8, 0), departure: nil,
                                                   breakMinutes: 0, now: at(6, 18, 0))
        XCTAssertEqual(warnings, [.timesWithNonPresentStatus])
    }

    func testNotFinished() {
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: at(6, 8, 0), departure: nil,
                                                   breakMinutes: 60, now: at(6, 12, 0))
        XCTAssertEqual(warnings, [.notFinished])
    }

    func testArrivalInFuture() {
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: at(7, 8, 0), departure: nil,
                                                   breakMinutes: 60, now: at(6, 12, 0))
        XCTAssertEqual(warnings, [.arrivalInFuture, .notFinished])
    }

    func testDepartureInFuture() {
        let warnings = WorkTimeCalculator.warnings(status: .present, arrival: at(6, 8, 0), departure: at(6, 17, 0),
                                                   breakMinutes: 60, now: at(6, 12, 0))
        XCTAssertEqual(warnings, [.departureInFuture])
    }

    func testStatusWithoutTimesHasNoWarnings() {
        XCTAssertEqual(WorkTimeCalculator.warnings(status: .sick, arrival: nil, departure: nil,
                                                   breakMinutes: 0, now: at(6, 12, 0)), [])
    }

    func testWarningTexts() {
        XCTAssertEqual(WorkWarning.departureBeforeArrival.text, "Уход раньше прихода")
        XCTAssertEqual(WorkWarning.breakLongerThanShift.text, "Перерыв длиннее смены")
        XCTAssertEqual(WorkWarning.overSixteenHours.text, "Смена больше 16 часов — проверь время")
        XCTAssertEqual(WorkWarning.timesWithNonPresentStatus.text, "Указано время, но статус не «Явка»")
        XCTAssertEqual(WorkWarning.arrivalInFuture.text, "Приход в будущем")
        XCTAssertEqual(WorkWarning.notFinished.text, "День не закрыт: нет времени ухода")
        XCTAssertEqual(WorkWarning.departureInFuture.text, "Уход в будущем")
    }

    // MARK: effectiveMinutes

    func testManualOverrideWins() {
        let record = AttendanceRecord(employeeId: UUID(), day: moscow.date(day: 6, month: 11, year: 2026),
                                      arrivalTime: at(6, 8, 0), departureTime: at(6, 17, 0), breakMinutes: 60,
                                      workedMinutes: 480, manualWorkedMinutes: 300)
        XCTAssertEqual(WorkTimeCalculator.effectiveMinutes(record), 300)
    }

    func testEffectiveMinutesFallsBackToWorkedThenZero() {
        let day = moscow.date(day: 6, month: 11, year: 2026)
        let closed = AttendanceRecord(employeeId: UUID(), day: day, workedMinutes: 480)
        XCTAssertEqual(WorkTimeCalculator.effectiveMinutes(closed), 480)
        let open = AttendanceRecord(employeeId: UUID(), day: day, arrivalTime: at(6, 8, 0))
        XCTAssertEqual(WorkTimeCalculator.effectiveMinutes(open), 0)
    }
}
