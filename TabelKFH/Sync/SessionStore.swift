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

    @ObservationIgnored private let container: ModelContainer
    @ObservationIgnored private let tokens: TokenStore
    @ObservationIgnored private let api: APIClient
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
        api = APIClient(base: base, session: session, token: { tokens.token() })
        if tokens.token() != nil {
            farm = defaults.data(forKey: Self.farmKey).flatMap { try? JSONDecoder().decode(FarmInfoDTO.self, from: $0) }
            var d = FetchDescriptor<SyncState>()
            d.fetchLimit = 1
            let stored = (try? container.mainContext.fetch(d))?.first?.role ?? ""
            state = .signedIn(role: stored.isEmpty ? "viewer" : stored)
            makeEngine()
        }
    }

    /// `-uitest`: signed in as the owner, no network, no engine.
    private init(uiTestContainer container: ModelContainer) {
        self.container = container
        tokens = UITestTokens()
        defaults = .standard
        deviceName = "uitest"
        offline = true
        api = APIClient(token: { nil })
        state = .signedIn(role: "owner")
    }

    static func uiTest(container: ModelContainer) -> SessionStore { SessionStore(uiTestContainer: container) }

    private var context: ModelContext { container.mainContext }

    private func makeEngine() {
        let engine = SyncEngine(container: container, api: api)
        // Idempotent: a later run after a 401 calls it again. An engine of an earlier sign-in is ignored.
        engine.onSignedOut = { [weak self, weak engine] in
            guard let self, let engine, self.engine === engine else { return }
            self.finishSignOut()
        }
        self.engine = engine
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

    static func canEdit(role: String, _ entity: SyncEntity) -> Bool {
        switch role {
        case "owner", "manager": return true
        case "timesheet": return [.employee, .hoursMark, .attendanceRecord, .timesheetSnapshot].contains(entity)
        case "coal": return [.coalSettings, .deliveryLocation, .driver, .coalSale, .driverPayment, .coalWorkDay].contains(entity)
        case "livestock": return [.livestockCategory, .livestockOperation, .slaughterRecord, .livestockSettings].contains(entity)
        default: return false
        }
    }

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
        context.rollback()
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        for type in SchemaV6.models { try deleteAll(type) }
        try context.save()
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("agroDraft.") {
            defaults.removeObject(forKey: key)
        }
    }

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
            tokens.save(answer.token)
            me = try await api.send("GET", "/me", body: nil)
        } catch {
            tokens.delete()
            throw error
        }
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
            let _: OK? = try? await api.send("POST", "/auth/logout", body: nil)
            tokens.delete()
            // A sync on its way finishes first (its requests now get 401), so nothing lands after the wipe.
            var waited = 0
            while engine?.status.syncing == true, waited < 200 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                waited += 1
            }
        }
        finishSignOut()
    }

    /// Wipes the phone and shows the sign-in screen; safe to call again.
    private func finishSignOut() {
        tokens.delete()
        try? wipeLocalData()
        defaults.removeObject(forKey: Self.farmKey)
        farm = nil
        engine = nil
        state = .signedOut
    }

    // MARK: Sync

    /// A sync now, then `GET /me` (the role and the zone may have changed).
    func syncNow() async {
        guard case .signedIn = state, let engine else { return }
        await engine.syncNow()
        guard case .signedIn = state else { return }
        do {
            let me: Me = try await api.send("GET", "/me", body: nil)
            guard case .signedIn = state else { return }
            adoptZone(me.farm.timezone)
            try store(me)
            if state != .signedIn(role: me.role) { state = .signedIn(role: me.role) }
        } catch APIError.unauthorized {
            finishSignOut()
        } catch {
            // Offline or a server error: the role and the farm stay as they were.
        }
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

private struct UITestTokens: TokenStore {
    func save(_ token: String) {}
    func token() -> String? { nil }
    func delete() {}
}
