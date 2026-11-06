import Foundation

/// What a day cell shows: only the status code, or the code and the hours of a Я day.
enum CellFormat: String, Codable, CaseIterable {
    case codes, codesAndHours

    var title: String {
        switch self {
        case .codes: return "Обозначения"
        case .codesAndHours: return "Обозначения и часы"
        }
    }
}

/// An employee as they were when the timesheet was generated.
struct EmployeeSnapshot: Codable, Equatable {
    let id: UUID
    let fullName, position: String
    let hireDate: Date
    let dismissalDate: Date?
}

/// One employee's mark for one day, reduced to what the timesheet needs.
struct RecordSnapshot: Codable, Equatable {
    let employeeId: UUID
    let day: Date
    let status: DayStatus
    /// Worked minutes of a finished Я day (manual total wins); nil otherwise.
    let minutes: Int?
    /// Я with an arrival and no departure: shown «Я?», 0 hours.
    let unfinished: Bool
}

/// Farm details printed on the timesheet.
struct FarmInfo: Codable, Equatable {
    let farmName, managerName, responsiblePerson, address, phone, extraInfo: String

    static let `default` = FarmInfo(farmName: "КФХ Зубахин", managerName: "", responsiblePerson: "",
                                    address: "", phone: "", extraInfo: "")
}

struct TimesheetCell: Codable, Equatable {
    /// nil = no mark that day.
    let status: DayStatus?
    /// Worked minutes of a finished Я day; nil otherwise.
    let minutes: Int?
    let unfinished: Bool
    /// false before the hire date and after the dismissal date: blank, not counted.
    let employed: Bool
    let format: CellFormat

    /// "" | "Я" | "Я\n8" | "Я\n7,5" | "Я?" | "Б" …
    var text: String {
        guard employed, let status else { return "" }
        if status == .present {
            if unfinished { return "Я?" }
            if format == .codesAndHours, let minutes, minutes > 0 {
                return "Я\n" + decimalHoursText(minutes: minutes)
            }
        }
        return status.rawValue
    }
}

struct TimesheetTotals: Codable, Equatable {
    /// Я days, unfinished ones included.
    var presentDays = 0
    var minutes = 0
    var dayOff = 0
    var sick = 0
    var vacation = 0
    var timeOff = 0
    var absent = 0
    var businessTrip = 0

    mutating func add(_ other: TimesheetTotals) {
        presentDays += other.presentDays
        minutes += other.minutes
        dayOff += other.dayOff
        sick += other.sick
        vacation += other.vacation
        timeOff += other.timeOff
        absent += other.absent
        businessTrip += other.businessTrip
    }

    mutating func count(_ cell: TimesheetCell) {
        guard cell.employed, let status = cell.status else { return }
        switch status {
        case .present:
            presentDays += 1
            minutes += cell.unfinished ? 0 : (cell.minutes ?? 0)
        case .dayOff: dayOff += 1
        case .sick: sick += 1
        case .vacation: vacation += 1
        case .timeOff: timeOff += 1
        case .absent: absent += 1
        case .businessTrip: businessTrip += 1
        }
    }
}

extension TimesheetTotals {
    /// Titles of the totals columns of the screen, the PDF and the XLSX.
    static let columnTitles = ["Явки", "Часы", "В", "Б", "О", "ОТ", "ПР", "К"]
    /// Index of «Часы» in `columnTitles`.
    static let minutesColumn = 1

    /// Values in `columnTitles` order; «Часы» as minutes.
    var columnValues: [Int] { [presentDays, minutes, dayOff, sick, vacation, timeOff, absent, businessTrip] }

    /// Values in `columnTitles` order as text; hours as a decimal with a comma.
    var columnTexts: [String] {
        columnValues.enumerated().map { index, value in
            index == Self.minutesColumn ? decimalHoursText(minutes: value) : String(value)
        }
    }
}

struct TimesheetRow: Codable, Equatable {
    /// 1-based row number.
    let number: Int
    let employee: EmployeeSnapshot
    /// One cell per day of the month, cells[0] is day 1.
    let cells: [TimesheetCell]
    let totals: TimesheetTotals
}

/// A generated timesheet. Stored as JSON in TimesheetSnapshot; never recomputed from live data.
struct TimesheetDocument: Codable, Equatable {
    let month, year, daysInMonth, version: Int
    let format: CellFormat
    let farmName, managerName, responsiblePerson: String
    let address, phone, extraInfo: String
    let createdAt: Date
    let rows: [TimesheetRow]
    let farmTotals: TimesheetTotals
    /// «Зубахин И. П. — 5 ноября», in row order, then by day.
    let unfinished: [String]
}

extension TimesheetDocument {
    /// «06.11.2026».
    func createdDateText(calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: createdAt)
        return String(format: "%02d.%02d.%04d", c.day ?? 0, c.month ?? 0, c.year ?? 0)
    }

    /// Codes and their meaning, for the legend under the table.
    static let legend = DayStatus.allCases.map { "\($0.rawValue) — \($0.title.lowercased())" }.joined(separator: ", ")
}

/// «Табель_Ноябрь_2026_v2.pdf» for the whole farm; «Табель_Ноябрь_2026_Зубахин_v2.pdf» for one employee's timesheet.
func exportFileName(_ d: TimesheetDocument, surname: String? = nil, ext: String) -> String {
    let person = surname.map { "_" + $0.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-") }
    return "Табель_\(Calendar.farm.monthName(d.month))_\(d.year)\(person ?? "")_v\(d.version).\(ext)"
}

/// Hours as a decimal with a comma, at most two decimals: 480 → «8», 450 → «7,5», 465 → «7,75», 470 → «7,83».
func decimalHoursText(minutes: Int) -> String {
    let sign = minutes < 0 ? "-" : ""
    let hundredths = (abs(minutes) * 100 + 30) / 60
    let whole = hundredths / 100, fraction = hundredths % 100
    if fraction == 0 { return "\(sign)\(whole)" }
    if fraction % 10 == 0 { return "\(sign)\(whole),\(fraction / 10)" }
    return "\(sign)\(whole)," + (fraction < 10 ? "0\(fraction)" : "\(fraction)")
}

enum TimesheetBuilder {
    /// Rows: employees whose employment overlaps the month, by ФИО (equal names by id). Days outside employment are blank and
    /// not counted; a day without a record is blank (never ПР); farm totals are the sum of the rows.
    static func build(month: Int, year: Int, employees: [EmployeeSnapshot], records: [RecordSnapshot],
                      settings: FarmInfo, format: CellFormat, version: Int, createdAt: Date,
                      calendar: Calendar = .farm) -> TimesheetDocument {
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

        // Records of this month by employee and day of month (1-based).
        var marks: [UUID: [Int: RecordSnapshot]] = [:]
        for r in records {
            let parts = calendar.dateComponents([.year, .month, .day], from: r.day)
            guard parts.year == year, parts.month == month, let day = parts.day else { continue }
            marks[r.employeeId, default: [:]][day] = r
        }

        var rows: [TimesheetRow] = []
        var farmTotals = TimesheetTotals()
        var unfinished: [String] = []
        for (index, e) in sorted.enumerated() {
            let hire = calendar.dayStart(e.hireDate)
            let dismissal = e.dismissalDate.map { calendar.dayStart($0) }
            var cells: [TimesheetCell] = []
            var totals = TimesheetTotals()
            for day in 1...days {
                let date = calendar.date(day: day, month: month, year: year)
                let employed = date >= hire && (dismissal.map { date <= $0 } ?? true)
                let r = employed ? marks[e.id]?[day] : nil
                let isPresent = r?.status == DayStatus.present
                let isUnfinished = isPresent && r?.unfinished == true
                let cell = TimesheetCell(status: r?.status,
                                         minutes: isPresent && !isUnfinished ? r?.minutes : nil,
                                         unfinished: isUnfinished, employed: employed, format: format)
                totals.count(cell)
                if isUnfinished {
                    unfinished.append("\(shortName(e.fullName)) — \(day) \(genitiveMonth(month))")
                }
                cells.append(cell)
            }
            farmTotals.add(totals)
            rows.append(TimesheetRow(number: index + 1, employee: e, cells: cells, totals: totals))
        }

        return TimesheetDocument(month: month, year: year, daysInMonth: days, version: version, format: format,
                                 farmName: settings.farmName, managerName: settings.managerName,
                                 responsiblePerson: settings.responsiblePerson, address: settings.address,
                                 phone: settings.phone, extraInfo: settings.extraInfo, createdAt: createdAt,
                                 rows: rows, farmTotals: farmTotals, unfinished: unfinished)
    }

    /// «Зубахин Иван Петрович» → «Зубахин И. П.»; a single word stays as it is.
    static func shortName(_ fullName: String) -> String {
        let parts = fullName.split(whereSeparator: \.isWhitespace).map(String.init)
        guard let lastName = parts.first else { return "" }
        let initials = parts.dropFirst().compactMap { word in word.first.map { letter in "\(letter.uppercased())." } }
        return ([lastName] + initials).joined(separator: " ")
    }

    /// «ноября» for 11.
    static func genitiveMonth(_ month: Int) -> String {
        let names = ["января", "февраля", "марта", "апреля", "мая", "июня",
                     "июля", "августа", "сентября", "октября", "ноября", "декабря"]
        return (1...12).contains(month) ? names[month - 1] : ""
    }
}
