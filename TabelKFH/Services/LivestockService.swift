import Foundation
import SwiftData

enum LivestockError: LocalizedError, Equatable {
    case invalid([String])
    /// Not enough heads: the category would go below zero on `date`; `available` heads were there.
    case notEnough(category: String, date: Date, available: Int)
    case alreadySaved
    case categoryNotFound
    case categoryInUse
    case duplicateCategory(String)

    var errorDescription: String? {
        switch self {
        case .invalid(let messages): return messages.joined(separator: "\n")
        case .notEnough(let category, let date, let available):
            return "Не хватает голов: в категории «\(category)» на \(CoalText.date(date)) было \(LivestockText.heads(available))."
        case .alreadySaved: return "Эта запись уже сохранена"
        case .categoryNotFound: return "Категория не найдена"
        case .categoryInUse: return "По этой категории есть операции, удалить её нельзя"
        case .duplicateCategory(let name): return "Категория «\(name)» уже есть"
        }
    }
}

enum LivestockText {
    /// «1 голова», «2 головы», «5 голов».
    static func heads(_ n: Int) -> String {
        "\(n) " + CoalText.plural(n, "голова", "головы", "голов")
    }

    /// «120,5 кг», «—» for nil.
    static func kilograms(_ grams: Int?) -> String {
        guard let grams else { return "—" }
        return CoalMath.tons(grams) + "\u{00A0}кг"
    }
}

/// What the owner types into an operation form.
struct LivestockDraft: Equatable {
    /// The operation's id: a second save of the same form is refused instead of counting twice.
    var id = UUID()
    var kind: LivestockOperationKind
    var date: Date
    var categoryId: UUID?
    var targetCategoryId: UUID?
    var headsText = ""
    var comment = ""
    // Slaughter only.
    var reason: SlaughterReason = .meat
    var liveWeightText = ""
    var carcassWeightText = ""
    var meatWeightText = ""

    init(kind: LivestockOperationKind, date: Date, categoryId: UUID? = nil) {
        self.kind = kind
        self.date = date
        self.categoryId = categoryId
    }

    var heads: Int? { Int(headsText.trimmingCharacters(in: .whitespaces)) }

    /// Kilograms with up to 3 decimals → grams; nil for an empty field.
    static func grams(_ text: String) -> Int?? {
        let t = text.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return .some(nil) }
        guard let g = CoalMath.parseTons(t) else { return nil }
        return .some(g)
    }

    var validationErrors: [String] {
        var errors: [String] = []
        if categoryId == nil { errors.append("Выберите категорию") }
        if let h = heads {
            if h <= 0 { errors.append("Количество голов — больше нуля") }
            if h > 100_000 { errors.append("Слишком большое количество голов") }
        } else {
            errors.append(headsText.trimmingCharacters(in: .whitespaces).isEmpty
                          ? "Укажите количество голов" : "Количество голов — целое число")
        }
        if kind == .transfer {
            if targetCategoryId == nil { errors.append("Выберите, в какую категорию перевести") }
            if targetCategoryId != nil && targetCategoryId == categoryId { errors.append("Категории перевода совпадают") }
        }
        if kind == .slaughter {
            for (text, name) in [(liveWeightText, "живой вес"), (carcassWeightText, "вес после забоя"),
                                 (meatWeightText, "вес мяса")] where Self.grams(text) == nil {
                errors.append("Неверный \(name): число килограммов, например 250,5")
            }
        }
        return errors
    }

    var isValid: Bool { validationErrors.isEmpty }
}

/// The herd: categories, operations (add, decrease, transfer, slaughter), checks and reports.
@MainActor
final class LivestockService {
    private let context: ModelContext
    private let calendar: Calendar
    private let now: () -> Date

    init(context: ModelContext, calendar: Calendar = .farm, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.calendar = calendar
        self.now = now
    }

    // MARK: Categories

    /// Makes the default categories once (they can be renamed later; own ones can be added).
    func ensureDefaults() {
        guard ((try? context.fetchCount(FetchDescriptor<LivestockSettings>())) ?? 0) == 0 else { return }
        // A role that may not change the herd (viewer) makes nothing: the farm's categories come with the sync.
        guard SyncedSave.canWrite(.livestockSettings), SyncedSave.canWrite(.livestockCategory) else { return }
        let moment = now()
        context.insert(LivestockSettings(id: StableId.livestockSettings, createdAt: moment))
        if ((try? context.fetchCount(FetchDescriptor<LivestockCategory>())) ?? 0) == 0 {
            for species in Species.allCases {
                for (index, name) in species.defaultCategories.enumerated() {
                    context.insert(LivestockCategory(id: StableId.livestockCategory(species: species.rawValue, name: name),
                                                     species: species, name: name, sortOrder: index, isBuiltIn: true,
                                                     createdAt: moment))
                }
            }
        }
        try? context.syncedSave()
    }

    func categories(_ species: Species? = nil) throws -> [LivestockCategory] {
        ensureDefaults()
        return try context.fetch(FetchDescriptor<LivestockCategory>())
            .filter { species == nil || $0.species == species }
            .sorted { ($0.species.order, $0.sortOrder, $0.name) < ($1.species.order, $1.sortOrder, $1.name) }
    }

    func category(id: UUID) throws -> LivestockCategory? {
        try context.fetch(FetchDescriptor<LivestockCategory>(predicate: #Predicate { $0.id == id })).first
    }

    @discardableResult
    func addCategory(_ name: String, species: Species) throws -> LivestockCategory {
        let trimmed = name.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard !trimmed.isEmpty else { throw LivestockError.invalid(["Укажите название категории"]) }
        let existing = try categories(species)
        if existing.contains(where: { $0.name.compare(trimmed, options: .caseInsensitive) == .orderedSame }) {
            throw LivestockError.duplicateCategory(trimmed)
        }
        let c = LivestockCategory(id: StableId.livestockCategory(species: species.rawValue, name: trimmed),
                                  species: species, name: trimmed, sortOrder: (existing.map(\.sortOrder).max() ?? 0) + 1,
                                  createdAt: now())
        context.insert(c)
        try context.syncedSave()
        return c
    }

    /// Only an own category without operations is deleted.
    func deleteCategory(_ c: LivestockCategory) throws {
        guard !c.isBuiltIn else { throw LivestockError.categoryInUse }
        let id: UUID? = c.id
        let used = try moves().contains { $0.categoryId == c.id || $0.targetCategoryId == id }
        if used { throw LivestockError.categoryInUse }
        context.delete(c)
        try context.syncedSave()
    }

    // MARK: Herd

    func operations() throws -> [LivestockOperation] {
        try context.fetch(FetchDescriptor<LivestockOperation>(sortBy: [SortDescriptor(\.date, order: .reverse),
                                                                       SortDescriptor(\.createdAt, order: .reverse)]))
    }

    private func moves() throws -> [HerdMove] { try operations().map(HerdMove.init) }

    /// Heads of each category now (after every operation).
    func balances() throws -> [UUID: Int] { LivestockLedger.balances(try moves()) }

    /// Heads of a species now.
    func total(_ species: Species) throws -> Int {
        let b = try balances()
        return try categories(species).reduce(0) { $0 + (b[$1.id] ?? 0) }
    }

    func slaughterRecord(for operationId: UUID) throws -> SlaughterRecord? {
        try context.fetch(FetchDescriptor<SlaughterRecord>(predicate: #Predicate { $0.operationId == operationId })).first
    }

    func operation(id: UUID) throws -> LivestockOperation? {
        try context.fetch(FetchDescriptor<LivestockOperation>(predicate: #Predicate { $0.id == id })).first
    }

    /// Saves a new operation. A decrease, a transfer or a slaughter is refused when there are not enough heads
    /// at its date or it would make any later balance negative.
    @discardableResult
    func save(_ d: LivestockDraft) throws -> LivestockOperation {
        guard d.isValid else { throw LivestockError.invalid(d.validationErrors) }
        if try operation(id: d.id) != nil { throw LivestockError.alreadySaved }
        let candidate = try move(from: d, createdAt: now())
        try check(replacing: nil, with: candidate)
        let moment = now()
        let o = LivestockOperation(id: d.id, kind: d.kind, date: d.date, categoryId: candidate.categoryId,
                                   targetCategoryId: candidate.targetCategoryId, heads: candidate.heads,
                                   comment: d.comment.trimmingCharacters(in: .whitespacesAndNewlines),
                                   createdAt: moment, updatedAt: moment)
        context.insert(o)
        try saveSlaughter(d, for: o)
        try context.syncedSave()
        return o
    }

    /// Saves the edited form over the operation; the herd is recomputed from the corrected history.
    func update(_ o: LivestockOperation, with d: LivestockDraft) throws {
        guard d.isValid else { throw LivestockError.invalid(d.validationErrors) }
        let candidate = try move(from: d, createdAt: o.createdAt, id: o.id)
        try check(replacing: o.id, with: candidate)
        o.kind = d.kind
        o.date = d.date
        o.categoryId = candidate.categoryId
        o.targetCategoryId = candidate.targetCategoryId
        o.heads = candidate.heads
        o.comment = d.comment.trimmingCharacters(in: .whitespacesAndNewlines)
        o.updatedAt = now()
        try saveSlaughter(d, for: o)
        try context.syncedSave()
    }

    /// Deletes an operation entered by mistake (with its slaughter details). Refused when the herd would go
    /// below zero without it (an addition the later decreases rely on).
    func delete(_ o: LivestockOperation) throws {
        try check(replacing: o.id, with: nil)
        if let s = try slaughterRecord(for: o.id) { context.delete(s) }
        context.delete(o)
        try context.syncedSave()
    }

    /// The form of a saved operation.
    func draft(for o: LivestockOperation) throws -> LivestockDraft {
        var d = LivestockDraft(kind: o.kind, date: o.date, categoryId: o.categoryId)
        d.id = o.id
        d.targetCategoryId = o.targetCategoryId
        d.headsText = "\(o.heads)"
        d.comment = o.comment
        if let s = try slaughterRecord(for: o.id) {
            d.reason = s.reason
            d.liveWeightText = s.liveWeightGrams.map(Self.kilogramsField) ?? ""
            d.carcassWeightText = s.carcassWeightGrams.map(Self.kilogramsField) ?? ""
            d.meatWeightText = s.meatWeightGrams.map(Self.kilogramsField) ?? ""
        }
        return d
    }

    static func kilogramsField(_ grams: Int) -> String {
        CoalMath.tons(grams).replacingOccurrences(of: "\u{00A0}", with: "")
    }

    private func move(from d: LivestockDraft, createdAt: Date, id: UUID? = nil) throws -> HerdMove {
        guard let categoryId = d.categoryId, try category(id: categoryId) != nil else { throw LivestockError.categoryNotFound }
        if d.kind == .transfer, let target = d.targetCategoryId, try category(id: target) == nil {
            throw LivestockError.categoryNotFound
        }
        return HerdMove(id: id ?? d.id, kind: d.kind, date: d.date, createdAt: createdAt, categoryId: categoryId,
                        targetCategoryId: d.kind == .transfer ? d.targetCategoryId : nil, heads: d.heads ?? 0)
    }

    /// The whole history with the change applied must never go below zero.
    private func check(replacing id: UUID?, with candidate: HerdMove?) throws {
        var all = try moves().filter { $0.id != id }
        if let candidate { all.append(candidate) }
        guard let shortage = LivestockLedger.firstShortage(all) else { return }
        let name = (try category(id: shortage.categoryId))?.name ?? "?"
        throw LivestockError.notEnough(category: name, date: shortage.date, available: max(0, shortage.heads))
    }

    private func saveSlaughter(_ d: LivestockDraft, for o: LivestockOperation) throws {
        let existing = try slaughterRecord(for: o.id)
        guard d.kind == .slaughter else {
            if let existing { context.delete(existing) }
            return
        }
        let s = existing ?? {
            let r = SlaughterRecord(operationId: o.id, reason: d.reason, createdAt: now())
            context.insert(r)
            return r
        }()
        s.reason = d.reason
        s.liveWeightGrams = LivestockDraft.grams(d.liveWeightText) ?? nil
        s.carcassWeightGrams = LivestockDraft.grams(d.carcassWeightText) ?? nil
        s.meatWeightGrams = LivestockDraft.grams(d.meatWeightText) ?? nil
    }

    // MARK: Reports

    /// «Движение поголовья» over `start <= date < end`, one row per category that had animals or moves.
    func movement(from start: Date, to end: Date) throws -> [HerdMovementRow] {
        let all = try moves()
        let before = LivestockLedger.balances(all, before: start)
        var rows: [UUID: HerdMovementRow] = [:]
        let cats = try categories()
        for c in cats {
            rows[c.id] = HerdMovementRow(categoryId: c.id, species: c.species, name: c.name, start: before[c.id] ?? 0)
        }
        for m in all where m.date >= start && m.date < end {
            for c in Set(m.categories) { rows[c]?.add(m) }
        }
        return cats.compactMap { rows[$0.id] }.filter { $0 != HerdMovementRow(categoryId: $0.categoryId, species: $0.species, name: $0.name) }
    }

    /// Slaughter over `start <= date < end` by category.
    func slaughterSummary(from start: Date, to end: Date) throws -> [SlaughterSummaryRow] {
        let cats = try categories()
        var rows: [UUID: SlaughterSummaryRow] = [:]
        for o in try operations() where o.kind == .slaughter && o.date >= start && o.date < end {
            guard let c = cats.first(where: { $0.id == o.categoryId }) else { continue }
            var r = rows[c.id] ?? SlaughterSummaryRow(categoryId: c.id, species: c.species, name: c.name)
            r.heads += o.heads
            r.operations += 1
            if let s = try slaughterRecord(for: o.id) {
                r.liveGrams += s.liveWeightGrams ?? 0
                r.carcassGrams += s.carcassWeightGrams ?? 0
                r.meatGrams += s.meatWeightGrams ?? 0
            }
            rows[c.id] = r
        }
        return cats.compactMap { rows[$0.id] }
    }

    /// Day, month or year containing `date`.
    func period(_ kind: LivestockPeriod, containing date: Date) -> (start: Date, end: Date) {
        let component: Calendar.Component = kind == .day ? .day : kind == .month ? .month : .year
        let interval = calendar.dateInterval(of: component, for: date)
        return (interval?.start ?? date, interval?.end ?? date)
    }
}

enum LivestockPeriod: Hashable { case day, month, year }

extension Species {
    var order: Int { Species.allCases.firstIndex(of: self) ?? 0 }
}
