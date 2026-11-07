import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class CoalServiceTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!
    private var settings: CoalSettingsService!
    private var drivers: DriverService!
    private var sales: CoalSaleService!
    private var payments: DriverPaymentService!
    private var days: CoalDayService!
    private var mungalov: Driver!
    private var kaydolov: Driver!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(9 * 3600)
        let now: () -> Date = { [unowned self] in self.clock }
        settings = CoalSettingsService(context: context, now: now)
        drivers = DriverService(context: context, now: now)
        sales = CoalSaleService(context: context, calendar: calendar, now: now)
        payments = DriverPaymentService(context: context, now: now)
        days = CoalDayService(context: context, calendar: calendar, now: now)
        mungalov = try drivers.add(DriverDraft(lastName: "Мунгалов"))
        kaydolov = try drivers.add(DriverDraft(lastName: "Кайдолов"))
    }

    private var today: Date { calendar.dayStart(clock) }

    private func location(_ name: String) throws -> DeliveryLocation {
        try XCTUnwrap(try settings.activeLocations().first { $0.name == name }, "no \(name)")
    }

    /// Saves a sale «now», then moves the clock on a minute.
    @discardableResult
    private func sell(_ tons: String, to place: String, by driver: Driver?) throws -> CoalSale {
        var d = sales.newDraft()
        d.tonsText = tons
        d.choose(try location(place))
        if let driver { d.choose(driver) } else { d.vehicle = .own }
        let sale = try sales.save(d)
        clock = clock.addingTimeInterval(60)
        return sale
    }

    func testDefaultsAreSeededOnce() throws {
        XCTAssertEqual(settings.pricePerTon, 650_000)
        let names = try settings.activeLocations().map { "\($0.name) \($0.deliveryPriceKopecks)" }
        XCTAssertEqual(names, ["Новоцурухайтуй 100000", "Приаргунск 120000"])
        try settings.removeLocation(try location("Приаргунск"))
        settings.current()
        XCTAssertEqual(try settings.activeLocations().count, 1, "removed village is not seeded again")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<CoalSettings>()), 1)
    }

    // Сценарий 1.
    func testScenario1HiredSaleToNovotsurukhaituy() throws {
        let s = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        XCTAssertEqual(s.coalAmount, 1_300_000)
        XCTAssertEqual(s.deliveryPrice, 100_000)
        XCTAssertEqual(s.totalAmount, 1_400_000)
        XCTAssertEqual(s.driverPaymentAmount, 100_000)
        XCTAssertEqual(s.driverNameSnapshot, "Мунгалов")
        XCTAssertEqual(s.vehicleType, .hired)
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 100_000)
    }

    // Сценарий 2.
    func testScenario2OwnVehicleSaleToPriargunsk() throws {
        let s = try sell("1", to: "Приаргунск", by: nil)
        XCTAssertEqual(s.coalAmount, 650_000)
        XCTAssertEqual(s.deliveryPrice, 120_000)
        XCTAssertEqual(s.totalAmount, 770_000)
        XCTAssertEqual(s.driverPaymentAmount, 0)
        XCTAssertNil(s.driverId)
        let report = try days.dayReport(today)
        XCTAssertEqual(report.totals.ownVehicleDeliveryRevenue, 120_000)
        XCTAssertEqual(report.totals.driverPayments, 0)
        XCTAssertTrue(report.drivers.isEmpty, "own vehicle owes nobody")
    }

    /// Сценарии 3–5: Мунгалов 1000 + 1200 + 1000, Кайдолов 1200 + 1000, and one own-vehicle sale.
    private func sellScenarioDay() throws {
        try sell("2", to: "Новоцурухайтуй", by: mungalov)
        try sell("1,5", to: "Приаргунск", by: mungalov)
        try sell("0,5", to: "Новоцурухайтуй", by: mungalov)
        try sell("1", to: "Приаргунск", by: kaydolov)
        try sell("2,5", to: "Новоцурухайтуй", by: kaydolov)
        try sell("1", to: "Приаргунск", by: nil)
    }

    func testScenarios3to5DriverAccruals() throws {
        try sellScenarioDay()
        let report = try days.dayReport(today)
        let m = try XCTUnwrap(report.drivers.first { $0.driverId == mungalov.id })
        XCTAssertEqual(m.deliveries, 3)
        XCTAssertEqual(m.accrued, 320_000)
        XCTAssertEqual(m.debtAfter, 320_000)
        let k = try XCTUnwrap(report.drivers.first { $0.driverId == kaydolov.id })
        XCTAssertEqual(k.deliveries, 2)
        XCTAssertEqual(k.accrued, 220_000)
        XCTAssertEqual(report.totals.driverPayments, 540_000)
        XCTAssertEqual(report.totalDebtAfter, 540_000)
        XCTAssertEqual(report.drivers.map(\.name), ["Кайдолов", "Мунгалов"])

        // А–Е.
        let t = report.totals
        XCTAssertEqual(t.salesCount, 6)
        XCTAssertEqual(t.kilograms, 8500)
        XCTAssertEqual(t.coalAmount, 5_525_000)
        XCTAssertEqual(t.deliveryAmount, 660_000)
        XCTAssertEqual(t.revenue, 6_185_000)
        XCTAssertEqual(t.ownVehicleDeliveryRevenue, 120_000)
        XCTAssertEqual(t.remainderAfterDrivers, 6_185_000 - 540_000)
        XCTAssertEqual(t.hiredDeliveries, 5)
        XCTAssertEqual(t.ownDeliveries, 1)
        XCTAssertEqual(report.rows.map(\.number), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(report.locations.map(\.name), ["Новоцурухайтуй", "Приаргунск"])
        XCTAssertEqual(report.locations.map(\.salesCount), [3, 3])
    }

    // Сценарий 6, and the rest carried to the next day.
    func testScenario6PartialPaymentLeavesDebtForNextDay() throws {
        try sellScenarioDay()
        try payments.pay(mungalov, amount: 200_000, comment: "наличными")
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 120_000)
        XCTAssertEqual(try drivers.balance(of: mungalov).paid, 200_000)
        let todayReport = try days.dayReport(today)
        let m = try XCTUnwrap(todayReport.drivers.first { $0.driverId == mungalov.id })
        XCTAssertEqual(m.paid, 200_000)
        XCTAssertEqual(m.debtAfter, 120_000)

        clock = clock.addingTimeInterval(24 * 3600)
        try sell("1", to: "Новоцурухайтуй", by: mungalov)
        let next = try days.dayReport(clock)
        let m2 = try XCTUnwrap(next.drivers.first { $0.driverId == mungalov.id })
        XCTAssertEqual(m2.debtBefore, 120_000)
        XCTAssertEqual(m2.accrued, 100_000)
        XCTAssertEqual(m2.paid, 0)
        XCTAssertEqual(m2.debtAfter, 220_000)
        let k2 = try XCTUnwrap(next.drivers.first { $0.driverId == kaydolov.id }, "debt carried without deliveries")
        XCTAssertEqual(k2.deliveries, 0)
        XCTAssertEqual(k2.debtAfter, 220_000)
    }

    // Сценарий 7.
    func testScenario7ClosingTheDayPaysNothingAndCountsOnce() throws {
        try sellScenarioDay()
        let day = try days.close(today)
        XCTAssertTrue(day.isClosed)
        XCTAssertEqual(day.closedAt, clock)
        XCTAssertEqual(day.totalRevenue, 6_185_000)
        XCTAssertEqual(day.totalDriverPayments, 540_000)
        XCTAssertEqual(day.ownVehicleDeliveryRevenue, 120_000)
        XCTAssertEqual(day.totalKilograms, 8500)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<DriverPayment>()), 0, "closing pays nothing")
        XCTAssertEqual(try sales.sales(on: today).count, 6)
        for _ in 0..<3 {
            let report = try days.dayReport(today)
            XCTAssertEqual(report.totalDebtAfter, 540_000, "reading the history again accrues nothing")
            XCTAssertEqual(report.closedAt, clock)
        }
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 320_000)
        XCTAssertEqual(try days.closedDays().count, 1)
        XCTAssertThrowsError(try days.close(today)) { XCTAssertEqual($0 as? CoalError, .dayAlreadyClosed) }
    }

    func testClosedDayLocksSalesUntilReopened() throws {
        let s = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        try days.close(today)
        var edit = CoalSaleDraft(s)
        edit.tonsText = "3"
        XCTAssertThrowsError(try sales.update(s, with: edit)) { XCTAssertEqual($0 as? CoalError, .dayClosed(self.today)) }
        XCTAssertThrowsError(try sales.delete(s))
        XCTAssertThrowsError(try sell("1", to: "Приаргунск", by: nil))
        XCTAssertEqual(s.kilograms, 2000)

        try days.reopen(today)
        try sales.update(s, with: edit)
        XCTAssertEqual(s.coalAmount, 1_950_000)
        XCTAssertEqual(try days.dayReport(today).totals.revenue, 2_050_000)
        let closed = try days.close(today)
        XCTAssertEqual(closed.totalRevenue, 2_050_000, "closing again stores the new totals")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<CoalWorkDay>()), 1)
    }

    func testMovingASaleIntoAClosedDayIsRefused() throws {
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        try days.close(yesterday)
        let s = try sell("1", to: "Приаргунск", by: nil)
        var edit = CoalSaleDraft(s)
        edit.saleDate = yesterday.addingTimeInterval(10 * 3600)
        XCTAssertThrowsError(try sales.update(s, with: edit)) { XCTAssertEqual($0 as? CoalError, .dayClosed(yesterday)) }
    }

    func testEditingAPastSaleRecomputesTheDebt() throws {
        let s = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        try payments.pay(mungalov, amount: 50_000)
        var edit = CoalSaleDraft(s)
        edit.choose(try location("Приаргунск"))
        try sales.update(s, with: edit)
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 70_000)
        edit.choose(kaydolov)
        try sales.update(s, with: edit)
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, -50_000, "the payment stays; the delivery moved")
        XCTAssertEqual(try drivers.balance(of: kaydolov).debt, 120_000)
        edit.vehicle = .own
        try sales.update(s, with: edit)
        XCTAssertEqual(try drivers.balance(of: kaydolov).debt, 0)
        XCTAssertNil(s.driverId)
        XCTAssertEqual(s.driverPaymentAmount, 0)
        try sales.delete(try sell("1", to: "Приаргунск", by: kaydolov))
        XCTAssertEqual(try drivers.balance(of: kaydolov).debt, 0)
    }

    func testPriceChangeKeepsSavedSales() throws {
        let old = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        try settings.setPricePerTon(700_000)
        try settings.updateLocation(try location("Новоцурухайтуй"), name: "Новоцурухайтуй",
                                    deliveryKopecks: 150_000, driverPaymentKopecks: nil)
        XCTAssertEqual(old.totalAmount, 1_400_000)
        XCTAssertEqual(old.coalPricePerTonSnapshot, 650_000)
        XCTAssertFalse(old.isPriceAdjusted)
        let new = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        XCTAssertEqual(new.totalAmount, 1_550_000)
        // Editing the old sale keeps its own prices.
        var edit = CoalSaleDraft(old)
        edit.comment = "позвонить"
        try sales.update(old, with: edit)
        XCTAssertEqual(old.totalAmount, 1_400_000)
        XCTAssertEqual(old.comment, "позвонить")
    }

    func testManualCorrectionKeepsListAndActualAmounts() throws {
        var d = sales.newDraft()
        d.tonsText = "2"
        d.choose(try location("Новоцурухайтуй"))
        d.choose(mungalov)
        d.startManual()
        XCTAssertEqual(d.manualCoalText, "13000")
        d.manualCoalText = "12500"
        XCTAssertEqual(d.total, 1_350_000)
        let s = try sales.save(d)
        XCTAssertEqual(s.coalAmount, 1_250_000)
        XCTAssertEqual(s.totalAmount, 1_350_000)
        XCTAssertEqual(s.listTotal, 1_400_000)
        XCTAssertTrue(s.isPriceAdjusted)
        XCTAssertEqual(s.driverPaymentAmount, 100_000, "the driver's pay does not follow the discount")
        let row = try XCTUnwrap(try days.dayReport(today).rows.first)
        XCTAssertEqual(row.listTotal, 1_400_000)
        let reopened = CoalSaleDraft(s)
        XCTAssertTrue(reopened.isManual)
        XCTAssertEqual(reopened.total, 1_350_000)
    }

    func testSeparateDriverPayPerVillage() throws {
        try settings.updateLocation(try location("Приаргунск"), name: "Приаргунск", deliveryKopecks: 120_000,
                                    driverPaymentKopecks: 100_000)
        let s = try sell("1", to: "Приаргунск", by: kaydolov)
        XCTAssertEqual(s.deliveryPrice, 120_000)
        XCTAssertEqual(s.driverPaymentAmount, 100_000)
        let report = try days.dayReport(today)
        XCTAssertEqual(report.totals.driverPayments, 100_000)
        XCTAssertEqual(report.totals.remainderAfterDrivers, 670_000)
    }

    func testValidation() throws {
        var d = sales.newDraft()
        XCTAssertEqual(d.validationErrors, ["Укажите количество угля", "Выберите населённый пункт",
                                            "Выберите водителя наёмной машины", "Укажите оплату водителю, например 1000"])
        d.tonsText = "0"
        d.choose(try location("Приаргунск"))
        XCTAssertTrue(d.validationErrors.contains("Количество угля должно быть больше нуля"))
        d.tonsText = "1,5"
        XCTAssertEqual(d.validationErrors, ["Выберите водителя наёмной машины"], "hired needs a driver")
        XCTAssertThrowsError(try sales.save(d))
        d.vehicle = .own
        XCTAssertTrue(d.isValid, "own vehicle needs no driver")
        d.tonsText = "101"
        XCTAssertFalse(d.isValid)
    }

    func testSecondSaveOfTheSameFormIsRefused() throws {
        var d = sales.newDraft()
        d.tonsText = "1"
        d.choose(try location("Приаргунск"))
        d.vehicle = .own
        try sales.save(d)
        XCTAssertThrowsError(try sales.save(d)) { XCTAssertEqual($0 as? CoalError, .alreadySaved) }
        XCTAssertEqual(try sales.sales(on: today).count, 1)
    }

    func testPaymentRules() throws {
        XCTAssertThrowsError(try payments.pay(mungalov, amount: 100)) { XCTAssertEqual($0 as? CoalError, .nothingToPay) }
        try sell("2", to: "Новоцурухайтуй", by: mungalov)
        XCTAssertThrowsError(try payments.pay(mungalov, amount: 0))
        XCTAssertThrowsError(try payments.pay(mungalov, amount: 100_001)) {
            XCTAssertEqual($0 as? CoalError, .paymentTooLarge(debt: 100_000))
        }
        let id = UUID()
        let p = try payments.pay(mungalov, amount: 100_000, id: id)
        XCTAssertThrowsError(try payments.pay(mungalov, amount: 1, id: id)) { XCTAssertEqual($0 as? CoalError, .alreadySaved) }
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 0)
        try payments.delete(p)
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 100_000)
    }

    func testArchivedDriverKeepsHistoryAndLeavesThePicker() throws {
        try sell("2", to: "Новоцурухайтуй", by: mungalov)
        XCTAssertThrowsError(try drivers.delete(mungalov)) { XCTAssertEqual($0 as? CoalError, .driverHasHistory) }
        try drivers.archive(mungalov)
        XCTAssertEqual(try drivers.active().map(\.lastName), ["Кайдолов"])
        XCTAssertEqual(try drivers.sales(of: mungalov).count, 1)
        XCTAssertEqual(try drivers.balance(of: mungalov).debt, 100_000)
        XCTAssertThrowsError(try sell("1", to: "Приаргунск", by: mungalov)) {
            XCTAssertEqual($0 as? CoalError, .driverArchived)
        }
        try drivers.restore(mungalov)
        XCTAssertEqual(try drivers.active().count, 2)
        // A driver without history is deleted.
        let new = try drivers.add(DriverDraft(lastName: "Петров"))
        try drivers.delete(new)
        XCTAssertEqual(try drivers.all().count, 2)
    }

    func testRenamingADriverRenamesTheirSales() throws {
        let s = try sell("2", to: "Новоцурухайтуй", by: mungalov)
        var d = DriverDraft(mungalov)
        d.firstName = " Сергей "
        try drivers.update(mungalov, with: d)
        XCTAssertEqual(s.driverNameSnapshot, "Мунгалов Сергей")
        XCTAssertEqual(mungalov.letter, "М")
        XCTAssertThrowsError(try drivers.add(DriverDraft(lastName: "  ")))
    }

    func testDriverSearch() throws {
        try drivers.add(DriverDraft(lastName: "Мусин"))
        XCTAssertEqual(try drivers.active(search: "му").map(\.lastName), ["Мунгалов", "Мусин"])
        XCTAssertEqual(try drivers.active(search: "КАЙ").map(\.lastName), ["Кайдолов"])
        XCTAssertEqual(try drivers.active(search: "лов").count, 0, "matches the start of a name")
    }

    func testLocations() throws {
        try settings.addLocation(name: " Забайкальск ", deliveryKopecks: 150_000, driverPaymentKopecks: 140_000)
        XCTAssertEqual(try settings.activeLocations().map(\.name), ["Забайкальск", "Новоцурухайтуй", "Приаргунск"])
        XCTAssertThrowsError(try settings.addLocation(name: "приаргунск", deliveryKopecks: 1, driverPaymentKopecks: nil)) {
            XCTAssertEqual($0 as? CoalError, .duplicateLocation("приаргунск"))
        }
        let s = try sell("1", to: "Приаргунск", by: nil)
        try settings.removeLocation(try location("Приаргунск"))
        XCTAssertEqual(try settings.activeLocations().count, 2)
        XCTAssertEqual(s.locationNameSnapshot, "Приаргунск", "the sale keeps the name")
        let back = try settings.addLocation(name: "Приаргунск", deliveryKopecks: 130_000, driverPaymentKopecks: nil)
        XCTAssertTrue(back.isActive)
        XCTAssertEqual(back.deliveryPriceKopecks, 130_000)
        XCTAssertEqual(try settings.allLocations().count, 3, "a hidden village is shown again, not duplicated")
        XCTAssertThrowsError(try settings.setPricePerTon(0))
    }

    func testMonthReport() throws {
        try sell("2", to: "Новоцурухайтуй", by: mungalov)
        clock = calendar.date(day: 20, month: 11, year: 2026).addingTimeInterval(10 * 3600)
        try sell("1", to: "Приаргунск", by: nil)
        try payments.pay(mungalov, amount: 40_000)
        clock = calendar.date(day: 2, month: 12, year: 2026).addingTimeInterval(10 * 3600)
        try sell("1", to: "Приаргунск", by: kaydolov)
        let november = try days.monthReport(month: 11, year: 2026)
        XCTAssertEqual(november.periodTitle(), "Ноябрь 2026")
        XCTAssertEqual(november.rows.count, 2)
        XCTAssertEqual(november.totals.revenue, 2_170_000)
        XCTAssertEqual(november.totals.kilograms, 3000)
        XCTAssertEqual(november.drivers.map(\.debtAfter), [60_000])
        XCTAssertEqual(november.totalPaid, 40_000)
        XCTAssertEqual(november.locations.map(\.revenue), [1_400_000, 770_000])
        let december = try days.monthReport(month: 12, year: 2026)
        XCTAssertEqual(december.drivers.first { $0.driverId == mungalov.id }?.debtBefore, 60_000)
    }

    func testWorkDayIdIsStable() throws {
        try sellScenarioDay()
        let day = try days.close(today)
        XCTAssertEqual(day.id, StableId.coalWorkDay(today, calendar: calendar))
    }

    func testLocationIdsAreStable() throws {
        XCTAssertEqual(try location("Приаргунск").id, StableId.deliveryLocation("Приаргунск"))
        let added = try settings.addLocation(name: "Луговое", deliveryKopecks: 90_000, driverPaymentKopecks: nil)
        XCTAssertEqual(added.id, StableId.deliveryLocation("Луговое"))
    }
}
