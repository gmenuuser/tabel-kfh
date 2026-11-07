import Foundation
import SwiftData

/// A saved calculation of «Агрономические калькуляторы»: the inputs as typed and the result lines as shown.
@Model
final class AgroCalculation {
    @Attribute(.unique) var id: UUID
    var kindRaw: String
    /// The field name or a comment.
    var title: String
    /// JSON `[String: String]`.
    var inputsData: Data
    /// JSON `[AgroLine]`.
    var resultData: Data
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(), kind: AgroKind, title: String, inputsData: Data, resultData: Data, createdAt: Date,
         updatedAt: Date) {
        self.id = id
        self.kindRaw = kind.rawValue
        self.title = title
        self.inputsData = inputsData
        self.resultData = resultData
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var kind: AgroKind { AgroKind(rawValue: kindRaw) ?? .seeding }
    var inputs: [String: String] { (try? JSONDecoder().decode([String: String].self, from: inputsData)) ?? [:] }
    var lines: [AgroLine] { (try? JSONDecoder().decode([AgroLine].self, from: resultData)) ?? [] }
}

/// Saves, corrects and deletes calculations; the result is always computed again from the inputs.
@MainActor
final class AgroService {
    private let context: ModelContext
    private let now: () -> Date

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
    }

    static func encode(_ inputs: [String: String]) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(inputs)) ?? Data("{}".utf8)
    }

    /// A new calculation, or the corrected `existing` one. Only a complete result is saved.
    @discardableResult
    func save(_ kind: AgroKind, inputs: [String: String], title: String,
              over existing: AgroCalculation? = nil) throws -> AgroCalculation {
        let result = AgroEngine.compute(kind, inputs)
        guard result.isComplete else {
            throw LivestockError.invalid(result.errors + result.missing.map { "Заполните: \($0)" })
        }
        let cleaned = inputs.filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
        let resultData = (try? JSONEncoder().encode(result.lines)) ?? Data("[]".utf8)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let moment = now()
        if let existing {
            existing.title = trimmed
            existing.inputsData = Self.encode(cleaned)
            existing.resultData = resultData
            existing.updatedAt = moment
            try context.syncedSave()
            return existing
        }
        let c = AgroCalculation(kind: kind, title: trimmed, inputsData: Self.encode(cleaned), resultData: resultData,
                                createdAt: moment, updatedAt: moment)
        context.insert(c)
        try context.syncedSave()
        return c
    }

    func delete(_ c: AgroCalculation) throws {
        context.delete(c)
        try context.syncedSave()
    }

    /// Newest first, filtered by kind, a text in the title or inputs, and `start <= createdAt < end`.
    func history(kind: AgroKind? = nil, search: String = "", from start: Date? = nil, to end: Date? = nil) throws -> [AgroCalculation] {
        let query = search.trimmingCharacters(in: .whitespaces)
        return try context.fetch(FetchDescriptor<AgroCalculation>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)]))
            .filter { c in
                if let kind, c.kind != kind { return false }
                if let start, c.createdAt < start { return false }
                if let end, c.createdAt >= end { return false }
                guard !query.isEmpty else { return true }
                return (c.title + " " + c.kind.title + " " + AgroEngine.inputsText(c.kind, c.inputs))
                    .range(of: query, options: .caseInsensitive, locale: Locale(identifier: "ru_RU")) != nil
            }
    }
}

/// The history as a printable document.
enum AgroReportLayout {
    static func history(_ items: [AgroCalculation], farm: FarmInfo, createdAt: Date,
                        calendar: Calendar = .farm) -> CoalReportDocument {
        var blocks: [ReportBlock] = [.title("АГРОНОМИЧЕСКИЕ РАСЧЁТЫ"), .boldText(farm.farmName),
                                     .text("Расчётов: \(items.count)")]
        if items.isEmpty {
            blocks.append(.text("Сохранённых расчётов нет."))
        } else {
            blocks.append(.table(ReportTable(
                columns: [ReportColumn(title: "Дата", weight: 12, align: .center),
                          ReportColumn(title: "Расчёт", weight: 14, align: .left),
                          ReportColumn(title: "Поле / комментарий", weight: 14, align: .left),
                          ReportColumn(title: "Данные", weight: 30, align: .left),
                          ReportColumn(title: "Результат", weight: 30, align: .left)],
                rows: items.map { c in
                    [.text(CoalText.dateTime(c.createdAt, calendar: calendar)), .text(c.kind.title), .text(c.title),
                     .text(AgroEngine.inputsText(c.kind, c.inputs)),
                     .text(c.lines.map { "\($0.title): \($0.text)" }.joined(separator: "; "))]
                },
                total: nil)))
        }
        blocks.append(.note("Расчёты справочные. Нормы препаратов — только по этикетке."))
        blocks.append(.signature(label: CoalReportLayout.signatureLabel, name: farm.managerName))
        blocks.append(.note("Дата формирования: \(CoalText.dateTime(createdAt, calendar: calendar))"))
        let c = calendar.dateComponents([.day, .month, .year], from: createdAt)
        return CoalReportDocument(
            fileName: String(format: "Агрорасчёты_%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0),
            runningHeader: "\(farm.farmName) · Агрономические расчёты", blocks: blocks)
    }
}
