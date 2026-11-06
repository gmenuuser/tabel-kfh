import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class BackupServiceTests: XCTestCase {
    private let calendar = Calendar.farm
    private var container: ModelContainer!
    private var context: ModelContext!
    /// 1 December 2026, 10:00.
    private var clock = Date()

    override func setUpWithError() throws {
        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        clock = at(1, 10, 0, month: 12)
    }

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    private func at(_ d: Int, _ hour: Int, _ minute: Int = 0, month: Int = 11) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: d, hour: hour, minute: minute))!
    }

    private func service() -> BackupService {
        BackupService(context: context, now: { [unowned self] in self.clock }, adoptTimeZone: { _ in })
    }

    private func attendance() -> AttendanceService {
        AttendanceService(context: context, now: { [unowned self] in self.clock })
    }

    @discardableResult
    private func addEmployee(_ lastName: String, archived: Bool = false, photo: Data? = nil) throws -> Employee {
        let e = Employee(lastName: lastName, firstName: "Иван", patronymic: "Петрович", position: "Тракторист",
                         hireDate: day(1, 1), dismissalDate: archived ? day(20, 11) : nil, isArchived: archived,
                         workSchedule: archived ? .twoTwo : .fiveTwo, standardWorkHours: 7.5,
                         defaultBreakMinutes: 45, photo: photo)
        context.insert(e)
        try context.save()
        return e
    }

    /// Two employees (one archived, with a photo), their marks, a timesheet version and farm settings.
    private func fillStore() throws {
        let settings = FarmSettingsStore.current(in: context)
        settings.farmName = "КФХ Луговое"
        settings.managerName = "Зубахин И. П."
        settings.phone = "123"
        try context.save()
        let e = try addEmployee("Зубахин")
        let old = try addEmployee("Старов", archived: true, photo: Data([0x89, 0x50, 0x4E, 0x47, 1, 2, 3]))
        try attendance().save(employee: e, day: day(2), status: .present, arrival: at(2, 8), departure: at(2, 17),
                              breakMinutes: 60, manualMinutes: nil, comment: "Пахота")
        try attendance().save(employee: e, day: day(3), status: .present, arrival: at(3, 8), departure: nil,
                              breakMinutes: 60, manualMinutes: 240, comment: "")
        try attendance().save(employee: old, day: day(4), status: .sick, arrival: nil, departure: nil,
                              breakMinutes: 0, manualMinutes: nil, comment: "")
        _ = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .generate(month: 11, year: 2026, employee: nil, format: .codesAndHours)
    }

    private func count<T: PersistentModel>(_ type: T.Type) throws -> Int {
        try context.fetchCount(FetchDescriptor<T>())
    }

    func testPayloadHasEveryStoredObject() throws {
        try fillStore()
        let p = try service().payload()
        XCTAssertEqual(p.settings.farmName, "КФХ Луговое")
        XCTAssertEqual(p.settings.managerName, "Зубахин И. П.")
        XCTAssertEqual(p.employees.count, 2)
        XCTAssertEqual(p.records.count, 3)
        XCTAssertEqual(p.snapshots.count, 1)
        let old = try XCTUnwrap(p.employees.first { $0.lastName == "Старов" })
        XCTAssertTrue(old.isArchived)
        XCTAssertEqual(old.workSchedule, .twoTwo)
        XCTAssertEqual(old.photo, Data([0x89, 0x50, 0x4E, 0x47, 1, 2, 3]))
        XCTAssertEqual(p.records.first { $0.comment == "Пахота" }?.workedMinutes, 480)
        XCTAssertEqual(p.records.first { $0.manualWorkedMinutes == 240 }?.departureTime, nil)
    }

    func testRestoreBringsBackExactlyTheBackedUpData() throws {
        try fillStore()
        let before = try service().payload()
        let backup = try service().makeBackup()

        // Changes after the backup: a new employee with a mark, an edited one, a second version, farm name.
        let extra = try addEmployee("Новиков")
        try attendance().save(employee: extra, day: day(5), status: .present, arrival: at(5, 8), departure: at(5, 12),
                              breakMinutes: 0, manualMinutes: nil, comment: "")
        let e = try XCTUnwrap(try context.fetch(FetchDescriptor<Employee>()).first { $0.lastName == "Зубахин" })
        e.position = "Механик"
        try attendance().save(employee: e, day: day(2), status: .absent, arrival: nil, departure: nil,
                              breakMinutes: 0, manualMinutes: nil, comment: "")
        _ = try TimesheetService(context: context, now: { [unowned self] in self.clock })
            .generate(month: 11, year: 2026, employee: nil, format: .codes)
        FarmSettingsStore.current(in: context).farmName = "КФХ Другое"
        try context.save()
        XCTAssertNotEqual(try service().payload(), before)

        try service().restore(backup)

        XCTAssertEqual(try service().payload(), before)
        XCTAssertEqual(try count(Employee.self), 2)
        XCTAssertEqual(try count(AttendanceRecord.self), 3)
        XCTAssertEqual(try count(TimesheetSnapshot.self), 1)
        XCTAssertEqual(try count(FarmSettings.self), 1)
        // Relationships are rebuilt.
        let restored = try XCTUnwrap(try context.fetch(FetchDescriptor<Employee>()).first { $0.lastName == "Зубахин" })
        XCTAssertEqual(restored.records.count, 2)
        XCTAssertTrue(try context.fetch(FetchDescriptor<AttendanceRecord>()).allSatisfy { $0.employee?.id == $0.employeeId })
        // The data is saved, not only changed in this context.
        let fresh = ModelContext(container)
        XCTAssertEqual(try fresh.fetchCount(FetchDescriptor<Employee>()), 2)
        XCTAssertEqual(try BackupService(context: fresh, now: { [unowned self] in self.clock }).payload(), before)
    }

    func testRestoreIntoAnEmptyStore() throws {
        try fillStore()
        let before = try service().payload()
        let backup = try service().makeBackup()

        container = try makeModelContainer(inMemory: true)
        context = ModelContext(container)
        try service().restore(backup)

        XCTAssertEqual(try service().payload(), before)
        XCTAssertEqual(try count(FarmSettings.self), 1)
    }

    // Spec 17.
    func testDamagedFileLeavesCurrentDataUntouched() throws {
        try fillStore()
        let backup = try service().makeBackup()
        try addEmployee("Новиков")
        let current = try service().payload()

        let damaged = Data(String(decoding: backup, as: UTF8.self)
            .replacingOccurrences(of: "КФХ Луговое", with: "КФХ Лугавое").utf8)
        XCTAssertThrowsError(try service().restore(damaged)) { error in
            XCTAssertEqual(error as? BackupError, .damaged)
        }
        XCTAssertThrowsError(try service().restore(Data("не копия".utf8))) { error in
            XCTAssertEqual(error as? BackupError, .notABackup)
        }

        XCTAssertEqual(try service().payload(), current)
        XCTAssertEqual(try count(Employee.self), 3)
    }

    func testSummaryAndTexts() throws {
        try fillStore()
        let summary = try service().summary(of: try service().makeBackup())
        XCTAssertEqual(summary.employees, 2)
        XCTAssertEqual(summary.records, 3)
        XCTAssertEqual(summary.createdAt, clock)

        XCTAssertEqual(BackupService.summaryText(employees: 5, records: 120, createdAt: day(6)),
                       "Копия от 06.11.2026: 5 сотрудников, 120 отметок. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.summaryText(employees: 1, records: 2, createdAt: day(6)),
                       "Копия от 06.11.2026: 1 сотрудник, 2 отметки. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.summaryText(employees: 21, records: 11, createdAt: day(6)),
                       "Копия от 06.11.2026: 21 сотрудник, 11 отметок. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.summaryText(employees: 3, records: 101, createdAt: day(6)),
                       "Копия от 06.11.2026: 3 сотрудника, 101 отметка. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.fileName(on: at(6, 23, 59)), "Табель-КФХ-копия-2026-11-06.json")
    }

    func testFarmSettingsHaveExactlyOneRow() throws {
        let first = FarmSettingsStore.current(in: context)
        XCTAssertEqual(first.farmName, "КФХ Зубахин")
        XCTAssertEqual(try ModelContext(container).fetchCount(FetchDescriptor<FarmSettings>()), 1, "saved")
        first.farmName = "КФХ Луговое"
        let again = FarmSettingsStore.current(in: context)
        XCTAssertEqual(again.farmName, "КФХ Луговое")
        XCTAssertEqual(try count(FarmSettings.self), 1)
        XCTAssertEqual(again.info, FarmInfo(farmName: "КФХ Луговое", managerName: "", responsiblePerson: "",
                                            address: "", phone: "", extraInfo: ""))
    }
}
