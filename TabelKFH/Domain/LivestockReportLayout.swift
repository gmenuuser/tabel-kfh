import Foundation

/// The livestock reports as printable documents (the same blocks, PDF and XLSX as the coal reports).
enum LivestockReportLayout {
    struct HerdLine: Equatable {
        let species: Species
        let name: String
        let heads: Int
    }

    struct SlaughterLine: Equatable {
        let date: Date
        let species: Species
        let category: String
        let heads: Int
        let reason: String
        let liveGrams: Int?
        let meatGrams: Int?
        let comment: String
    }

    /// «28.11.2026», «Ноябрь 2026», «2026».
    static func periodTitle(_ kind: LivestockPeriod, start: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.month, .year], from: start)
        switch kind {
        case .day: return CoalText.date(start, calendar: calendar)
        case .month: return calendar.monthTitle(month: c.month ?? 1, year: c.year ?? 2026)
        case .year: return "\(c.year ?? 2026) год"
        }
    }

    private static func fileStamp(_ kind: LivestockPeriod, start: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: start)
        switch kind {
        case .day: return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        case .month: return String(format: "%04d-%02d", c.year ?? 0, c.month ?? 0)
        case .year: return String(format: "%04d", c.year ?? 0)
        }
    }

    private static func footer(manager: String, createdAt: Date, calendar: Calendar) -> [ReportBlock] {
        [.signature(label: CoalReportLayout.signatureLabel, name: manager),
         .note("Дата формирования: \(CoalText.dateTime(createdAt, calendar: calendar))")]
    }

    // MARK: Остаток поголовья

    static func herd(_ lines: [HerdLine], on date: Date, farm: FarmInfo, createdAt: Date,
                     calendar: Calendar = .farm) -> CoalReportDocument {
        var blocks: [ReportBlock] = [.title("ОСТАТОК ПОГОЛОВЬЯ"), .boldText(farm.farmName),
                                     .text("На дату: \(CoalText.date(date, calendar: calendar))")]
        let columns = [ReportColumn(title: "Категория", weight: 50, align: .left),
                       ReportColumn(title: "Голов", weight: 20, align: .right)]
        var pairs: [ReportPair] = []
        for species in Species.allCases {
            let rows = lines.filter { $0.species == species }
            let total = rows.reduce(0) { $0 + $1.heads }
            blocks.append(.heading(species.title))
            blocks.append(.table(ReportTable(columns: columns, rows: rows.map { [.text($0.name), .count($0.heads)] },
                                             total: [.text("Итого \(species.title)"), .count(total)])))
            pairs.append(ReportPair(label: species.title, value: .count(total), bold: false))
        }
        pairs.append(ReportPair(label: "Всего животных", value: .count(lines.reduce(0) { $0 + $1.heads }), bold: true))
        blocks.append(.heading("Итого"))
        blocks.append(.pairs(pairs))
        blocks += footer(manager: farm.managerName, createdAt: createdAt, calendar: calendar)
        let c = calendar.dateComponents([.day, .month, .year], from: date)
        return CoalReportDocument(
            fileName: String(format: "Поголовье_%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0),
            runningHeader: "\(farm.farmName) · Остаток поголовья на \(CoalText.date(date, calendar: calendar))",
            blocks: blocks)
    }

    // MARK: Забой

    static func slaughter(_ summary: [SlaughterSummaryRow], lines: [SlaughterLine], period: LivestockPeriod,
                          start: Date, farm: FarmInfo, createdAt: Date, calendar: Calendar = .farm) -> CoalReportDocument {
        let title = periodTitle(period, start: start, calendar: calendar)
        var blocks: [ReportBlock] = [.title("ОТЧЁТ ПО ЗАБОЮ"), .boldText(farm.farmName), .text("Период: \(title)")]
        func weight(_ g: Int) -> ReportCell { g == 0 ? .text("—") : .tons(g) }
        let columns = [ReportColumn(title: "Вид", weight: 10, align: .left),
                       ReportColumn(title: "Категория", weight: 22, align: .left),
                       ReportColumn(title: "Голов", weight: 10, align: .right),
                       ReportColumn(title: "Живой вес, кг", weight: 16, align: .right),
                       ReportColumn(title: "После забоя, кг", weight: 16, align: .right),
                       ReportColumn(title: "Мясо, кг", weight: 14, align: .right)]
        blocks.append(.heading("По категориям"))
        if summary.isEmpty {
            blocks.append(.text("Забоя за период не было."))
        } else {
            let sum = { (f: (SlaughterSummaryRow) -> Int) in summary.reduce(0) { $0 + f($1) } }
            blocks.append(.table(ReportTable(
                columns: columns,
                rows: summary.map { [.text($0.species.title), .text($0.name), .count($0.heads), weight($0.liveGrams),
                                     weight($0.carcassGrams), weight($0.meatGrams)] },
                total: [.text("Итого"), .text(""), .count(sum { $0.heads }), weight(sum { $0.liveGrams }),
                        weight(sum { $0.carcassGrams }), weight(sum { $0.meatGrams })])))
            blocks.append(.pairs(Species.allCases.map { s in
                ReportPair(label: "\(s.title): забито голов",
                           value: .count(summary.filter { $0.species == s }.reduce(0) { $0 + $1.heads }), bold: false)
            }))
            blocks.append(.note("Веса суммируются только по записям, где они указаны."))
        }
        if !lines.isEmpty {
            blocks.append(.heading("Записи забоя"))
            blocks.append(.table(ReportTable(
                columns: [ReportColumn(title: "Дата", weight: 12, align: .center),
                          ReportColumn(title: "Вид", weight: 8, align: .left),
                          ReportColumn(title: "Категория", weight: 16, align: .left),
                          ReportColumn(title: "Голов", weight: 8, align: .right),
                          ReportColumn(title: "Причина", weight: 16, align: .left),
                          ReportColumn(title: "Живой, кг", weight: 11, align: .right),
                          ReportColumn(title: "Мясо, кг", weight: 11, align: .right),
                          ReportColumn(title: "Комментарий", weight: 18, align: .left)],
                rows: lines.map { l in
                    [.text(CoalText.date(l.date, calendar: calendar)), .text(l.species.title), .text(l.category),
                     .count(l.heads), .text(l.reason), l.liveGrams.map(ReportCell.tons) ?? .text("—"),
                     l.meatGrams.map(ReportCell.tons) ?? .text("—"), .text(l.comment)]
                },
                total: nil)))
        }
        blocks += footer(manager: farm.managerName, createdAt: createdAt, calendar: calendar)
        return CoalReportDocument(fileName: "Забой_\(fileStamp(period, start: start, calendar: calendar))",
                                  runningHeader: "\(farm.farmName) · Отчёт по забою \(title)", blocks: blocks)
    }

    // MARK: Движение поголовья

    static func movement(_ rows: [HerdMovementRow], period: LivestockPeriod, start: Date, farm: FarmInfo,
                         createdAt: Date, calendar: Calendar = .farm) -> CoalReportDocument {
        let title = periodTitle(period, start: start, calendar: calendar)
        var blocks: [ReportBlock] = [.title("ДВИЖЕНИЕ ПОГОЛОВЬЯ"), .boldText(farm.farmName), .text("Период: \(title)")]
        let columns = [ReportColumn(title: "Категория", weight: 20, align: .left),
                       ReportColumn(title: "На начало", weight: 9, align: .right),
                       ReportColumn(title: "Поступ-ло", weight: 9, align: .right),
                       ReportColumn(title: "Приплод", weight: 9, align: .right),
                       ReportColumn(title: "Перевод +", weight: 9, align: .right),
                       ReportColumn(title: "Перевод −", weight: 9, align: .right),
                       ReportColumn(title: "Забой", weight: 8, align: .right),
                       ReportColumn(title: "Продажа", weight: 9, align: .right),
                       ReportColumn(title: "Падёж", weight: 8, align: .right),
                       ReportColumn(title: "Прочее", weight: 8, align: .right),
                       ReportColumn(title: "На конец", weight: 9, align: .right)]
        func cells(_ r: HerdMovementRow, name: String) -> [ReportCell] {
            [.text(name), .count(r.start), .count(r.initial + r.purchase), .count(r.birth), .count(r.transferIn),
             .count(r.transferOut), .count(r.slaughter), .count(r.sale), .count(r.death), .count(r.other), .count(r.end)]
        }
        var all: HerdMovementRow?
        for species in Species.allCases {
            let speciesRows = rows.filter { $0.species == species }
            guard let first = speciesRows.first else { continue }
            let total = speciesRows.dropFirst().reduce(first) { $0 + $1 }
            all = all.map { $0 + total } ?? total
            blocks.append(.heading(species.title))
            blocks.append(.table(ReportTable(columns: columns, rows: speciesRows.map { cells($0, name: $0.name) },
                                             total: cells(total, name: "Итого \(species.title)"))))
        }
        if let all {
            blocks.append(.heading("Всё хозяйство"))
            blocks.append(.pairs([
                ReportPair(label: "Остаток на начало периода", value: .count(all.start), bold: true),
                ReportPair(label: "Поступление (и начальное поголовье)", value: .count(all.initial + all.purchase), bold: false),
                ReportPair(label: "Приплод", value: .count(all.birth), bold: false),
                ReportPair(label: "Забой", value: .count(all.slaughter), bold: false),
                ReportPair(label: "Продажи", value: .count(all.sale), bold: false),
                ReportPair(label: "Падёж", value: .count(all.death), bold: false),
                ReportPair(label: "Прочее списание", value: .count(all.other), bold: false),
                ReportPair(label: "Остаток на конец периода", value: .count(all.end), bold: true),
            ]))
            blocks.append(.note("Переводы между категориями не меняют общее поголовье."))
        } else {
            blocks.append(.text("Животных и операций за период нет."))
        }
        blocks += footer(manager: farm.managerName, createdAt: createdAt, calendar: calendar)
        return CoalReportDocument(fileName: "Движение_поголовья_\(fileStamp(period, start: start, calendar: calendar))",
                                  runningHeader: "\(farm.farmName) · Движение поголовья \(title)", blocks: blocks)
    }
}
