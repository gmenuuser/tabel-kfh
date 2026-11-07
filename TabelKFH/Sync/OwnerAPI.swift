import Foundation

// The owner's lists on the server (server/src/app.ts): helpers, devices, conflicts, server backups.

/// `GET /members`.
struct MemberDTO: Decodable, Identifiable, Equatable {
    let id: String
    let login: String?
    let email: String?
    let name: String?
    let role: String

    var displayName: String { [login, email, name].compactMap { $0 }.first { !$0.isEmpty } ?? "Без имени" }
}

/// `GET /devices`.
struct DeviceDTO: Decodable, Identifiable, Equatable {
    let id: String
    let name: String?
    let platform: String?
    let email: String?
    let login: String?
    let lastSeenAt: String?
    let revoked: Bool
    let current: Bool

    var title: String {
        if let name, !name.isEmpty { return name }
        return platform == "ios" ? "iPhone" : (platform == "web" ? "Сайт" : (platform ?? "Устройство"))
    }

    var who: String { login ?? email ?? "" }
}

/// `GET /conflicts`.
struct ConflictDTO: Decodable, Identifiable, Equatable {
    let id: String
    let entity: String
    let recordId: String
    let field: String
    let serverValue: SyncValue?
    let clientValue: SyncValue?
}

/// `GET /backups` (the database's column names).
struct ServerBackupDTO: Decodable, Identifiable, Equatable {
    let id: String
    let records: Int
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id, records
        case createdAt = "created_at"
    }
}

/// `{ok: true}` and the like.
struct OKAnswer: Decodable {
    let ok: Bool?
}

/// The bodies of the owner's requests.
struct FarmPatch: Encodable {
    let name: String
    let managerName: String
}

struct NewMember: Encodable {
    let login: String
    let password: String
    let role: String
}

struct RolePatch: Encodable { let role: String }
struct PasswordBody: Encodable { let password: String }
struct ResolveBody: Encodable { let choice: String }
struct ConfirmBody: Encodable { let confirm = true }

enum OwnerText {
    /// A date of the server («2026-11-07T10:00:00.000Z») as «07.11.2026 13:00» in the farm's zone.
    static func moment(_ iso: String?) -> String {
        guard let iso, let date = parse(iso) else { return "" }
        return moment(date)
    }

    static func moment(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "dd.MM.yyyy HH:mm"
        return f.string(from: date)
    }

    static func parse(_ iso: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: iso) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)
    }

    /// A value of a conflict, readable.
    static func value(_ v: SyncValue?) -> String {
        switch v {
        case nil, .null?: return "пусто"
        case .bool(let b)?: return b ? "да" : "нет"
        case .int(let i)?: return "\(i)"
        case .double(let d)?: return String(format: "%g", d)
        case .string(let s)?: return s.isEmpty ? "пусто" : s
        case .array(let a)?: return a.map { value($0) }.joined(separator: ", ")
        case .object?: return "запись"
        }
    }

    /// What the conflict is about.
    static func field(_ c: ConflictDTO) -> String {
        c.field == "__deleted" ? "удаление" : c.field
    }
}
