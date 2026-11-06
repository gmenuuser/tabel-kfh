import Foundation

/// The coal module in the backup file: every stored field of every coal row.
struct CoalBackup: Codable, Equatable {
    struct Settings: Codable, Equatable {
        let pricePerTonKopecks: Int
        let createdAt, updatedAt: Date
    }

    struct Location: Codable, Equatable {
        let id: UUID
        let name: String
        let deliveryPriceKopecks: Int
        let driverPaymentKopecks: Int?
        let isActive: Bool
        let createdAt: Date
    }

    struct DriverRow: Codable, Equatable {
        let id: UUID
        let lastName, firstName, middleName, phone, comment: String
        let isArchived: Bool
        let createdAt: Date
    }

    struct Sale: Codable, Equatable {
        let id: UUID
        let saleDate: Date
        let kilograms: Int
        let locationNameSnapshot: String
        let coalPricePerTonSnapshot, deliveryPriceSnapshot: Int
        let coalAmount, deliveryPrice, totalAmount: Int
        let vehicleType: VehicleType
        let driverId: UUID?
        let driverNameSnapshot: String
        let driverPaymentAmount: Int
        let comment: String
        let createdAt, updatedAt: Date
    }

    struct Payment: Codable, Equatable {
        let id, driverId: UUID
        let driverNameSnapshot: String
        let paymentDate: Date
        let amount: Int
        let comment: String
        let createdAt: Date
    }

    struct Day: Codable, Equatable {
        let id: UUID
        let date: Date
        let isClosed: Bool
        let closedAt: Date?
        let totalRevenue, totalKilograms, totalDriverPayments, ownVehicleDeliveryRevenue: Int
        let createdAt: Date
    }

    /// nil when the coal module was never opened.
    var settings: Settings?
    var locations: [Location]
    var drivers: [DriverRow]
    var sales: [Sale]
    var payments: [Payment]
    var days: [Day]

    /// The store's rules: unique ids, a hired sale has a listed driver and an own one has none, payments
    /// belong to listed drivers, one row per day, amounts add up.
    func isValid(calendar: Calendar) -> Bool {
        func unique<T>(_ items: [T], _ id: (T) -> UUID) -> Bool { Set(items.map(id)).count == items.count }
        guard unique(locations, \.id), unique(drivers, \.id), unique(sales, \.id), unique(payments, \.id),
              unique(days, \.id) else { return false }
        if let settings, settings.pricePerTonKopecks <= 0 { return false }
        let driverIds = Set(drivers.map(\.id))
        for s in sales {
            guard s.kilograms > 0, s.coalAmount >= 0, s.deliveryPrice >= 0, s.driverPaymentAmount >= 0,
                  s.totalAmount == s.coalAmount + s.deliveryPrice else { return false }
            switch s.vehicleType {
            case .hired: guard let id = s.driverId, driverIds.contains(id) else { return false }
            case .own: guard s.driverId == nil, s.driverPaymentAmount == 0 else { return false }
            }
        }
        for p in payments {
            guard driverIds.contains(p.driverId), p.amount > 0 else { return false }
        }
        return Set(days.map { calendar.dayStart($0.date) }).count == days.count
    }
}
