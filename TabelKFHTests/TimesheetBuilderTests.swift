import XCTest
@testable import TabelKFH

final class TimesheetBuilderTests: XCTestCase {
    private let calendar = Calendar.farm
    private let createdAt = Date(timeIntervalSince1970: 1_793_000_000)

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func employee(_ name: String = "Зубахин Иван Петрович", hire: Date? = nil,
                          dismissal: Date? = nil) -> EmployeeSnapshot {
        EmployeeSnapshot(id: UUID(), fullName: name, position: "Тракторист",
                         hireDate: hire ?? day(1, 3, 2025), dismissalDate: dismissal)
    }

    private func mark(_ e: EmployeeSnapshot, _ d: Int, _ status: DayStatus, minutes: Int? = nil,
                      unfinished: Bool = false, month: Int = 11) -> RecordSnapshot {
        RecordSnapshot(employeeId: e.id, day: day(d, month), status: status,
                       minutes: status == .present && !unfinished ? (minutes ?? 480) : nil, unfinished: unfinished)
    }

    private func build(month: Int = 11, year: Int = 2026, _ employees: [EmployeeSnapshot],
                       _ records: [RecordSnapshot] = [], format: CellFormat = .codes,
                       settings: FarmInfo = .default, version: Int = 1) -> TimesheetDocument {
        TimesheetBuilder.build(month: month, year: year, employees: employees, records: records,
                               settings: settings, format: format, version: version, createdAt: createdAt)
    }

    // Spec 10–13.
    func testDayColumnsFollowTheMonthLength() {
        let e = employee(hire: day(1, 1, 2020))
        for (month, year, days) in [(2, 2026, 28), (2, 2028, 29), (11, 2026, 30), (12, 2026, 31)] {
            let doc = build(month: month, year: year, [e])
            XCTAssertEqual(doc.daysInMonth, days, "\(month).\(year)")
            XCTAssertEqual(doc.rows.count, 1)
            XCTAssertEqual(doc.rows[0].cells.count, days, "\(month).\(year)")
            XCTAssertTrue(doc.rows[0].cells.allSatisfy(\.employed))
        }
    }

    func testDaysBeforeHireAreBlankAndNotCounted() {
        let e = employee(hire: day(10))
        let doc = build([e], [mark(e, 5, .present), mark(e, 9, .sick), mark(e, 10, .present), mark(e, 11, .dayOff)])
        let cells = doc.rows[0].cells

        for i in 0..<9 {
            XCTAssertFalse(cells[i].employed, "day \(i + 1)")
            XCTAssertEqual(cells[i].text, "", "day \(i + 1)")
        }
        XCTAssertNil(cells[4].status)
        XCTAssertTrue(cells[9].employed)
        XCTAssertEqual(cells[9].text, "Я")
        XCTAssertEqual(cells[10].text, "В")
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 480, dayOff: 1))
    }

    func testDaysAfterDismissalAreBlankAndNotCounted() {
        let e = employee(dismissal: day(20))
        let doc = build([e], [mark(e, 20, .present), mark(e, 21, .present), mark(e, 25, .sick)])
        let cells = doc.rows[0].cells

        XCTAssertTrue(cells[19].employed)
        XCTAssertEqual(cells[19].text, "Я")
        for i in 20..<30 {
            XCTAssertFalse(cells[i].employed, "day \(i + 1)")
            XCTAssertEqual(cells[i].text, "", "day \(i + 1)")
        }
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 480))
    }

    func testEmployeesOutsideTheMonthAreNotInTheTimesheet() {
        let december = employee("Петров Пётр", hire: day(1, 12))
        let october = employee("Сидоров Сидор", hire: day(1, 1), dismissal: day(31, 10))
        let lastDay = employee("Алексеев Алексей", hire: day(30))
        let firstDay = employee("Борисов Борис", hire: day(1, 1), dismissal: day(1))

        let doc = build([december, october, lastDay, firstDay])

        XCTAssertEqual(doc.rows.map(\.employee.fullName), ["Алексеев Алексей", "Борисов Борис"])
        XCTAssertEqual(doc.rows[0].cells.filter(\.employed).count, 1)
        XCTAssertTrue(doc.rows[0].cells[29].employed)
        XCTAssertEqual(doc.rows[1].cells.filter(\.employed).count, 1)
        XCTAssertTrue(doc.rows[1].cells[0].employed)
    }

    // Spec 9: weekends, sick days and the other statuses are counted per kind.
    func testTotalsOfAFullMonth() {
        let e = employee()
        var records: [RecordSnapshot] = []
        var d = 1
        func add(_ status: DayStatus, _ count: Int) {
            for _ in 0..<count {
                records.append(mark(e, d, status))
                d += 1
            }
        }
        add(.present, 20); add(.dayOff, 2); add(.sick, 3); add(.vacation, 1)
        add(.timeOff, 1); add(.absent, 1); add(.businessTrip, 2)
        XCTAssertEqual(d, 31)

        let doc = build([e], records)

        XCTAssertEqual(doc.rows[0].totals,
                       TimesheetTotals(presentDays: 20, minutes: 9600, dayOff: 2, sick: 3, vacation: 1,
                                       timeOff: 1, absent: 1, businessTrip: 2))
        XCTAssertEqual(doc.rows[0].cells.map(\.text).joined(separator: " "),
                       (Array(repeating: "Я", count: 20) + ["В", "В", "Б", "Б", "Б", "О", "ОТ", "ПР", "К", "К"])
                        .joined(separator: " "))
        XCTAssertEqual(doc.farmTotals, doc.rows[0].totals)
    }

    func testDayWithoutRecordIsBlankNeverAbsent() {
        let e = employee()
        let doc = build([e], [mark(e, 2, .present)])
        let cell = doc.rows[0].cells[0]

        XCTAssertTrue(cell.employed)
        XCTAssertNil(cell.status)
        XCTAssertEqual(cell.text, "")
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 480))
    }

    func testUnfinishedDayIsMarkedCountedAsPresenceWithoutHoursAndListed() {
        let e = employee()
        let doc = build([e], [mark(e, 5, .present, unfinished: true), mark(e, 6, .present, minutes: 450)],
                        format: .codesAndHours)
        let cell = doc.rows[0].cells[4]

        XCTAssertEqual(cell.text, "Я?")
        XCTAssertTrue(cell.unfinished)
        XCTAssertNil(cell.minutes)
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 2, minutes: 450))
        XCTAssertEqual(doc.unfinished, ["Зубахин И. П. — 5 ноября"])
        XCTAssertEqual(build([e], [mark(e, 5, .present, unfinished: true)], format: .codes).rows[0].cells[4].text,
                       "Я?")
    }

    func testFarmTotalsAreTheSumOfRows() {
        let a = employee("Андреев Андрей")
        let b = employee("Борисов Борис", hire: day(15))
        let records = [mark(a, 1, .present, minutes: 450), mark(a, 2, .sick), mark(a, 3, .absent),
                       mark(b, 15, .present, minutes: 300), mark(b, 16, .present, unfinished: true),
                       mark(b, 17, .dayOff), mark(b, 18, .businessTrip), mark(b, 19, .vacation),
                       mark(b, 20, .timeOff), mark(b, 1, .present)]

        let doc = build([b, a], records)

        XCTAssertEqual(doc.rows.map(\.number), [1, 2])
        XCTAssertEqual(doc.rows.map(\.employee.fullName), ["Андреев Андрей", "Борисов Борис"])
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals(presentDays: 1, minutes: 450, sick: 1, absent: 1))
        XCTAssertEqual(doc.rows[1].totals,
                       TimesheetTotals(presentDays: 2, minutes: 300, dayOff: 1, vacation: 1, timeOff: 1,
                                       businessTrip: 1))
        XCTAssertEqual(doc.farmTotals,
                       TimesheetTotals(presentDays: 3, minutes: 750, dayOff: 1, sick: 1, vacation: 1, timeOff: 1,
                                       absent: 1, businessTrip: 1))
        XCTAssertEqual(doc.unfinished, ["Борисов Б. — 16 ноября"])
    }

    func testCodesAndHoursFormat() {
        let e = employee()
        let records = [mark(e, 1, .present, minutes: 450), mark(e, 2, .present, minutes: 480),
                       mark(e, 3, .sick), mark(e, 4, .present, minutes: 0)]

        let withHours = build([e], records, format: .codesAndHours).rows[0].cells
        XCTAssertEqual(withHours[0].text, "Я\n7,5")
        XCTAssertEqual(withHours[1].text, "Я\n8")
        XCTAssertEqual(withHours[2].text, "Б")
        XCTAssertEqual(withHours[3].text, "Я")

        let codesOnly = build([e], records, format: .codes).rows[0].cells
        XCTAssertEqual(codesOnly[0].text, "Я")
        XCTAssertEqual(codesOnly[0].minutes, 450)
    }

    func testDecimalHoursText() {
        XCTAssertEqual(decimalHoursText(minutes: 0), "0")
        XCTAssertEqual(decimalHoursText(minutes: 480), "8")
        XCTAssertEqual(decimalHoursText(minutes: 450), "7,5")
        XCTAssertEqual(decimalHoursText(minutes: 465), "7,75")
        XCTAssertEqual(decimalHoursText(minutes: 470), "7,83")
        XCTAssertEqual(decimalHoursText(minutes: 61), "1,02")
        XCTAssertEqual(decimalHoursText(minutes: 9600), "160")
    }

    func testRecordsOfOtherMonthsAndEmployeesAreIgnored() {
        let e = employee()
        let stranger = employee("Чужой Человек")
        let records = [mark(e, 5, .present, month: 10), mark(stranger, 5, .present)]

        let doc = build([e], records)

        XCTAssertEqual(doc.rows.count, 1)
        XCTAssertEqual(doc.rows[0].totals, TimesheetTotals())
    }

    func testHeaderFieldsAndCodableRoundTrip() throws {
        let settings = FarmInfo(farmName: "КФХ Тест", managerName: "Зубахин И. П.", responsiblePerson: "Иванова А. А.",
                                address: "с. Луговое", phone: "+7 900 000-00-00", extraInfo: "ИНН 123")
        let e = employee()
        let doc = build([e], [mark(e, 5, .present, unfinished: true), mark(e, 6, .sick)],
                        format: .codesAndHours, settings: settings, version: 3)

        XCTAssertEqual(doc.month, 11)
        XCTAssertEqual(doc.year, 2026)
        XCTAssertEqual(doc.version, 3)
        XCTAssertEqual(doc.format, .codesAndHours)
        XCTAssertEqual(doc.farmName, "КФХ Тест")
        XCTAssertEqual(doc.managerName, "Зубахин И. П.")
        XCTAssertEqual(doc.responsiblePerson, "Иванова А. А.")
        XCTAssertEqual(doc.address, "с. Луговое")
        XCTAssertEqual(doc.phone, "+7 900 000-00-00")
        XCTAssertEqual(doc.extraInfo, "ИНН 123")
        XCTAssertEqual(doc.createdAt, createdAt)

        let decoded = try JSONDecoder().decode(TimesheetDocument.self, from: JSONEncoder().encode(doc))
        XCTAssertEqual(decoded, doc)
    }

    func testShortNameAndDefaultFarm() {
        XCTAssertEqual(TimesheetBuilder.shortName("Зубахин Иван Петрович"), "Зубахин И. П.")
        XCTAssertEqual(TimesheetBuilder.shortName("Зубахин Иван"), "Зубахин И.")
        XCTAssertEqual(TimesheetBuilder.shortName("Зубахин"), "Зубахин")
        XCTAssertEqual(FarmInfo.default.farmName, "КФХ Зубахин")
        XCTAssertEqual(FarmInfo.default.responsiblePerson, "")
    }

    // Equal ФИО: the employee id decides the order, whatever order the employees come in.
    func testEqualNamesAreOrderedByEmployeeId() {
        let low = EmployeeSnapshot(id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
                                   fullName: "Иванов Иван", position: "", hireDate: day(1, 1), dismissalDate: nil)
        let high = EmployeeSnapshot(id: UUID(uuidString: "00000000-0000-0000-0000-000000000002")!,
                                    fullName: "Иванов Иван", position: "", hireDate: day(1, 1), dismissalDate: nil)

        for input in [[high, low], [low, high]] {
            let doc = build(input)
            XCTAssertEqual(doc.rows.map(\.employee.id), [low.id, high.id])
            XCTAssertEqual(doc.rows.map(\.number), [1, 2])
        }
    }
}
