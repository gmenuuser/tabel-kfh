import Foundation
import SwiftData

/// Makes a backup of all the data and restores one. A restore checks the whole file first and then
/// replaces everything in one save; on any error the current data stays as it was.
@MainActor
final class BackupService {
    private let context: ModelContext
    private let now: () -> Date
    private let calendar: Calendar
    /// Called after a successful restore with the backup's farm time zone (or the current one).
    private let adoptTimeZone: (TimeZone) -> Void

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now, calendar: Calendar = .farm,
         adoptTimeZone: @escaping (TimeZone) -> Void = FarmTimeZone.adoptInApp) {
        self.context = context
        self.now = now
        self.calendar = calendar
        self.adoptTimeZone = adoptTimeZone
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
                             snapshots: snapshots.map(Self.backup),
                             timeZone: calendar.timeZone.identifier,
                             coal: try coalPayload())
    }

    func makeBackup() throws -> Data {
        try BackupCodec.encode(payload(), createdAt: now(), appVersion: Self.appVersion)
    }

    /// What a restore of `d` would bring; throws `BackupError` for a file that cannot be restored.
    /// `coalSales` is nil for a file made before the coal module.
    func summary(of d: Data) throws -> (employees: Int, records: Int, coalSales: Int?, createdAt: Date) {
        let file = try BackupCodec.decodeFile(d)
        return (file.payload.employees.count, file.payload.records.count, file.payload.coal?.sales.count,
                file.createdAt)
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

            if let coal = p.coal { try restoreCoal(coal) }

            try context.save()
        } catch {
            context.rollback()
            throw error
        }
        // The backup's day keys are midnights of its farm zone; a backup without one keeps the current zone.
        adoptTimeZone(p.timeZone.flatMap(TimeZone.init(identifier:)) ?? calendar.timeZone)
    }

    /// «Копия от 06.11.2026: 5 сотрудников, 120 отметок. Текущие данные будут заменены.»
    /// With the coal module: «…, 120 отметок, 12 продаж угля. …». For a file made before it (`coalSales` nil)
    /// the coal data is kept as it is.
    static func summaryText(employees: Int, records: Int, coalSales: Int? = nil, createdAt: Date,
                            calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: createdAt)
        let date = String(format: "%02d.%02d.%04d", c.day ?? 0, c.month ?? 0, c.year ?? 0)
        let people = "\(employees) " + plural(employees, "сотрудник", "сотрудника", "сотрудников")
        let marks = "\(records) " + plural(records, "отметка", "отметки", "отметок")
        guard let coalSales else {
            return "Копия от \(date): \(people), \(marks). Текущие данные будут заменены."
        }
        let sales = "\(coalSales) " + plural(coalSales, "продажа", "продажи", "продаж") + " угля"
        return "Копия от \(date): \(people), \(marks), \(sales). Текущие данные будут заменены."
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

    // MARK: - Coal module

    private func coalPayload() throws -> CoalBackup {
        func byId<T>(_ items: [T], _ id: (T) -> UUID) -> [T] { items.sorted { id($0).uuidString < id($1).uuidString } }
        let settings = try context.fetch(FetchDescriptor<CoalSettings>(sortBy: [SortDescriptor(\.createdAt)])).first
        return CoalBackup(
            settings: settings.map { CoalBackup.Settings(pricePerTonKopecks: $0.pricePerTonKopecks,
                                                         createdAt: $0.createdAt, updatedAt: $0.updatedAt) },
            locations: byId(try context.fetch(FetchDescriptor<DeliveryLocation>()), { $0.id }).map {
                CoalBackup.Location(id: $0.id, name: $0.name, deliveryPriceKopecks: $0.deliveryPriceKopecks,
                                    driverPaymentKopecks: $0.driverPaymentKopecks, isActive: $0.isActive,
                                    createdAt: $0.createdAt)
            },
            drivers: byId(try context.fetch(FetchDescriptor<Driver>()), { $0.id }).map {
                CoalBackup.DriverRow(id: $0.id, lastName: $0.lastName, firstName: $0.firstName,
                                     middleName: $0.middleName, phone: $0.phone, comment: $0.comment,
                                     isArchived: $0.isArchived, createdAt: $0.createdAt)
            },
            sales: byId(try context.fetch(FetchDescriptor<CoalSale>()), { $0.id }).map {
                CoalBackup.Sale(id: $0.id, saleDate: $0.saleDate, kilograms: $0.kilograms,
                                locationNameSnapshot: $0.locationNameSnapshot,
                                coalPricePerTonSnapshot: $0.coalPricePerTonSnapshot,
                                deliveryPriceSnapshot: $0.deliveryPriceSnapshot, coalAmount: $0.coalAmount,
                                deliveryPrice: $0.deliveryPrice, totalAmount: $0.totalAmount,
                                vehicleType: $0.vehicleType, driverId: $0.driverId,
                                driverNameSnapshot: $0.driverNameSnapshot, driverPaymentAmount: $0.driverPaymentAmount,
                                comment: $0.comment, createdAt: $0.createdAt, updatedAt: $0.updatedAt)
            },
            payments: byId(try context.fetch(FetchDescriptor<DriverPayment>()), { $0.id }).map {
                CoalBackup.Payment(id: $0.id, driverId: $0.driverId, driverNameSnapshot: $0.driverNameSnapshot,
                                   paymentDate: $0.paymentDate, amount: $0.amount, comment: $0.comment,
                                   createdAt: $0.createdAt)
            },
            days: byId(try context.fetch(FetchDescriptor<CoalWorkDay>()), { $0.id }).map {
                CoalBackup.Day(id: $0.id, date: $0.date, isClosed: $0.isClosed, closedAt: $0.closedAt,
                               totalRevenue: $0.totalRevenue, totalKilograms: $0.totalKilograms,
                               totalDriverPayments: $0.totalDriverPayments,
                               ownVehicleDeliveryRevenue: $0.ownVehicleDeliveryRevenue, createdAt: $0.createdAt)
            })
    }

    /// Rows whose id is in the backup are updated in place, the others deleted, the missing ones inserted,
    /// the same way as the timesheet data; the caller saves once.
    private func restoreCoal(_ c: CoalBackup) throws {
        let settingsRows = try context.fetch(FetchDescriptor<CoalSettings>(sortBy: [SortDescriptor(\.createdAt)]))
        if let b = c.settings {
            let s: CoalSettings
            if let first = settingsRows.first {
                s = first
            } else {
                s = CoalSettings(createdAt: b.createdAt, updatedAt: b.updatedAt)
                context.insert(s)
            }
            settingsRows.dropFirst().forEach { context.delete($0) }
            s.pricePerTonKopecks = b.pricePerTonKopecks
            s.createdAt = b.createdAt
            s.updatedAt = b.updatedAt
        } else {
            // The module was never opened in the backup: its defaults are made again when it is opened.
            settingsRows.forEach { context.delete($0) }
        }

        merge(c.locations, existing: try context.fetch(FetchDescriptor<DeliveryLocation>()), id: { $0.id },
              rowId: { $0.id },
              make: { DeliveryLocation(id: $0.id, name: $0.name, deliveryPriceKopecks: 0, createdAt: $0.createdAt) }) { b, l in
            l.name = b.name
            l.deliveryPriceKopecks = b.deliveryPriceKopecks
            l.driverPaymentKopecks = b.driverPaymentKopecks
            l.isActive = b.isActive
            l.createdAt = b.createdAt
        }
        merge(c.drivers, existing: try context.fetch(FetchDescriptor<Driver>()), id: { $0.id }, rowId: { $0.id },
              make: { Driver(id: $0.id, lastName: $0.lastName, createdAt: $0.createdAt) }) { b, d in
            d.lastName = b.lastName
            d.firstName = b.firstName
            d.middleName = b.middleName
            d.phone = b.phone
            d.comment = b.comment
            d.isArchived = b.isArchived
            d.createdAt = b.createdAt
        }
        merge(c.sales, existing: try context.fetch(FetchDescriptor<CoalSale>()), id: { $0.id }, rowId: { $0.id },
              make: { CoalSale(id: $0.id, saleDate: $0.saleDate, kilograms: 0, locationNameSnapshot: "",
                               coalPricePerTonSnapshot: 0, deliveryPriceSnapshot: 0, coalAmount: 0,
                               deliveryPrice: 0, vehicleType: $0.vehicleType, createdAt: $0.createdAt,
                               updatedAt: $0.updatedAt) }) { b, s in
            s.saleDate = b.saleDate
            s.kilograms = b.kilograms
            s.locationNameSnapshot = b.locationNameSnapshot
            s.coalPricePerTonSnapshot = b.coalPricePerTonSnapshot
            s.deliveryPriceSnapshot = b.deliveryPriceSnapshot
            s.coalAmount = b.coalAmount
            s.deliveryPrice = b.deliveryPrice
            s.totalAmount = b.totalAmount
            s.vehicleType = b.vehicleType
            s.driverId = b.driverId
            s.driverNameSnapshot = b.driverNameSnapshot
            s.driverPaymentAmount = b.driverPaymentAmount
            s.comment = b.comment
            s.createdAt = b.createdAt
            s.updatedAt = b.updatedAt
        }
        merge(c.payments, existing: try context.fetch(FetchDescriptor<DriverPayment>()), id: { $0.id },
              rowId: { $0.id },
              make: { DriverPayment(id: $0.id, driverId: $0.driverId, driverNameSnapshot: "",
                                    paymentDate: $0.paymentDate, amount: 0, createdAt: $0.createdAt) }) { b, p in
            p.driverId = b.driverId
            p.driverNameSnapshot = b.driverNameSnapshot
            p.paymentDate = b.paymentDate
            p.amount = b.amount
            p.comment = b.comment
            p.createdAt = b.createdAt
        }
        merge(c.days, existing: try context.fetch(FetchDescriptor<CoalWorkDay>()), id: { $0.id }, rowId: { $0.id },
              make: { CoalWorkDay(id: $0.id, date: $0.date, createdAt: $0.createdAt) }) { b, d in
            d.date = b.date
            d.isClosed = b.isClosed
            d.closedAt = b.closedAt
            d.totalRevenue = b.totalRevenue
            d.totalKilograms = b.totalKilograms
            d.totalDriverPayments = b.totalDriverPayments
            d.ownVehicleDeliveryRevenue = b.ownVehicleDeliveryRevenue
            d.createdAt = b.createdAt
        }
    }

    /// Updates the rows listed in `backup`, deletes the others, inserts the missing ones.
    private func merge<B, M: PersistentModel>(_ backup: [B], existing: [M], id: (B) -> UUID, rowId: (M) -> UUID,
                                             make: (B) -> M, apply: (B, M) -> Void) {
        let wanted = Set(backup.map(id))
        var rows: [UUID: M] = [:]
        for row in existing {
            let key = rowId(row)
            if wanted.contains(key), rows[key] == nil { rows[key] = row } else { context.delete(row) }
        }
        for b in backup {
            let row: M
            if let found = rows[id(b)] {
                row = found
            } else {
                row = make(b)
                context.insert(row)
            }
            apply(b, row)
        }
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
