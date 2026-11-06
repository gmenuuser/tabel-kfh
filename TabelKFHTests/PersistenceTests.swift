import XCTest
import SwiftData
@testable import TabelKFH

final class PersistenceTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("PersistenceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // Spec 18: data is kept after the app restarts.
    func testDataSurvivesRestart() throws {
        let url = directory.appendingPathComponent("tabel.store")
        let calendar = Calendar.farm
        let hire = calendar.date(day: 1, month: 3, year: 2025)
        let day = calendar.date(day: 6, month: 11, year: 2026)
        let arrival = day.addingTimeInterval(8 * 3600)
        let departure = day.addingTimeInterval(17 * 3600)
        let created = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let photo = Data([0x89, 0x50, 0x4E, 0x47, 1, 2, 3])
        let employeeId = UUID()
        let recordId = UUID()

        do {
            let container = try makeModelContainer(url: url)
            let context = ModelContext(container)
            let employee = Employee(
                id: employeeId, lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович",
                position: "Тракторист", hireDate: hire, workSchedule: .sixOne,
                standardWorkHours: 7.5, defaultBreakMinutes: 45, photo: photo)
            context.insert(employee)
            let record = AttendanceRecord(
                id: recordId, employeeId: employeeId, day: day, arrivalTime: arrival,
                departureTime: departure, breakMinutes: 60, workedMinutes: 480,
                manualWorkedMinutes: 470, status: .present, comment: "Пахота",
                createdAt: created, updatedAt: created)
            context.insert(record)
            record.employee = employee
            let settings = FarmSettings()
            settings.managerName = "Зубахин Иван Петрович"
            settings.phone = "+7 900 000-00-00"
            context.insert(settings)
            try context.save()
        }

        let container = try makeModelContainer(url: url)
        let context = ModelContext(container)

        let employees = try context.fetch(FetchDescriptor<Employee>())
        XCTAssertEqual(employees.count, 1)
        let e = try XCTUnwrap(employees.first)
        XCTAssertEqual(e.id, employeeId)
        XCTAssertEqual(e.lastName, "Зубахин")
        XCTAssertEqual(e.firstName, "Иван")
        XCTAssertEqual(e.patronymic, "Петрович")
        XCTAssertEqual(e.position, "Тракторист")
        XCTAssertEqual(e.hireDate, hire)
        XCTAssertNil(e.dismissalDate)
        XCTAssertFalse(e.isArchived)
        XCTAssertEqual(e.workSchedule, .sixOne)
        XCTAssertEqual(e.standardWorkHours, 7.5)
        XCTAssertEqual(e.defaultBreakMinutes, 45)
        XCTAssertEqual(e.photo, photo)
        XCTAssertEqual(e.records.count, 1)

        let records = try context.fetch(FetchDescriptor<AttendanceRecord>())
        XCTAssertEqual(records.count, 1)
        let r = try XCTUnwrap(records.first)
        XCTAssertEqual(r.id, recordId)
        XCTAssertEqual(r.employeeId, employeeId)
        XCTAssertEqual(r.employee?.id, employeeId)
        XCTAssertEqual(r.day, day)
        XCTAssertEqual(r.arrivalTime, arrival)
        XCTAssertEqual(r.departureTime, departure)
        XCTAssertEqual(r.breakMinutes, 60)
        XCTAssertEqual(r.workedMinutes, 480)
        XCTAssertEqual(r.manualWorkedMinutes, 470)
        XCTAssertEqual(r.status, .present)
        XCTAssertEqual(r.statusRaw, "Я")
        XCTAssertEqual(r.comment, "Пахота")
        XCTAssertEqual(r.createdAt, created)
        XCTAssertEqual(r.updatedAt, created)

        let settings = try context.fetch(FetchDescriptor<FarmSettings>())
        XCTAssertEqual(settings.count, 1)
        let s = try XCTUnwrap(settings.first)
        XCTAssertEqual(s.farmName, "КФХ Зубахин")
        XCTAssertEqual(s.managerName, "Зубахин Иван Петрович")
        XCTAssertEqual(s.phone, "+7 900 000-00-00")
        XCTAssertEqual(s.address, "")
    }

    func testDeletingEmployeeDeletesRecords() throws {
        let container = try makeModelContainer(inMemory: true)
        let context = ModelContext(container)
        let employee = Employee(lastName: "Петров", firstName: "Пётр", hireDate: .now)
        context.insert(employee)
        let record = AttendanceRecord(employeeId: employee.id, day: Calendar.farm.dayStart(.now), status: .dayOff)
        context.insert(record)
        record.employee = employee
        try context.save()

        context.delete(employee)
        try context.save()

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<AttendanceRecord>()), 0)
    }

    func testSnapshotStoresData() throws {
        let container = try makeModelContainer(inMemory: true)
        let context = ModelContext(container)
        let snapshot = TimesheetSnapshot(month: 11, year: 2026, version: 2, employeeId: nil,
                                         createdAt: .now, documentData: Data("{}".utf8),
                                         employeeData: Data("[]".utf8))
        context.insert(snapshot)
        try context.save()

        let fetched = try XCTUnwrap(try context.fetch(FetchDescriptor<TimesheetSnapshot>()).first)
        XCTAssertEqual(fetched.month, 11)
        XCTAssertEqual(fetched.year, 2026)
        XCTAssertEqual(fetched.version, 2)
        XCTAssertNil(fetched.employeeId)
        XCTAssertEqual(fetched.documentData, Data("{}".utf8))
        XCTAssertEqual(fetched.employeeData, Data("[]".utf8))
    }

    func testSchemaVersions() {
        XCTAssertEqual(SchemaV1.versionIdentifier, Schema.Version(1, 0, 0))
        XCTAssertEqual(SchemaV2.versionIdentifier, Schema.Version(2, 0, 0))
        XCTAssertEqual(SchemaV3.versionIdentifier, Schema.Version(3, 0, 0))
        XCTAssertEqual(SchemaV4.versionIdentifier, Schema.Version(4, 0, 0))
        XCTAssertEqual(TabelMigrationPlan.schemas.count, 5)
        XCTAssertEqual(TabelMigrationPlan.stages.count, 4)
        XCTAssertEqual(SchemaV1.models.count, 4)
        XCTAssertEqual(SchemaV2.models.count, 10)
        XCTAssertEqual(SchemaV3.models.count, 14)
        XCTAssertEqual(SchemaV4.models.count, 15)
        XCTAssertEqual(SchemaV5.models.count, 16)
    }
}
