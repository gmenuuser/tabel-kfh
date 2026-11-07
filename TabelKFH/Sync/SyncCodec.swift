import Foundation
import SwiftData

// The phone's models ↔ the server's records `{entity, id, version, data, deleted}`: `data` has exactly the field
// names and value shapes the site writes (`web/src/pages/*.tsx`, `engine.save('<entity>', id, {...})`).
//
// Rules: a field is the model's property name, `…Raw` without `Raw` (`vehicleTypeRaw` → `vehicleType`, the raw
// string as is). Dates are ISO 8601 in UTC with milliseconds, as JavaScript's `toISOString()`; dates without the
// fraction are read too. UUIDs are lowercase strings. An empty optional is `null` (sent, so clearing reaches the
// server). Money, mass and heads are integers. Not synced: `Employee.photo` and the `AttendanceRecord.employee`
// relationship (restored by `employeeId` on insert). `AgroCalculation`: `inputs` is an object of strings, `lines`
// an array of `{title, text, main}`. `TimesheetSnapshot.documentData/employeeData` are base64 strings (the site does
// not read them). `FarmSettings` syncs only `responsiblePerson`, `address`, `phone`, `extraInfo` (name and manager go
// through `/farm`). `CoalSettings`, `FarmSettings`, `LivestockSettings` are one row per store with the constant ids
// of `StableId`.
//
// Reading: a missing field (or one of the wrong type, or `null` for a non-optional property) keeps the model's
// value — on insert the model's default. `hoursMark` with `hours: null` is a removed mark (the server keeps the
// record): `insert` adds no row for it and `apply` leaves the hours; deleting the phone's row is the caller's job,
// see `SyncCodec.isRemovedHoursMark`.

/// One JSON value of a record's `data`. A whole number equals the same double (`.int(8) == .double(8)`).
enum SyncValue: Codable, Equatable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([SyncValue])
    case object([String: SyncValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let v = try? c.decode(Bool.self) {
            self = .bool(v)
        } else if let v = try? c.decode(Int.self) {
            self = .int(v)
        } else if let v = try? c.decode(Double.self) {
            self = .double(v)
        } else if let v = try? c.decode(String.self) {
            self = .string(v)
        } else if let v = try? c.decode([SyncValue].self) {
            self = .array(v)
        } else {
            self = .object(try c.decode([String: SyncValue].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    static func == (l: SyncValue, r: SyncValue) -> Bool {
        switch (l, r) {
        case (.null, .null): return true
        case let (.bool(a), .bool(b)): return a == b
        case let (.int(a), .int(b)): return a == b
        case let (.double(a), .double(b)): return a == b
        case let (.int(a), .double(b)), let (.double(b), .int(a)): return Double(a) == b
        case let (.string(a), .string(b)): return a == b
        case let (.array(a), .array(b)): return a == b
        case let (.object(a), .object(b)): return a == b
        default: return false
        }
    }
}

typealias SyncFields = [String: SyncValue]

/// The record types the server syncs (`GET /entities`, `server/src/entities.ts`).
enum SyncEntity: String, CaseIterable {
    case employee, hoursMark, attendanceRecord, timesheetSnapshot, farmSettings
    case coalSettings, deliveryLocation, driver, coalSale, driverPayment, coalWorkDay
    case livestockCategory, livestockOperation, slaughterRecord, livestockSettings
    case agroCalculation

    var modelType: any SyncRecordable.Type {
        switch self {
        case .employee: return Employee.self
        case .hoursMark: return WorkHoursMark.self
        case .attendanceRecord: return AttendanceRecord.self
        case .timesheetSnapshot: return TimesheetSnapshot.self
        case .farmSettings: return FarmSettings.self
        case .coalSettings: return CoalSettings.self
        case .deliveryLocation: return DeliveryLocation.self
        case .driver: return Driver.self
        case .coalSale: return CoalSale.self
        case .driverPayment: return DriverPayment.self
        case .coalWorkDay: return CoalWorkDay.self
        case .livestockCategory: return LivestockCategory.self
        case .livestockOperation: return LivestockOperation.self
        case .slaughterRecord: return SlaughterRecord.self
        case .livestockSettings: return LivestockSettings.self
        case .agroCalculation: return AgroCalculation.self
        }
    }

    /// One row per store, found whatever its id.
    var isSingleton: Bool { self == .farmSettings || self == .coalSettings || self == .livestockSettings }
}

/// A model that is one server record.
protocol SyncRecordable: PersistentModel {
    static var syncEntity: SyncEntity { get }
    /// The record id: the model's `id`, or the constant of a one-per-store row.
    var syncId: UUID { get }
    /// The record's `data`.
    func syncFields() -> SyncFields
    /// Takes the given fields; the others stay as they are.
    func apply(_ fields: SyncFields)
}

enum SyncCodec {
    /// Pulled records go in here: a known id (or the existing row of a one-per-store entity) takes the fields,
    /// an unknown one becomes a new row with the model's defaults for missing fields. Does not save the context.
    /// A removed hours mark (`hours: null`) adds nothing.
    static func insert(_ entity: SyncEntity, id: UUID, fields: SyncFields, into context: ModelContext) throws {
        if entity == .hoursMark, isRemovedHoursMark(fields) { return }
        let model: any SyncRecordable
        if let existing = try find(entity, id: id, in: context) {
            existing.apply(fields)
            model = existing
        } else {
            model = make(entity, id: id)
            model.apply(fields)
            context.insert(model)
        }
        if let record = model as? AttendanceRecord, record.employee?.id != record.employeeId {
            let employeeId = record.employeeId
            var d = FetchDescriptor<Employee>(predicate: #Predicate { $0.id == employeeId })
            d.fetchLimit = 1
            record.employee = try context.fetch(d).first
        }
    }

    /// The row of the record; for a one-per-store entity its single row whatever the id.
    static func find(_ entity: SyncEntity, id: UUID, in context: ModelContext) throws -> (any SyncRecordable)? {
        switch entity {
        case .employee: return try first(FetchDescriptor<Employee>(predicate: #Predicate { $0.id == id }), context)
        case .hoursMark: return try first(FetchDescriptor<WorkHoursMark>(predicate: #Predicate { $0.id == id }), context)
        case .attendanceRecord:
            return try first(FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.id == id }), context)
        case .timesheetSnapshot:
            return try first(FetchDescriptor<TimesheetSnapshot>(predicate: #Predicate { $0.id == id }), context)
        case .farmSettings: return try first(FetchDescriptor<FarmSettings>(), context)
        case .coalSettings: return try first(FetchDescriptor<CoalSettings>(), context)
        case .deliveryLocation:
            return try first(FetchDescriptor<DeliveryLocation>(predicate: #Predicate { $0.id == id }), context)
        case .driver: return try first(FetchDescriptor<Driver>(predicate: #Predicate { $0.id == id }), context)
        case .coalSale: return try first(FetchDescriptor<CoalSale>(predicate: #Predicate { $0.id == id }), context)
        case .driverPayment:
            return try first(FetchDescriptor<DriverPayment>(predicate: #Predicate { $0.id == id }), context)
        case .coalWorkDay: return try first(FetchDescriptor<CoalWorkDay>(predicate: #Predicate { $0.id == id }), context)
        case .livestockCategory:
            return try first(FetchDescriptor<LivestockCategory>(predicate: #Predicate { $0.id == id }), context)
        case .livestockOperation:
            return try first(FetchDescriptor<LivestockOperation>(predicate: #Predicate { $0.id == id }), context)
        case .slaughterRecord:
            return try first(FetchDescriptor<SlaughterRecord>(predicate: #Predicate { $0.id == id }), context)
        case .livestockSettings: return try first(FetchDescriptor<LivestockSettings>(), context)
        case .agroCalculation:
            return try first(FetchDescriptor<AgroCalculation>(predicate: #Predicate { $0.id == id }), context)
        }
    }

    /// A `hoursMark` record of a removed mark: the server keeps it with `hours: null`.
    static func isRemovedHoursMark(_ fields: SyncFields) -> Bool {
        if case .null? = fields["hours"] { return true }
        return false
    }

    // MARK: Dates

    private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let wholeSeconds: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// «2026-11-06T15:00:00.123Z», rounded to the millisecond (as `Date.toISOString()`).
    static func string(from date: Date) -> String {
        let ms = (date.timeIntervalSince1970 * 1000).rounded()
        let seconds = (ms / 1000).rounded(.down)
        let rest = Int(ms - seconds * 1000)
        let whole = wholeSeconds.string(from: Date(timeIntervalSince1970: seconds))
        return String(whole.dropLast()) + String(format: ".%03dZ", rest)
    }

    /// ISO 8601 with or without the fraction of a second; rounded to the millisecond so a date read back and
    /// written again gives the same string.
    static func date(from string: String) -> Date? {
        guard let d = withFraction.date(from: string) ?? wholeSeconds.date(from: string) else { return nil }
        return Date(timeIntervalSince1970: (d.timeIntervalSince1970 * 1000).rounded() / 1000)
    }

    // MARK: Private

    private static func first<T: SyncRecordable>(_ descriptor: FetchDescriptor<T>, _ context: ModelContext) throws -> T? {
        var d = descriptor
        d.fetchLimit = 1
        return try context.fetch(d).first
    }

    /// A placeholder for the id of a missing link.
    fileprivate static let noId = UUID(uuid: (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

    /// A new row with the model's defaults; values a model has no default for: "", 0, now, `noId`.
    private static func make(_ entity: SyncEntity, id: UUID) -> any SyncRecordable {
        let now = Date()
        switch entity {
        case .employee: return Employee(id: id, lastName: "", firstName: "", hireDate: now)
        case .hoursMark: return WorkHoursMark(id: id, employeeId: noId, day: now, hours: 0, createdAt: now, updatedAt: now)
        case .attendanceRecord: return AttendanceRecord(id: id, employeeId: noId, day: now, createdAt: now, updatedAt: now)
        case .timesheetSnapshot:
            return TimesheetSnapshot(id: id, month: 0, year: 0, version: 0, employeeId: nil, createdAt: now,
                                     documentData: Data(), employeeData: Data())
        case .farmSettings: return FarmSettings()
        case .coalSettings: return CoalSettings(createdAt: now, updatedAt: now)
        case .deliveryLocation: return DeliveryLocation(id: id, name: "", deliveryPriceKopecks: 0, createdAt: now)
        case .driver: return Driver(id: id, lastName: "", createdAt: now)
        case .coalSale:
            return CoalSale(id: id, saleDate: now, kilograms: 0, locationNameSnapshot: "", coalPricePerTonSnapshot: 0,
                            deliveryPriceSnapshot: 0, coalAmount: 0, deliveryPrice: 0, vehicleType: .own,
                            createdAt: now, updatedAt: now)
        case .driverPayment:
            return DriverPayment(id: id, driverId: noId, driverNameSnapshot: "", paymentDate: now, amount: 0, createdAt: now)
        case .coalWorkDay: return CoalWorkDay(id: id, date: now, createdAt: now)
        case .livestockCategory: return LivestockCategory(id: id, species: .cattle, name: "", sortOrder: 0, createdAt: now)
        case .livestockOperation:
            return LivestockOperation(id: id, kind: .other, date: now, categoryId: noId, heads: 0, createdAt: now,
                                      updatedAt: now)
        case .slaughterRecord: return SlaughterRecord(id: id, operationId: noId, reason: .other, createdAt: now)
        case .livestockSettings: return LivestockSettings(id: id, createdAt: now)
        case .agroCalculation:
            return AgroCalculation(id: id, kind: .seeding, title: "", inputsData: Data("{}".utf8),
                                   resultData: Data("[]".utf8), createdAt: now, updatedAt: now)
        }
    }
}

// MARK: - Field helpers

private extension SyncValue {
    static func from(_ v: Date?) -> SyncValue { v.map { .string(SyncCodec.string(from: $0)) } ?? .null }
    static func from(_ v: UUID?) -> SyncValue { v.map { .string($0.uuidString.lowercased()) } ?? .null }
    static func from(_ v: Int?) -> SyncValue { v.map { .int($0) } ?? .null }
}

/// Typed reads of `data`. `nil` = missing or of the wrong type (keep the value); for optional properties
/// `.some(nil)` = `null` (clear it).
private struct FieldReader {
    let fields: SyncFields
    init(_ fields: SyncFields) { self.fields = fields }

    func string(_ k: String) -> String? {
        if case .string(let v)? = fields[k] { return v }
        return nil
    }

    func int(_ k: String) -> Int? {
        switch fields[k] {
        case .int(let v)?: return v
        case .double(let v)? where v.isFinite && abs(v) < 9e15: return Int(v.rounded())
        default: return nil
        }
    }

    func double(_ k: String) -> Double? {
        switch fields[k] {
        case .double(let v)?: return v
        case .int(let v)?: return Double(v)
        default: return nil
        }
    }

    func bool(_ k: String) -> Bool? {
        if case .bool(let v)? = fields[k] { return v }
        return nil
    }

    func date(_ k: String) -> Date? { string(k).flatMap(SyncCodec.date(from:)) }

    func uuid(_ k: String) -> UUID? { string(k).flatMap(UUID.init(uuidString:)) }

    func optionalInt(_ k: String) -> Int?? {
        if case .null? = fields[k] { return .some(nil) }
        return int(k).map { .some($0) }
    }

    func optionalDate(_ k: String) -> Date?? {
        if case .null? = fields[k] { return .some(nil) }
        return date(k).map { .some($0) }
    }

    func optionalUUID(_ k: String) -> UUID?? {
        if case .null? = fields[k] { return .some(nil) }
        return uuid(k).map { .some($0) }
    }

    func data(_ k: String) -> Data? { string(k).flatMap { Data(base64Encoded: $0) } }
}

// MARK: - Timesheet

extension Employee: SyncRecordable {
    static var syncEntity: SyncEntity { .employee }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["lastName"] = .string(lastName)
        f["firstName"] = .string(firstName)
        f["patronymic"] = .string(patronymic)
        f["position"] = .string(position)
        f["hireDate"] = .from(hireDate)
        f["dismissalDate"] = .from(dismissalDate)
        f["isArchived"] = .bool(isArchived)
        f["workSchedule"] = .string(workScheduleRaw)
        f["standardWorkHours"] = .double(standardWorkHours)
        f["defaultBreakMinutes"] = .int(defaultBreakMinutes)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("lastName") { lastName = v }
        if let v = r.string("firstName") { firstName = v }
        if let v = r.string("patronymic") { patronymic = v }
        if let v = r.string("position") { position = v }
        if let v = r.date("hireDate") { hireDate = v }
        if let v = r.optionalDate("dismissalDate") { dismissalDate = v }
        if let v = r.bool("isArchived") { isArchived = v }
        if let v = r.string("workSchedule") { workScheduleRaw = v }
        if let v = r.double("standardWorkHours") { standardWorkHours = v }
        if let v = r.int("defaultBreakMinutes") { defaultBreakMinutes = v }
    }
}

extension WorkHoursMark: SyncRecordable {
    static var syncEntity: SyncEntity { .hoursMark }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["employeeId"] = .from(employeeId)
        f["day"] = .from(day)
        f["hours"] = .int(hours)
        f["createdAt"] = .from(createdAt)
        f["updatedAt"] = .from(updatedAt)
        return f
    }

    /// `hours: null` (a removed mark) leaves the hours as they are.
    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.uuid("employeeId") { employeeId = v }
        if let v = r.date("day") { day = v }
        if let v = r.int("hours") { hours = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}

extension AttendanceRecord: SyncRecordable {
    static var syncEntity: SyncEntity { .attendanceRecord }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["employeeId"] = .from(employeeId)
        f["day"] = .from(day)
        f["arrivalTime"] = .from(arrivalTime)
        f["departureTime"] = .from(departureTime)
        f["breakMinutes"] = .int(breakMinutes)
        f["workedMinutes"] = .from(workedMinutes)
        f["manualWorkedMinutes"] = .from(manualWorkedMinutes)
        f["status"] = .string(statusRaw)
        f["comment"] = .string(comment)
        f["createdAt"] = .from(createdAt)
        f["updatedAt"] = .from(updatedAt)
        return f
    }

    /// The `employee` relationship is set by `SyncCodec.insert`.
    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.uuid("employeeId") { employeeId = v }
        if let v = r.date("day") { day = v }
        if let v = r.optionalDate("arrivalTime") { arrivalTime = v }
        if let v = r.optionalDate("departureTime") { departureTime = v }
        if let v = r.int("breakMinutes") { breakMinutes = v }
        if let v = r.optionalInt("workedMinutes") { workedMinutes = v }
        if let v = r.optionalInt("manualWorkedMinutes") { manualWorkedMinutes = v }
        if let v = r.string("status") { statusRaw = v }
        if let v = r.string("comment") { comment = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}

extension TimesheetSnapshot: SyncRecordable {
    static var syncEntity: SyncEntity { .timesheetSnapshot }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["month"] = .int(month)
        f["year"] = .int(year)
        f["version"] = .int(version)
        f["employeeId"] = .from(employeeId)
        f["createdAt"] = .from(createdAt)
        f["documentData"] = .string(documentData.base64EncodedString())
        f["employeeData"] = .string(employeeData.base64EncodedString())
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.int("month") { month = v }
        if let v = r.int("year") { year = v }
        if let v = r.int("version") { version = v }
        if let v = r.optionalUUID("employeeId") { employeeId = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.data("documentData") { documentData = v }
        if let v = r.data("employeeData") { employeeData = v }
    }
}

extension FarmSettings: SyncRecordable {
    static var syncEntity: SyncEntity { .farmSettings }
    var syncId: UUID { StableId.farmSettings }

    /// Only these four: the farm name and the manager go through `/farm`.
    func syncFields() -> SyncFields {
        ["responsiblePerson": .string(responsiblePerson), "address": .string(address), "phone": .string(phone),
         "extraInfo": .string(extraInfo)]
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("responsiblePerson") { responsiblePerson = v }
        if let v = r.string("address") { address = v }
        if let v = r.string("phone") { phone = v }
        if let v = r.string("extraInfo") { extraInfo = v }
    }
}

// MARK: - Coal

extension CoalSettings: SyncRecordable {
    static var syncEntity: SyncEntity { .coalSettings }
    var syncId: UUID { StableId.coalSettings }

    func syncFields() -> SyncFields {
        ["pricePerTonKopecks": .int(pricePerTonKopecks), "createdAt": .from(createdAt), "updatedAt": .from(updatedAt)]
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.int("pricePerTonKopecks") { pricePerTonKopecks = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}

extension DeliveryLocation: SyncRecordable {
    static var syncEntity: SyncEntity { .deliveryLocation }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["name"] = .string(name)
        f["deliveryPriceKopecks"] = .int(deliveryPriceKopecks)
        f["driverPaymentKopecks"] = .from(driverPaymentKopecks)
        f["isActive"] = .bool(isActive)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("name") { name = v }
        if let v = r.int("deliveryPriceKopecks") { deliveryPriceKopecks = v }
        if let v = r.optionalInt("driverPaymentKopecks") { driverPaymentKopecks = v }
        if let v = r.bool("isActive") { isActive = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

extension Driver: SyncRecordable {
    static var syncEntity: SyncEntity { .driver }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["lastName"] = .string(lastName)
        f["firstName"] = .string(firstName)
        f["middleName"] = .string(middleName)
        f["phone"] = .string(phone)
        f["comment"] = .string(comment)
        f["isArchived"] = .bool(isArchived)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("lastName") { lastName = v }
        if let v = r.string("firstName") { firstName = v }
        if let v = r.string("middleName") { middleName = v }
        if let v = r.string("phone") { phone = v }
        if let v = r.string("comment") { comment = v }
        if let v = r.bool("isArchived") { isArchived = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

extension CoalSale: SyncRecordable {
    static var syncEntity: SyncEntity { .coalSale }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["saleDate"] = .from(saleDate)
        f["kilograms"] = .int(kilograms)
        f["locationNameSnapshot"] = .string(locationNameSnapshot)
        f["coalPricePerTonSnapshot"] = .int(coalPricePerTonSnapshot)
        f["deliveryPriceSnapshot"] = .int(deliveryPriceSnapshot)
        f["coalAmount"] = .int(coalAmount)
        f["deliveryPrice"] = .int(deliveryPrice)
        f["totalAmount"] = .int(totalAmount)
        f["vehicleType"] = .string(vehicleTypeRaw)
        f["driverId"] = .from(driverId)
        f["driverNameSnapshot"] = .string(driverNameSnapshot)
        f["driverPaymentAmount"] = .int(driverPaymentAmount)
        f["comment"] = .string(comment)
        f["createdAt"] = .from(createdAt)
        f["updatedAt"] = .from(updatedAt)
        return f
    }

    /// Without `totalAmount` but with an amount, the total is computed again.
    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.date("saleDate") { saleDate = v }
        if let v = r.int("kilograms") { kilograms = v }
        if let v = r.string("locationNameSnapshot") { locationNameSnapshot = v }
        if let v = r.int("coalPricePerTonSnapshot") { coalPricePerTonSnapshot = v }
        if let v = r.int("deliveryPriceSnapshot") { deliveryPriceSnapshot = v }
        if let v = r.int("coalAmount") { coalAmount = v }
        if let v = r.int("deliveryPrice") { deliveryPrice = v }
        if let v = r.int("totalAmount") {
            totalAmount = v
        } else if r.int("coalAmount") != nil || r.int("deliveryPrice") != nil {
            totalAmount = coalAmount + deliveryPrice
        }
        if let v = r.string("vehicleType") { vehicleTypeRaw = v }
        if let v = r.optionalUUID("driverId") { driverId = v }
        if let v = r.string("driverNameSnapshot") { driverNameSnapshot = v }
        if let v = r.int("driverPaymentAmount") { driverPaymentAmount = v }
        if let v = r.string("comment") { comment = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}

extension DriverPayment: SyncRecordable {
    static var syncEntity: SyncEntity { .driverPayment }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["driverId"] = .from(driverId)
        f["driverNameSnapshot"] = .string(driverNameSnapshot)
        f["paymentDate"] = .from(paymentDate)
        f["amount"] = .int(amount)
        f["comment"] = .string(comment)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.uuid("driverId") { driverId = v }
        if let v = r.string("driverNameSnapshot") { driverNameSnapshot = v }
        if let v = r.date("paymentDate") { paymentDate = v }
        if let v = r.int("amount") { amount = v }
        if let v = r.string("comment") { comment = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

extension CoalWorkDay: SyncRecordable {
    static var syncEntity: SyncEntity { .coalWorkDay }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["date"] = .from(date)
        f["isClosed"] = .bool(isClosed)
        f["closedAt"] = .from(closedAt)
        f["totalRevenue"] = .int(totalRevenue)
        f["totalKilograms"] = .int(totalKilograms)
        f["totalDriverPayments"] = .int(totalDriverPayments)
        f["ownVehicleDeliveryRevenue"] = .int(ownVehicleDeliveryRevenue)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.date("date") { date = v }
        if let v = r.bool("isClosed") { isClosed = v }
        if let v = r.optionalDate("closedAt") { closedAt = v }
        if let v = r.int("totalRevenue") { totalRevenue = v }
        if let v = r.int("totalKilograms") { totalKilograms = v }
        if let v = r.int("totalDriverPayments") { totalDriverPayments = v }
        if let v = r.int("ownVehicleDeliveryRevenue") { ownVehicleDeliveryRevenue = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

// MARK: - Livestock

extension LivestockCategory: SyncRecordable {
    static var syncEntity: SyncEntity { .livestockCategory }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["species"] = .string(speciesRaw)
        f["name"] = .string(name)
        f["sortOrder"] = .int(sortOrder)
        f["isBuiltIn"] = .bool(isBuiltIn)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("species") { speciesRaw = v }
        if let v = r.string("name") { name = v }
        if let v = r.int("sortOrder") { sortOrder = v }
        if let v = r.bool("isBuiltIn") { isBuiltIn = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

extension LivestockOperation: SyncRecordable {
    static var syncEntity: SyncEntity { .livestockOperation }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["kind"] = .string(kindRaw)
        f["date"] = .from(date)
        f["categoryId"] = .from(categoryId)
        f["targetCategoryId"] = .from(targetCategoryId)
        f["heads"] = .int(heads)
        f["comment"] = .string(comment)
        f["createdAt"] = .from(createdAt)
        f["updatedAt"] = .from(updatedAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("kind") { kindRaw = v }
        if let v = r.date("date") { date = v }
        if let v = r.uuid("categoryId") { categoryId = v }
        if let v = r.optionalUUID("targetCategoryId") { targetCategoryId = v }
        if let v = r.int("heads") { heads = v }
        if let v = r.string("comment") { comment = v }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}

extension SlaughterRecord: SyncRecordable {
    static var syncEntity: SyncEntity { .slaughterRecord }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["operationId"] = .from(operationId)
        f["reason"] = .string(reasonRaw)
        f["liveWeightGrams"] = .from(liveWeightGrams)
        f["carcassWeightGrams"] = .from(carcassWeightGrams)
        f["meatWeightGrams"] = .from(meatWeightGrams)
        f["createdAt"] = .from(createdAt)
        return f
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.uuid("operationId") { operationId = v }
        if let v = r.string("reason") { reasonRaw = v }
        if let v = r.optionalInt("liveWeightGrams") { liveWeightGrams = v }
        if let v = r.optionalInt("carcassWeightGrams") { carcassWeightGrams = v }
        if let v = r.optionalInt("meatWeightGrams") { meatWeightGrams = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

extension LivestockSettings: SyncRecordable {
    static var syncEntity: SyncEntity { .livestockSettings }
    var syncId: UUID { StableId.livestockSettings }

    func syncFields() -> SyncFields {
        ["defaultsCreated": .bool(defaultsCreated), "createdAt": .from(createdAt)]
    }

    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.bool("defaultsCreated") { defaultsCreated = v }
        if let v = r.date("createdAt") { createdAt = v }
    }
}

// MARK: - Agro

extension AgroCalculation: SyncRecordable {
    static var syncEntity: SyncEntity { .agroCalculation }
    var syncId: UUID { id }

    func syncFields() -> SyncFields {
        var f: SyncFields = [:]
        f["kind"] = .string(kindRaw)
        f["title"] = .string(title)
        f["inputs"] = .object(inputs.mapValues { SyncValue.string($0) })
        f["lines"] = .array(lines.map { line -> SyncValue in
            .object(["title": .string(line.title), "text": .string(line.text), "main": .bool(line.main)])
        })
        f["createdAt"] = .from(createdAt)
        f["updatedAt"] = .from(updatedAt)
        return f
    }

    /// `inputs` takes string (and number) values; a line without `main` is not the main one.
    func apply(_ fields: SyncFields) {
        let r = FieldReader(fields)
        if let v = r.string("kind") { kindRaw = v }
        if let v = r.string("title") { title = v }
        if case .object(let o)? = fields["inputs"] {
            var inputs: [String: String] = [:]
            for (k, v) in o {
                switch v {
                case .string(let s): inputs[k] = s
                case .int(let i): inputs[k] = String(i)
                case .double(let d): inputs[k] = String(d)
                default: continue
                }
            }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            if let data = try? encoder.encode(inputs) { inputsData = data }
        }
        if case .array(let a)? = fields["lines"] {
            let lines: [AgroLine] = a.compactMap { item in
                guard case .object(let o) = item else { return nil }
                let line = FieldReader(o)
                return AgroLine(title: line.string("title") ?? "", text: line.string("text") ?? "",
                                main: line.bool("main") ?? false)
            }
            if let data = try? JSONEncoder().encode(lines) { resultData = data }
        }
        if let v = r.date("createdAt") { createdAt = v }
        if let v = r.date("updatedAt") { updatedAt = v }
    }
}
