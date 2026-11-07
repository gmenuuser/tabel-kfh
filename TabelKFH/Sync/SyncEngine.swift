import Foundation
import Observation
import SwiftData

/// What the sync shows: online, sending now, changes waiting (`pending`, «sending» included), refused (`failed`),
/// open conflicts on the server.
struct SyncStatus: Equatable {
    var online = true
    var syncing = false
    var pending = 0
    var failed = 0
    var conflicts = 0
    var lastSyncAt: Date?
    var lastError: String?
}

/// Offline first, as the site (`web/src/lib/sync.ts`): every change is already in the store and the queue
/// (`syncedSave()`); `syncNow()` sends the queue (`POST /sync/push`), then takes what other devices changed
/// (`GET /sync/pull`), then counts open conflicts (`GET /conflicts`). Nothing leaves the queue without the server's
/// answer. Works in the container's main context, the one the screens use.
@MainActor
@Observable
final class SyncEngine {
    private(set) var status = SyncStatus()
    /// Called after a 401 (the device was signed out or its access revoked).
    @ObservationIgnored var onSignedOut: (() -> Void)?

    private let container: ModelContainer
    private let api: APIClient
    @ObservationIgnored private var running: Task<Void, Never>?
    @ObservationIgnored private var again = false
    @ObservationIgnored private var signedOut = false
    @ObservationIgnored private var soon: Task<Void, Never>?

    /// Ops in one push (the server takes up to 500).
    static let batchSize = 100

    init(container: ModelContainer, api: APIClient) {
        self.container = container
        self.api = api
        var d = FetchDescriptor<SyncState>()
        d.fetchLimit = 1
        if let state = (try? container.mainContext.fetch(d))?.first {
            status.conflicts = state.conflicts
            status.lastSyncAt = state.lastSyncAt
            status.lastError = state.lastError.isEmpty ? nil : state.lastError
        }
        refreshCounts()
    }

    private var context: ModelContext { container.mainContext }

    /// One sync at a time: a call during a sync runs one more right after it (a change just made).
    func syncNow() async {
        if let running {
            again = true
            await running.value
            return
        }
        signedOut = false
        let task = Task { @MainActor in
            repeat {
                self.again = false
                await self.run()
            } while self.again && self.status.online && !self.signedOut
            self.running = nil
        }
        running = task
        await task.value
    }

    /// Sync in a second; calls within that second make one sync.
    func scheduleSoon() {
        soon?.cancel()
        soon = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard !Task.isCancelled, let self else { return }
            self.soon = nil
            await self.syncNow()
        }
    }

    // MARK: One run

    private func run() async {
        status.syncing = true
        var unauthorized = false
        do {
            try await pushAll()
            try await pullAll()
            let open: ConflictList = try await api.send("GET", "/conflicts", body: nil)
            let state = SyncState.current(in: context)
            state.conflicts = open.conflicts.count
            state.lastSyncAt = Date()
            state.lastError = ""
            status.online = true
        } catch APIError.unauthorized {
            unauthorized = true
            SyncState.current(in: context).lastError = APIError.unauthorized.localizedDescription
        } catch APIError.offline {
            status.online = false
            SyncState.current(in: context).lastError = APIError.offline.localizedDescription
        } catch {
            status.online = true
            SyncState.current(in: context).lastError = error.localizedDescription
        }
        try? context.syncedSave()
        let state = SyncState.current(in: context)
        status.conflicts = state.conflicts
        status.lastSyncAt = state.lastSyncAt
        status.lastError = state.lastError.isEmpty ? nil : state.lastError
        status.syncing = false
        refreshCounts()
        if unauthorized {
            signedOut = true
            onSignedOut?()
        }
    }

    private func refreshCounts() {
        let refused = "error"
        status.pending = (try? context.fetchCount(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.state != refused }))) ?? 0
        status.failed = (try? context.fetchCount(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.state == refused }))) ?? 0
    }

    // MARK: Push

    /// Sends the queue in order, at most one op per record in a push: a later op of a record goes after the answer
    /// to the earlier one, with the version that answer gave.
    private func pushAll() async throws {
        while true {
            let refused = "error"
            let queue = try context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.state != refused },
                                                                   sortBy: [SortDescriptor(\.seq)]))
            var records = Set<String>()
            var batch: [SyncOp] = []
            for op in queue where batch.count < Self.batchSize {
                if records.insert("\(op.entityName):\(op.recordId)").inserted { batch.append(op) }
            }
            if batch.isEmpty { return }
            // Saved as «sending» before the request: a change made meanwhile is never merged into these.
            for op in batch { op.state = "sending" }
            try context.syncedSave()

            let response: PushResponse
            do {
                response = try await api.send("POST", "/sync/push", body: PushBody(ops: batch.map(PushOp.init)))
            } catch {
                // No answer: the ops stay queued with their opIds (the server ignores repeats).
                for op in batch {
                    op.state = "pending"
                    op.attempts += 1
                }
                try? context.syncedSave()
                throw error
            }

            var settled = Set<UUID>()
            for r in response.results {
                guard let opId = UUID(uuidString: r.opId), !settled.contains(opId),
                      let op = batch.first(where: { $0.opId == opId }) else { continue }
                switch r.status {
                case "rejected":
                    op.state = "error"
                    op.attempts += 1
                    op.errorText = (r.errors ?? []).joined(separator: ", ")
                case "applied", "partial", "conflict":
                    if let version = r.version {
                        let entityName = op.entityName
                        let recordId = op.recordId
                        let base = op.baseVersion
                        if let entity = SyncEntity(rawValue: entityName),
                           let shadow = try self.shadow(SyncShadow.makeKey(entity, recordId)) {
                            shadow.version = version
                        }
                        if r.status != "conflict" {
                            // Later changes of this record were made on top of this one.
                            let later = try context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate {
                                $0.entityName == entityName && $0.recordId == recordId && $0.opId != opId
                            }))
                            for o in later where o.baseVersion == base { o.baseVersion = version }
                        }
                    }
                    context.delete(op)
                default:
                    continue
                }
                settled.insert(opId)
            }
            // Sent without an answer of its own: queued again, as after a lost connection.
            for op in batch where !settled.contains(op.opId) {
                op.state = "pending"
                op.attempts += 1
            }
            try context.syncedSave()
            if settled.count < batch.count { return }
        }
    }

    // MARK: Pull

    private func pullAll() async throws {
        while true {
            let since = SyncState.current(in: context).cursor
            let page: PullPage = try await api.send("GET", "/sync/pull?since=\(since)", body: nil)
            try apply(page)
            if !page.more { return }
        }
    }

    /// One page, applied and saved in one go on the main actor (nothing else runs in between), queueing nothing.
    private func apply(_ page: PullPage) throws {
        // A change on a screen not saved yet is queued first, so the remote save below cannot take it unqueued.
        if context.hasChanges { try context.syncedSave() }
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        do {
            let queue = try context.fetch(FetchDescriptor<SyncOp>(sortBy: [SortDescriptor(\.seq)]))
            // Employees first: an attendance record links to its employee when it is inserted.
            let changes = page.changes.filter { $0.entity == SyncEntity.employee.rawValue }
                + page.changes.filter { $0.entity != SyncEntity.employee.rawValue }
            for change in changes { try apply(change, queue: queue) }
            SyncState.current(in: context).cursor = page.next
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    /// The server's record, with this device's unsent changes kept on top (`sync.ts` pullAll). The shadow becomes
    /// the record as it now is here, so the pending ops stay the only difference.
    private func apply(_ change: Change, queue: [SyncOp]) throws {
        guard let entity = SyncEntity(rawValue: change.entity), let id = UUID(uuidString: change.id) else { return }
        let mine = queue.filter { $0.entityName == change.entity && $0.recordId == id }
        let key = SyncShadow.makeKey(entity, id)
        let shadow: SyncShadow
        if let existing = try self.shadow(key) {
            shadow = existing
        } else {
            shadow = SyncShadow(key: key, fieldsJSON: SyncJSON.encode([:]))
            context.insert(shadow)
        }
        shadow.version = change.version

        if change.deleted || mine.contains(where: { $0.kind == "delete" }) {
            if !entity.isSingleton, let model = try SyncCodec.find(entity, id: id, in: context) {
                context.delete(model)
            }
            shadow.wasDeleted = true
            shadow.fieldsJSON = SyncJSON.encode([:])
            return
        }

        var fields = change.data ?? [:]
        for op in mine where op.kind == "upsert" { fields.merge(op.fields) { _, local in local } }
        shadow.wasDeleted = false
        if entity == .hoursMark, SyncCodec.isRemovedHoursMark(fields) {
            // The server keeps a removed mark with `hours: null`; here the row goes.
            if let model = try SyncCodec.find(.hoursMark, id: id, in: context) { context.delete(model) }
            shadow.fieldsJSON = SyncJSON.encode(fields)
            return
        }
        try SyncCodec.insert(entity, id: id, fields: fields, into: context)
        if let model = try SyncCodec.find(entity, id: id, in: context) {
            shadow.fieldsJSON = SyncJSON.encode(model.syncFields())
        }
    }

    private func shadow(_ key: String) throws -> SyncShadow? {
        var d = FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })
        d.fetchLimit = 1
        return try context.fetch(d).first
    }
}

// MARK: - Wire

private struct PushBody: Encodable {
    let ops: [PushOp]
}

private struct PushOp: Encodable {
    let opId: String
    let entity: String
    let recordId: String
    let kind: String
    let baseVersion: Int
    let fields: SyncFields?

    init(_ op: SyncOp) {
        opId = op.opId.uuidString.lowercased()
        entity = op.entityName
        recordId = op.recordId.uuidString.lowercased()
        kind = op.kind
        baseVersion = op.baseVersion
        fields = op.kind == "delete" ? nil : op.fields
    }
}

private struct PushResponse: Decodable {
    struct Result: Decodable {
        let opId: String
        let status: String
        let version: Int?
        let errors: [String]?
    }
    let results: [Result]
}

private struct Change: Decodable {
    let entity: String
    let id: String
    let version: Int
    let deleted: Bool
    let data: SyncFields?
}

private struct PullPage: Decodable {
    let changes: [Change]
    let next: Int
    let more: Bool
}

private struct ConflictList: Decodable {
    let conflicts: [SyncFields]
}
