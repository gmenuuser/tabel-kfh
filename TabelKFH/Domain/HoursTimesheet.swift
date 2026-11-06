import Foundation

/// The four marks of a day: 0, 4, 8 or 12 hours. An empty cell (no mark) is not the same as 0 hours.
enum WorkHours {
    static let options = [0, 4, 8, 12]

    static func isValid(_ hours: Int) -> Bool { options.contains(hours) }
}

/// Totals of one person or of the farm in the timesheet of hours.
struct HoursTotals: Codable, Equatable {
    var hours = 0
    /// Days with more than 0 hours.
    var workedDays = 0
    var zero = 0
    var four = 0
    var eight = 0
    var twelve = 0

    init() {}

    mutating func count(_ h: Int) {
        hours += h
        if h > 0 { workedDays += 1 }
        switch h {
        case 0: zero += 1
        case 4: four += 1
        case 8: eight += 1
        case 12: twelve += 1
        default: break
        }
    }

    mutating func add(_ o: HoursTotals) {
        hours += o.hours; workedDays += o.workedDays
        zero += o.zero; four += o.four; eight += o.eight; twelve += o.twelve
    }

    static let columnTitles = ["Часов", "Раб. дней", "0 ч", "4 ч", "8 ч", "12 ч"]
    var columnValues: [Int] { [hours, workedDays, zero, four, eight, twelve] }

    /// Days marked with `h` hours.
    func days(_ h: Int) -> Int {
        switch h {
        case 0: return zero
        case 4: return four
        case 8: return eight
        case 12: return twelve
        default: return 0
        }
    }
}

/// One day's hours of one employee, reduced for the builder.
struct HoursMarkSnapshot: Equatable {
    let employeeId: UUID
    let day: Date
    let hours: Int
}

extension TimesheetDocument {
    /// The timesheet of hours (since the farm switched from status codes).
    var isHours: Bool { farmHoursTotals != nil }

    var totalsTitles: [String] { isHours ? HoursTotals.columnTitles : TimesheetTotals.columnTitles }

    func totalsValues(_ row: TimesheetRow) -> [Int] {
        isHours ? (row.hoursTotals ?? HoursTotals()).columnValues : row.totals.columnValues
    }

    var farmTotalsValues: [Int] { isHours ? (farmHoursTotals ?? HoursTotals()).columnValues : farmTotals.columnValues }

    /// The legend under the table.
    var legendText: String {
        isHours
            ? "Обозначения: число — отработанные часы за день (0, 4, 8 или 12); пустая клетка — отметки нет "
              + "(это не 0 часов). «Раб. дней» — дни с ненулевыми часами."
            : "Обозначения: " + TimesheetDocument.legend
    }
}

enum HoursTimesheetBuilder {
    /// Rows: employees whose employment overlaps the month (archived ones too), by ФИО. Days outside employment
    /// are blank and not counted; a day without a mark is blank and counted nowhere.
    static func build(month: Int, year: Int, employees: [EmployeeSnapshot], marks: [HoursMarkSnapshot],
                      settings: FarmInfo, createdAt: Date, calendar: Calendar = .farm) -> TimesheetDocument {
        let days = calendar.daysInMonth(month: month, year: year)
        let first = calendar.date(day: 1, month: month, year: year)
        let last = calendar.date(day: days, month: month, year: year)
        let working = employees.filter { e in
            guard calendar.dayStart(e.hireDate) <= last else { return false }
            if let dismissal = e.dismissalDate { return calendar.dayStart(dismissal) >= first }
            return true
        }
        let locale = Locale(identifier: "ru_RU")
        let sorted = working.sorted {
            switch $0.fullName.compare($1.fullName, options: [.caseInsensitive], locale: locale) {
            case .orderedAscending: return true
            case .orderedDescending: return false
            case .orderedSame: return $0.id.uuidString < $1.id.uuidString
            }
        }
        var byDay: [UUID: [Int: Int]] = [:]
        for m in marks {
            let c = calendar.dateComponents([.year, .month, .day], from: m.day)
            guard c.year == year, c.month == month, let day = c.day else { continue }
            byDay[m.employeeId, default: [:]][day] = m.hours
        }
        var rows: [TimesheetRow] = []
        var farm = HoursTotals()
        for (index, e) in sorted.enumerated() {
            let hire = calendar.dayStart(e.hireDate)
            let dismissal = e.dismissalDate.map { calendar.dayStart($0) }
            var totals = HoursTotals()
            var cells: [TimesheetCell] = []
            for day in 1...days {
                let date = calendar.date(day: day, month: month, year: year)
                let employed = date >= hire && (dismissal.map { date <= $0 } ?? true)
                let hours = employed ? byDay[e.id]?[day] : nil
                if let hours { totals.count(hours) }
                cells.append(TimesheetCell(status: nil, minutes: nil, unfinished: false, employed: employed,
                                           format: .codes, hours: hours))
            }
            farm.add(totals)
            rows.append(TimesheetRow(number: index + 1, employee: e, cells: cells, totals: TimesheetTotals(),
                                     hoursTotals: totals))
        }
        return TimesheetDocument(month: month, year: year, daysInMonth: days, version: 0, format: .codes,
                                 farmName: settings.farmName, managerName: settings.managerName,
                                 responsiblePerson: settings.responsiblePerson, address: settings.address,
                                 phone: settings.phone, extraInfo: settings.extraInfo, createdAt: createdAt,
                                 rows: rows, farmTotals: TimesheetTotals(), unfinished: [], farmHoursTotals: farm)
    }
}
