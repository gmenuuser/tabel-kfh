import Foundation
import SwiftData

/// «Цены»: the coal price per ton and the villages with their delivery prices.
/// Changing a price never touches saved sales: each sale keeps its own copy.
@MainActor
final class CoalSettingsService {
    private let context: ModelContext
    private let now: () -> Date

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
    }

    /// The settings row. On the first call it is created with 6500 ₽/т, and the owner's two villages
    /// (Новоцурухайтуй 1000 ₽, Приаргунск 1200 ₽) are added once.
    @discardableResult
    func current() -> CoalSettings {
        var descriptor = FetchDescriptor<CoalSettings>(sortBy: [SortDescriptor(\.createdAt)])
        descriptor.fetchLimit = 1
        if let existing = (try? context.fetch(descriptor))?.first { return existing }
        let moment = now()
        let settings = CoalSettings(createdAt: moment, updatedAt: moment)
        context.insert(settings)
        if ((try? context.fetchCount(FetchDescriptor<DeliveryLocation>())) ?? 0) == 0 {
            for (index, d) in CoalDefaults.locations.enumerated() {
                context.insert(DeliveryLocation(name: d.name, deliveryPriceKopecks: d.deliveryKopecks,
                                                createdAt: moment.addingTimeInterval(Double(index))))
            }
        }
        try? context.save()
        return settings
    }

    var pricePerTon: Int { current().pricePerTonKopecks }

    func setPricePerTon(_ kopecks: Int) throws {
        guard kopecks > 0, kopecks <= CoalMath.maxKopecks else {
            throw CoalError.invalid(["Цена угля — больше нуля и не больше 10 000 000 ₽ за тонну"])
        }
        let settings = current()
        guard settings.pricePerTonKopecks != kopecks else { return }
        settings.pricePerTonKopecks = kopecks
        settings.updatedAt = now()
        try context.save()
    }

    // MARK: - Villages

    /// The villages offered in a new sale, by name.
    func activeLocations() throws -> [DeliveryLocation] {
        current()
        return try allLocations().filter(\.isActive)
    }

    func allLocations() throws -> [DeliveryLocation] {
        try context.fetch(FetchDescriptor<DeliveryLocation>()).sorted {
            $0.name.compare($1.name, options: [.caseInsensitive], locale: Locale(identifier: "ru_RU")) == .orderedAscending
        }
    }

    /// Adds a village. A hidden («удалённый») village with the same name is shown again with the new prices.
    @discardableResult
    func addLocation(name: String, deliveryKopecks: Int, driverPaymentKopecks: Int?) throws -> DeliveryLocation {
        let trimmed = try validate(name: name, delivery: deliveryKopecks, driverPayment: driverPaymentKopecks)
        let same = try allLocations().filter { Self.sameName($0.name, trimmed) }
        if same.contains(where: \.isActive) { throw CoalError.duplicateLocation(trimmed) }
        let location: DeliveryLocation
        if let hidden = same.first {
            location = hidden
            location.name = trimmed
            location.isActive = true
        } else {
            location = DeliveryLocation(name: trimmed, deliveryPriceKopecks: deliveryKopecks, createdAt: now())
            context.insert(location)
        }
        location.deliveryPriceKopecks = deliveryKopecks
        location.driverPaymentKopecks = driverPaymentKopecks
        try context.save()
        return location
    }

    func updateLocation(_ location: DeliveryLocation, name: String, deliveryKopecks: Int,
                        driverPaymentKopecks: Int?) throws {
        let trimmed = try validate(name: name, delivery: deliveryKopecks, driverPayment: driverPaymentKopecks)
        if try allLocations().contains(where: { $0.id != location.id && $0.isActive && Self.sameName($0.name, trimmed) }) {
            throw CoalError.duplicateLocation(trimmed)
        }
        location.name = trimmed
        location.deliveryPriceKopecks = deliveryKopecks
        location.driverPaymentKopecks = driverPaymentKopecks
        try context.save()
    }

    /// «Удалить»: the village leaves the list of a new sale; saved sales keep its name and prices.
    func removeLocation(_ location: DeliveryLocation) throws {
        location.isActive = false
        try context.save()
    }

    private func validate(name: String, delivery: Int, driverPayment: Int?) throws -> String {
        let trimmed = name.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        var errors: [String] = []
        if trimmed.isEmpty { errors.append("Укажите название населённого пункта") }
        if delivery < 0 || delivery > CoalMath.maxKopecks { errors.append("Неверная стоимость доставки") }
        if let driverPayment, driverPayment < 0 || driverPayment > CoalMath.maxKopecks {
            errors.append("Неверная оплата водителю")
        }
        if !errors.isEmpty { throw CoalError.invalid(errors) }
        return trimmed
    }

    private static func sameName(_ a: String, _ b: String) -> Bool {
        a.compare(b, options: [.caseInsensitive], locale: Locale(identifier: "ru_RU")) == .orderedSame
    }
}
