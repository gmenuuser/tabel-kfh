import Foundation

/// The livestock module in the backup file: every stored field of every row.
struct LivestockBackup: Codable, Equatable {
    struct Category: Codable, Equatable {
        let id: UUID
        let species: Species
        let name: String
        let sortOrder: Int
        let isBuiltIn: Bool
        let createdAt: Date
    }

    struct Operation: Codable, Equatable {
        let id: UUID
        let kind: LivestockOperationKind
        let date: Date
        let categoryId: UUID
        let targetCategoryId: UUID?
        let heads: Int
        let comment: String
        let createdAt, updatedAt: Date
    }

    struct Slaughter: Codable, Equatable {
        let id, operationId: UUID
        let reason: SlaughterReason
        let liveWeightGrams, carcassWeightGrams, meatWeightGrams: Int?
        let createdAt: Date
    }

    /// false when the module was never opened (its default categories are made again on the next opening).
    var defaultsCreated: Bool
    var categories: [Category]
    var operations: [Operation]
    var slaughters: [Slaughter]

    /// Unique ids, operations of listed categories with positive heads, transfers to another listed category,
    /// slaughter details of slaughter operations, and a herd that never goes below zero.
    var isValid: Bool {
        func unique<T>(_ items: [T], _ id: (T) -> UUID) -> Bool { Set(items.map(id)).count == items.count }
        guard unique(categories, \.id), unique(operations, \.id), unique(slaughters, \.id),
              Set(slaughters.map(\.operationId)).count == slaughters.count else { return false }
        let categoryIds = Set(categories.map(\.id))
        for o in operations {
            guard o.heads > 0, categoryIds.contains(o.categoryId) else { return false }
            if o.kind == .transfer {
                guard let t = o.targetCategoryId, categoryIds.contains(t), t != o.categoryId else { return false }
            } else if o.targetCategoryId != nil {
                return false
            }
        }
        let slaughterOps = Set(operations.filter { $0.kind == .slaughter }.map(\.id))
        guard slaughters.allSatisfy({ slaughterOps.contains($0.operationId) }) else { return false }
        let moves = operations.map {
            HerdMove(id: $0.id, kind: $0.kind, date: $0.date, createdAt: $0.createdAt, categoryId: $0.categoryId,
                     targetCategoryId: $0.targetCategoryId, heads: $0.heads)
        }
        return LivestockLedger.firstShortage(moves) == nil
    }
}
