import XCTest
import SwiftData
@testable import TabelKFH

/// What each role sees and may change; the status line; the refused changes the person removes.
@MainActor
final class RolesTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        container.mainContext.autosaveEnabled = false
        SyncedSave.isApplyingRemote = false
        SyncedSave.onQueued = nil
        SyncedSave.allowed = nil
    }

    override func tearDown() {
        SyncedSave.allowed = nil
        SyncedSave.isApplyingRemote = false
    }

    private func count<T: PersistentModel>(_ type: T.Type) throws -> Int { try context.fetchCount(FetchDescriptor<T>()) }

    // MARK: Roles

    func testTimesheetRoleSeesOnlyTimesheet() {
        XCTAssertEqual(Roles.destinations(role: "timesheet"), [.timesheet, .reports])
        XCTAssertEqual(Roles.destinations(role: "coal"), [.coal, .reports])
        XCTAssertEqual(Roles.destinations(role: "livestock"), [.livestock, .reports])
        for role in ["owner", "manager", "viewer"] {
            XCTAssertEqual(Roles.destinations(role: role), [.timesheet, .coal, .livestock, .agro, .reports], role)
        }
        XCTAssertEqual(Roles.destinations(role: "кто-то"), [])
        // Reports only of the modules the role sees.
        XCTAssertEqual(Roles.modules(role: "timesheet"), [.timesheet])
        XCTAssertEqual(Roles.modules(role: "viewer"), [.timesheet, .coal, .livestock, .agro])
        XCTAssertEqual(Roles.title("timesheet"), "Только табель")
        XCTAssertEqual(Roles.title("viewer"), "Только просмотр")
    }

    func testViewerCannotQueue() throws {
        for role in ["viewer", "timesheet", "livestock"] {
            XCTAssertFalse(SessionStore.canEdit(role: role, .coalSale), role)
        }
        XCTAssertTrue(SessionStore.canEdit(role: "coal", .coalSale))
        SyncedSave.allowed = { Roles.canEdit(role: "viewer", $0) }

        // Opening coal, livestock and the settings makes no rows (the server would refuse them).
        XCTAssertEqual(CoalSettingsService(context: context).current().pricePerTonKopecks, CoalDefaults.pricePerTonKopecks)
        XCTAssertEqual(CoalSaleService(context: context).newDraft().pricePerTon, CoalDefaults.pricePerTonKopecks)
        LivestockService(context: context).ensureDefaults()
        FarmSettingsStore.current(in: context)
        XCTAssertEqual(try count(CoalSettings.self), 0)
        XCTAssertEqual(try count(DeliveryLocation.self), 0)
        XCTAssertEqual(try count(LivestockSettings.self), 0)
        XCTAssertEqual(try count(LivestockCategory.self), 0)
        XCTAssertEqual(try count(FarmSettings.self), 0)

        // A change that got past a screen never reaches the queue.
        context.insert(Employee(lastName: "Петров", firstName: "Пётр", hireDate: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertThrowsError(try context.syncedSave()) { error in
            XCTAssertEqual(error.localizedDescription, "Нет прав на изменение этих данных")
        }
        XCTAssertFalse(context.hasChanges)
        XCTAssertEqual(try count(Employee.self), 0)
        XCTAssertEqual(try count(SyncOp.self), 0)

        // The timesheet helper: employees yes, coal no.
        SyncedSave.allowed = { Roles.canEdit(role: "timesheet", $0) }
        context.insert(Employee(lastName: "Петров", firstName: "Пётр", hireDate: Date(timeIntervalSince1970: 1_700_000_000)))
        try context.syncedSave()
        XCTAssertEqual(try count(SyncOp.self), 1)
        CoalSettingsService(context: context).current()
        XCTAssertEqual(try count(CoalSettings.self), 0)
        XCTAssertEqual(try count(SyncOp.self), 1)
    }

    func testOwnerSeedsAsBefore() throws {
        SyncedSave.allowed = { Roles.canEdit(role: "owner", $0) }
        CoalSettingsService(context: context).current()
        LivestockService(context: context).ensureDefaults()
        XCTAssertEqual(try count(CoalSettings.self), 1)
        XCTAssertEqual(try count(LivestockSettings.self), 1)
        XCTAssertGreaterThan(try count(LivestockCategory.self), 0)
    }

    // MARK: Status line

    func testStatusTexts() {
        var s = SyncStatus()
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Все данные синхронизированы")

        s = SyncStatus(online: false, pending: 3)
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Нет связи. Ждут отправки: 3")
        XCTAssertEqual(SyncStatusText.line(SyncStatus(online: false), role: "coal"), "Нет связи. Ждут отправки: 0")

        s = SyncStatus(failed: 1)
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Ошибка синхронизации")
        s = SyncStatus(lastError: "Сервер ответил 500")
        XCTAssertEqual(SyncStatusText.line(s, role: "timesheet"), "Ошибка синхронизации")

        s = SyncStatus(conflicts: 2)
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Конфликты: 2")
        XCTAssertEqual(SyncStatusText.line(s, role: "timesheet"), "Конфликты: 2 — обратитесь к владельцу")
        XCTAssertEqual(SyncStatusText.line(s, role: "manager"), "Конфликты: 2 — обратитесь к владельцу")

        s = SyncStatus(pending: 2)
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Ждут отправки: 2")
        s.syncing = true
        XCTAssertEqual(SyncStatusText.line(s, role: "owner"), "Синхронизация…")
        XCTAssertEqual(SyncStatusText.action, "Синхронизировать сейчас")
    }

    // MARK: Refused changes

    private func refusedEmployee(version: Int?) throws -> (Employee, SyncOp) {
        let e = Employee(lastName: "Новый", firstName: "Иван", hireDate: Date(timeIntervalSince1970: 1_700_000_000))
        context.insert(e)
        try context.syncedSave()
        let op = try XCTUnwrap(context.fetch(FetchDescriptor<SyncOp>()).first)
        op.state = "error"
        op.errorText = "Фамилия обязательна"
        if let version {
            let key = SyncShadow.makeKey(.employee, e.id)
            let shadow = try XCTUnwrap(context.fetch(FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })).first)
            shadow.version = version
        }
        SyncState.current(in: context).cursor = 9
        try context.save()
        return (e, op)
    }

    func testDeleteRefusedNewRecordRemovesIt() throws {
        let (_, op) = try refusedEmployee(version: nil)
        XCTAssertEqual(RefusedChanges.list(in: context).count, 1)
        XCTAssertEqual(RefusedChanges.title(op), "Сотрудник")

        try RefusedChanges.discard(op, in: context)

        XCTAssertEqual(try count(SyncOp.self), 0)
        XCTAssertEqual(try count(Employee.self), 0)
        XCTAssertEqual(try count(SyncShadow.self), 0)
        XCTAssertEqual(SyncState.current(in: context).cursor, 0)
        XCTAssertFalse(context.hasChanges)
    }

    /// A refused new employee goes with its hours marks (also one removed before it was sent, known only by its op)
    /// and attendance, their ops and shadows; another employee's mark stays.
    func testDeleteRefusedNewEmployeeRemovesItsMarks() throws {
        let calendar = Calendar.farm
        let day = calendar.date(day: 5, month: 11, year: 2026)
        let (e, op) = try refusedEmployee(version: nil)
        let other = Employee(lastName: "Старый", firstName: "Пётр", hireDate: Date(timeIntervalSince1970: 1_700_000_000))
        context.insert(other)
        try context.syncedSave()
        let hours = HoursService(context: context, calendar: calendar, now: { day.addingTimeInterval(3600) })
        try hours.set(8, for: e, on: day)
        try hours.set(6, for: e, on: day.addingTimeInterval(86_400))
        try hours.set(nil, for: e, on: day.addingTimeInterval(86_400))
        try hours.set(7, for: other, on: day)
        let attendance = AttendanceService(context: context, now: { day.addingTimeInterval(3600) }, calendar: calendar)
        _ = try attendance.setStatus(.sick, for: e, on: day)
        XCTAssertEqual(try count(AttendanceRecord.self), 1)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SyncOp>()).filter { $0.entityName == "hoursMark" }.count, 3)

        try RefusedChanges.discard(op, in: context)

        let ops = try context.fetch(FetchDescriptor<SyncOp>())
        XCTAssertEqual(Set(ops.map(\.entityName)), ["employee", "hoursMark"], "only the other employee's records")
        XCTAssertTrue(ops.allSatisfy { $0.recordId == other.id || $0.fields["employeeId"] == .string(other.id.uuidString.lowercased()) })
        XCTAssertEqual(try context.fetch(FetchDescriptor<WorkHoursMark>()).map(\.employeeId), [other.id])
        XCTAssertEqual(try count(AttendanceRecord.self), 0)
        XCTAssertEqual(try count(SyncShadow.self), 2, "the other employee and its mark")
        XCTAssertEqual(try context.fetch(FetchDescriptor<Employee>()).map(\.id), [other.id])
        XCTAssertFalse(context.hasChanges)
    }

    /// A refused new driver goes with his payments; his sale stays, unlinked, and the unlinking is sent.
    func testDeleteRefusedNewDriverRemovesPaymentsAndUnlinksSales() throws {
        let moment = Date(timeIntervalSince1970: 1_800_000_000)
        let driver = Driver(lastName: "Мунгалов", createdAt: moment)
        context.insert(driver)
        try context.syncedSave()
        let op = try XCTUnwrap(context.fetch(FetchDescriptor<SyncOp>()).first)
        op.state = "error"
        let sale = CoalSale(saleDate: moment, kilograms: 2000, locationNameSnapshot: "Луговое", coalPricePerTonSnapshot: 3000,
                            deliveryPriceSnapshot: 500, coalAmount: 6000, deliveryPrice: 500, vehicleType: .hired,
                            driverId: driver.id, driverNameSnapshot: "Мунгалов", driverPaymentAmount: 500,
                            createdAt: moment, updatedAt: moment)
        context.insert(sale)
        context.insert(DriverPayment(driverId: driver.id, driverNameSnapshot: "Мунгалов", paymentDate: moment, amount: 500,
                                     createdAt: moment))
        try context.syncedSave()

        try RefusedChanges.discard(op, in: context)

        XCTAssertEqual(try count(Driver.self), 0)
        XCTAssertEqual(try count(DriverPayment.self), 0)
        let kept = try XCTUnwrap(context.fetch(FetchDescriptor<CoalSale>()).first)
        XCTAssertNil(kept.driverId)
        XCTAssertEqual(kept.driverNameSnapshot, "Мунгалов")
        let ops = try context.fetch(FetchDescriptor<SyncOp>())
        XCTAssertEqual(ops.map(\.entityName), ["coalSale"])
        XCTAssertEqual(ops.first?.fields["driverId"], .null, "the unsent sale goes without the link")
        XCTAssertFalse(context.hasChanges)
    }

    /// A refused new own category goes with its operations and their slaughter records.
    func testDeleteRefusedNewCategoryRemovesItsOperations() throws {
        let moment = Date(timeIntervalSince1970: 1_800_000_000)
        let category = LivestockCategory(species: .cattle, name: "Нетели", sortOrder: 9, createdAt: moment)
        context.insert(category)
        try context.syncedSave()
        let op = try XCTUnwrap(context.fetch(FetchDescriptor<SyncOp>()).first)
        op.state = "error"
        let operation = LivestockOperation(kind: .slaughter, date: moment, categoryId: category.id, heads: 1,
                                           createdAt: moment, updatedAt: moment)
        context.insert(operation)
        context.insert(SlaughterRecord(operationId: operation.id, reason: .meat, createdAt: moment))
        try context.syncedSave()

        try RefusedChanges.discard(op, in: context)

        XCTAssertEqual(try count(LivestockCategory.self), 0)
        XCTAssertEqual(try count(LivestockOperation.self), 0)
        XCTAssertEqual(try count(SlaughterRecord.self), 0)
        XCTAssertEqual(try count(SyncOp.self), 0)
        XCTAssertEqual(try count(SyncShadow.self), 0)
    }

    func testDeleteRefusedChangeOfServerRecordKeepsIt() throws {
        let (e, op) = try refusedEmployee(version: 4)

        try RefusedChanges.discard(op, in: context)

        XCTAssertEqual(try count(SyncOp.self), 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<Employee>()).map(\.id), [e.id])
        // The next pull starts over, so the server's values come back.
        XCTAssertEqual(SyncState.current(in: context).cursor, 0)
        XCTAssertFalse(context.hasChanges)
    }

    func testDeleteRefusedNewSingletonKeepsTheRow() throws {
        FarmSettingsStore.current(in: context)
        let op = try XCTUnwrap(context.fetch(FetchDescriptor<SyncOp>()).first)
        XCTAssertEqual(op.entityName, SyncEntity.farmSettings.rawValue)
        op.state = "error"
        try context.save()

        try RefusedChanges.discard(op, in: context)

        XCTAssertEqual(try count(SyncOp.self), 0)
        XCTAssertEqual(try count(FarmSettings.self), 1, "singletons are never deleted, like SyncEngine.apply")
        XCTAssertFalse(context.hasChanges)
    }

    func testRefusedEntityTitles() {
        XCTAssertEqual(RefusedChanges.title(entityName: "coalSale"), "Продажа угля")
        XCTAssertEqual(RefusedChanges.title(entityName: "hoursMark"), "Часы")
        XCTAssertEqual(RefusedChanges.title(entityName: "farmSettings"), "Настройки хозяйства")
        XCTAssertEqual(RefusedChanges.title(entityName: "нечто"), "нечто")
    }
}
