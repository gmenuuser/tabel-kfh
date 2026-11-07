import Foundation
import Network
import Observation
import SwiftData
import SwiftUI
import UIKit

/// The farm as `GET /me` gives it (server/src/app.ts).
struct FarmInfoDTO: Codable, Equatable {
    var id: String
    var name: String
    var region: String
    var managerName: String
    var timezone: String
}

/// The signed-in device: the token (Keychain), the role and the farm, the sync engine and what starts it.
/// A phone is either signed in to one farm (its store holds only that farm's data from the server) or signed out
/// (its store is empty): the old local-only mode is never shown.
@MainActor
@Observable
final class SessionStore {
    enum State: Equatable {
        case signedOut
        /// Signing in: the first download («Загрузка данных…»).
        case loading
        case signedIn(role: String)
    }

    private(set) var state: State = .signedOut
    private(set) var farm: FarmInfoDTO?
    /// The engine of this sign-in; nil when signed out, and under `-uitest`.
    private(set) var engine: SyncEngine?
    /// Shown on the sign-in screen after the server refused the device («Доступ к хозяйству отключён…»).
    var signOutNotice: String?

    @ObservationIgnored private let container: ModelContainer
    @ObservationIgnored private let tokens: TokenStore
    @ObservationIgnored private let api: APIClient
    @ObservationIgnored private let base: URL
    @ObservationIgnored private let urlSession: URLSession
    /// The token as the current engine may use it; closed when that engine stops, so a run of an earlier
    /// sign-in never sends the token of a later one.
    @ObservationIgnored private var engineGate: TokenGate?
    /// The requests of the current sign-in (`/me` after a sync, the owner's lists), through the engine's gate.
    @ObservationIgnored private var engineAPI: APIClient?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let deviceName: String
    @ObservationIgnored private let offline: Bool
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var monitor: NWPathMonitor?
    @ObservationIgnored private var networkWasDown = false

    /// UserDefaults key of the last `/me` farm (shown offline).
    static let farmKey = "session.farm"

    /// On launch: a stored token means signed in with the stored role (the sync then runs in the background);
    /// no token means the sign-in screen.
    init(container: ModelContainer, tokens: TokenStore = KeychainTokenStore(),
         base: URL = URL(string: "https://kfh.cubely.ru")!, session: URLSession = .shared,
         defaults: UserDefaults = .standard, deviceName: String = UIDevice.current.name) {
        self.container = container
        self.tokens = tokens
        self.defaults = defaults
        self.deviceName = deviceName
        self.offline = false
        self.base = base
        urlSession = session
        api = APIClient(base: base, session: session, token: { tokens.token() })
        if tokens.token() == nil {
            // Signed out, but a sign-in's rows are still here (a wipe that failed): wiped now. A phone that was never
            // signed in has no `SyncState` and keeps its own data (offered as a copy before the sign-in).
            var d = FetchDescriptor<SyncState>()
            d.fetchLimit = 1
            if ((try? container.mainContext.fetchCount(d)) ?? 0) > 0 { try? wipeLocalData() }
        } else {
            farm = defaults.data(forKey: Self.farmKey).flatMap { try? JSONDecoder().decode(FarmInfoDTO.self, from: $0) }
            var d = FetchDescriptor<SyncState>()
            d.fetchLimit = 1
            let stored = (try? container.mainContext.fetch(d))?.first?.role ?? ""
            state = .signedIn(role: stored.isEmpty ? "viewer" : stored)
            makeEngine()
        }
        guardWrites()
    }

    /// `-uitest`: signed in (as the owner, or `role`; nil: signed out), no network, no engine; the owner's lists
    /// are canned.
    private init(uiTestContainer container: ModelContainer, role: String?) {
        self.container = container
        tokens = UITestTokens()
        defaults = .standard
        deviceName = "uitest"
        offline = true
        base = URL(string: "https://kfh.cubely.ru")!
        urlSession = .shared
        api = APIClient(token: { nil })
        state = role.map { State.signedIn(role: $0) } ?? .signedOut
        if let role {
            farm = FarmInfoDTO(id: "uitest", name: "КФХ Зубахин", region: "Приаргунский район",
                               managerName: "Зубахин А. В.", timezone: "Asia/Chita")
        }
        guardWrites()
    }

    static func uiTest(container: ModelContainer, role: String? = "owner") -> SessionStore {
        SessionStore(uiTestContainer: container, role: role)
    }

    /// Saves of a change the role may not make are refused (`SyncedSave.allowed`), so no screen can queue one.
    private func guardWrites() {
        SyncedSave.allowed = { [weak self] entity in
            guard let self, case .signedIn(let role) = self.state else { return true }
            return Roles.canEdit(role: role, entity)
        }
    }

    private var context: ModelContext { container.mainContext }

    private func makeEngine() {
        stopEngine()
        let gate = TokenGate()
        let tokens = self.tokens
        let gated = APIClient(base: base, session: urlSession, token: { gate.isOpen ? tokens.token() : nil })
        let engine = SyncEngine(container: container, api: gated)
        // Idempotent: a later run after a 401 calls it again. An engine of an earlier sign-in is ignored.
        engine.onSignedOut = { [weak self, weak engine] in
            guard let self, let engine, self.engine === engine else { return }
            self.finishSignOut(revoked: true)
        }
        self.engine = engine
        engineGate = gate
        engineAPI = gated
    }

    /// The run on its way writes nothing more and sends no token again.
    private func stopEngine() {
        engineGate?.close()
        engineGate = nil
        engineAPI = nil
        engine?.stop()
    }

    // MARK: Role

    var role: String? {
        if case .signedIn(let role) = state { return role }
        return nil
    }

    /// May the signed-in user change records of `entity` (as `canWrite`, server/src/entities.ts).
    var canEdit: (SyncEntity) -> Bool {
        let role = self.role
        return { entity in role.map { Self.canEdit(role: $0, entity) } ?? false }
    }

    nonisolated static func canEdit(role: String, _ entity: SyncEntity) -> Bool { Roles.canEdit(role: role, entity) }

    var isOwner: Bool { role == "owner" }

    /// The big buttons of the main screen for this role.
    var destinations: [MainDestination] { role.map { Roles.destinations(role: $0) } ?? [] }

    /// What the sync shows (nothing to show under `-uitest`: all synced).
    var syncStatus: SyncStatus { engine?.status ?? SyncStatus() }

    // MARK: Store

    /// Changes not sent yet (refused ones not counted): «N изменений ещё не отправлены».
    var pendingChanges: Int {
        let refused = "error"
        return (try? context.fetchCount(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.state != refused }))) ?? 0
    }

    /// The phone has its own data (offer a copy to «Файлы» before signing in wipes it).
    var hasLocalData: Bool {
        ((try? context.fetchCount(FetchDescriptor<Employee>())) ?? 0) > 0
            || ((try? context.fetchCount(FetchDescriptor<CoalSale>())) ?? 0) > 0
            || ((try? context.fetchCount(FetchDescriptor<LivestockOperation>())) ?? 0) > 0
    }

    /// Deletes every row of every model (the data, the queue, the shadows, the sync state with its cursor) and the
    /// calculator drafts; queues nothing.
    func wipeLocalData() throws {
        // Reports written for sharing hold the farm's data too. They go first: a store wipe that fails is tried
        // again (on sign-out, and on the next launch while a sign-in's rows are left), the files with it.
        for directory in Self.exportDirectories where FileManager.default.fileExists(atPath: directory.path) {
            try? FileManager.default.removeItem(at: directory)
        }
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("agroDraft.") {
            defaults.removeObject(forKey: key)
        }
        context.rollback()
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        for type in SchemaV6.models { try deleteAll(type) }
        try context.save()
    }

    /// The folders the reports are written to for sharing (`tmp/Export`, `tmp/CoalExport`).
    static var exportDirectories: [URL] { [ExportFiles.directory, CoalExportFiles.directory] }

    private func deleteAll<T: PersistentModel>(_ type: T.Type) throws {
        for model in try context.fetch(FetchDescriptor<T>()) { context.delete(model) }
    }

    // MARK: Sign in

    /// `POST /auth/login` → token → `GET /me` (role, farm, zone) → wipe → the first download → signed in.
    /// A wrong password, no network or no farm: signed out, the phone unchanged, the error has the server's text.
    /// The first download failing (offline): signed in all the same, the data comes with a later sync.
    func signIn(login: String, password: String) async throws {
        guard !offline else { return }
        let me: Me
        do {
            let answer: LoginAnswer
            do {
                answer = try await api.send("POST", "/auth/login",
                                            body: LoginBody(login: login, password: password, deviceName: deviceName))
            } catch APIError.unauthorized {
                // The login's 401 is a wrong login or password, not a revoked device.
                throw APIError.server(status: 401, message: "Неверный логин или пароль")
            }
            do {
                try tokens.save(answer.token)
            } catch {
                throw SessionMessage("Не удалось сохранить вход на телефоне")
            }
            me = try await api.send("GET", "/me", body: nil)
        } catch {
            tokens.delete()
            throw error
        }
        signOutNotice = nil
        state = .loading
        do {
            adoptZone(me.farm.timezone)
            try wipeLocalData()
            try store(me)
        } catch {
            tokens.delete()
            state = .signedOut
            throw error
        }
        makeEngine()
        await engine?.syncNow()
        // A 401 during the download signed the phone out again.
        guard tokens.token() != nil else { throw APIError.unauthorized }
        state = .signedIn(role: me.role)
    }

    // MARK: Sign out

    /// `force: false` — the person chose «Выйти» (the UI has asked about unsent changes): the server is told
    /// (`POST /auth/logout`, no network is fine). `force: true` — the server already refused the device.
    /// Then everything on the phone is wiped and the sign-in screen shows.
    func signOut(force: Bool) async {
        if !force, tokens.token() != nil {
            // The run on its way stops first: nothing it fetches is written, the wipe below is final.
            stopEngine()
            let _: OK? = try? await api.send("POST", "/auth/logout", body: nil)
        }
        finishSignOut(revoked: force)
    }

    /// Stops the sync, wipes the phone and shows the sign-in screen; safe to call again. `revoked`: the server
    /// refused the device, the sign-in screen says so (with the unsent changes that were lost).
    private func finishSignOut(revoked: Bool = false) {
        let lost = pendingChanges
        let wasSignedOut = state == .signedOut
        stopEngine()
        tokens.delete()
        do {
            try wipeLocalData()
        } catch {
            // Once more; failing again, the next launch wipes (signed out with a sign-in's rows left).
            try? wipeLocalData()
        }
        if revoked && !wasSignedOut {
            signOutNotice = lost > 0 ? "Доступ к хозяйству отключён. Неотправленных изменений удалено: \(lost)."
                                     : "Доступ к хозяйству отключён."
        }
        defaults.removeObject(forKey: Self.farmKey)
        farm = nil
        engine = nil
        state = .signedOut
    }

    // MARK: Sync

    /// A sync now, then `GET /me` (the role and the zone may have changed).
    func syncNow() async {
        guard case .signedIn = state, let engine, !engine.stopped else { return }
        await engine.syncNow()
        await refreshMe(of: engine)
    }

    /// `GET /me` of the sign-in `engine` belongs to: nothing when that sign-in ended (a sign-out began, or another
    /// account signed in) before or while it was asked.
    private func refreshMe(of engine: SyncEngine) async {
        func current() -> Bool {
            guard case .signedIn = state else { return false }
            return self.engine === engine && !engine.stopped
        }
        guard current(), let api = engineAPI else { return }
        do {
            let me: Me = try await api.send("GET", "/me", body: nil)
            guard current() else { return }
            adoptZone(me.farm.timezone)
            try store(me)
            if state != .signedIn(role: me.role) { state = .signedIn(role: me.role) }
        } catch APIError.unauthorized {
            guard current() else { return }
            finishSignOut(revoked: true)
        } catch {
            // Offline or a server error: the role and the farm stay as they were.
        }
    }

    /// The next pull starts from the first change (after a server restore, or a refused change removed), so every
    /// record takes the server's version again.
    func resyncFromStart() async {
        guard case .signedIn = state else { return }
        SyncedSave.isApplyingRemote = true
        SyncState.current(in: context).cursor = 0
        try? context.save()
        SyncedSave.isApplyingRemote = false
        await syncNow()
    }

    /// «Удалить» in «Не принятые изменения» (`RefusedChanges.discard`), then a sync from the start.
    func deleteRefused(_ op: SyncOp) async throws {
        try RefusedChanges.discard(op, in: context)
        engine?.refreshStatus()
        await syncNow()
    }

    // MARK: Owner

    /// A request of this sign-in (the owner's lists: `/members`, `/devices`, `/conflicts`, `/backups`, `PATCH /farm`).
    /// A 401 signs the phone out, as the sync does. Under `-uitest`: canned answers.
    func send<T: Decodable>(_ method: String, _ path: String, body: Encodable? = nil) async throws -> T {
        if offline { return try JSONDecoder().decode(T.self, from: UITestServer.answer(method, path)) }
        guard case .signedIn = state, let engine, let api = engineAPI else { throw APIError.unauthorized }
        do {
            return try await api.send(method, path, body: body)
        } catch APIError.unauthorized {
            if self.engine === engine && !engine.stopped { finishSignOut(revoked: true) }
            throw APIError.unauthorized
        }
    }

    /// The owner renames the farm or its manager (`PATCH /farm`), then `/me` brings the new names here.
    func updateFarm(name: String, managerName: String) async throws {
        let _: OKAnswer = try await send("PATCH", "/farm", body: FarmPatch(name: name, managerName: managerName))
        if offline {
            farm?.name = name
            farm?.managerName = managerName
            let settings = FarmSettingsStore.current(in: context)
            SyncedSave.isApplyingRemote = true
            settings.farmName = name
            settings.managerName = managerName
            try? context.save()
            SyncedSave.isApplyingRemote = false
            return
        }
        guard let engine else { return }
        await refreshMe(of: engine)
    }

    /// Starts the sync triggers: the app becoming active (and every 60 s while it is), the network coming back,
    /// a change just queued.
    func startTriggers() {
        guard !offline, monitor == nil else { return }
        SyncedSave.onQueued = { [weak self] in
            guard let self, case .signedIn = self.state else { return }
            self.engine?.scheduleSoon()
        }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in self?.network(up: up) }
        }
        monitor.start(queue: DispatchQueue(label: "ru.kfh.tabel.network"))
        self.monitor = monitor
    }

    private func network(up: Bool) {
        defer { networkWasDown = !up }
        if up && networkWasDown { Task { await syncNow() } }
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        guard !offline else { return }
        timer?.cancel()
        timer = nil
        guard phase == .active else { return }
        timer = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.syncNow()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }

    // MARK: Farm

    private func adoptZone(_ identifier: String) {
        guard let zone = TimeZone(identifier: identifier) else { return }
        FarmTimeZone.adoptFromServer(zone, defaults: defaults)
    }

    /// The role into `SyncState`, the farm for display offline, its name and manager into `FarmSettings` (printed
    /// on the PDF/XLSX headers). Neither field is synced, so nothing is queued.
    private func store(_ me: Me) throws {
        if let data = try? JSONEncoder().encode(me.farm) { defaults.set(data, forKey: Self.farmKey) }
        if farm != me.farm { farm = me.farm }
        // A change on a screen not saved yet is queued first; the save below queues nothing.
        if context.hasChanges { try context.syncedSave() }
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        let sync = SyncState.current(in: context)
        if sync.role != me.role { sync.role = me.role }
        var d = FetchDescriptor<FarmSettings>()
        d.fetchLimit = 1
        let settings: FarmSettings
        if let existing = try context.fetch(d).first {
            settings = existing
        } else {
            settings = FarmSettings()
            context.insert(settings)
        }
        if settings.farmName != me.farm.name { settings.farmName = me.farm.name }
        if settings.managerName != me.farm.managerName { settings.managerName = me.farm.managerName }
        if context.hasChanges { try context.save() }
    }
}

// MARK: - Wire

private struct LoginBody: Encodable {
    let login: String
    let password: String
    let deviceName: String
    let platform = "ios"
}

private struct LoginAnswer: Decodable {
    let token: String
    let role: String
}

private struct Me: Decodable {
    let role: String
    let farm: FarmInfoDTO
}

private struct OK: Decodable {}

/// The owner's lists under `-uitest` (screenshots of «Помощники», «Устройства», «Конфликты», «Резервные копии»).
private enum UITestServer {
    static func answer(_ method: String, _ path: String) -> Data {
        let text: String
        switch (method, path) {
        case ("GET", "/members"):
            text = #"[{"id":"u1","login":null,"email":"zubakhin@example.ru","name":"Зубахин","role":"owner"},{"id":"u2","login":"anna","email":null,"name":"anna","role":"timesheet"},{"id":"u3","login":"sergey","email":null,"name":"sergey","role":"coal"}]"#
        case ("GET", "/devices"):
            text = #"[{"id":"d1","name":"iPhone владельца","platform":"ios","email":"zubakhin@example.ru","login":null,"lastSeenAt":"2026-11-06T02:00:00.000Z","revoked":false,"current":true},{"id":"d2","name":"iPhone Анны","platform":"ios","email":null,"login":"anna","lastSeenAt":"2026-11-05T09:30:00.000Z","revoked":false,"current":false},{"id":"d3","name":"Сайт","platform":"web","email":null,"login":"sergey","lastSeenAt":null,"revoked":true,"current":false}]"#
        case ("GET", "/conflicts"):
            text = #"{"conflicts":[{"id":"c1","entity":"employee","recordId":"r1","field":"position","serverValue":"Тракторист","clientValue":"Механизатор"}]}"#
        case ("GET", "/backups"):
            text = #"[{"id":"b1","sha256":"x","records":124,"size":"52000","created_at":"2026-11-05T19:00:00.000Z"}]"#
        case ("POST", "/backups"):
            text = #"{"id":"b2","records":124,"createdAt":"2026-11-06T02:00:00.000Z"}"#
        default:
            text = #"{"ok":true}"#
        }
        return Data(text.utf8)
    }
}

/// A message for the sign-in screen.
struct SessionMessage: LocalizedError {
    let errorDescription: String?
    init(_ text: String) { errorDescription = text }
}

/// Lets an engine's requests carry the token until its sign-in ends (read on URLSession's threads).
private final class TokenGate: @unchecked Sendable {
    private let lock = NSLock()
    private var open = true
    var isOpen: Bool { lock.lock(); defer { lock.unlock() }; return open }
    func close() { lock.lock(); open = false; lock.unlock() }
}

private struct UITestTokens: TokenStore {
    func save(_ token: String) throws {}
    func token() -> String? { nil }
    func delete() {}
}
