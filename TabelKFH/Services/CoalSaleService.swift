import Foundation
import SwiftData

/// The «Новая продажа» form. Amounts are recomputed from it on every change.
struct CoalSaleDraft: Equatable {
    /// The id the sale gets: a second save of the same form is refused instead of adding the sale twice.
    var id = UUID()
    var saleDate: Date
    var tonsText = ""
    /// The chosen village and its prices at the moment it was chosen.
    var locationName = ""
    var listDeliveryPrice = 0
    var pricePerTon: Int
    var vehicle: VehicleType = .hired
    var driverId: UUID?
    var driverName = ""
    /// The hired driver's pay in rubles, as typed («1000»).
    var driverPaymentText = ""
    /// Hand-corrected amounts in rubles; the list amounts stay in the sale too.
    var isManual = false
    var manualCoalText = ""
    var manualDeliveryText = ""
    var comment = ""

    init(saleDate: Date, pricePerTon: Int) {
        self.saleDate = saleDate
        self.pricePerTon = pricePerTon
    }

    var kilograms: Int? { CoalMath.parseTons(tonsText) }

    /// Tons × the price per ton.
    var listCoalAmount: Int { CoalMath.coalAmount(kilograms: kilograms ?? 0, pricePerTon: pricePerTon) }
    var listTotal: Int { listCoalAmount + listDeliveryPrice }

    var coalAmount: Int { isManual ? CoalMath.parseRubles(manualCoalText) ?? listCoalAmount : listCoalAmount }
    var deliveryPrice: Int {
        isManual ? CoalMath.parseRubles(manualDeliveryText) ?? listDeliveryPrice : listDeliveryPrice
    }
    /// Итого к оплате.
    var total: Int { coalAmount + deliveryPrice }
    var isAdjusted: Bool { total != listTotal }

    /// 0 for the own vehicle.
    var driverPayment: Int { vehicle == .hired ? CoalMath.parseRubles(driverPaymentText) ?? 0 : 0 }

    /// Takes the village and its current prices; the driver's pay follows the village.
    mutating func choose(_ location: DeliveryLocation) {
        locationName = location.name
        listDeliveryPrice = location.deliveryPriceKopecks
        driverPaymentText = CoalMath.rublesField(location.effectiveDriverPaymentKopecks)
    }

    mutating func choose(_ driver: Driver) {
        vehicle = .hired
        driverId = driver.id
        driverName = driver.fullName
    }

    /// Starts a hand correction from the list amounts.
    mutating func startManual() {
        manualCoalText = CoalMath.rublesField(listCoalAmount)
        manualDeliveryText = CoalMath.rublesField(listDeliveryPrice)
        isManual = true
    }

    var validationErrors: [String] {
        var errors: [String] = []
        if let kg = kilograms {
            if kg <= 0 { errors.append("Количество угля должно быть больше нуля") }
            if kg > CoalMath.maxKilograms { errors.append("Не больше 100 тонн в одной продаже") }
        } else {
            errors.append(tonsText.trimmingCharacters(in: .whitespaces).isEmpty
                          ? "Укажите количество угля" : "Количество угля — число, например 1,5")
        }
        if locationName.isEmpty { errors.append("Выберите населённый пункт") }
        if vehicle == .hired {
            if driverId == nil { errors.append("Выберите водителя наёмной машины") }
            if let pay = CoalMath.parseRubles(driverPaymentText) {
                if pay > CoalMath.maxKopecks { errors.append("Неверная оплата водителю") }
            } else {
                errors.append("Укажите оплату водителю, например 1000")
            }
        }
        if isManual {
            if CoalMath.parseRubles(manualCoalText) == nil { errors.append("Неверная стоимость угля") }
            if CoalMath.parseRubles(manualDeliveryText) == nil { errors.append("Неверная стоимость доставки") }
        }
        if coalAmount > CoalMath.maxKopecks || deliveryPrice > CoalMath.maxKopecks {
            errors.append("Слишком большая сумма")
        }
        return errors
    }

    var isValid: Bool { validationErrors.isEmpty }
}

extension CoalSaleDraft {
    /// The form of a saved sale: its own prices, not today's.
    init(_ s: CoalSale) {
        self.init(saleDate: s.saleDate, pricePerTon: s.coalPricePerTonSnapshot)
        id = s.id
        tonsText = CoalMath.tons(s.kilograms).replacingOccurrences(of: "\u{00A0}", with: "")
        locationName = s.locationNameSnapshot
        listDeliveryPrice = s.deliveryPriceSnapshot
        vehicle = s.vehicleType
        driverId = s.driverId
        driverName = s.driverNameSnapshot
        driverPaymentText = CoalMath.rublesField(s.driverPaymentAmount)
        let listCoal = CoalMath.coalAmount(kilograms: s.kilograms, pricePerTon: s.coalPricePerTonSnapshot)
        if s.coalAmount != listCoal || s.deliveryPrice != s.deliveryPriceSnapshot {
            isManual = true
            manualCoalText = CoalMath.rublesField(s.coalAmount)
            manualDeliveryText = CoalMath.rublesField(s.deliveryPrice)
        }
        comment = s.comment
    }
}

/// Sales: save, edit, delete, and the sales of a day or a period. A closed day's sales are locked.
@MainActor
final class CoalSaleService {
    private let context: ModelContext
    private let calendar: Calendar
    private let now: () -> Date

    init(context: ModelContext, calendar: Calendar = .farm, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.calendar = calendar
        self.now = now
    }

    /// An empty form at the current coal price, dated now.
    func newDraft() -> CoalSaleDraft {
        CoalSaleDraft(saleDate: now(), pricePerTon: CoalSettingsService(context: context, now: now).pricePerTon)
    }

    /// «СОХРАНИТЬ ПРОДАЖУ».
    @discardableResult
    func save(_ draft: CoalSaleDraft) throws -> CoalSale {
        guard draft.isValid else { throw CoalError.invalid(draft.validationErrors) }
        if try sale(id: draft.id) != nil { throw CoalError.alreadySaved }
        try ensureOpen(draft.saleDate)
        let driverName = try checkedDriverName(draft, previousDriverId: nil)
        let moment = now()
        let s = CoalSale(id: draft.id, saleDate: draft.saleDate, kilograms: 0, locationNameSnapshot: "",
                         coalPricePerTonSnapshot: 0, deliveryPriceSnapshot: 0, coalAmount: 0, deliveryPrice: 0,
                         vehicleType: draft.vehicle, createdAt: moment, updatedAt: moment)
        apply(draft, driverName: driverName, to: s)
        context.insert(s)
        try context.syncedSave()
        return s
    }

    /// Saves the edited form over the sale. Neither the old nor the new day may be closed.
    func update(_ s: CoalSale, with draft: CoalSaleDraft) throws {
        guard draft.isValid else { throw CoalError.invalid(draft.validationErrors) }
        try ensureOpen(s.saleDate)
        try ensureOpen(draft.saleDate)
        let driverName = try checkedDriverName(draft, previousDriverId: s.driverId)
        apply(draft, driverName: driverName, to: s)
        s.updatedAt = now()
        try context.syncedSave()
    }

    func delete(_ s: CoalSale) throws {
        try ensureOpen(s.saleDate)
        context.delete(s)
        try context.syncedSave()
    }

    func sale(id: UUID) throws -> CoalSale? {
        try context.fetch(FetchDescriptor<CoalSale>(predicate: #Predicate { $0.id == id })).first
    }

    /// The sales of the day of `day`, in time order.
    func sales(on day: Date) throws -> [CoalSale] {
        let start = calendar.dayStart(day)
        return try sales(from: start, to: calendar.date(byAdding: .day, value: 1, to: start) ?? start)
    }

    /// Sales with `start <= saleDate < end`, in time order.
    func sales(from start: Date, to end: Date) throws -> [CoalSale] {
        let descriptor = FetchDescriptor<CoalSale>(
            predicate: #Predicate { $0.saleDate >= start && $0.saleDate < end },
            sortBy: [SortDescriptor(\.saleDate), SortDescriptor(\.createdAt)])
        return try context.fetch(descriptor)
    }

    func allSales() throws -> [CoalSale] {
        try context.fetch(FetchDescriptor<CoalSale>(sortBy: [SortDescriptor(\.saleDate)]))
    }

    private func ensureOpen(_ date: Date) throws {
        let day = calendar.dayStart(date)
        let descriptor = FetchDescriptor<CoalWorkDay>(predicate: #Predicate { $0.date == day && $0.isClosed == true })
        if try context.fetchCount(descriptor) > 0 { throw CoalError.dayClosed(day) }
    }

    /// The hired driver's current name; an archived driver is accepted only when the sale already had them.
    private func checkedDriverName(_ draft: CoalSaleDraft, previousDriverId: UUID?) throws -> String {
        guard draft.vehicle == .hired, let id = draft.driverId else { return "" }
        guard let driver = try DriverService(context: context, now: now).driver(id: id) else {
            throw CoalError.driverNotFound
        }
        if driver.isArchived && previousDriverId != id { throw CoalError.driverArchived }
        return driver.fullName
    }

    private func apply(_ d: CoalSaleDraft, driverName: String, to s: CoalSale) {
        s.saleDate = d.saleDate
        s.kilograms = d.kilograms ?? 0
        s.locationNameSnapshot = d.locationName
        s.coalPricePerTonSnapshot = d.pricePerTon
        s.deliveryPriceSnapshot = d.listDeliveryPrice
        s.coalAmount = d.coalAmount
        s.deliveryPrice = d.deliveryPrice
        s.totalAmount = d.coalAmount + d.deliveryPrice
        s.vehicleType = d.vehicle
        s.driverId = d.vehicle == .hired ? d.driverId : nil
        s.driverNameSnapshot = d.vehicle == .hired ? driverName : ""
        s.driverPaymentAmount = d.driverPayment
        s.comment = d.comment.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
