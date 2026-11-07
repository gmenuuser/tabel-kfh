import XCTest
import SwiftData
@testable import TabelKFH

/// The token kept in memory (the CI simulator runs the app unsigned, without a Keychain).
private final class MemoryTokens: TokenStore {
    var stored: String?
    var failSave = false
    func save(_ token: String) throws {
        if failSave { throw CocoaError(.fileWriteUnknown) }
        stored = token
    }
    func token() -> String? { stored }
    func delete() { stored = nil }
}

/// The farm server of these tests (thread safe: answered on URLSession's threads).
private final class FakeServer {
    private let lock = NSLock()
    private var pulls: [String]
    private var timezone: String
    private var revoked = false

    init(pulls: [String] = [], timezone: String = "Asia/Chita") {
        self.pulls = pulls
        self.timezone = timezone
    }

    func revoke() { lock.lock(); revoked = true; lock.unlock() }

    func addPull(_ page: String) { lock.lock(); pulls.append(page); lock.unlock() }

    func handle(_ r: StubURLProtocol.Request) throws -> (Int, Data) {
        lock.lock(); defer { lock.unlock() }
        if revoked && r.path != "/auth/login" { return (401, Data(#"{"error":"Нужно войти заново"}"#.utf8)) }
        switch r.path {
        case "/auth/login":
            let body = (r.body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
            guard body["login"] as? String == "ivan", body["password"] as? String == "secret-pass",
                  body["platform"] as? String == "ios", body["deviceName"] is String else {
                return (401, Data(#"{"error":"Неверный логин или пароль"}"#.utf8))
            }
            return (200, Data(#"{"token":"tok-1","deviceId":"d1","farmId":"f1","userId":"u1","role":"owner","farms":[{"farmId":"f1","role":"owner"}]}"#.utf8))
        case "/me":
            return (200, Data(#"{"userId":"u1","deviceId":"d1","role":"owner","farm":{"id":"f1","name":"КФХ Зубахин А.В.","region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"\#(timezone)"}}"#.utf8))
        case "/auth/logout":
            return (200, Data(#"{"ok":true}"#.utf8))
        case "/sync/push":
            let ops = (r.body.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any])?["ops"] as? [[String: Any]] ?? []
            let results = ops.map { ["opId": $0["opId"] ?? "", "status": "applied", "version": 1] }
            return (200, try JSONSerialization.data(withJSONObject: ["results": results]))
        case "/sync/pull":
            if !pulls.isEmpty { return (200, Data(pulls.removeFirst().utf8)) }
            let since = Int(r.query.replacingOccurrences(of: "since=", with: "")) ?? 0
            return (200, Data(#"{"changes":[],"next":\#(since),"more":false}"#.utf8))
        case "/conflicts":
            return (200, Data(#"{"conflicts":[]}"#.utf8))
        default:
            return (404, Data(#"{"error":"Не найдено"}"#.utf8))
        }
    }

    /// A pull page with one employee.
    static func employeePage(_ id: UUID, lastName: String, next: Int) -> String {
        #"{"changes":[{"entity":"employee","id":"\#(id.uuidString.lowercased())","version":1,"deleted":false,"data":{"lastName":"\#(lastName)","firstName":"Иван","hireDate":"2025-03-01T00:00:00.000Z"},"updatedAt":"2026-11-07T00:00:00.000Z"}],"next":\#(next),"more":false}"#
    }
}

@MainActor
final class SessionStoreTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var tokens: MemoryTokens!
    private var defaults: UserDefaults!
    private var suiteName = ""
    private var savedZone: TimeZone!

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        container.mainContext.autosaveEnabled = false
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
        StubURLProtocol.reset()
        tokens = MemoryTokens()
        suiteName = "SessionStoreTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        savedZone = Calendar.farm.timeZone
    }

    override func tearDown() {
        StubURLProtocol.reset()
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
        Calendar.useFarmTimeZone(savedZone)
        UserDefaults().removePersistentDomain(forName: suiteName)
    }

    // MARK: Helpers

    private func makeSession(_ server: FakeServer) -> SessionStore {
        StubURLProtocol.handler = { try server.handle($0) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return SessionStore(container: container, tokens: tokens, base: URL(string: "https://kfh.test")!,
                            session: URLSession(configuration: configuration), defaults: defaults,
                            deviceName: "iPhone теста")
    }

    private func employees() throws -> [Employee] { try context.fetch(FetchDescriptor<Employee>()) }

    private func localEmployee(_ lastName: String) throws {
        context.insert(Employee(lastName: lastName, firstName: "Пётр", hireDate: Date(timeIntervalSince1970: 1_700_000_000)))
        try context.syncedSave()
    }

    private func requests(_ path: String) -> [StubURLProtocol.Request] {
        StubURLProtocol.requests.filter { $0.path == path }
    }

    // MARK: Tests

    func testSignInWipesAndLoads() async throws {
        try localEmployee("Старый")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 1)
        defaults.set("{}", forKey: "agroDraft.seeding")
        let pulled = UUID()
        let session = makeSession(FakeServer(pulls: [FakeServer.employeePage(pulled, lastName: "Новый", next: 3)]))
        XCTAssertEqual(session.state, .signedOut)
        XCTAssertTrue(session.hasLocalData)

        try await session.signIn(login: "ivan", password: "secret-pass")

        XCTAssertEqual(session.state, .signedIn(role: "owner"))
        XCTAssertEqual(try employees().map(\.lastName), ["Новый"])
        XCTAssertEqual(try employees().first?.id, pulled)
        // The old employee was wiped, not sent.
        XCTAssertEqual(requests("/sync/push").count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 0)
        XCTAssertEqual(tokens.stored, "tok-1")
        XCTAssertEqual(requests("/me").first?.authorization, "Bearer tok-1")
        XCTAssertEqual(SyncState.current(in: context).cursor, 3)
        XCTAssertEqual(SyncState.current(in: context).role, "owner")
        XCTAssertNil(defaults.object(forKey: "agroDraft.seeding"))
        XCTAssertEqual(session.farm?.name, "КФХ Зубахин А.В.")
        XCTAssertEqual(FarmSettingsStore.current(in: context).farmName, "КФХ Зубахин А.В.")
        XCTAssertEqual(FarmSettingsStore.current(in: context).managerName, "Зубахин А. В.")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 0)
    }

    func testSignInStoresTimezone() async throws {
        let session = makeSession(FakeServer(timezone: "Asia/Chita"))
        Calendar.useFarmTimeZone(TimeZone(identifier: "Europe/Moscow")!)

        try await session.signIn(login: "ivan", password: "secret-pass")

        XCTAssertEqual(Calendar.farm.timeZone.identifier, "Asia/Chita")
        XCTAssertEqual(FarmTimeZone(defaults: defaults).stored?.identifier, "Asia/Chita")
    }

    func testWrongPasswordShowsServerMessage() async throws {
        try localEmployee("Свой")
        let session = makeSession(FakeServer())
        do {
            try await session.signIn(login: "ivan", password: "wrong")
            XCTFail("signed in with a wrong password")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Неверный логин или пароль")
        }
        XCTAssertEqual(session.state, .signedOut)
        XCTAssertNil(tokens.stored)
        // Nothing was wiped.
        XCTAssertEqual(try employees().map(\.lastName), ["Свой"])
    }

    func testSignOutWipesEverything() async throws {
        let session = makeSession(FakeServer(pulls: [FakeServer.employeePage(UUID(), lastName: "Новый", next: 3)]))
        try await session.signIn(login: "ivan", password: "secret-pass")
        // An unsent change: the queue is not empty.
        try localEmployee("Не отправлен")
        XCTAssertEqual(session.pendingChanges, 1)
        XCTAssertGreaterThan(try context.fetchCount(FetchDescriptor<SyncShadow>()), 0)

        await session.signOut(force: false)

        XCTAssertEqual(session.state, .signedOut)
        XCTAssertEqual(requests("/auth/logout").count, 1)
        XCTAssertEqual(requests("/auth/logout").first?.authorization, "Bearer tok-1")
        XCTAssertNil(tokens.stored)
        XCTAssertEqual(try employees().count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncShadow>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncState>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FarmSettings>()), 0)
        XCTAssertNil(session.farm)
        XCTAssertEqual(session.pendingChanges, 0)
    }

    func testRevokedDeviceSignsOutWithoutAsking() async throws {
        let server = FakeServer(pulls: [FakeServer.employeePage(UUID(), lastName: "Новый", next: 3)])
        let session = makeSession(server)
        try await session.signIn(login: "ivan", password: "secret-pass")
        try localEmployee("Не отправлен")
        server.revoke()

        await session.syncNow()

        XCTAssertEqual(session.state, .signedOut)
        XCTAssertNil(tokens.stored)
        XCTAssertEqual(try employees().count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncShadow>()), 0)
        // No logout request: the server already refused the device.
        XCTAssertEqual(requests("/auth/logout").count, 0)
        XCTAssertEqual(session.signOutNotice, "Доступ к хозяйству отключён. Неотправленных изменений удалено: 1.")

        // Called again by a later run: nothing breaks.
        await session.syncNow()
        await session.signOut(force: true)
        XCTAssertEqual(session.state, .signedOut)
    }

    func testSignOutStopsTheRunInFlight() async throws {
        let server = FakeServer(pulls: [FakeServer.employeePage(UUID(), lastName: "Первый", next: 3)])
        let session = makeSession(server)
        try await session.signIn(login: "ivan", password: "secret-pass")
        // The next pull is answered only after the sign-out, with a page of the farm.
        server.addPull(FakeServer.employeePage(UUID(), lastName: "Поздний", next: 9))
        let release = DispatchSemaphore(value: 0)
        let asked = expectation(description: "pull asked")
        StubURLProtocol.hold = { r in
            guard r.path == "/sync/pull" else { return nil }
            asked.fulfill()
            return release
        }
        let sync = Task { await session.syncNow() }
        await fulfillment(of: [asked], timeout: 10)

        await session.signOut(force: false)
        StubURLProtocol.hold = nil
        release.signal()
        await sync.value
        try await Task.sleep(nanoseconds: 300_000_000)

        XCTAssertEqual(session.state, .signedOut)
        XCTAssertNil(session.signOutNotice)
        XCTAssertEqual(try employees().count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncState>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncShadow>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncOp>()), 0)
    }

    func testWipeRemovesExportedFiles() throws {
        let session = makeSession(FakeServer())
        let manager = FileManager.default
        for directory in SessionStore.exportDirectories {
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data("табель".utf8).write(to: directory.appendingPathComponent("Табель.pdf"))
        }
        XCTAssertEqual(SessionStore.exportDirectories.map(\.lastPathComponent).sorted(), ["CoalExport", "Export"])

        try session.wipeLocalData()

        for directory in SessionStore.exportDirectories {
            XCTAssertFalse(manager.fileExists(atPath: directory.path), directory.path)
        }
    }

    func testLaunchWithoutTokenWipesLeftovers() throws {
        // A sign-in's rows with no token (a wipe that failed): wiped on launch.
        try localEmployee("Остался")
        SyncState.current(in: context).cursor = 7
        try context.save()

        let session = makeSession(FakeServer())

        XCTAssertEqual(session.state, .signedOut)
        XCTAssertEqual(try employees().count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SyncState>()), 0)
    }

    func testTokenNotSavedShowsMessage() async throws {
        tokens.failSave = true
        let session = makeSession(FakeServer())
        do {
            try await session.signIn(login: "ivan", password: "secret-pass")
            XCTFail("signed in without a stored token")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Не удалось сохранить вход на телефоне")
        }
        XCTAssertEqual(session.state, .signedOut)
    }

    func testLaunchWithTokenIsSignedInWithStoredRole() throws {
        tokens.stored = "tok-1"
        SyncState.current(in: context).role = "coal"
        try context.save()

        let session = makeSession(FakeServer())

        XCTAssertEqual(session.state, .signedIn(role: "coal"))
        XCTAssertTrue(session.canEdit(.coalSale))
        XCTAssertFalse(session.canEdit(.employee))
        XCTAssertFalse(session.canEdit(.farmSettings))
    }

    func testLaunchWithoutTokenIsSignedOut() {
        let session = makeSession(FakeServer())
        XCTAssertEqual(session.state, .signedOut)
        XCTAssertFalse(session.canEdit(.employee))
    }

    func testCanEditByRole() {
        let all = SyncEntity.allCases
        XCTAssertTrue(all.allSatisfy { SessionStore.canEdit(role: "owner", $0) })
        XCTAssertTrue(all.allSatisfy { SessionStore.canEdit(role: "manager", $0) })
        XCTAssertFalse(all.contains { SessionStore.canEdit(role: "viewer", $0) })
        XCTAssertEqual(Set(all.filter { SessionStore.canEdit(role: "timesheet", $0) }),
                       [.employee, .hoursMark, .attendanceRecord, .timesheetSnapshot])
        XCTAssertEqual(Set(all.filter { SessionStore.canEdit(role: "coal", $0) }),
                       [.coalSettings, .deliveryLocation, .driver, .coalSale, .driverPayment, .coalWorkDay])
        XCTAssertEqual(Set(all.filter { SessionStore.canEdit(role: "livestock", $0) }),
                       [.livestockCategory, .livestockOperation, .slaughterRecord, .livestockSettings])
    }
}
