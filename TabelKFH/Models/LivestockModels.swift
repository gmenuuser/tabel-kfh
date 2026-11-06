import Foundation
import SwiftData

// «Скот и забой». Heads are whole numbers, weights whole grams (entered in kilograms with up to 3 decimals).
// Links are by UUID, as in the coal module; the herd is never stored, it is always computed from operations.

/// КРС, козы, свиньи.
enum Species: String, Codable, CaseIterable {
    case cattle, goats, pigs

    var title: String {
        switch self {
        case .cattle: return "КРС"
        case .goats: return "Козы"
        case .pigs: return "Свиньи"
        }
    }

    /// The default categories, created once.
    var defaultCategories: [String] {
        switch self {
        case .cattle: return ["Коровы", "Быки", "Телята", "Молодняк", "Другие"]
        case .goats: return ["Козы", "Козлы", "Козлята", "Молодняк", "Другие"]
        case .pigs: return ["Свиноматки", "Хряки", "Поросята", "Откормочные", "Другие"]
        }
    }
}

/// What an operation does to the herd.
enum LivestockOperationKind: String, Codable, CaseIterable {
    /// Начальное поголовье, поступление (покупка), приплод: + heads.
    case initial, purchase, birth
    /// From one category to another: − in the source, + in the target, the total stays.
    case transfer
    /// Забой, продажа, падёж, прочее списание: − heads.
    case slaughter, sale, death, other

    var title: String {
        switch self {
        case .initial: return "Начальное поголовье"
        case .purchase: return "Поступление"
        case .birth: return "Приплод"
        case .transfer: return "Перевод"
        case .slaughter: return "Забой"
        case .sale: return "Продажа"
        case .death: return "Падёж"
        case .other: return "Прочее списание"
        }
    }

    var isAddition: Bool { self == .initial || self == .purchase || self == .birth }
    /// Reasons of «Уменьшить».
    static let decreases: [LivestockOperationKind] = [.slaughter, .sale, .death, .transfer, .other]
    static let additions: [LivestockOperationKind] = [.purchase, .birth, .initial]
}

/// Why animals were slaughtered.
enum SlaughterReason: String, Codable, CaseIterable {
    case meat, forced, other

    var title: String {
        switch self {
        case .meat: return "На мясо"
        case .forced: return "Вынужденный забой"
        case .other: return "Другое"
        }
    }
}

/// A category of one species: «Коровы», «Козлята» or the owner's own.
@Model
final class LivestockCategory {
    @Attribute(.unique) var id: UUID
    var speciesRaw: String
    var name: String
    var sortOrder: Int
    /// One of the default categories (cannot be deleted).
    var isBuiltIn: Bool
    var createdAt: Date

    init(id: UUID = UUID(), species: Species, name: String, sortOrder: Int, isBuiltIn: Bool = false, createdAt: Date) {
        self.id = id
        self.speciesRaw = species.rawValue
        self.name = name
        self.sortOrder = sortOrder
        self.isBuiltIn = isBuiltIn
        self.createdAt = createdAt
    }

    var species: Species {
        get { Species(rawValue: speciesRaw) ?? .cattle }
        set { speciesRaw = newValue.rawValue }
    }
}

/// One movement of the herd.
@Model
final class LivestockOperation {
    @Attribute(.unique) var id: UUID
    var kindRaw: String
    /// The day (and time) the animals came or left.
    var date: Date
    var categoryId: UUID
    /// The target of a transfer; nil otherwise.
    var targetCategoryId: UUID?
    var heads: Int
    var comment: String
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(), kind: LivestockOperationKind, date: Date, categoryId: UUID, targetCategoryId: UUID? = nil,
         heads: Int, comment: String = "", createdAt: Date, updatedAt: Date) {
        self.id = id
        self.kindRaw = kind.rawValue
        self.date = date
        self.categoryId = categoryId
        self.targetCategoryId = targetCategoryId
        self.heads = heads
        self.comment = comment
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var kind: LivestockOperationKind {
        get { LivestockOperationKind(rawValue: kindRaw) ?? .other }
        set { kindRaw = newValue.rawValue }
    }
}

/// The details of a slaughter operation, linked to it by `operationId`.
@Model
final class SlaughterRecord {
    @Attribute(.unique) var id: UUID
    var operationId: UUID
    var reasonRaw: String
    var liveWeightGrams: Int?
    var carcassWeightGrams: Int?
    var meatWeightGrams: Int?
    var createdAt: Date

    init(id: UUID = UUID(), operationId: UUID, reason: SlaughterReason, liveWeightGrams: Int? = nil,
         carcassWeightGrams: Int? = nil, meatWeightGrams: Int? = nil, createdAt: Date) {
        self.id = id
        self.operationId = operationId
        self.reasonRaw = reason.rawValue
        self.liveWeightGrams = liveWeightGrams
        self.carcassWeightGrams = carcassWeightGrams
        self.meatWeightGrams = meatWeightGrams
        self.createdAt = createdAt
    }

    var reason: SlaughterReason {
        get { SlaughterReason(rawValue: reasonRaw) ?? .other }
        set { reasonRaw = newValue.rawValue }
    }
}

/// One row per store: the default categories were made.
@Model
final class LivestockSettings {
    @Attribute(.unique) var id: UUID
    var defaultsCreated: Bool
    var createdAt: Date

    init(id: UUID = UUID(), defaultsCreated: Bool = true, createdAt: Date) {
        self.id = id
        self.defaultsCreated = defaultsCreated
        self.createdAt = createdAt
    }
}
