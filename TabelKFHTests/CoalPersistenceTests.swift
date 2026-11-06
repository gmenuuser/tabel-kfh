import XCTest
import SwiftData
@testable import TabelKFH

/// The owner's phone has a version 1 store (timesheet only). The app with the coal module must open it,
/// keep every value and store coal data next to it.
final class CoalPersistenceTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("CoalPersistenceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// A store written exactly as the shipped app writes it: schema V1, no migration plan.
    private func makeVersion1Container(url: URL) throws -> ModelContainer {
        let schema = Schema(versionedSchema: SchemaV1.self)
        let configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    func testVersion1StoreOpensWithCoalModuleAndKeepsData() throws {
        let url = directory.appendingPathComponent("tabel.store")
        let calendar = Calendar.farm
        let hire = calendar.date(day: 1, month: 3, year: 2025)
        let day = calendar.date(day: 6, month: 11, year: 2026)
        let arrival = day.addingTimeInterval(8 * 3600)
        let departure = day.addingTimeInterval(17 * 3600)
        let created = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let employeeId = UUID(), recordId = UUID(), snapshotId = UUID()

        do {
            let container = try makeVersion1Container(url: url)
            let context = ModelContext(container)
            let employee = Employee(id: employeeId, lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович",
                                    position: "Тракторист", hireDate: hire, workSchedule: .twoTwo,
                                    standardWorkHours: 11, defaultBreakMinutes: 30, photo: Data([1, 2, 3]))
            context.insert(employee)
            let record = AttendanceRecord(id: recordId, employeeId: employeeId, day: day, arrivalTime: arrival,
                                          departureTime: departure, breakMinutes: 30, workedMinutes: 510,
                                          status: .present, comment: "Пахота", createdAt: created, updatedAt: created)
            context.insert(record)
            record.employee = employee
            let settings = FarmSettings()
            settings.managerName = "Зубахин Иван Петрович"
            context.insert(settings)
            context.insert(TimesheetSnapshot(id: snapshotId, month: 11, year: 2026, version: 1, employeeId: nil,
                                             createdAt: created, documentData: Data("{}".utf8),
                                             employeeData: Data("[]".utf8)))
            try context.save()
        }

        do {
            let container = try makeModelContainer(url: url)
            let context = ModelContext(container)

            let employees = try context.fetch(FetchDescriptor<Employee>())
            XCTAssertEqual(employees.count, 1)
            let e = try XCTUnwrap(employees.first)
            XCTAssertEqual(e.id, employeeId)
            XCTAssertEqual(e.fullName, "Зубахин Иван Петрович")
            XCTAssertEqual(e.position, "Тракторист")
            XCTAssertEqual(e.hireDate, hire)
            XCTAssertEqual(e.workSchedule, .twoTwo)
            XCTAssertEqual(e.standardWorkHours, 11)
            XCTAssertEqual(e.defaultBreakMinutes, 30)
            XCTAssertEqual(e.photo, Data([1, 2, 3]))
            XCTAssertEqual(e.records.count, 1)

            let r = try XCTUnwrap(try context.fetch(FetchDescriptor<AttendanceRecord>()).first)
            XCTAssertEqual(r.id, recordId)
            XCTAssertEqual(r.arrivalTime, arrival)
            XCTAssertEqual(r.departureTime, departure)
            XCTAssertEqual(r.workedMinutes, 510)
            XCTAssertEqual(r.comment, "Пахота")
            XCTAssertEqual(r.employee?.id, employeeId)
            XCTAssertEqual(try context.fetch(FetchDescriptor<FarmSettings>()).first?.managerName,
                           "Зубахин Иван Петрович")
            XCTAssertEqual(try context.fetch(FetchDescriptor<TimesheetSnapshot>()).first?.id, snapshotId)

            // The coal tables exist and take data.
            XCTAssertEqual(try context.fetchCount(FetchDescriptor<CoalSale>()), 0)
            let driver = Driver(lastName: "Мунгалов", createdAt: created)
            context.insert(driver)
            context.insert(CoalSale(saleDate: arrival, kilograms: 2000, locationNameSnapshot: "Новоцурухайтуй",
                                    coalPricePerTonSnapshot: 650_000, deliveryPriceSnapshot: 100_000,
                                    coalAmount: 1_300_000, deliveryPrice: 100_000, vehicleType: .hired,
                                    driverId: driver.id, driverNameSnapshot: "Мунгалов",
                                    driverPaymentAmount: 100_000, createdAt: created, updatedAt: created))
            try context.save()
        }

        // And the migrated store opens again with both parts.
        let container = try makeModelContainer(url: url)
        let context = ModelContext(container)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Employee>()), 1)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<AttendanceRecord>()), 1)
        let sale = try XCTUnwrap(try context.fetch(FetchDescriptor<CoalSale>()).first)
        XCTAssertEqual(sale.totalAmount, 1_400_000)
        XCTAssertEqual(sale.vehicleType, .hired)
        XCTAssertEqual(sale.driverNameSnapshot, "Мунгалов")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Driver>()), 1)
    }

    // Spec 18: coal data is kept after the app restarts.
    func testCoalDataSurvivesRestart() throws {
        let url = directory.appendingPathComponent("coal.store")
        let created = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let driverId = UUID(), saleId = UUID(), paymentId = UUID(), dayId = UUID(), locationId = UUID()
        do {
            let context = ModelContext(try makeModelContainer(url: url))
            context.insert(CoalSettings(pricePerTonKopecks: 700_000, createdAt: created, updatedAt: created))
            context.insert(DeliveryLocation(id: locationId, name: "Приаргунск", deliveryPriceKopecks: 120_000,
                                            driverPaymentKopecks: 110_000, isActive: false, createdAt: created))
            context.insert(Driver(id: driverId, lastName: "Кайдолов", firstName: "Пётр", middleName: "Ильич",
                                  phone: "+7 914 000-00-00", comment: "КамАЗ", isArchived: true, createdAt: created))
            let sale = CoalSale(id: saleId, saleDate: created, kilograms: 1500, locationNameSnapshot: "Приаргунск",
                                coalPricePerTonSnapshot: 650_000, deliveryPriceSnapshot: 120_000,
                                coalAmount: 950_000, deliveryPrice: 120_000, vehicleType: .hired, driverId: driverId,
                                driverNameSnapshot: "Кайдолов Пётр Ильич", driverPaymentAmount: 110_000,
                                comment: "скидка", createdAt: created, updatedAt: created)
            context.insert(sale)
            context.insert(DriverPayment(id: paymentId, driverId: driverId, driverNameSnapshot: "Кайдолов Пётр Ильич",
                                         paymentDate: created, amount: 50_000, comment: "аванс", createdAt: created))
            context.insert(CoalWorkDay(id: dayId, date: Calendar.farm.dayStart(created), isClosed: true,
                                       closedAt: created, totalRevenue: 1_070_000, totalKilograms: 1500,
                                       totalDriverPayments: 110_000, ownVehicleDeliveryRevenue: 0, createdAt: created))
            try context.save()
        }

        let context = ModelContext(try makeModelContainer(url: url))
        XCTAssertEqual(try context.fetch(FetchDescriptor<CoalSettings>()).first?.pricePerTonKopecks, 700_000)
        let location = try XCTUnwrap(try context.fetch(FetchDescriptor<DeliveryLocation>()).first)
        XCTAssertEqual(location.id, locationId)
        XCTAssertEqual(location.driverPaymentKopecks, 110_000)
        XCTAssertFalse(location.isActive)
        let driver = try XCTUnwrap(try context.fetch(FetchDescriptor<Driver>()).first)
        XCTAssertEqual(driver.fullName, "Кайдолов Пётр Ильич")
        XCTAssertEqual(driver.phone, "+7 914 000-00-00")
        XCTAssertEqual(driver.comment, "КамАЗ")
        XCTAssertTrue(driver.isArchived)
        let sale = try XCTUnwrap(try context.fetch(FetchDescriptor<CoalSale>()).first)
        XCTAssertEqual(sale.id, saleId)
        XCTAssertEqual(sale.kilograms, 1500)
        XCTAssertEqual(sale.coalAmount, 950_000)
        XCTAssertEqual(sale.totalAmount, 1_070_000)
        XCTAssertEqual(sale.listTotal, 1_095_000)
        XCTAssertTrue(sale.isPriceAdjusted)
        XCTAssertEqual(sale.driverPaymentAmount, 110_000)
        XCTAssertEqual(sale.comment, "скидка")
        let payment = try XCTUnwrap(try context.fetch(FetchDescriptor<DriverPayment>()).first)
        XCTAssertEqual(payment.id, paymentId)
        XCTAssertEqual(payment.amount, 50_000)
        XCTAssertEqual(payment.comment, "аванс")
        let day = try XCTUnwrap(try context.fetch(FetchDescriptor<CoalWorkDay>()).first)
        XCTAssertEqual(day.id, dayId)
        XCTAssertTrue(day.isClosed)
        XCTAssertEqual(day.closedAt, created)
        XCTAssertEqual(day.totalRevenue, 1_070_000)
    }
}
