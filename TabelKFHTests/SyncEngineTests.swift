import XCTest
import SwiftData
@testable import TabelKFH

/// A fake server: every request of the test session goes to `handler` (called on URLSession's threads).
final class StubURLProtocol: URLProtocol {
    struct Request {
        let method: String
        let path: String
        let query: String
        let authorization: String?
        let body: Data?

        /// `ops` of a push body.
        var ops: [[String: Any]] {
            guard let body, let o = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return [] }
            return o["ops"] as? [[String: Any]] ?? []
        }
    }

    private static let lock = NSLock()
    private static var storedHandler: ((Request) throws -> (Int, Data))?
    private static var storedRequests: [Request] = []

    static var handler: ((Request) throws -> (Int, Data))? {
        get { lock.lock(); defer { lock.unlock() }; return storedHandler }
        set { lock.lock(); storedHandler = newValue; lock.unlock() }
    }

    static var requests: [Request] {
        lock.lock(); defer { lock.unlock() }
        return storedRequests
    }

    static func reset() {
        lock.lock()
        storedHandler = nil
        storedRequests = []
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let r = Request(method: request.httpMethod ?? "GET", path: request.url?.path ?? "",
                        query: request.url?.query ?? "", authorization: request.value(forHTTPHeaderField: "Authorization"),
                        body: Self.body(of: request))
        Self.lock.lock()
        Self.storedRequests.append(r)
        let h = Self.storedHandler
        Self.lock.unlock()
        do {
            guard let h else { throw URLError(.notConnectedToInternet) }
            let (status, data) = try h(r)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    /// URLSession hands the body to a protocol as a stream.
    private static func body(of request: URLRequest) -> Data? {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}

/// Pull pages handed out in order (thread safe).
private final class Pages {
    private let lock = NSLock()
    private var pages: [String]
    init(_ pages: [String]) { self.pages = pages }
    func next() -> String? {
        lock.lock(); defer { lock.unlock() }
        return pages.isEmpty ? nil : pages.removeFirst()
    }
}

/// Wire helpers, usable from URLSession's threads (not main-actor isolated).
private enum Fake {
    static func data(_ s: String) -> Data { Data(s.utf8) }

    static func json(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object) }

    /// Every op applied, version `version`.
    static func applyAll(version: Int) -> ([[String: Any]]) -> [[String: Any]] {
        { ops in ops.map { ["opId": $0["opId"] ?? "", "status": "applied", "version": version] } }
    }

    /// One record of a pull page.
    static func change(_ entity: SyncEntity, _ id: UUID, version: Int, deleted: Bool = false,
                       _ fields: SyncFields = [:]) -> String {
        let data = String(decoding: SyncJSON.encode(fields), as: UTF8.self)
        return #"{"entity":"\#(entity.rawValue)","id":"\#(id.uuidString.lowercased())","version":\#(version),"deleted":\#(deleted),"data":\#(data),"updatedAt":"2026-11-07T00:00:00.000Z"}"#
    }

    static func page(_ changes: [String], next: Int, more: Bool = false) -> String {
        #"{"changes":[\#(changes.joined(separator: ","))],"next":\#(next),"more":\#(more)}"#
    }
}

@MainActor
final class SyncEngineTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var engine: SyncEngine!

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        container.mainContext.autosaveEnabled = false
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
        StubURLProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let api = APIClient(base: URL(string: "https://kfh.test")!, session: URLSession(configuration: configuration),
                            token: { "tok" })
        engine = SyncEngine(container: container, api: api)
    }

    override func tearDown() {
        StubURLProtocol.reset()
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
    }

    // MARK: Helpers

    /// The server: push answers from `push`, pull pages in order (then an empty last page), `conflicts` open.
    private func serve(push: @escaping ([[String: Any]]) throws -> [[String: Any]] = Fake.applyAll(version: 1),
                       pulls: [String] = [], conflicts: Int = 0) {
        let pages = Pages(pulls)
        StubURLProtocol.handler = { r in
            switch r.path {
            case "/sync/push":
                return (200, try Fake.json(["results": try push(r.ops)]))
            case "/sync/pull":
                if let page = pages.next() { return (200, Fake.data(page)) }
                let since = r.query.replacingOccurrences(of: "since=", with: "")
                return (200, Fake.data(#"{"changes":[],"next":\#(Int(since) ?? 0),"more":false}"#))
            case "/conflicts":
                let list = (0..<conflicts).map { ["id": "c\($0)", "entity": "driver", "field": "phone",
                                                  "serverValue": "1", "clientValue": NSNull()] as [String: Any] }
                return (200, try Fake.json(["conflicts": list]))
            default:
                return (404, Fake.data(#"{"error":"Не найдено"}"#))
            }
        }
    }

    private func ops() throws -> [SyncOp] {
        try context.fetch(FetchDescriptor<SyncOp>(sortBy: [SortDescriptor(\.seq)]))
    }

    private func shadow(_ entity: SyncEntity, _ id: UUID) throws -> SyncShadow? {
        let key = SyncShadow.makeKey(entity, id)
        return try context.fetch(FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })).first
    }

    private func drivers() throws -> [Driver] {
        try context.fetch(FetchDescriptor<Driver>(sortBy: [SortDescriptor(\.lastName)]))
    }

    private func pushes() -> [StubURLProtocol.Request] { StubURLProtocol.requests.filter { $0.path == "/sync/push" } }

    private func localDriver(_ lastName: String = "Мунгалов") throws -> Driver {
        let d = Driver(lastName: lastName, firstName: "Сергей", createdAt: Date(timeIntervalSince1970: 1_800_000_000))
        context.insert(d)
        try context.syncedSave()
        return d
    }

    /// A driver saved here and already on the server (version 1).
    private func sentDriver() async throws -> Driver {
        let d = try localDriver()
        serve()
        await engine.syncNow()
        XCTAssertEqual(try ops().count, 0)
        StubURLProtocol.reset()
        return d
    }

    // MARK: Tests

    func testPushThenPull() async throws {
        let d = try localDriver()
        let employeeId = UUID()
        serve(pulls: [Fake.page([
            Fake.change(.driver, d.id, version: 1, d.syncFields()),
            Fake.change(.employee, employeeId, version: 3, ["lastName": .string("Зубахин"), "firstName": .string("Иван"),
                                                             "hireDate": .string("2025-03-01T00:00:00.000Z")]),
        ], next: 7)])
        await engine.syncNow()

        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(SyncState.current(in: context).cursor, 7)
        XCTAssertEqual(try shadow(.driver, d.id)?.version, 1)
        let e = try XCTUnwrap(try context.fetch(FetchDescriptor<Employee>()).first)
        XCTAssertEqual(e.id, employeeId)
        XCTAssertEqual(e.lastName, "Зубахин")
        XCTAssertEqual(try shadow(.employee, employeeId)?.version, 3)

        let requests = StubURLProtocol.requests
        XCTAssertEqual(requests.map { $0.path }, ["/sync/push", "/sync/pull", "/conflicts"])
        XCTAssertEqual(requests[0].method, "POST")
        XCTAssertEqual(requests[1].query, "since=0")
        XCTAssertTrue(requests.allSatisfy { $0.authorization == "Bearer tok" })
        let op = try XCTUnwrap(requests[0].ops.first)
        XCTAssertEqual(op["entity"] as? String, "driver")
        XCTAssertEqual(op["recordId"] as? String, d.id.uuidString.lowercased())
        XCTAssertEqual(op["kind"] as? String, "upsert")
        XCTAssertEqual(op["baseVersion"] as? Int, 0)
        XCTAssertEqual((op["fields"] as? [String: Any])?["lastName"] as? String, "Мунгалов")

        XCTAssertTrue(engine.status.online)
        XCTAssertFalse(engine.status.syncing)
        XCTAssertEqual(engine.status.pending, 0)
        XCTAssertEqual(engine.status.failed, 0)
        XCTAssertNotNil(engine.status.lastSyncAt)
        XCTAssertNil(engine.status.lastError)
    }

    func testNetworkDropKeepsQueueAndSameOpId() async throws {
        _ = try localDriver()
        let opId = try XCTUnwrap(try ops().first).opId
        StubURLProtocol.handler = { _ in throw URLError(.networkConnectionLost) }
        await engine.syncNow()

        let kept = try ops()
        XCTAssertEqual(kept.count, 1)
        XCTAssertEqual(kept.first?.opId, opId)
        XCTAssertEqual(kept.first?.state, "pending")
        XCTAssertEqual(kept.first?.attempts, 1)
        XCTAssertFalse(engine.status.online)
        XCTAssertEqual(engine.status.pending, 1)
        XCTAssertEqual(SyncState.current(in: context).cursor, 0)

        StubURLProtocol.reset()
        serve()
        await engine.syncNow()
        XCTAssertEqual(pushes().first?.ops.first?["opId"] as? String, opId.uuidString.lowercased())
        XCTAssertEqual(try ops().count, 0)
        XCTAssertTrue(engine.status.online)
        XCTAssertEqual(engine.status.pending, 0)
    }

    func testRejectedGoesToFailed() async throws {
        _ = try localDriver()
        serve(push: { ops in ops.map { ["opId": $0["opId"] ?? "", "status": "rejected",
                                         "errors": ["Нет прав на изменение этих данных", "Фамилия обязательна"]] } })
        await engine.syncNow()

        let op = try XCTUnwrap(try ops().first)
        XCTAssertEqual(op.state, "error")
        XCTAssertEqual(op.errorText, "Нет прав на изменение этих данных, Фамилия обязательна")
        XCTAssertEqual(op.attempts, 1)
        XCTAssertEqual(engine.status.failed, 1)
        XCTAssertEqual(engine.status.pending, 0)

        // A refused change is not sent again by itself.
        await engine.syncNow()
        XCTAssertEqual(pushes().count, 1)
        XCTAssertEqual(try ops().count, 1)
    }

    func testConflictRemovesOpAndCountsConflict() async throws {
        let d = try await sentDriver()
        d.phone = "+7 914 000-00-00"
        try context.syncedSave()
        serve(push: { ops in ops.map { ["opId": $0["opId"] ?? "", "status": "conflict", "version": 3,
                                         "conflicts": ["phone"]] } }, conflicts: 1)
        await engine.syncNow()

        XCTAssertEqual(pushes().first?.ops.first?["baseVersion"] as? Int, 1)
        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(try shadow(.driver, d.id)?.version, 3)
        XCTAssertEqual(engine.status.conflicts, 1)
        XCTAssertEqual(SyncState.current(in: context).conflicts, 1)
    }

    func testLaterOpOfSameRecordMovesToReturnedVersion() async throws {
        let d = try localDriver()
        StubURLProtocol.handler = { _ in throw URLError(.timedOut) }
        await engine.syncNow()
        // The first change went out once: this one is a new op made on top of it (base 0).
        d.phone = "123"
        try context.syncedSave()
        XCTAssertEqual(try ops().map { $0.baseVersion }, [0, 0])

        StubURLProtocol.reset()
        var version = 0
        serve(push: { ops in
            ops.map { op in
                version += 1
                return ["opId": op["opId"] ?? "", "status": "applied", "version": version]
            }
        })
        await engine.syncNow()

        let p = pushes()
        XCTAssertEqual(p.count, 2)
        XCTAssertEqual(p.map { $0.ops.count }, [1, 1])
        XCTAssertEqual(p[1].ops.first?["baseVersion"] as? Int, 1)
        XCTAssertEqual(((p[1].ops.first?["fields"]) as? [String: Any])?["phone"] as? String, "123")
        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(try shadow(.driver, d.id)?.version, 2)
    }

    func testPullMoreLoops() async throws {
        let a = UUID(), b = UUID()
        serve(pulls: [
            Fake.page([Fake.change(.driver, a, version: 1, ["lastName": .string("Аксёнов")])], next: 5, more: true),
            Fake.page([Fake.change(.driver, b, version: 1, ["lastName": .string("Белов")])], next: 9, more: true),
            Fake.page([], next: 12, more: false),
        ])
        await engine.syncNow()

        let pulls = StubURLProtocol.requests.filter { $0.path == "/sync/pull" }
        XCTAssertEqual(pulls.map { $0.query }, ["since=0", "since=5", "since=9"])
        XCTAssertEqual(SyncState.current(in: context).cursor, 12)
        XCTAssertEqual(try drivers().map { $0.lastName }, ["Аксёнов", "Белов"])
        XCTAssertEqual(try ops().count, 0)
    }

    /// A change made here and not yet confirmed stays on top of what the server sends (as the site does).
    func testPullKeepsPendingLocalFields() async throws {
        let d = try await sentDriver()
        d.phone = "local"
        try context.syncedSave()
        var server = d.syncFields()
        server["phone"] = .string("server")
        server["comment"] = .string("с сайта")
        // The server does not answer this op (it stays queued), then sends the site's version of the record.
        serve(push: { _ in [] }, pulls: [Fake.page([Fake.change(.driver, d.id, version: 2, server)], next: 4)])
        await engine.syncNow()

        let driver = try XCTUnwrap(try drivers().first)
        XCTAssertEqual(driver.phone, "local")
        XCTAssertEqual(driver.comment, "с сайта")
        let queued = try ops()
        XCTAssertEqual(queued.count, 1)
        XCTAssertEqual(queued.first?.fields, ["phone": .string("local")])
        let s = try XCTUnwrap(try shadow(.driver, d.id))
        XCTAssertEqual(s.version, 2)
        XCTAssertEqual(s.fields, driver.syncFields())
        // The pending op stays the only difference: saving again queues nothing new.
        try context.syncedSave()
        XCTAssertEqual(try ops().count, 1)
        XCTAssertEqual(SyncState.current(in: context).cursor, 4)
    }

    func testPullDeleteRemovesRecord() async throws {
        let d = try await sentDriver()
        let id = d.id
        serve(pulls: [Fake.page([Fake.change(.driver, id, version: 2, deleted: true)], next: 3)])
        await engine.syncNow()

        XCTAssertEqual(try drivers().count, 0)
        let s = try XCTUnwrap(try shadow(.driver, id))
        XCTAssertTrue(s.wasDeleted)
        XCTAssertEqual(s.version, 2)
        XCTAssertEqual(try ops().count, 0)
    }

    func testPullNullHoursRemovesMark() async throws {
        let created = Date(timeIntervalSince1970: 1_800_000_000)
        let mark = WorkHoursMark(employeeId: UUID(), day: Calendar.farm.date(day: 5, month: 11, year: 2026), hours: 8,
                                 createdAt: created, updatedAt: created)
        context.insert(mark)
        try context.syncedSave()
        let id = mark.id
        var removed = mark.syncFields()
        removed["hours"] = .null
        serve(pulls: [Fake.page([Fake.change(.hoursMark, id, version: 2, removed)], next: 2)])
        await engine.syncNow()

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<WorkHoursMark>()), 0)
        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(try shadow(.hoursMark, id)?.version, 2)
        XCTAssertFalse(try XCTUnwrap(try shadow(.hoursMark, id)).wasDeleted)
    }

    func testUnauthorizedSignsOut() async throws {
        _ = try localDriver()
        var signedOut = 0
        engine.onSignedOut = { signedOut += 1 }
        StubURLProtocol.handler = { _ in (401, Fake.data(#"{"error":"Нужно войти заново: устройство не подключено или доступ отозван"}"#)) }
        await engine.syncNow()

        XCTAssertEqual(signedOut, 1)
        XCTAssertEqual(try ops().count, 1)
        XCTAssertEqual(try ops().first?.state, "pending")
        XCTAssertEqual(StubURLProtocol.requests.count, 1)
    }

    func testServerErrorKeepsQueueAndShowsMessage() async throws {
        _ = try localDriver()
        StubURLProtocol.handler = { _ in (500, Fake.data(#"{"error":"Сбой сервера"}"#)) }
        await engine.syncNow()

        XCTAssertEqual(try ops().count, 1)
        XCTAssertTrue(engine.status.online)
        XCTAssertEqual(engine.status.lastError, "Сбой сервера")
    }

    func testPulledRecordsAreNotQueued() async throws {
        var queued = 0
        SyncedSave.onQueued = { queued += 1 }
        let employeeId = UUID(), recordId = UUID(), driverId = UUID()
        let day = "2026-11-05T00:00:00.000Z"
        // The attendance record comes before its employee in the page: it is still linked.
        serve(pulls: [Fake.page([
            Fake.change(.attendanceRecord, recordId, version: 2,
                        ["employeeId": .string(employeeId.uuidString.lowercased()), "day": .string(day),
                         "status": .string("Я"), "createdAt": .string(day), "updatedAt": .string(day)]),
            Fake.change(.employee, employeeId, version: 1, ["lastName": .string("Зубахин"), "firstName": .string("Иван"),
                                                             "hireDate": .string("2025-03-01T00:00:00.000Z")]),
            Fake.change(.driver, driverId, version: 4, ["lastName": .string("Мунгалов"), "phone": .string("1")]),
        ], next: 8)])
        await engine.syncNow()

        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(queued, 0)
        XCTAssertEqual(pushes().count, 0)
        let record = try XCTUnwrap(try context.fetch(FetchDescriptor<AttendanceRecord>()).first)
        XCTAssertEqual(record.employee?.id, employeeId)

        // A later edit here sends only what changed, on top of the pulled version.
        let driver = try XCTUnwrap(try drivers().first)
        driver.phone = "2"
        try context.syncedSave()
        let op = try XCTUnwrap(try ops().first)
        XCTAssertEqual(try ops().count, 1)
        XCTAssertEqual(op.fields, ["phone": .string("2")])
        XCTAssertEqual(op.baseVersion, 4)
    }
}
