import Foundation
import SwiftData

/// Payments to hired drivers. A payment is never made by the app on its own: only «Выплатить водителю» adds one.
@MainActor
final class DriverPaymentService {
    private let context: ModelContext
    private let now: () -> Date

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
    }

    /// Records money handed to the driver. Refused when nothing is owed or the amount is above the debt;
    /// `id` guards against saving the same form twice.
    @discardableResult
    func pay(_ driver: Driver, amount: Int, date: Date? = nil, comment: String = "", id: UUID = UUID()) throws -> DriverPayment {
        if try context.fetchCount(FetchDescriptor<DriverPayment>(predicate: #Predicate { $0.id == id })) > 0 {
            throw CoalError.alreadySaved
        }
        guard amount > 0 else { throw CoalError.invalid(["Сумма выплаты должна быть больше нуля"]) }
        let debt = try DriverService(context: context, now: now).balance(of: driver).debt
        guard debt > 0 else { throw CoalError.nothingToPay }
        guard amount <= debt else { throw CoalError.paymentTooLarge(debt: debt) }
        let moment = now()
        let p = DriverPayment(id: id, driverId: driver.id, driverNameSnapshot: driver.fullName,
                              paymentDate: date ?? moment, amount: amount,
                              comment: comment.trimmingCharacters(in: .whitespacesAndNewlines), createdAt: moment)
        context.insert(p)
        try context.save()
        return p
    }

    /// Removes a payment entered by mistake; the debt grows back.
    func delete(_ p: DriverPayment) throws {
        context.delete(p)
        try context.save()
    }

    /// Payments with `start <= paymentDate < end`, oldest first.
    func payments(from start: Date, to end: Date) throws -> [DriverPayment] {
        try context.fetch(FetchDescriptor<DriverPayment>(
            predicate: #Predicate { $0.paymentDate >= start && $0.paymentDate < end },
            sortBy: [SortDescriptor(\.paymentDate), SortDescriptor(\.createdAt)]))
    }

    func allPayments() throws -> [DriverPayment] {
        try context.fetch(FetchDescriptor<DriverPayment>(sortBy: [SortDescriptor(\.paymentDate, order: .reverse)]))
    }
}

/// Working days of the coal module: reports, «Завершить рабочий день», «Открыть день для редактирования».
@MainActor
final class CoalDayService {
    private let context: ModelContext
    private let calendar: Calendar
    private let now: () -> Date

    init(context: ModelContext, calendar: Calendar = .farm, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.calendar = calendar
        self.now = now
    }

    func workDay(on date: Date) throws -> CoalWorkDay? {
        let day = calendar.dayStart(date)
        return try context.fetch(FetchDescriptor<CoalWorkDay>(predicate: #Predicate { $0.date == day })).first
    }

    func isClosed(_ date: Date) throws -> Bool {
        try workDay(on: date)?.isClosed ?? false
    }

    /// The report of one day, computed from the sales and payments.
    func dayReport(_ date: Date) throws -> CoalReport {
        let start = calendar.dayStart(date)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start
        let workDay = try workDay(on: start)
        return try report(kind: .day, start: start, end: end,
                          closedAt: workDay?.isClosed == true ? workDay?.closedAt : nil)
    }

    /// The report of a calendar month.
    func monthReport(month: Int, year: Int) throws -> CoalReport {
        let start = calendar.date(day: 1, month: month, year: year)
        let end = calendar.date(byAdding: .month, value: 1, to: start) ?? start
        return try report(kind: .month, start: start, end: end, closedAt: nil)
    }

    /// «Завершить рабочий день»: locks the day's sales and keeps its totals with the moment of closing.
    /// Nothing is paid: the drivers' debts stay as they are.
    @discardableResult
    func close(_ date: Date) throws -> CoalWorkDay {
        let start = calendar.dayStart(date)
        let existing = try workDay(on: start)
        if existing?.isClosed == true { throw CoalError.dayAlreadyClosed }
        let report = try dayReport(start)
        let moment = now()
        let day = existing ?? CoalWorkDay(id: StableId.coalWorkDay(start, calendar: calendar), date: start, createdAt: moment)
        if existing == nil { context.insert(day) }
        day.isClosed = true
        day.closedAt = moment
        day.totalRevenue = report.totals.revenue
        day.totalKilograms = report.totals.kilograms
        day.totalDriverPayments = report.totals.driverPayments
        day.ownVehicleDeliveryRevenue = report.totals.ownVehicleDeliveryRevenue
        try context.save()
        return day
    }

    /// «Открыть день для редактирования». Closing it again stores the new totals.
    func reopen(_ date: Date) throws {
        guard let day = try workDay(on: date), day.isClosed else { throw CoalError.dayNotClosed }
        day.isClosed = false
        try context.save()
    }

    /// Closed days, newest first.
    func closedDays() throws -> [CoalWorkDay] {
        try context.fetch(FetchDescriptor<CoalWorkDay>(predicate: #Predicate { $0.isClosed == true },
                                                       sortBy: [SortDescriptor(\.date, order: .reverse)]))
    }

    private func report(kind: CoalReport.Kind, start: Date, end: Date, closedAt: Date?) throws -> CoalReport {
        let sales = try context.fetch(FetchDescriptor<CoalSale>(predicate: #Predicate { $0.saleDate < end }))
        let payments = try context.fetch(FetchDescriptor<DriverPayment>(predicate: #Predicate { $0.paymentDate < end }))
        let drivers = try context.fetch(FetchDescriptor<Driver>())
        return CoalReportBuilder.build(kind: kind, start: start, end: end, sales: sales, payments: payments,
                                       drivers: drivers, farm: FarmSettingsStore.current(in: context).info,
                                       closedAt: closedAt, createdAt: now(), calendar: calendar)
    }
}
