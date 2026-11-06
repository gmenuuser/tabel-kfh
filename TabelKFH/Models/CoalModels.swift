import Foundation
import SwiftData

// The coal module («Продажа угля»). Money is whole kopecks (`Int`), mass is whole kilograms (`Int`);
// see `CoalMath`. Nothing here refers to the timesheet models.

/// Who delivers a sale.
enum VehicleType: String, Codable, CaseIterable {
    /// A hired driver's truck: the delivery is owed to the driver.
    case hired
    /// The farm's own truck: the delivery stays with the farm, nobody is owed anything.
    case own

    var title: String {
        switch self {
        case .hired: return "Наёмная машина"
        case .own: return "Собственная машина КФХ"
        }
    }

    /// For table cells: «Наёмная», «Своя».
    var shortTitle: String {
        switch self {
        case .hired: return "Наёмная"
        case .own: return "Своя"
        }
    }
}

/// The coal price. One row per store, see `CoalSettingsService.current()`.
@Model
final class CoalSettings {
    var pricePerTonKopecks: Int
    var createdAt: Date
    var updatedAt: Date

    init(pricePerTonKopecks: Int = CoalDefaults.pricePerTonKopecks, createdAt: Date, updatedAt: Date) {
        self.pricePerTonKopecks = pricePerTonKopecks
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// A village the farm delivers to, with its delivery price for the buyer and the pay of a hired driver.
@Model
final class DeliveryLocation {
    @Attribute(.unique) var id: UUID
    var name: String
    var deliveryPriceKopecks: Int
    /// What a hired driver gets for one delivery here; nil = the delivery price.
    var driverPaymentKopecks: Int?
    /// «Удалить» only hides a location: sales keep its name.
    var isActive: Bool
    var createdAt: Date

    init(id: UUID = UUID(), name: String, deliveryPriceKopecks: Int, driverPaymentKopecks: Int? = nil,
         isActive: Bool = true, createdAt: Date) {
        self.id = id
        self.name = name
        self.deliveryPriceKopecks = deliveryPriceKopecks
        self.driverPaymentKopecks = driverPaymentKopecks
        self.isActive = isActive
        self.createdAt = createdAt
    }

    /// The hired driver's pay for one delivery here.
    var effectiveDriverPaymentKopecks: Int { driverPaymentKopecks ?? deliveryPriceKopecks }
}

/// A hired driver. Archiving hides the driver from the picker; sales and payments stay.
@Model
final class Driver {
    @Attribute(.unique) var id: UUID
    var lastName: String
    var firstName: String
    var middleName: String
    var phone: String
    var comment: String
    var isArchived: Bool
    var createdAt: Date

    init(id: UUID = UUID(), lastName: String, firstName: String = "", middleName: String = "",
         phone: String = "", comment: String = "", isArchived: Bool = false, createdAt: Date) {
        self.id = id
        self.lastName = lastName
        self.firstName = firstName
        self.middleName = middleName
        self.phone = phone
        self.comment = comment
        self.isArchived = isArchived
        self.createdAt = createdAt
    }

    /// «Мунгалов Сергей Иванович», empty parts skipped.
    var fullName: String {
        [lastName, firstName, middleName].joined(separator: " ")
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// The first letter of the surname, «М».
    var letter: String {
        lastName.trimmingCharacters(in: .whitespacesAndNewlines).first.map { String($0).uppercased() } ?? "?"
    }
}

/// One sale with the prices that were in force when it was made.
@Model
final class CoalSale {
    @Attribute(.unique) var id: UUID
    var saleDate: Date
    var kilograms: Int
    var locationNameSnapshot: String
    /// The list prices at the moment of the sale.
    var coalPricePerTonSnapshot: Int
    var deliveryPriceSnapshot: Int
    /// What the buyer actually pays: the list amounts unless corrected by hand.
    var coalAmount: Int
    var deliveryPrice: Int
    var totalAmount: Int
    var vehicleTypeRaw: String
    var driverId: UUID?
    var driverNameSnapshot: String
    /// Owed to the hired driver for this delivery; 0 for the own vehicle.
    var driverPaymentAmount: Int
    var comment: String
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(), saleDate: Date, kilograms: Int, locationNameSnapshot: String,
         coalPricePerTonSnapshot: Int, deliveryPriceSnapshot: Int, coalAmount: Int, deliveryPrice: Int,
         vehicleType: VehicleType, driverId: UUID? = nil, driverNameSnapshot: String = "",
         driverPaymentAmount: Int = 0, comment: String = "", createdAt: Date, updatedAt: Date) {
        self.id = id
        self.saleDate = saleDate
        self.kilograms = kilograms
        self.locationNameSnapshot = locationNameSnapshot
        self.coalPricePerTonSnapshot = coalPricePerTonSnapshot
        self.deliveryPriceSnapshot = deliveryPriceSnapshot
        self.coalAmount = coalAmount
        self.deliveryPrice = deliveryPrice
        self.totalAmount = coalAmount + deliveryPrice
        self.vehicleTypeRaw = vehicleType.rawValue
        self.driverId = driverId
        self.driverNameSnapshot = driverNameSnapshot
        self.driverPaymentAmount = driverPaymentAmount
        self.comment = comment
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var vehicleType: VehicleType {
        get { VehicleType(rawValue: vehicleTypeRaw) ?? .own }
        set { vehicleTypeRaw = newValue.rawValue }
    }

    /// The amount at the list prices of the moment of the sale.
    var listTotal: Int {
        CoalMath.coalAmount(kilograms: kilograms, pricePerTon: coalPricePerTonSnapshot) + deliveryPriceSnapshot
    }

    /// The amounts differ from the list prices.
    var isPriceAdjusted: Bool { totalAmount != listTotal }
}

/// Money actually handed to a hired driver.
@Model
final class DriverPayment {
    @Attribute(.unique) var id: UUID
    var driverId: UUID
    var driverNameSnapshot: String
    var paymentDate: Date
    var amount: Int
    var comment: String
    var createdAt: Date

    init(id: UUID = UUID(), driverId: UUID, driverNameSnapshot: String, paymentDate: Date, amount: Int,
         comment: String = "", createdAt: Date) {
        self.id = id
        self.driverId = driverId
        self.driverNameSnapshot = driverNameSnapshot
        self.paymentDate = paymentDate
        self.amount = amount
        self.comment = comment
        self.createdAt = createdAt
    }
}

/// A closed (or reopened) working day. The totals are what was shown when the day was closed;
/// reports and debts are always computed again from the sales and payments.
@Model
final class CoalWorkDay {
    @Attribute(.unique) var id: UUID
    /// Midnight of the day on the farm calendar.
    var date: Date
    var isClosed: Bool
    var closedAt: Date?
    var totalRevenue: Int
    var totalKilograms: Int
    var totalDriverPayments: Int
    var ownVehicleDeliveryRevenue: Int
    var createdAt: Date

    init(id: UUID = UUID(), date: Date, isClosed: Bool = false, closedAt: Date? = nil, totalRevenue: Int = 0,
         totalKilograms: Int = 0, totalDriverPayments: Int = 0, ownVehicleDeliveryRevenue: Int = 0,
         createdAt: Date) {
        self.id = id
        self.date = date
        self.isClosed = isClosed
        self.closedAt = closedAt
        self.totalRevenue = totalRevenue
        self.totalKilograms = totalKilograms
        self.totalDriverPayments = totalDriverPayments
        self.ownVehicleDeliveryRevenue = ownVehicleDeliveryRevenue
        self.createdAt = createdAt
    }
}

/// The owner's starting prices.
enum CoalDefaults {
    static let pricePerTonKopecks = 650_000
    static let locations: [(name: String, deliveryKopecks: Int)] = [
        ("Новоцурухайтуй", 100_000),
        ("Приаргунск", 120_000),
    ]
}
