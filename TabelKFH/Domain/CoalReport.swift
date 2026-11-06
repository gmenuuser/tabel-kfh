import Foundation

/// One line of the sales table.
struct CoalSaleRow: Equatable {
    let number: Int
    let id: UUID
    let date: Date
    let kilograms: Int
    let location: String
    let vehicle: VehicleType
    /// The hired driver's name; "" for the own vehicle.
    let driver: String
    let coalAmount: Int
    let deliveryPrice: Int
    let totalAmount: Int
    let driverPayment: Int
    /// The amount at the list prices when it differs from `totalAmount`, else nil.
    let listTotal: Int?
    let comment: String
}

/// The figures of a day or a month, computed from the sales.
struct CoalTotals: Equatable {
    var salesCount = 0
    var kilograms = 0
    /// А. Everything the buyers pay: coal and delivery, own and hired trucks.
    var revenue = 0
    /// Б.
    var coalAmount = 0
    /// В.
    var deliveryAmount = 0
    /// Г. Owed to hired drivers for the deliveries of the period (not what was paid).
    var driverPayments = 0
    /// Д. Deliveries made by the farm's own truck.
    var ownVehicleDeliveryRevenue = 0
    var hiredDeliveries = 0
    var ownDeliveries = 0

    /// Е. А − Г. Not a profit: the coal's cost and other expenses are not counted.
    var remainderAfterDrivers: Int { revenue - driverPayments }

    init() {}

    init(_ sales: [CoalSale]) {
        for s in sales { add(s) }
    }

    mutating func add(_ s: CoalSale) {
        salesCount += 1
        kilograms += s.kilograms
        revenue += s.totalAmount
        coalAmount += s.coalAmount
        deliveryAmount += s.deliveryPrice
        switch s.vehicleType {
        case .hired:
            hiredDeliveries += 1
            driverPayments += s.driverPaymentAmount
        case .own:
            ownDeliveries += 1
            ownVehicleDeliveryRevenue += s.deliveryPrice
        }
    }
}

/// What the farm owes one hired driver over a period.
struct DriverSettlement: Equatable, Identifiable {
    let driverId: UUID
    let name: String
    /// Debt carried in from before the period.
    var debtBefore = 0
    var deliveries = 0
    /// Accrued for the period's deliveries.
    var accrued = 0
    /// Paid during the period.
    var paid = 0

    var id: UUID { driverId }
    /// To pay at the end of the period: debt before + accrued − paid.
    var debtAfter: Int { debtBefore + accrued - paid }
}

/// One village's share of a period.
struct LocationTotal: Equatable {
    let name: String
    var salesCount = 0
    var kilograms = 0
    var revenue = 0
    var deliveryAmount = 0
}

/// A payment as listed in a report.
struct CoalPaymentRow: Equatable {
    let date: Date
    let driver: String
    let amount: Int
    let comment: String
}

/// A day or a month report: the sales, the totals, the drivers' accounts and the villages.
/// Built from the saved sales and payments every time; nothing is taken from stored totals.
struct CoalReport: Equatable {
    enum Kind: Equatable { case day, month }

    let kind: Kind
    /// Midnight of the first day and of the day after the last one.
    let start: Date
    let end: Date
    let farmName: String
    let managerName: String
    let rows: [CoalSaleRow]
    let totals: CoalTotals
    let drivers: [DriverSettlement]
    let locations: [LocationTotal]
    let payments: [CoalPaymentRow]
    /// When the day was closed; nil for an open day and for a month.
    let closedAt: Date?
    let createdAt: Date

    /// Σ of the drivers' debts at the end of the period.
    var totalDebtAfter: Int { drivers.reduce(0) { $0 + $1.debtAfter } }
    var totalPaid: Int { payments.reduce(0) { $0 + $1.amount } }

    /// «06.11.2026» or «Ноябрь 2026».
    func periodTitle(calendar: Calendar = .farm) -> String {
        switch kind {
        case .day:
            return CoalText.date(start, calendar: calendar)
        case .month:
            let c = calendar.dateComponents([.month, .year], from: start)
            return calendar.monthTitle(month: c.month ?? 1, year: c.year ?? 2026)
        }
    }
}

enum CoalReportBuilder {
    /// - Parameters:
    ///   - sales, payments: every sale and payment of the store (earlier ones give the debt carried in).
    ///   - drivers: every driver, archived ones included.
    static func build(kind: CoalReport.Kind, start: Date, end: Date, sales: [CoalSale], payments: [DriverPayment],
                      drivers: [Driver], farm: FarmInfo, closedAt: Date? = nil, createdAt: Date,
                      calendar: Calendar = .farm) -> CoalReport {
        let inPeriod = sales.filter { $0.saleDate >= start && $0.saleDate < end }
            .sorted { ($0.saleDate, $0.createdAt) < ($1.saleDate, $1.createdAt) }
        let rows = inPeriod.enumerated().map { index, s in
            CoalSaleRow(number: index + 1, id: s.id, date: s.saleDate, kilograms: s.kilograms,
                        location: s.locationNameSnapshot, vehicle: s.vehicleType,
                        driver: s.vehicleType == .hired ? s.driverNameSnapshot : "",
                        coalAmount: s.coalAmount, deliveryPrice: s.deliveryPrice, totalAmount: s.totalAmount,
                        driverPayment: s.vehicleType == .hired ? s.driverPaymentAmount : 0,
                        listTotal: s.isPriceAdjusted ? s.listTotal : nil, comment: s.comment)
        }

        let names = Dictionary(drivers.map { ($0.id, $0.fullName) }, uniquingKeysWith: { a, _ in a })
        var accounts: [UUID: DriverSettlement] = [:]
        func account(_ id: UUID, fallbackName: String) -> DriverSettlement {
            accounts[id] ?? DriverSettlement(driverId: id, name: names[id] ?? fallbackName)
        }
        for s in sales where s.vehicleType == .hired && s.saleDate < end {
            guard let id = s.driverId else { continue }
            var a = account(id, fallbackName: s.driverNameSnapshot)
            if s.saleDate < start {
                a.debtBefore += s.driverPaymentAmount
            } else {
                a.deliveries += 1
                a.accrued += s.driverPaymentAmount
            }
            accounts[id] = a
        }
        let periodPayments = payments.filter { $0.paymentDate >= start && $0.paymentDate < end }
            .sorted { ($0.paymentDate, $0.createdAt) < ($1.paymentDate, $1.createdAt) }
        for p in payments where p.paymentDate < end {
            var a = account(p.driverId, fallbackName: p.driverNameSnapshot)
            if p.paymentDate < start { a.debtBefore -= p.amount } else { a.paid += p.amount }
            accounts[p.driverId] = a
        }
        let settlements = accounts.values
            .filter { $0.deliveries > 0 || $0.paid != 0 || $0.debtBefore != 0 }
            .sorted { $0.name.compare($1.name, locale: Locale(identifier: "ru_RU")) == .orderedAscending }

        var locations: [String: LocationTotal] = [:]
        for s in inPeriod {
            var l = locations[s.locationNameSnapshot] ?? LocationTotal(name: s.locationNameSnapshot)
            l.salesCount += 1
            l.kilograms += s.kilograms
            l.revenue += s.totalAmount
            l.deliveryAmount += s.deliveryPrice
            locations[s.locationNameSnapshot] = l
        }

        return CoalReport(
            kind: kind, start: start, end: end, farmName: farm.farmName, managerName: farm.managerName,
            rows: rows, totals: CoalTotals(inPeriod), drivers: settlements,
            locations: locations.values.sorted {
                $0.name.compare($1.name, locale: Locale(identifier: "ru_RU")) == .orderedAscending
            },
            payments: periodPayments.map {
                CoalPaymentRow(date: $0.paymentDate, driver: names[$0.driverId] ?? $0.driverNameSnapshot,
                               amount: $0.amount, comment: $0.comment)
            },
            closedAt: closedAt, createdAt: createdAt)
    }
}

/// A driver's account over all time.
struct DriverBalance: Equatable {
    var deliveries = 0
    /// Σ of the driver's hired deliveries.
    var earned = 0
    /// Σ of the payments.
    var paid = 0
    /// What the farm still owes; never set by hand, always earned − paid.
    var debt: Int { earned - paid }

    init() {}

    init(driverId: UUID, sales: [CoalSale], payments: [DriverPayment]) {
        for s in sales where s.vehicleType == .hired && s.driverId == driverId {
            deliveries += 1
            earned += s.driverPaymentAmount
        }
        for p in payments where p.driverId == driverId { paid += p.amount }
    }
}
