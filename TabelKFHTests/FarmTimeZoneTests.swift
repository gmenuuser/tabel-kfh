import XCTest
import SwiftData
@testable import TabelKFH

/// The farm's time zone is pinned on the first launch: day keys never move when the phone's zone changes.
@MainActor
final class FarmTimeZoneTests: XCTestCase {
    private let yekaterinburg = TimeZone(identifier: "Asia/Yekaterinburg")!
    private let moscow = TimeZone(identifier: "Europe/Moscow")!
    private var suiteName = ""
    private var defaults: UserDefaults!
    private var deviceZone: TimeZone!

    override func setUp() {
        super.setUp()
        suiteName = "FarmTimeZoneTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        deviceZone = NSTimeZone.default
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        NSTimeZone.default = deviceZone
        super.tearDown()
    }

    // MARK: Storage

    func testFirstLaunchStoresTheDeviceZoneAndLaterLaunchesKeepIt() {
        let store = FarmTimeZone(defaults: defaults)
        XCTAssertNil(store.stored)

        XCTAssertEqual(store.launchZone(uiTest: false, device: yekaterinburg), yekaterinburg)
        XCTAssertEqual(defaults.string(forKey: "farmTimeZone"), "Asia/Yekaterinburg")

        // The phone moved to Moscow time: the farm stays on its own zone.
        XCTAssertEqual(store.launchZone(uiTest: false, device: moscow), yekaterinburg)
        XCTAssertEqual(store.stored, yekaterinburg)
    }

    func testUITestLaunchUsesTheFixedZoneAndStoresNothing() {
        let store = FarmTimeZone(defaults: defaults)
        XCTAssertEqual(store.launchZone(uiTest: true, device: yekaterinburg).identifier, FarmTimeZone.uiTestIdentifier)
        XCTAssertNil(store.stored)
    }

    func testAdoptReplacesTheStoredZone() {
        let store = FarmTimeZone(defaults: defaults)
        store.launchZone(uiTest: false, device: moscow)
        store.adopt(yekaterinburg)
        XCTAssertEqual(store.stored, yekaterinburg)
    }

    func testFarmCalendarWithAnExplicitZone() {
        let calendar = Calendar.farm(timeZone: yekaterinburg)
        XCTAssertEqual(calendar.timeZone, yekaterinburg)
        XCTAssertEqual(calendar.identifier, .gregorian)
        XCTAssertEqual(calendar.firstWeekday, 2)
        XCTAssertEqual(calendar.locale?.identifier, "ru_RU")
    }

    // MARK: Day keys

    func testFarmDayRecordIsFoundAndCountedWhileThePhoneIsOnMoscowTime() throws {
        NSTimeZone.default = moscow
        XCTAssertEqual(TimeZone.current.identifier, "Europe/Moscow")
        let farm = Calendar.farm(timeZone: yekaterinburg)
        let container = try makeModelContainer(inMemory: true)
        let context = ModelContext(container)
        let now = farm.date(from: DateComponents(year: 2026, month: 12, day: 1, hour: 10))!

        var draft = EmployeeDraft()
        draft.lastName = "Зубахин"
        draft.firstName = "Иван"
        draft.hireDate = farm.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 15))!
        let employee = try EmployeeService(context: context, calendar: farm).add(draft)
        XCTAssertEqual(employee.hireDate, farm.date(day: 1, month: 11, year: 2026))

        // 01:30–09:30 on 6 November at the farm = 23:30 on 5 November – 07:30 on 6 November in Moscow.
        let arrival = farm.date(from: DateComponents(year: 2026, month: 11, day: 6, hour: 1, minute: 30))!
        let departure = farm.date(from: DateComponents(year: 2026, month: 11, day: 6, hour: 9, minute: 30))!
        let attendance = AttendanceService(context: context, now: { now }, calendar: farm)
        let saved = try attendance.save(employee: employee, day: arrival, status: .present, arrival: arrival,
                                        departure: departure, breakMinutes: 0, manualMinutes: nil, comment: "")
        XCTAssertEqual(saved.day, farm.date(day: 6, month: 11, year: 2026))

        XCTAssertEqual(attendance.record(for: employee, day: arrival)?.id, saved.id)
        XCTAssertEqual(attendance.record(for: employee, day: farm.date(day: 6, month: 11, year: 2026))?.id, saved.id)
        XCTAssertNil(attendance.record(for: employee, day: farm.date(day: 5, month: 11, year: 2026)))

        let timesheets = TimesheetService(context: context, now: { now }, calendar: farm)
        let document = try timesheets.document(
            timesheets.generate(month: 11, year: 2026, employee: nil, format: .codesAndHours))
        XCTAssertEqual(document.rows.count, 1)
        XCTAssertEqual(document.rows[0].cells[5].text, "Я\n8")
        XCTAssertEqual(document.rows[0].cells[4].text, "")
        XCTAssertEqual(document.rows[0].totals.minutes, 480)
    }

    // MARK: Backup

    func testBackupCarriesTheFarmZoneAndRestoreAdoptsIt() throws {
        let farm = Calendar.farm(timeZone: yekaterinburg)
        let now = farm.date(from: DateComponents(year: 2026, month: 12, day: 1, hour: 10))!
        let source = ModelContext(try makeModelContainer(inMemory: true))
        source.insert(Employee(lastName: "Зубахин", firstName: "Иван", hireDate: farm.date(day: 1, month: 1, year: 2026)))
        try source.save()

        let data = try BackupService(context: source, now: { now }, calendar: farm).makeBackup()
        XCTAssertEqual(try BackupCodec.decode(data).timeZone, "Asia/Yekaterinburg")

        // A new phone whose farm zone is Moscow.
        var adopted: [String] = []
        let target = ModelContext(try makeModelContainer(inMemory: true))
        try BackupService(context: target, now: { now }, calendar: .farm(timeZone: moscow),
                          adoptTimeZone: { adopted.append($0.identifier) }).restore(data)
        XCTAssertEqual(adopted, ["Asia/Yekaterinburg"])
        XCTAssertEqual(try target.fetchCount(FetchDescriptor<Employee>()), 1)
    }

    func testBackupWithoutAZoneKeepsTheCurrentFarmZone() throws {
        let payload = BackupPayload(settings: .default, employees: [], records: [], snapshots: [])
        XCTAssertNil(payload.timeZone)
        let data = try BackupCodec.encode(payload, createdAt: Date(), appVersion: "1.0")
        XCTAssertNil(try BackupCodec.decode(data).timeZone)

        var adopted: [String] = []
        let target = ModelContext(try makeModelContainer(inMemory: true))
        try BackupService(context: target, calendar: .farm(timeZone: moscow),
                          adoptTimeZone: { adopted.append($0.identifier) }).restore(data)
        XCTAssertEqual(adopted, ["Europe/Moscow"])
    }

    func testBackupWithAnUnknownZoneIsRejected() throws {
        let payload = BackupPayload(settings: .default, employees: [], records: [], snapshots: [],
                                    timeZone: "Mars/Olympus")
        let data = try BackupCodec.encode(payload, createdAt: Date(), appVersion: "1.0")
        XCTAssertThrowsError(try BackupCodec.decode(data)) { error in
            XCTAssertEqual(error as? BackupError, .invalidData)
        }
    }
}
