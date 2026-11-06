import Foundation

/// An operation reduced to what the counting needs (so the rules are tested without a store).
struct HerdMove: Equatable {
    let id: UUID
    let kind: LivestockOperationKind
    let date: Date
    let createdAt: Date
    let categoryId: UUID
    let targetCategoryId: UUID?
    let heads: Int

    init(id: UUID, kind: LivestockOperationKind, date: Date, createdAt: Date, categoryId: UUID,
         targetCategoryId: UUID?, heads: Int) {
        self.id = id
        self.kind = kind
        self.date = date
        self.createdAt = createdAt
        self.categoryId = categoryId
        self.targetCategoryId = targetCategoryId
        self.heads = heads
    }

    init(_ o: LivestockOperation) {
        self.init(id: o.id, kind: o.kind, date: o.date, createdAt: o.createdAt, categoryId: o.categoryId,
                  targetCategoryId: o.targetCategoryId, heads: o.heads)
    }

    /// The change of one category: + for additions and the target of a transfer, − for the rest.
    func delta(for category: UUID) -> Int {
        if kind == .transfer {
            var d = 0
            if categoryId == category { d -= heads }
            if targetCategoryId == category { d += heads }
            return d
        }
        guard categoryId == category else { return 0 }
        return kind.isAddition ? heads : -heads
    }

    /// The categories it changes.
    var categories: [UUID] { [categoryId] + (kind == .transfer ? [targetCategoryId].compactMap { $0 } : []) }
}

/// Остаток = начальное поголовье + поступление + приплод + переводы в категорию − забой − продажа − падёж −
/// прочее списание − переводы из категории. Always computed from the operations.
enum LivestockLedger {
    /// Heads of each category after every operation with `date < before` (all of them when nil).
    static func balances(_ moves: [HerdMove], before: Date? = nil) -> [UUID: Int] {
        var result: [UUID: Int] = [:]
        for m in moves where before.map({ m.date < $0 }) ?? true {
            for c in Set(m.categories) { result[c, default: 0] += m.delta(for: c) }
        }
        return result
    }

    /// The first moment a category goes below zero, in date order; nil when the history is consistent.
    static func firstShortage(_ moves: [HerdMove]) -> (categoryId: UUID, date: Date, heads: Int)? {
        let ordered = moves.sorted { ($0.date, $0.createdAt) < ($1.date, $1.createdAt) }
        var running: [UUID: Int] = [:]
        for m in ordered {
            for c in Set(m.categories) {
                running[c, default: 0] += m.delta(for: c)
                if running[c, default: 0] < 0 {
                    return (c, m.date, running[c, default: 0] - m.delta(for: c))
                }
            }
        }
        return nil
    }
}

/// «Движение поголовья» of one category over a period.
struct HerdMovementRow: Equatable {
    let categoryId: UUID
    let species: Species
    let name: String
    var start = 0
    var initial = 0
    var purchase = 0
    var birth = 0
    var transferIn = 0
    var transferOut = 0
    var slaughter = 0
    var sale = 0
    var death = 0
    var other = 0

    var end: Int { start + initial + purchase + birth + transferIn - transferOut - slaughter - sale - death - other }

    mutating func add(_ m: HerdMove) {
        switch m.kind {
        case .initial: if m.categoryId == categoryId { initial += m.heads }
        case .purchase: if m.categoryId == categoryId { purchase += m.heads }
        case .birth: if m.categoryId == categoryId { birth += m.heads }
        case .slaughter: if m.categoryId == categoryId { slaughter += m.heads }
        case .sale: if m.categoryId == categoryId { sale += m.heads }
        case .death: if m.categoryId == categoryId { death += m.heads }
        case .other: if m.categoryId == categoryId { other += m.heads }
        case .transfer:
            if m.categoryId == categoryId { transferOut += m.heads }
            if m.targetCategoryId == categoryId { transferIn += m.heads }
        }
    }

    static func + (a: HerdMovementRow, b: HerdMovementRow) -> HerdMovementRow {
        var r = a
        r.start += b.start; r.initial += b.initial; r.purchase += b.purchase; r.birth += b.birth
        r.transferIn += b.transferIn; r.transferOut += b.transferOut; r.slaughter += b.slaughter
        r.sale += b.sale; r.death += b.death; r.other += b.other
        return r
    }
}

/// Slaughter of one category over a period; weights only from the records that have them.
struct SlaughterSummaryRow: Equatable {
    let categoryId: UUID
    let species: Species
    let name: String
    var heads = 0
    var operations = 0
    var liveGrams = 0
    var carcassGrams = 0
    var meatGrams = 0
}
