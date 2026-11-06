import Foundation
import SwiftData

/// Makes a backup of all the data and restores one. A restore checks the whole file first and then
/// replaces everything in one save; on any error the current data stays as it was.
@MainActor
final class BackupService {
    private let context: ModelContext
    private let now: () -> Date
    private let calendar = Calendar.farm

    init(context: ModelContext, now: @escaping () -> Date = Date.init) {
        self.context = context
        self.now = now
    }

    /// «1.0» from the app bundle.
    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    /// Everything in the store, each list ordered by id.
    func payload() throws -> BackupPayload {
        let employees = try context.fetch(FetchDescriptor<Employee>()).sorted { $0.id.uuidString < $1.id.uuidString }
        let ids = Set(employees.map(\.id))
        // The store keeps one record per (employee, day); should two ever exist, the latest edit is kept so
        // the backup can always be restored.
        var latest: [RecordKey: AttendanceRecord] = [:]
        for r in try context.fetch(FetchDescriptor<AttendanceRecord>()) where ids.contains(r.employeeId) {
            let key = RecordKey(employeeId: r.employeeId, day: calendar.dayStart(r.day))
            if let kept = latest[key], kept.updatedAt >= r.updatedAt { continue }
            latest[key] = r
        }
        let records = latest.values.sorted { $0.id.uuidString < $1.id.uuidString }
        let snapshots = try context.fetch(FetchDescriptor<TimesheetSnapshot>()).sorted { $0.id.uuidString < $1.id.uuidString }
        return BackupPayload(settings: FarmSettingsStore.current(in: context).info,
                             employees: employees.map(Self.backup),
                             records: records.map(Self.backup),
                             snapshots: snapshots.map(Self.backup))
    }

    func makeBackup() throws -> Data {
        try BackupCodec.encode(payload(), createdAt: now(), appVersion: Self.appVersion)
    }

    /// What a restore of `d` would bring; throws `BackupError` for a file that cannot be restored.
    func summary(of d: Data) throws -> (employees: Int, records: Int, createdAt: Date) {
        let file = try BackupCodec.decodeFile(d)
        return (file.payload.employees.count, file.payload.records.count, file.createdAt)
    }

    /// Replaces all the data with the backup's. Objects whose id is in the backup are updated in place,
    /// the others are deleted, the missing ones are inserted; one save at the end.
    func restore(_ d: Data) throws {
        let p = try BackupCodec.decode(d)
        do {
            let backupEmployees = Set(p.employees.map(\.id))
            var employees: [UUID: Employee] = [:]
            var leaving: [Employee] = []
            for e in try context.fetch(FetchDescriptor<Employee>()) {
                if backupEmployees.contains(e.id), employees[e.id] == nil { employees[e.id] = e } else { leaving.append(e) }
            }
            for b in p.employees {
                let e = employees[b.id] ?? insertEmployee(b)
                Self.apply(b, to: e)
                employees[b.id] = e
            }

            let backupRecords = Set(p.records.map(\.id))
            var records: [UUID: AttendanceRecord] = [:]
            for r in try context.fetch(FetchDescriptor<AttendanceRecord>()) {
                if backupRecords.contains(r.id), records[r.id] == nil { records[r.id] = r } else { context.delete(r) }
            }
            for b in p.records {
                let r: AttendanceRecord
                if let existing = records[b.id] {
                    r = existing
                } else {
                    r = AttendanceRecord(id: b.id, employeeId: b.employeeId, day: b.day)
                    context.insert(r)
                }
                Self.apply(b, to: r)
                r.employee = employees[b.employeeId]
            }
            // After the records are moved, so the cascade takes only records that are not in the backup.
            leaving.forEach { context.delete($0) }

            let backupSnapshots = Set(p.snapshots.map(\.id))
            var snapshots: [UUID: TimesheetSnapshot] = [:]
            for s in try context.fetch(FetchDescriptor<TimesheetSnapshot>()) {
                if backupSnapshots.contains(s.id), snapshots[s.id] == nil { snapshots[s.id] = s } else { context.delete(s) }
            }
            for b in p.snapshots {
                if let s = snapshots[b.id] {
                    Self.apply(b, to: s)
                } else {
                    context.insert(TimesheetSnapshot(id: b.id, month: b.month, year: b.year, version: b.version,
                                                     employeeId: b.employeeId, createdAt: b.createdAt,
                                                     documentData: b.documentData, employeeData: b.employeeData))
                }
            }

            let settings = try context.fetch(FetchDescriptor<FarmSettings>())
            settings.dropFirst().forEach { context.delete($0) }
            if let first = settings.first {
                first.apply(p.settings)
            } else {
                let s = FarmSettings()
                s.apply(p.settings)
                context.insert(s)
            }

            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    /// «Копия от 06.11.2026: 5 сотрудников, 120 отметок. Текущие данные будут заменены.»
    static func summaryText(employees: Int, records: Int, createdAt: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: createdAt)
        let date = String(format: "%02d.%02d.%04d", c.day ?? 0, c.month ?? 0, c.year ?? 0)
        let people = "\(employees) " + plural(employees, "сотрудник", "сотрудника", "сотрудников")
        let marks = "\(records) " + plural(records, "отметка", "отметки", "отметок")
        return "Копия от \(date): \(people), \(marks). Текущие данные будут заменены."
    }

    /// «Табель-КФХ-копия-2026-11-06.json».
    static func fileName(on date: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: date)
        return String(format: "Табель-КФХ-копия-%04d-%02d-%02d.json", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Russian plural: 1 сотрудник, 2 сотрудника, 5 сотрудников, 11 сотрудников, 21 сотрудник.
    static func plural(_ n: Int, _ one: String, _ few: String, _ many: String) -> String {
        let tens = abs(n) % 100, units = abs(n) % 10
        if (11...14).contains(tens) { return many }
        if units == 1 { return one }
        if (2...4).contains(units) { return few }
        return many
    }

    // MARK: - Mapping

    private struct RecordKey: Hashable {
        let employeeId: UUID
        let day: Date
    }

    private func insertEmployee(_ b: EmployeeBackup) -> Employee {
        let e = Employee(id: b.id, lastName: b.lastName, firstName: b.firstName, hireDate: b.hireDate)
        context.insert(e)
        return e
    }

    private static func backup(_ e: Employee) -> EmployeeBackup {
        EmployeeBackup(id: e.id, lastName: e.lastName, firstName: e.firstName, patronymic: e.patronymic,
                       position: e.position, hireDate: e.hireDate, dismissalDate: e.dismissalDate,
                       isArchived: e.isArchived, workSchedule: e.workSchedule, standardWorkHours: e.standardWorkHours,
                       defaultBreakMinutes: e.defaultBreakMinutes, photo: e.photo)
    }

    private static func backup(_ r: AttendanceRecord) -> RecordBackup {
        RecordBackup(id: r.id, employeeId: r.employeeId, day: r.day, arrivalTime: r.arrivalTime,
                     departureTime: r.departureTime, breakMinutes: r.breakMinutes, workedMinutes: r.workedMinutes,
                     manualWorkedMinutes: r.manualWorkedMinutes, status: r.status, comment: r.comment,
                     createdAt: r.createdAt, updatedAt: r.updatedAt)
    }

    private static func backup(_ s: TimesheetSnapshot) -> SnapshotBackup {
        SnapshotBackup(id: s.id, month: s.month, year: s.year, version: s.version, employeeId: s.employeeId,
                       createdAt: s.createdAt, documentData: s.documentData, employeeData: s.employeeData)
    }

    private static func apply(_ b: EmployeeBackup, to e: Employee) {
        e.lastName = b.lastName
        e.firstName = b.firstName
        e.patronymic = b.patronymic
        e.position = b.position
        e.hireDate = b.hireDate
        e.dismissalDate = b.dismissalDate
        e.isArchived = b.isArchived
        e.workSchedule = b.workSchedule
        e.standardWorkHours = b.standardWorkHours
        e.defaultBreakMinutes = b.defaultBreakMinutes
        e.photo = b.photo
    }

    private static func apply(_ b: RecordBackup, to r: AttendanceRecord) {
        r.employeeId = b.employeeId
        r.day = b.day
        r.arrivalTime = b.arrivalTime
        r.departureTime = b.departureTime
        r.breakMinutes = b.breakMinutes
        r.workedMinutes = b.workedMinutes
        r.manualWorkedMinutes = b.manualWorkedMinutes
        r.status = b.status
        r.comment = b.comment
        r.createdAt = b.createdAt
        r.updatedAt = b.updatedAt
    }

    private static func apply(_ b: SnapshotBackup, to s: TimesheetSnapshot) {
        s.month = b.month
        s.year = b.year
        s.version = b.version
        s.employeeId = b.employeeId
        s.createdAt = b.createdAt
        s.documentData = b.documentData
        s.employeeData = b.employeeData
    }
}
