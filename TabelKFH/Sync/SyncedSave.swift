import Foundation
import SwiftData

/// The send queue is filled by the same save that stores a change (as the site, `web/src/lib/sync.ts`): a change
/// saved on the phone is never lost before it is sent.
enum SyncedSave {
    /// True while pulled records are being applied: their saves queue nothing.
    static var isApplyingRemote = false
    /// Called after a save that queued something («send in a moment»).
    static var onQueued: (() -> Void)?

    /// Queue dates strictly increase, so ops keep the order they were made in.
    fileprivate static var lastStamp = Date.distantPast
    fileprivate static func nextStamp() -> Date {
        let now = Date()
        lastStamp = now > lastStamp ? now : lastStamp.addingTimeInterval(0.000_001)
        return lastStamp
    }
}

extension ModelContext {
    /// `save()` that also queues every inserted, changed or deleted synced record, in the same save.
    /// Only fields that differ from the record's shadow go into the op; the record's last op, when it is an upsert
    /// not yet sent, takes later changes instead of a new op. A deleted hours mark is sent as `hours: null` (the
    /// server keeps the record); any other deleted record as `delete`.
    func syncedSave() throws {
        if SyncedSave.isApplyingRemote {
            try save()
            return
        }
        // Everything about deleted models is read before the save. A record deleted and inserted again (same id)
        // in one save is an upsert.
        var changes: [(entity: SyncEntity, id: UUID, fields: SyncFields, delete: Bool)] = []
        var seen = Set<String>()
        let deletedModels = deletedModelsArray
        let deletedIds = Set(deletedModels.map { $0.persistentModelID })
        for model in insertedModelsArray + changedModelsArray where !deletedIds.contains(model.persistentModelID) {
            guard let r = model as? any SyncRecordable else { continue }
            let entity = type(of: r).syncEntity
            let id = r.syncId
            guard seen.insert(SyncShadow.makeKey(entity, id)).inserted else { continue }
            changes.append((entity: entity, id: id, fields: r.syncFields(), delete: false))
        }
        for model in deletedModels {
            guard let r = model as? any SyncRecordable else { continue }
            let entity = type(of: r).syncEntity
            let id = r.syncId
            guard seen.insert(SyncShadow.makeKey(entity, id)).inserted else { continue }
            if let mark = r as? WorkHoursMark {
                var fields = mark.syncFields()
                fields["hours"] = .null
                changes.append((entity: entity, id: id, fields: fields, delete: false))
            } else {
                changes.append((entity: entity, id: id, fields: [:], delete: true))
            }
        }

        var queued = false
        for c in changes {
            let key = SyncShadow.makeKey(c.entity, c.id)
            var sd = FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })
            sd.fetchLimit = 1
            let shadow = try fetch(sd).first
            if c.delete {
                insert(SyncOp(entityName: c.entity.rawValue, recordId: c.id, kind: "delete",
                              baseVersion: shadow?.version ?? 0, fieldsJSON: SyncJSON.encode([:]),
                              createdAt: SyncedSave.nextStamp()))
                if let shadow {
                    shadow.wasDeleted = true
                } else {
                    insert(SyncShadow(key: key, fieldsJSON: SyncJSON.encode([:]), wasDeleted: true))
                }
                queued = true
                continue
            }
            let known: SyncFields = shadow.map { $0.wasDeleted ? [:] : $0.fields } ?? [:]
            let changed = c.fields.filter { known[$0.key] != $0.value }
            guard !changed.isEmpty else { continue }
            if try !mergeIntoUnsentOp(changed, entityName: c.entity.rawValue, recordId: c.id) {
                insert(SyncOp(entityName: c.entity.rawValue, recordId: c.id, kind: "upsert",
                              baseVersion: shadow?.version ?? 0, fieldsJSON: SyncJSON.encode(changed),
                              createdAt: SyncedSave.nextStamp()))
            }
            if let shadow {
                shadow.fieldsJSON = SyncJSON.encode(c.fields)
                shadow.wasDeleted = false
            } else {
                insert(SyncShadow(key: key, fieldsJSON: SyncJSON.encode(c.fields)))
            }
            queued = true
        }
        try save()
        if queued { SyncedSave.onQueued?() }
    }

    /// Adds the fields to the record's last op when that op is an upsert never sent; false otherwise.
    private func mergeIntoUnsentOp(_ fields: SyncFields, entityName: String, recordId: UUID) throws -> Bool {
        var d = FetchDescriptor<SyncOp>(predicate: #Predicate { $0.entityName == entityName && $0.recordId == recordId },
                                        sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
        d.fetchLimit = 1
        guard let op = try fetch(d).first, op.state == "pending", op.attempts == 0, op.kind == "upsert" else {
            return false
        }
        op.fieldsJSON = SyncJSON.encode(op.fields.merging(fields) { $1 })
        return true
    }
}
