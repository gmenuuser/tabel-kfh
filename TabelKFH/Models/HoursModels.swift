import Foundation
import SwiftData

/// One employee's hours for one day: 0, 4, 8 or 12. The old status records (`AttendanceRecord`) stay in the
/// store untouched as history; they are never turned into hours.
@Model
final class WorkHoursMark {
    @Attribute(.unique) var id: UUID
    var employeeId: UUID
    /// Midnight of the day on the farm calendar.
    var day: Date
    var hours: Int
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(), employeeId: UUID, day: Date, hours: Int, createdAt: Date, updatedAt: Date) {
        self.id = id
        self.employeeId = employeeId
        self.day = day
        self.hours = hours
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

enum HoursError: LocalizedError, Equatable {
    case invalidHours
    case outsideEmployment

    var errorDescription: String? {
        switch self {
        case .invalidHours: return "Можно отметить только 0, 4, 8 или 12 часов"
        case .outsideEmployment: return "В этот день сотрудник не работал в хозяйстве (до приёма или после увольнения)"
        }
    }
}

/// The timesheet of hours: one mark per (employee, day), set, replaced or removed; the month is built from them.
@MainActor
final class HoursService {
    private let context: ModelContext
    private let calendar: Calendar
    private let now: () -> Date

    init(context: ModelContext, calendar: Calendar = .farm, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.calendar = calendar
        self.now = now
    }

    private func marks(of employeeId: UUID, on day: Date) throws -> [WorkHoursMark] {
        let start = calendar.dayStart(day)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start
        return try context.fetch(FetchDescriptor<WorkHoursMark>(
            predicate: #Predicate { $0.employeeId == employeeId && $0.day >= start && $0.day < end },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]))
    }

    /// The hours of (employee, day); nil = no mark (which is not 0 hours).
    func hours(of e: Employee, on day: Date) -> Int? {
        (try? marks(of: e.id, on: day))?.first?.hours
    }

    /// Sets the day's hours, replacing a previous mark; nil removes it. Only this day changes.
    func set(_ hours: Int?, for e: Employee, on day: Date) throws {
        if let hours, !WorkHours.isValid(hours) { throw HoursError.invalidHours }
        let d = calendar.dayStart(day)
        guard d >= calendar.dayStart(e.hireDate),
              e.dismissalDate.map({ d <= calendar.dayStart($0) }) ?? true else { throw HoursError.outsideEmployment }
        let existing = try marks(of: e.id, on: d)
        // One mark per person and day: any extra row is removed.
        existing.dropFirst().forEach { context.delete($0) }
        if let hours {
            let moment = now()
            if let mark = existing.first {
                if mark.hours != hours {
                    mark.hours = hours
                    mark.updatedAt = moment
                }
            } else {
                context.insert(WorkHoursMark(id: StableId.hoursMark(employeeId: e.id, day: d, calendar: calendar), employeeId: e.id, day: d, hours: hours, createdAt: moment, updatedAt: moment))
            }
        } else {
            existing.first.map { context.delete($0) }
        }
        try context.save()
    }

    /// The button row: the selected value again removes the mark, another value replaces it.
    @discardableResult
    func tap(_ hours: Int, for e: Employee, on day: Date) throws -> Int? {
        let new: Int? = self.hours(of: e, on: day) == hours ? nil : hours
        try set(new, for: e, on: day)
        return new
    }

    /// «Всем неотмеченным»: `hours` for everyone in `employees` without a mark that day; returns how many.
    @discardableResult
    func markUnmarked(_ employees: [Employee], hours: Int, on day: Date) throws -> Int {
        guard WorkHours.isValid(hours) else { throw HoursError.invalidHours }
        var marked = 0
        for e in employees where self.hours(of: e, on: day) == nil {
            do {
                try set(hours, for: e, on: day)
                marked += 1
            } catch HoursError.outsideEmployment {
                continue
            }
        }
        return marked
    }

    /// The month's timesheet of hours of the whole farm, built from the marks every time.
    func month(_ month: Int, _ year: Int) throws -> TimesheetDocument {
        let first = calendar.date(day: 1, month: month, year: year)
        let last = calendar.date(day: calendar.daysInMonth(month: month, year: year), month: month, year: year)
        let next = calendar.date(byAdding: .month, value: 1, to: first) ?? last
        let employees = EmployeeService(context: context, calendar: calendar).employed(from: first, through: last).map {
            EmployeeSnapshot(id: $0.id, fullName: $0.fullName, position: $0.position, hireDate: $0.hireDate,
                             dismissalDate: $0.dismissalDate)
        }
        let ids = Set(employees.map(\.id))
        let marks = try context.fetch(FetchDescriptor<WorkHoursMark>(
            predicate: #Predicate { $0.day >= first && $0.day < next },
            sortBy: [SortDescriptor(\.updatedAt)]))
            .filter { ids.contains($0.employeeId) }
            .map { HoursMarkSnapshot(employeeId: $0.employeeId, day: $0.day, hours: $0.hours) }
        return HoursTimesheetBuilder.build(month: month, year: year, employees: employees, marks: marks,
                                           settings: FarmSettingsStore.current(in: context).info, createdAt: now(),
                                           calendar: calendar)
    }
}
