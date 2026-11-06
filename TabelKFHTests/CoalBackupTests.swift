import XCTest
import SwiftData
@testable import TabelKFH

/// The coal module travels in the same backup file as the timesheet.
@MainActor
final class CoalBackupTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(10 * 3600)
    }

    private var now: () -> Date { { [unowned self] in self.clock } }

    private func backup() -> BackupService {
        BackupService(context: context, now: now, adoptTimeZone: { _ in })
    }

    /// A price change, a hidden village, two drivers (one archived), three sales, a payment, a closed day.
    private func fillCoal() throws {
        let settings = CoalSettingsService(context: context, now: now)
        try settings.setPricePerTon(700_000)
        try settings.addLocation(name: "Забайкальск", deliveryKopecks: 150_000, driverPaymentKopecks: 140_000)
        try settings.removeLocation(try XCTUnwrap(settings.activeLocations().first { $0.name == "Приаргунск" }))
        let drivers = DriverService(context: context, now: now)
        let m = try drivers.add(DriverDraft(lastName: "Мунгалов", firstName: "Сергей", phone: "+7 914 1"))
        let k = try drivers.add(DriverDraft(lastName: "Кайдолов", comment: "КамАЗ"))
        let sales = CoalSaleService(context: context, calendar: calendar, now: now)
        let plan: [(String, String, Driver?)] = [("2", "Новоцурухайтуй", m), ("1,5", "Забайкальск", k),
                                                 ("1", "Новоцурухайтуй", nil)]
        for (tons, place, driver) in plan {
            var d = sales.newDraft()
            d.tonsText = tons
            d.choose(try XCTUnwrap(settings.activeLocations().first { $0.name == place }))
            if let driver { d.choose(driver) } else { d.vehicle = .own }
            if driver == nil { d.startManual(); d.manualCoalText = "6000" }
            d.comment = "продажа \(tons)"
            try sales.save(d)
            clock = clock.addingTimeInterval(600)
        }
        try DriverPaymentService(context: context, now: now).pay(m, amount: 50_000, comment: "аванс")
        try drivers.archive(k)
        try CoalDayService(context: context, calendar: calendar, now: now).close(clock)
    }

    private func coalState() throws -> CoalBackup? {
        try backup().payload().coal
    }

    func testRoundTripKeepsEveryCoalRow() throws {
        try fillCoal()
        let before = try XCTUnwrap(try coalState())
        XCTAssertEqual(before.sales.count, 3)
        XCTAssertEqual(before.drivers.count, 2)
        XCTAssertEqual(before.payments.count, 1)
        XCTAssertEqual(before.days.count, 1)
        XCTAssertEqual(before.locations.count, 3)
        XCTAssertEqual(before.settings?.pricePerTonKopecks, 700_000)
        let data = try backup().makeBackup()

        // Everything changes after the backup.
        let sales = CoalSaleService(context: context, calendar: calendar, now: now)
        try CoalDayService(context: context, calendar: calendar, now: now).reopen(clock)
        for s in try sales.allSales() { try sales.delete(s) }
        try CoalSettingsService(context: context, now: now).setPricePerTon(800_000)
        try DriverService(context: context, now: now).add(DriverDraft(lastName: "Лишний"))

        try backup().restore(data)
        XCTAssertEqual(try coalState(), before)
        let report = try CoalDayService(context: context, calendar: calendar, now: now).dayReport(clock)
        XCTAssertEqual(report.rows.count, 3)
        XCTAssertNotNil(report.closedAt)
        XCTAssertEqual(report.totalDebtAfter, 100_000 + 140_000 - 50_000)
        XCTAssertEqual(try backup().summary(of: data).coalSales, 3)
    }

    func testFileWithoutCoalKeepsCurrentCoalData() throws {
        // A file made before the module: version 1, no «coal».
        var payload = try backup().payload()
        payload.coal = nil
        let file = try BackupCodec.encode(payload, createdAt: clock, appVersion: "1.0")
        var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: file) as? [String: Any])
        object["schemaVersion"] = 1
        let old = try JSONSerialization.data(withJSONObject: object)

        try fillCoal()
        let before = try coalState()
        try backup().restore(old)
        XCTAssertEqual(try coalState(), before, "the old file leaves the coal module alone")
        XCTAssertNil(try backup().summary(of: old).coalSales)
    }

    func testEmptyCoalModuleRestoresToDefaultsOnNextOpening() throws {
        let data = try backup().makeBackup()
        XCTAssertNil(try backup().payload().coal?.settings, "the module was not opened")
        try fillCoal()
        try backup().restore(data)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<CoalSale>()), 0)
        let settings = CoalSettingsService(context: context, now: now)
        XCTAssertEqual(settings.pricePerTon, 650_000)
        XCTAssertEqual(try settings.activeLocations().map(\.name), ["Новоцурухайтуй", "Приаргунск"])
    }

    func testBrokenCoalRulesAreInvalidData() throws {
        try fillCoal()
        let payload = try backup().payload()
        func rejected(_ change: (inout CoalBackup) -> Void, file: StaticString = #filePath, line: UInt = #line) throws {
            var p = payload
            var coal = try XCTUnwrap(p.coal)
            change(&coal)
            p.coal = coal
            let data = try BackupCodec.encode(p, createdAt: clock, appVersion: "1.0")
            XCTAssertThrowsError(try BackupCodec.decode(data), file: file, line: line) {
                XCTAssertEqual($0 as? BackupError, .invalidData, file: file, line: line)
            }
        }
        try rejected { $0.drivers.removeAll() }
        try rejected { $0.sales.append($0.sales[0]) }
        try rejected { $0.days.append(CoalBackup.Day(id: UUID(), date: $0.days[0].date, isClosed: false, closedAt: nil,
                                                     totalRevenue: 0, totalKilograms: 0, totalDriverPayments: 0,
                                                     ownVehicleDeliveryRevenue: 0, createdAt: Date())) }
        try rejected {
            let s = $0.sales[0]
            $0.sales[0] = CoalBackup.Sale(id: s.id, saleDate: s.saleDate, kilograms: s.kilograms,
                                          locationNameSnapshot: s.locationNameSnapshot,
                                          coalPricePerTonSnapshot: s.coalPricePerTonSnapshot,
                                          deliveryPriceSnapshot: s.deliveryPriceSnapshot, coalAmount: s.coalAmount,
                                          deliveryPrice: s.deliveryPrice, totalAmount: s.totalAmount + 1,
                                          vehicleType: s.vehicleType, driverId: s.driverId,
                                          driverNameSnapshot: s.driverNameSnapshot,
                                          driverPaymentAmount: s.driverPaymentAmount, comment: s.comment,
                                          createdAt: s.createdAt, updatedAt: s.updatedAt)
        }
        // An unknown vehicle code.
        let text = String(decoding: try backup().makeBackup(), as: UTF8.self)
        XCTAssertTrue(text.contains("\"vehicleType\" : \"hired\""))
        XCTAssertThrowsError(try BackupCodec.decode(Data(text.replacingOccurrences(
            of: "\"vehicleType\" : \"hired\"", with: "\"vehicleType\" : \"truck\"").utf8))) {
            XCTAssertEqual($0 as? BackupError, .invalidData)
        }
    }

    func testSummaryTextMentionsCoalSales() {
        let day = calendar.date(day: 6, month: 11, year: 2026)
        XCTAssertEqual(BackupService.summaryText(employees: 5, records: 120, coalSales: 12, createdAt: day),
                       "Копия от 06.11.2026: 5 сотрудников, 120 отметок, 12 продаж угля. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.summaryText(employees: 1, records: 2, coalSales: 1, createdAt: day),
                       "Копия от 06.11.2026: 1 сотрудник, 2 отметки, 1 продажа угля. Текущие данные будут заменены.")
        XCTAssertEqual(BackupService.summaryText(employees: 1, records: 2, createdAt: day),
                       "Копия от 06.11.2026: 1 сотрудник, 2 отметки. Текущие данные будут заменены.")
    }
}
