import XCTest
@testable import TabelKFH

final class WorkDayTests: XCTestCase {
    private let calendar = Calendar.farm

    func testDaysInMonth() {
        XCTAssertEqual(calendar.daysInMonth(month: 2, year: 2026), 28)
        XCTAssertEqual(calendar.daysInMonth(month: 2, year: 2028), 29)
        XCTAssertEqual(calendar.daysInMonth(month: 11, year: 2026), 30)
        XCTAssertEqual(calendar.daysInMonth(month: 12, year: 2026), 31)
    }

    func testFarmCalendarStartsOnMonday() {
        XCTAssertEqual(Calendar.farm.firstWeekday, 2)
        XCTAssertEqual(Calendar.farm.identifier, .gregorian)
        XCTAssertEqual(Calendar.farm.locale?.identifier, "ru_RU")
    }

    func testMonthTitle() {
        XCTAssertEqual(calendar.monthTitle(month: 11, year: 2026), "Ноябрь 2026")
        XCTAssertEqual(calendar.monthTitle(month: 1, year: 2027), "Январь 2027")
    }

    func testDateAndDayStart() {
        let d = calendar.date(day: 6, month: 11, year: 2026)
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: d)
        XCTAssertEqual(parts.year, 2026)
        XCTAssertEqual(parts.month, 11)
        XCTAssertEqual(parts.day, 6)
        XCTAssertEqual(parts.hour, 0)
        XCTAssertEqual(parts.minute, 0)
        XCTAssertEqual(calendar.dayStart(d.addingTimeInterval(13 * 3600 + 125)), d)
    }

    func testEmployeeFullName() {
        let full = Employee(lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович", hireDate: .now)
        XCTAssertEqual(full.fullName, "Зубахин Иван Петрович")
        let noPatronymic = Employee(lastName: "Зубахин", firstName: "Иван", hireDate: .now)
        XCTAssertEqual(noPatronymic.fullName, "Зубахин Иван")
        let messy = Employee(lastName: "  Зубахин ", firstName: "Иван  ", patronymic: " ", hireDate: .now)
        XCTAssertEqual(messy.fullName, "Зубахин Иван")
        XCTAssertEqual(full.initials, "ИЗ")
    }

    func testStatusCodesAndTitles() {
        XCTAssertEqual(DayStatus.allCases.map(\.rawValue), ["Я", "В", "Б", "О", "ОТ", "ПР", "К"])
        XCTAssertEqual(DayStatus.allCases.map(\.title),
                       ["Явка", "Выходной", "Больничный", "Отпуск", "Отгул", "Прогул", "Командировка"])
        XCTAssertEqual(WorkSchedule.allCases.map(\.title), ["5/2", "6/1", "2/2", "Свободный"])
    }
}
