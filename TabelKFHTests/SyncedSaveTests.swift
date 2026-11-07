import XCTest
import SwiftData
@testable import TabelKFH

/// Every local change also goes to the send queue in the same save (`context.syncedSave()`).
@MainActor
final class SyncedSaveTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
    }

    override func tearDown() {
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
    }

    private func ops() throws -> [SyncOp] {
        try context.fetch(FetchDescriptor<SyncOp>(sortBy: [SortDescriptor(\.createdAt)]))
    }

    private func fields(_ op: SyncOp) throws -> SyncFields {
        try JSONDecoder().decode(SyncFields.self, from: op.fieldsJSON)
    }

    private func shadow(_ entity: SyncEntity, _ id: UUID) throws -> SyncShadow? {
        let key = "\(entity.rawValue):\(id.uuidString.lowercased())"
        return try context.fetch(FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })).first
    }

    /// The queue went to the server once: a later change cannot be merged into it.
    private func markSent() throws {
        for op in try ops() {
            op.state = "sending"
            op.attempts = 1
        }
        try context.save()
    }

    private func driver() throws -> Driver {
        let d = Driver(lastName: "Мунгалов", firstName: "Сергей", createdAt: Date(timeIntervalSince1970: 1_800_000_000))
        context.insert(d)
        try context.syncedSave()
        return d
    }

    func testInsertQueuesAllFields() throws {
        var queued = 0
        SyncedSave.onQueued = { queued += 1 }
        let d = try driver()
        let all = try ops()
        XCTAssertEqual(all.count, 1)
        let op = try XCTUnwrap(all.first)
        XCTAssertEqual(op.entity, "driver")
        XCTAssertEqual(op.recordId, d.id)
        XCTAssertEqual(op.kind, "upsert")
        XCTAssertEqual(op.baseVersion, 0)
        XCTAssertEqual(op.state, "pending")
        XCTAssertEqual(op.attempts, 0)
        XCTAssertEqual(try fields(op), d.syncFields())
        let s = try XCTUnwrap(try shadow(.driver, d.id))
        XCTAssertEqual(s.version, 0)
        XCTAssertFalse(s.deleted)
        XCTAssertEqual(try JSONDecoder().decode(SyncFields.self, from: s.fieldsJSON), d.syncFields())
        XCTAssertEqual(queued, 1)
    }

    func testEditQueuesOnlyChangedFields() throws {
        let d = try driver()
        try markSent()
        let first = try XCTUnwrap(try ops().first)
        d.phone = "+7 914 000-00-00"
        try context.syncedSave()
        let all = try ops()
        XCTAssertEqual(all.count, 2)
        let op = try XCTUnwrap(all.last)
        XCTAssertNotEqual(op.opId, first.opId)
        XCTAssertEqual(try fields(op), ["phone": .string("+7 914 000-00-00")])
        XCTAssertEqual(op.kind, "upsert")
        XCTAssertEqual(op.state, "pending")

        // The same value again changes nothing.
        d.phone = "+7 914 000-00-00"
        try context.syncedSave()
        XCTAssertEqual(try ops().count, 2)
    }

    func testTwoEditsBeforeSendMergeIntoOneOp() throws {
        let d = try driver()
        try markSent()
        d.phone = "123"
        try context.syncedSave()
        d.comment = "по средам"
        try context.syncedSave()
        let all = try ops()
        XCTAssertEqual(all.count, 2, "the sent op is not merged into, the two edits make one op")
        XCTAssertEqual(try fields(try XCTUnwrap(all.last)), ["phone": .string("123"), "comment": .string("по средам")])
        XCTAssertEqual(try fields(try XCTUnwrap(all.first)), d.syncFields().merging(["phone": .string(""),
                                                                                      "comment": .string("")]) { $1 })
    }

    func testDeleteQueuesDelete() throws {
        let d = try driver()
        let id = d.id
        try markSent()
        context.delete(d)
        try context.syncedSave()
        let op = try XCTUnwrap(try ops().last)
        XCTAssertEqual(try ops().count, 2)
        XCTAssertEqual(op.kind, "delete")
        XCTAssertEqual(op.entity, "driver")
        XCTAssertEqual(op.recordId, id)
        XCTAssertEqual(op.state, "pending")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Driver>()), 0)
        XCTAssertEqual(try shadow(.driver, id)?.deleted, true)
    }

    func testRemovedHoursMarkQueuesNull() throws {
        let e = Employee(lastName: "Зубахин", firstName: "Иван", hireDate: calendar.date(day: 1, month: 1, year: 2026))
        context.insert(e)
        try context.syncedSave()
        let day = calendar.date(day: 5, month: 11, year: 2026)
        let hours = HoursService(context: context, calendar: calendar, now: { day.addingTimeInterval(3600) })
        try hours.set(8, for: e, on: day)
        try markSent()
        try hours.set(nil, for: e, on: day)
        let op = try XCTUnwrap(try ops().last)
        XCTAssertEqual(op.entity, "hoursMark")
        XCTAssertEqual(op.recordId, StableId.hoursMark(employeeId: e.id, day: day, calendar: calendar))
        XCTAssertEqual(op.kind, "upsert")
        XCTAssertEqual(try fields(op), ["hours": .null])
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<WorkHoursMark>()), 0)
    }

    func testRemoteApplyQueuesNothing() throws {
        var queued = 0
        SyncedSave.onQueued = { queued += 1 }
        SyncedSave.isApplyingRemote = true
        _ = try driver()
        SyncedSave.isApplyingRemote = false
        XCTAssertEqual(try ops().count, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Driver>()), 1, "still saved")
        XCTAssertEqual(queued, 0)
    }

    func testOneSaleOneOp() throws {
        let clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(9 * 3600)
        let settings = CoalSettingsService(context: context, now: { clock })
        let sales = CoalSaleService(context: context, calendar: calendar, now: { clock })
        let place = try XCTUnwrap(try settings.activeLocations().first)
        var d = sales.newDraft()
        d.tonsText = "2"
        d.choose(place)
        d.vehicle = .own
        let sale = try sales.save(d)
        let saleOps = try ops().filter { $0.entity == "coalSale" }
        XCTAssertEqual(saleOps.count, 1)
        XCTAssertEqual(saleOps.first?.recordId, sale.id)
        XCTAssertEqual(try fields(try XCTUnwrap(saleOps.first)), sale.syncFields())
    }
}
