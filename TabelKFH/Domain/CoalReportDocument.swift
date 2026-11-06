import Foundation

/// A printable report as a list of blocks; the PDF and the XLSX are drawn from the same blocks,
/// so both files always say the same thing.
struct CoalReportDocument: Equatable {
    /// «Уголь_день_2026-11-06» (no extension).
    let fileName: String
    /// Repeated small at the top of the following pages: «КФХ Зубахин · Отчёт за день 06.11.2026».
    let runningHeader: String
    let blocks: [ReportBlock]
}

enum ReportBlock: Equatable {
    case title(String)
    case text(String)
    case boldText(String)
    /// Grey, smaller.
    case note(String)
    case heading(String)
    case table(ReportTable)
    case pairs([ReportPair])
    case signature(label: String, name: String)
}

enum ReportAlign: Equatable { case left, center, right }

struct ReportColumn: Equatable {
    let title: String
    /// Share of the page width (the shares of a table are scaled to the full width).
    let weight: Double
    let align: ReportAlign
}

struct ReportCell: Equatable {
    enum Kind: Equatable { case text, money, tons, count }

    let text: String
    let kind: Kind
    /// The value written as a number into the spreadsheet; nil for text.
    let number: String?

    static func text(_ s: String) -> ReportCell { ReportCell(text: s, kind: .text, number: nil) }
    static func money(_ kopecks: Int) -> ReportCell {
        ReportCell(text: CoalMath.rublesNumber(kopecks), kind: .money, number: CoalMath.spreadsheetRubles(kopecks))
    }
    static func tons(_ kilograms: Int) -> ReportCell {
        ReportCell(text: CoalMath.tons(kilograms), kind: .tons, number: CoalMath.spreadsheetTons(kilograms))
    }
    static func count(_ n: Int) -> ReportCell { ReportCell(text: "\(n)", kind: .count, number: "\(n)") }
}

struct ReportTable: Equatable {
    let columns: [ReportColumn]
    let rows: [[ReportCell]]
    /// The bold «Итого» row.
    let total: [ReportCell]?
}

struct ReportPair: Equatable {
    let label: String
    let value: ReportCell
    let bold: Bool
}

/// Turns a day or month report into blocks.
enum CoalReportLayout {
    static let signatureLabel = "Руководитель КФХ: ___________________"
    static let remainderNote = "Остаток после выплат водителям — не чистая прибыль: себестоимость угля и прочие расходы не учтены."

    static func document(_ r: CoalReport, calendar: Calendar = .farm) -> CoalReportDocument {
        var blocks: [ReportBlock] = []
        let period = r.periodTitle(calendar: calendar)
        switch r.kind {
        case .day:
            blocks.append(.title("ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ"))
            blocks.append(.boldText(r.farmName))
            blocks.append(.text("Дата: \(period) (\(CoalText.longDate(r.start, calendar: calendar).components(separatedBy: ",").first ?? ""))"))
            if let closedAt = r.closedAt {
                blocks.append(.text("Рабочий день завершён: \(CoalText.dateTime(closedAt, calendar: calendar))"))
            } else {
                blocks.append(.text("Рабочий день не завершён"))
            }
        case .month:
            blocks.append(.title("ОТЧЁТ ЗА МЕСЯЦ"))
            blocks.append(.boldText(r.farmName))
            blocks.append(.text("Месяц: \(period)"))
        }

        blocks.append(.heading("Продажи угля"))
        if r.rows.isEmpty {
            blocks.append(.text("Продаж нет."))
        } else {
            blocks.append(.table(salesTable(r, calendar: calendar)))
            let adjusted = r.rows.compactMap { row -> String? in
                guard let list = row.listTotal else { return nil }
                return "№\(row.number): по прайсу \(CoalMath.rubles(list)), фактически \(CoalMath.rubles(row.totalAmount))"
            }
            if !adjusted.isEmpty {
                blocks.append(.note("* Цена изменена вручную. " + adjusted.joined(separator: "; ") + "."))
            }
        }

        let t = r.totals
        blocks.append(.heading("Финансовые итоги"))
        var pairs: [ReportPair] = [
            ReportPair(label: "Продаж", value: .count(t.salesCount), bold: false),
            ReportPair(label: "Продано угля, т", value: .tons(t.kilograms), bold: false),
            ReportPair(label: "Доставок наёмными машинами", value: .count(t.hiredDeliveries), bold: false),
            ReportPair(label: "Доставок собственной машиной", value: .count(t.ownDeliveries), bold: false),
            ReportPair(label: "А. Общая выручка хозяйства, ₽", value: .money(t.revenue), bold: true),
            ReportPair(label: "Б. Стоимость проданного угля, ₽", value: .money(t.coalAmount), bold: false),
            ReportPair(label: "В. Общая стоимость доставки, ₽", value: .money(t.deliveryAmount), bold: false),
            ReportPair(label: "Г. Выплаты наёмным водителям (начислено), ₽", value: .money(t.driverPayments), bold: false),
            ReportPair(label: "Д. Доход от собственной машины, ₽", value: .money(t.ownVehicleDeliveryRevenue), bold: false),
            ReportPair(label: "Е. Остаток после выплат водителям (А − Г), ₽", value: .money(t.remainderAfterDrivers), bold: true),
        ]
        if r.totalPaid > 0 {
            pairs.append(ReportPair(label: "Фактически выплачено водителям за период, ₽", value: .money(r.totalPaid),
                                    bold: false))
        }
        blocks.append(.pairs(pairs))
        blocks.append(.note(remainderNote))

        blocks.append(.heading("Взаиморасчёты с водителями"))
        if r.drivers.isEmpty {
            blocks.append(.text("Начислений наёмным водителям нет."))
        } else {
            blocks.append(.table(driversTable(r)))
            blocks.append(.note("Начисленное не считается выплаченным: долг уменьшается только записанными выплатами "
                                + "и переносится на следующие дни."))
        }

        if r.kind == .month && !r.locations.isEmpty {
            blocks.append(.heading("Итоги по населённым пунктам"))
            blocks.append(.table(locationsTable(r)))
        }

        if !r.payments.isEmpty {
            blocks.append(.heading("Выплаты водителям"))
            blocks.append(.table(paymentsTable(r, calendar: calendar)))
        }

        blocks.append(.signature(label: signatureLabel, name: r.managerName))
        blocks.append(.note("Дата формирования: \(CoalText.dateTime(r.createdAt, calendar: calendar))"))

        let kindTitle = r.kind == .day ? "Отчёт за день" : "Отчёт за месяц"
        return CoalReportDocument(fileName: fileName(r, calendar: calendar),
                                  runningHeader: "\(r.farmName) · \(kindTitle) \(period)", blocks: blocks)
    }

    /// «Уголь_день_2026-11-06», «Уголь_месяц_2026-11».
    static func fileName(_ r: CoalReport, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: r.start)
        switch r.kind {
        case .day: return String(format: "Уголь_день_%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        case .month: return String(format: "Уголь_месяц_%04d-%02d", c.year ?? 0, c.month ?? 0)
        }
    }

    private static func salesTable(_ r: CoalReport, calendar: Calendar) -> ReportTable {
        let timeTitle = r.kind == .day ? "Время" : "Дата, время"
        let columns = [
            ReportColumn(title: "№", weight: 4, align: .center),
            ReportColumn(title: timeTitle, weight: r.kind == .day ? 7 : 12, align: .center),
            ReportColumn(title: "Тонн", weight: 6, align: .right),
            ReportColumn(title: "Населённый пункт", weight: 17, align: .left),
            ReportColumn(title: "Машина", weight: 9, align: .left),
            ReportColumn(title: "Водитель", weight: 15, align: .left),
            ReportColumn(title: "Уголь, ₽", weight: 11, align: .right),
            ReportColumn(title: "Доставка, ₽", weight: 10, align: .right),
            ReportColumn(title: "Итого, ₽", weight: 11, align: .right),
        ]
        let rows: [[ReportCell]] = r.rows.map { row in
            let time = r.kind == .day ? CoalText.time(row.date, calendar: calendar)
                                      : CoalText.dateTime(row.date, calendar: calendar)
            let total = ReportCell.money(row.totalAmount)
            return [.count(row.number), .text(time), .tons(row.kilograms), .text(row.location),
                    .text(row.vehicle.shortTitle), .text(row.driver.isEmpty ? "—" : row.driver),
                    .money(row.coalAmount), .money(row.deliveryPrice),
                    row.listTotal == nil ? total : ReportCell(text: total.text + "*", kind: .money, number: total.number)]
        }
        let t = r.totals
        return ReportTable(columns: columns, rows: rows,
                           total: [.text("Итого"), .text(""), .tons(t.kilograms), .text(CoalText.sales(t.salesCount)),
                                   .text(""), .text(""), .money(t.coalAmount), .money(t.deliveryAmount), .money(t.revenue)])
    }

    private static func driversTable(_ r: CoalReport) -> ReportTable {
        let columns = [
            ReportColumn(title: "Водитель", weight: 26, align: .left),
            ReportColumn(title: "Доставок", weight: 9, align: .center),
            ReportColumn(title: "Долг на начало, ₽", weight: 14, align: .right),
            ReportColumn(title: "Начислено, ₽", weight: 13, align: .right),
            ReportColumn(title: "Выплачено, ₽", weight: 13, align: .right),
            ReportColumn(title: "К выплате, ₽", weight: 14, align: .right),
        ]
        let rows: [[ReportCell]] = r.drivers.map {
            [.text($0.name), .count($0.deliveries), .money($0.debtBefore), .money($0.accrued), .money($0.paid),
             .money($0.debtAfter)]
        }
        let sum = { (f: (DriverSettlement) -> Int) in r.drivers.reduce(0) { $0 + f($1) } }
        return ReportTable(columns: columns, rows: rows,
                           total: [.text("ИТОГО К ВЫПЛАТЕ"), .count(sum { $0.deliveries }), .money(sum { $0.debtBefore }),
                                   .money(sum { $0.accrued }), .money(sum { $0.paid }), .money(sum { $0.debtAfter })])
    }

    private static func locationsTable(_ r: CoalReport) -> ReportTable {
        let columns = [
            ReportColumn(title: "Населённый пункт", weight: 30, align: .left),
            ReportColumn(title: "Продаж", weight: 10, align: .center),
            ReportColumn(title: "Тонн", weight: 10, align: .right),
            ReportColumn(title: "Доставка, ₽", weight: 15, align: .right),
            ReportColumn(title: "Выручка, ₽", weight: 15, align: .right),
        ]
        let rows: [[ReportCell]] = r.locations.map {
            [.text($0.name), .count($0.salesCount), .tons($0.kilograms), .money($0.deliveryAmount), .money($0.revenue)]
        }
        let t = r.totals
        return ReportTable(columns: columns, rows: rows,
                           total: [.text("Итого"), .count(t.salesCount), .tons(t.kilograms), .money(t.deliveryAmount),
                                   .money(t.revenue)])
    }

    private static func paymentsTable(_ r: CoalReport, calendar: Calendar) -> ReportTable {
        let columns = [
            ReportColumn(title: "Дата", weight: 14, align: .center),
            ReportColumn(title: "Водитель", weight: 26, align: .left),
            ReportColumn(title: "Сумма, ₽", weight: 14, align: .right),
            ReportColumn(title: "Комментарий", weight: 30, align: .left),
        ]
        let rows: [[ReportCell]] = r.payments.map {
            [.text(CoalText.dateTime($0.date, calendar: calendar)), .text($0.driver), .money($0.amount), .text($0.comment)]
        }
        return ReportTable(columns: columns, rows: rows,
                           total: [.text("Итого"), .text(""), .money(r.totalPaid), .text("")])
    }
}
