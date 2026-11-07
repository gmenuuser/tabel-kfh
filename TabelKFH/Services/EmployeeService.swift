import Foundation
import SwiftData

/// All fields of the employee form. Text fields are trimmed when saved.
struct EmployeeDraft: Equatable {
    var lastName = ""
    var firstName = ""
    var patronymic = ""
    var position = ""
    var hireDate = Calendar.farm.dayStart(AppClock.now())
    var dismissalDate: Date?
    var workSchedule: WorkSchedule = .fiveTwo
    /// Norm of working hours per day.
    var standardWorkHours: Double = 8
    var defaultBreakMinutes = 60
    var photo: Data?

    static let hoursRange: ClosedRange<Double> = 1...24
    static let breakRange: ClosedRange<Int> = 0...240

    /// Russian messages, empty when the draft can be saved.
    var validationErrors: [String] {
        var errors: [String] = []
        if Self.trimmed(lastName).isEmpty { errors.append("Укажи фамилию") }
        if Self.trimmed(firstName).isEmpty { errors.append("Укажи имя") }
        if let dismissalDate,
           Calendar.farm.dayStart(dismissalDate) < Calendar.farm.dayStart(hireDate) {
            errors.append("Дата увольнения раньше даты приёма")
        }
        if !Self.hoursRange.contains(standardWorkHours) { errors.append("Норма — от 1 до 24 часов в день") }
        if !Self.breakRange.contains(defaultBreakMinutes) { errors.append("Перерыв — от 0 до 240 минут") }
        return errors
    }

    var isValid: Bool { validationErrors.isEmpty }

    static func trimmed(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension EmployeeDraft {
    /// The form filled from an existing employee.
    init(_ e: Employee) {
        self.init(lastName: e.lastName, firstName: e.firstName, patronymic: e.patronymic, position: e.position,
                  hireDate: e.hireDate, dismissalDate: e.dismissalDate, workSchedule: e.workSchedule,
                  standardWorkHours: e.standardWorkHours, defaultBreakMinutes: e.defaultBreakMinutes,
                  photo: e.photo)
    }
}

enum EmployeeError: LocalizedError, Equatable {
    case invalid([String])

    var errorDescription: String? {
        switch self {
        case .invalid(let messages): return messages.joined(separator: "\n")
        }
    }
}

/// Employees: add, edit, archive, restore, and who works on a given day.
/// Archiving only hides an employee; their records and timesheets stay.
@MainActor
final class EmployeeService {
    private let context: ModelContext
    private let calendar: Calendar

    init(context: ModelContext, calendar: Calendar = .farm) {
        self.context = context
        self.calendar = calendar
    }

    @discardableResult
    func add(_ draft: EmployeeDraft) throws -> Employee {
        try validate(draft)
        let e = Employee(lastName: "", firstName: "", hireDate: calendar.dayStart(draft.hireDate))
        apply(draft, to: e)
        context.insert(e)
        try context.syncedSave()
        return e
    }

    /// Changes only the employee; existing records keep their own break and minutes.
    func update(_ e: Employee, from draft: EmployeeDraft) throws {
        try validate(draft)
        apply(draft, to: e)
        try context.syncedSave()
    }

    func archive(_ e: Employee) throws {
        e.isArchived = true
        try context.syncedSave()
    }

    func restore(_ e: Employee) throws {
        e.isArchived = false
        try context.syncedSave()
    }

    /// Not archived and employed on `day` (hired on or before it, not dismissed before it), by name.
    func active(on day: Date) -> [Employee] {
        employed(on: day).filter { !$0.isArchived }
    }

    /// Employed on `day`, archived or not, by name: who can have a mark that day.
    func employed(on day: Date) -> [Employee] {
        employed(from: day, through: day)
    }

    /// Employed on at least one day of `start…end` (hired by `end`, not dismissed before `start`),
    /// archived or not, by name.
    func employed(from start: Date, through end: Date) -> [Employee] {
        let first = calendar.dayStart(start)
        let last = calendar.dayStart(end)
        return Self.sortedByName(all().filter { e in
            guard calendar.dayStart(e.hireDate) <= last else { return false }
            if let dismissal = e.dismissalDate { return calendar.dayStart(dismissal) >= first }
            return true
        })
    }

    func archived() -> [Employee] {
        Self.sortedByName(all().filter(\.isArchived))
    }

    /// Я days and worked minutes of `records`, counting, like the timesheet, only days within `e`'s employment;
    /// an unfinished day counts as a day with 0 minutes.
    static func monthTotals(_ records: [AttendanceRecord], of e: Employee,
                            calendar: Calendar = .farm) -> (presentDays: Int, minutes: Int) {
        let hire = calendar.dayStart(e.hireDate)
        let dismissal = e.dismissalDate.map { calendar.dayStart($0) }
        let counted = records.filter { r in
            let day = calendar.dayStart(r.day)
            return r.status == .present && day >= hire && (dismissal.map { day <= $0 } ?? true)
        }
        let minutes = counted.map { r in
            let unfinished = r.arrivalTime != nil && r.departureTime == nil && r.manualWorkedMinutes == nil
            return unfinished ? 0 : WorkTimeCalculator.effectiveMinutes(r)
        }
        return (counted.count, minutes.reduce(0, +))
    }

    /// By ФИО in Russian alphabetical order, ignoring case.
    static func sortedByName(_ employees: [Employee]) -> [Employee] {
        let locale = Locale(identifier: "ru_RU")
        return employees.sorted {
            $0.fullName.compare($1.fullName, options: [.caseInsensitive], locale: locale) == .orderedAscending
        }
    }

    private func all() -> [Employee] {
        (try? context.fetch(FetchDescriptor<Employee>())) ?? []
    }

    private func validate(_ draft: EmployeeDraft) throws {
        let errors = draft.validationErrors
        if !errors.isEmpty { throw EmployeeError.invalid(errors) }
    }

    private func apply(_ d: EmployeeDraft, to e: Employee) {
        e.lastName = EmployeeDraft.trimmed(d.lastName)
        e.firstName = EmployeeDraft.trimmed(d.firstName)
        e.patronymic = EmployeeDraft.trimmed(d.patronymic)
        e.position = EmployeeDraft.trimmed(d.position)
        e.hireDate = calendar.dayStart(d.hireDate)
        e.dismissalDate = d.dismissalDate.map { calendar.dayStart($0) }
        e.workSchedule = d.workSchedule
        e.standardWorkHours = d.standardWorkHours
        e.defaultBreakMinutes = d.defaultBreakMinutes
        e.photo = d.photo
    }
}
