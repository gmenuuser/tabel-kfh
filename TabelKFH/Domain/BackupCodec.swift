import CryptoKit
import Foundation

/// Every stored field of an employee, the photo included (base64 in the file).
struct EmployeeBackup: Codable, Equatable {
    let id: UUID
    let lastName, firstName, patronymic, position: String
    let hireDate: Date
    let dismissalDate: Date?
    let isArchived: Bool
    let workSchedule: WorkSchedule
    let standardWorkHours: Double
    let defaultBreakMinutes: Int
    let photo: Data?
}

/// Every stored field of an attendance record.
struct RecordBackup: Codable, Equatable {
    let id, employeeId: UUID
    let day: Date
    let arrivalTime, departureTime: Date?
    let breakMinutes: Int
    let workedMinutes, manualWorkedMinutes: Int?
    let status: DayStatus
    let comment: String
    let createdAt, updatedAt: Date
}

/// A saved timesheet version as it is stored.
struct SnapshotBackup: Codable, Equatable {
    let id: UUID
    let month, year, version: Int
    let employeeId: UUID?
    let createdAt: Date
    let documentData, employeeData: Data
}

/// All the app's data.
struct BackupPayload: Codable, Equatable {
    var settings: FarmInfo
    var employees: [EmployeeBackup]
    var records: [RecordBackup]
    var snapshots: [SnapshotBackup]
}

/// The backup file: a header that identifies it and the payload with its checksum.
struct BackupFile: Codable {
    let format: String
    let schemaVersion: Int
    let createdAt: Date
    let appVersion: String
    /// SHA-256 (hex) of the payload's canonical JSON.
    let payloadSHA256: String
    let payload: BackupPayload
}

enum BackupError: LocalizedError, Equatable {
    case notABackup, unsupportedVersion(Int), damaged

    var errorDescription: String? {
        switch self {
        case .notABackup: return "Это не резервная копия Табеля КФХ"
        case .unsupportedVersion(let n): return "Копия сделана более новой версией приложения (схема \(n))"
        case .damaged: return "Файл копии повреждён: контрольная сумма не совпадает"
        }
    }
}

/// JSON backup with a format name, a schema version and a SHA-256 of the payload.
/// Dates are kept as the exact number of seconds (`Date`'s own encoding), so a restore gives back the same values.
enum BackupCodec {
    static let format = "tabel-kfh-backup"
    static let schemaVersion = 1

    static func encode(_ p: BackupPayload, createdAt: Date, appVersion: String) throws -> Data {
        let file = BackupFile(format: format, schemaVersion: schemaVersion, createdAt: createdAt,
                              appVersion: appVersion, payloadSHA256: try checksum(p), payload: p)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        return try encoder.encode(file)
    }

    /// The payload of a valid backup; throws `BackupError` otherwise.
    static func decode(_ d: Data) throws -> BackupPayload {
        try decodeFile(d).payload
    }

    /// Checks, in order: our format, a supported schema version, a readable payload, the checksum,
    /// and the store's rules (unique ids, one record per employee and day, records of listed employees).
    static func decodeFile(_ d: Data) throws -> BackupFile {
        guard let object = try? JSONSerialization.jsonObject(with: d), let header = object as? [String: Any] else {
            throw mentionsFormat(d) ? BackupError.damaged : BackupError.notABackup
        }
        guard header["format"] as? String == format else { throw BackupError.notABackup }
        guard let version = header["schemaVersion"] as? Int else { throw BackupError.damaged }
        if version > schemaVersion { throw BackupError.unsupportedVersion(version) }
        guard version == schemaVersion else { throw BackupError.damaged }

        let file: BackupFile
        do {
            file = try JSONDecoder().decode(BackupFile.self, from: d)
        } catch {
            // A missing field, a wrong type, an unknown status or schedule code.
            throw BackupError.damaged
        }
        guard (try? checksum(file.payload)) == file.payloadSHA256.lowercased() else { throw BackupError.damaged }
        try validate(file.payload)
        return file
    }

    /// SHA-256 (lowercase hex) of the payload encoded with sorted keys.
    static func checksum(_ p: BackupPayload) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let digest = SHA256.hash(data: try encoder.encode(p))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private struct RecordKey: Hashable {
        let employeeId: UUID
        let day: Date
    }

    private static func validate(_ p: BackupPayload, calendar: Calendar = .farm) throws {
        let employeeIds = Set(p.employees.map(\.id))
        guard employeeIds.count == p.employees.count,
              Set(p.records.map(\.id)).count == p.records.count,
              Set(p.snapshots.map(\.id)).count == p.snapshots.count else { throw BackupError.damaged }
        var days = Set<RecordKey>()
        for r in p.records {
            guard employeeIds.contains(r.employeeId),
                  days.insert(RecordKey(employeeId: r.employeeId, day: calendar.dayStart(r.day))).inserted
            else { throw BackupError.damaged }
        }
    }

    /// A cut or broken file of ours still names the format near its start.
    private static func mentionsFormat(_ d: Data) -> Bool {
        String(decoding: d.prefix(4096), as: UTF8.self).contains("\"\(format)\"")
    }
}
