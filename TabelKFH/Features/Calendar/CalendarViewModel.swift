import Foundation
import Observation
import SwiftData

/// One square of the Monday-first month grid: a leading blank or a day of the month.
struct CalendarCell: Identifiable, Equatable {
    /// Position in the grid.
    let id: Int
    /// nil for a leading blank.
    let day: Date?
    let number: Int
    let isToday: Bool
    let isFuture: Bool
    /// All employees: how many marks of each status the day has. Empty with an employee filter.
    let statusCounts: [DayStatus: Int]
    /// With an employee filter: that employee's status of the day, nil = no mark.
    let status: DayStatus?
    /// With an employee filter: the day's total for Я with a finished or corrected total.
    let minutes: Int?
    /// With an employee filter: false before hire and after dismissal. Always true for all employees.
    let isEmployed: Bool

    var isBlank: Bool { day == nil }

    static func blank(_ index: Int) -> CalendarCell {
        CalendarCell(id: index, day: nil, number: 0, isToday: false, isFuture: false, statusCounts: [:],
                     status: nil, minutes: nil, isEmployed: false)
    }
}

/// Календарь: one month of marks for the whole farm or one employee, and the people of a day.
@MainActor
@Observable
final class CalendarViewModel {
    /// An employee employed on the day and their mark, if any.
    struct DayEntry: Identifiable {
        let employee: Employee
        let record: AttendanceRecord?
        var id: UUID { employee.id }
    }

    var month: Int
    var year: Int
    /// nil = все сотрудники.
    var employeeFilter: Employee?

    private let context: ModelContext
    private let now: () -> Date
    private let calendar = Calendar.farm

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
        let parts = Calendar.farm.dateComponents([.month, .year], from: now())
        self.month = parts.month ?? 1
        self.year = parts.year ?? 2026
    }

    /// «Ноябрь 2026».
    var title: String { calendar.monthTitle(month: month, year: year) }

    var today: Date { calendar.dayStart(now()) }

    func next() { shift(by: 1) }

    func previous() { shift(by: -1) }

    func show(month: Int, year: Int) {
        self.month = month
        self.year = year
    }

    /// Leading blanks so that the 1st falls under its weekday (Пн first), then one cell per day.
    func cells() -> [CalendarCell] {
        let first = calendar.date(day: 1, month: month, year: year)
        let count = calendar.daysInMonth(month: month, year: year)
        let weekday = calendar.component(.weekday, from: first)
        let leading = (weekday - calendar.firstWeekday + 7) % 7
        let today = self.today
        let byDay = Dictionary(grouping: monthRecords(), by: { calendar.dayStart($0.day) })
        let filter = employeeFilter
        let hire = filter.map { calendar.dayStart($0.hireDate) }
        let dismissal = filter?.dismissalDate.map { calendar.dayStart($0) }

        var cells = (0..<leading).map(CalendarCell.blank)
        for number in 1...count {
            let day = calendar.date(day: number, month: month, year: year)
            let records = byDay[day] ?? []
            var counts: [DayStatus: Int] = [:]
            var status: DayStatus?
            var minutes: Int?
            var employed = true
            if filter != nil {
                let r = records.first
                status = r?.status
                if let r, r.status == .present, r.workedMinutes != nil || r.manualWorkedMinutes != nil {
                    minutes = WorkTimeCalculator.effectiveMinutes(r)
                }
                if let hire, day < hire { employed = false }
                if let dismissal, day > dismissal { employed = false }
            } else {
                for r in records { counts[r.status, default: 0] += 1 }
            }
            cells.append(CalendarCell(id: leading + number - 1, day: day, number: number,
                                      isToday: day == today, isFuture: day > today, statusCounts: counts,
                                      status: status, minutes: minutes, isEmployed: employed))
        }
        return cells
    }

    /// Employees employed on `day` (archived ones too) with their mark of that day, by name.
    func entries(on day: Date) -> [DayEntry] {
        let attendance = AttendanceService(context: context, now: now)
        return EmployeeService(context: context).employed(on: day).map { e in
            DayEntry(employee: e, record: attendance.record(for: e, day: day))
        }
    }

    /// Choices of the employee menu: everyone employed during the shown month, archived ones too.
    func employees() -> [Employee] {
        let first = calendar.date(day: 1, month: month, year: year)
        let last = calendar.date(day: calendar.daysInMonth(month: month, year: year), month: month, year: year)
        return EmployeeService(context: context).employed(from: first, through: last)
    }

    private func monthRecords() -> [AttendanceRecord] {
        let start = calendar.date(day: 1, month: month, year: year)
        let end = calendar.date(byAdding: .month, value: 1, to: start) ?? start
        let descriptor: FetchDescriptor<AttendanceRecord>
        if let id = employeeFilter?.id {
            descriptor = FetchDescriptor(
                predicate: #Predicate { $0.employeeId == id && $0.day >= start && $0.day < end })
        } else {
            descriptor = FetchDescriptor(predicate: #Predicate { $0.day >= start && $0.day < end })
        }
        return (try? context.fetch(descriptor)) ?? []
    }

    private func shift(by delta: Int) {
        let first = calendar.date(day: 1, month: month, year: year)
        guard let moved = calendar.date(byAdding: .month, value: delta, to: first) else { return }
        let parts = calendar.dateComponents([.month, .year], from: moved)
        month = parts.month ?? month
        year = parts.year ?? year
    }
}
