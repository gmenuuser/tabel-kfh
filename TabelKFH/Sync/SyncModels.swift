import Foundation
import SwiftData

/// One change waiting for the server (`POST /sync/push`, server/README.md «Протокол синхронизации»).
/// `kind`: «upsert» (the changed fields) or «delete». `state`: «pending» (waiting), «sending» (in a push whose
/// answer has not come), «error» (refused, kept to be looked at). `attempts` counts the pushes it went in: a
/// change sent at least once keeps its `opId` and is never merged into (a lost answer is repeated by the server).
@Model
final class SyncOp {
    @Attribute(.unique) var opId: UUID
    /// The `SyncEntity` raw value (`entity` and `deleted` are taken by Core Data's NSManagedObject).
    var entityName: String
    var recordId: UUID
    var kind: String
    var baseVersion: Int
    var fieldsJSON: Data
    var createdAt: Date
    var state: String
    var attempts: Int
    var errorText: String
    /// The queue order (1, 2, 3…, counted on from the last op in the queue): ops go to the server in it, whatever
    /// the device clock says. Added to V6 before V6 reached any phone, so no new schema version.
    var seq: Int = 0

    init(opId: UUID = UUID(), entityName: String, recordId: UUID, kind: String, baseVersion: Int, fieldsJSON: Data,
         createdAt: Date, state: String = "pending", attempts: Int = 0, errorText: String = "", seq: Int = 0) {
        self.opId = opId
        self.entityName = entityName
        self.recordId = recordId
        self.kind = kind
        self.baseVersion = baseVersion
        self.fieldsJSON = fieldsJSON
        self.createdAt = createdAt
        self.state = state
        self.attempts = attempts
        self.errorText = errorText
        self.seq = seq
    }

    var fields: SyncFields { SyncJSON.decode(fieldsJSON) }
}

/// The last known state of a record: its server `version` and the phone's own `syncFields()` of it as last saved
/// or pulled. A local save sends only the fields that differ from it.
@Model
final class SyncShadow {
    /// «entity:id», id lowercase.
    @Attribute(.unique) var key: String
    var version: Int
    var fieldsJSON: Data
    /// The record is deleted (the server's `deleted`).
    var wasDeleted: Bool

    init(key: String, version: Int = 0, fieldsJSON: Data, wasDeleted: Bool = false) {
        self.key = key
        self.version = version
        self.fieldsJSON = fieldsJSON
        self.wasDeleted = wasDeleted
    }

    var fields: SyncFields { SyncJSON.decode(fieldsJSON) }

    static func makeKey(_ entity: SyncEntity, _ id: UUID) -> String { "\(entity.rawValue):\(id.uuidString.lowercased())" }
}

/// The store's sync progress, one row (`SyncState.current(in:)`). The farm's time zone is kept by `FarmTimeZone`.
@Model
final class SyncState {
    /// The last pulled change number (`GET /sync/pull?since=`).
    var cursor: Int
    var lastSyncAt: Date?
    var lastError: String
    var conflicts: Int
    /// The signed-in user's role on the farm.
    var role: String

    init(cursor: Int = 0, lastSyncAt: Date? = nil, lastError: String = "", conflicts: Int = 0, role: String = "") {
        self.cursor = cursor
        self.lastSyncAt = lastSyncAt
        self.lastError = lastError
        self.conflicts = conflicts
        self.role = role
    }

    /// The row; inserted (not saved) when the store has none.
    static func current(in context: ModelContext) -> SyncState {
        var d = FetchDescriptor<SyncState>()
        d.fetchLimit = 1
        if let existing = (try? context.fetch(d))?.first { return existing }
        let state = SyncState()
        context.insert(state)
        return state
    }
}

/// `SyncFields` ↔ the JSON kept in `SyncOp` / `SyncShadow` (sorted keys, so equal fields give equal bytes).
enum SyncJSON {
    static func encode(_ fields: SyncFields) -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return (try? e.encode(fields)) ?? Data("{}".utf8)
    }

    static func decode(_ data: Data) -> SyncFields {
        (try? JSONDecoder().decode(SyncFields.self, from: data)) ?? [:]
    }
}
