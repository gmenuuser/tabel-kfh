import Foundation
import SwiftData

enum TimesheetError: LocalizedError, Equatable {
    case invalidMonth

    var errorDescription: String? {
        switch self {
        case .invalidMonth: return "Неверный месяц"
        }
    }
}

/// Generates timesheets and keeps every generated version. A version is frozen JSON:
/// later edits to employees, records or settings never change it.
@MainActor
final class TimesheetService {
    private let context: ModelContext
    private let now: () -> Date
    private let calendar = Calendar.farm

    init(context: ModelContext, now: @escaping () -> Date = Date.init) {
        self.context = context
        self.now = now
    }

    /// A new version for (month, year, employee): the last version + 1. `employee == nil` = всё хозяйство.
    @discardableResult
    func generate(month: Int, year: Int, employee: Employee?, format: CellFormat) throws -> TimesheetSnapshot {
        guard (1...12).contains(month), year > 0 else { throw TimesheetError.invalidMonth }
        let first = calendar.date(day: 1, month: month, year: year)
        let last = calendar.date(day: calendar.daysInMonth(month: month, year: year), month: month, year: year)

        let people: [Employee]
        if let employee {
            people = [employee]
        } else {
            people = EmployeeService(context: context).employed(from: first, through: last)
        }
        let employees = people.map { e in
            EmployeeSnapshot(id: e.id, fullName: e.fullName, position: e.position,
                             hireDate: e.hireDate, dismissalDate: e.dismissalDate)
        }
        let ids = Set(employees.map(\.id))
        let descriptor = FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.day >= first && $0.day <= last })
        let records = try context.fetch(descriptor)
            .filter { ids.contains($0.employeeId) }
            .map { Self.snapshot($0) }

        let version = try latestVersion(month: month, year: year, employeeId: employee?.id) + 1
        let settings = try farmInfo()
        let createdAt = now()
        let document = TimesheetBuilder.build(month: month, year: year, employees: employees, records: records,
                                              settings: settings, format: format, version: version,
                                              createdAt: createdAt, calendar: calendar)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let documentData = try encoder.encode(document)
        let employeeData = try encoder.encode(document.rows.map(\.employee))
        let snapshot = TimesheetSnapshot(month: month, year: year, version: version, employeeId: employee?.id,
                                         createdAt: createdAt, documentData: documentData,
                                         employeeData: employeeData)
        context.insert(snapshot)
        try context.save()
        return snapshot
    }

    /// Versions of (month, year, employee), newest first. `employee == nil` = всё хозяйство.
    func versions(month: Int, year: Int, employee: Employee?) -> [TimesheetSnapshot] {
        (try? snapshots(month: month, year: year, employeeId: employee?.id)) ?? []
    }

    func document(_ s: TimesheetSnapshot) throws -> TimesheetDocument {
        try JSONDecoder().decode(TimesheetDocument.self, from: s.documentData)
    }

    /// The farm details; defaults («КФХ Зубахин», the rest empty) while settings were never saved.
    private func farmInfo() throws -> FarmInfo {
        var descriptor = FetchDescriptor<FarmSettings>()
        descriptor.fetchLimit = 1
        guard let s = try context.fetch(descriptor).first else { return .default }
        return FarmInfo(farmName: s.farmName, managerName: s.managerName, responsiblePerson: s.responsiblePerson,
                        address: s.address, phone: s.phone, extraInfo: s.extraInfo)
    }

    private func latestVersion(month: Int, year: Int, employeeId: UUID?) throws -> Int {
        try snapshots(month: month, year: year, employeeId: employeeId).first?.version ?? 0
    }

    private func snapshots(month: Int, year: Int, employeeId: UUID?) throws -> [TimesheetSnapshot] {
        let descriptor = FetchDescriptor<TimesheetSnapshot>(
            predicate: #Predicate { $0.month == month && $0.year == year },
            sortBy: [SortDescriptor(\.version, order: .reverse)])
        // The optional employeeId is compared in memory: nil means the whole-farm timesheet.
        return try context.fetch(descriptor).filter { $0.employeeId == employeeId }
    }

    private static func snapshot(_ r: AttendanceRecord) -> RecordSnapshot {
        let unfinished = r.status == .present && r.arrivalTime != nil && r.departureTime == nil
            && r.manualWorkedMinutes == nil
        let minutes: Int? = r.status == .present && !unfinished ? WorkTimeCalculator.effectiveMinutes(r) : nil
        return RecordSnapshot(employeeId: r.employeeId, day: r.day, status: r.status,
                              minutes: minutes, unfinished: unfinished)
    }
}
