import UIKit

/// A timesheet as an A4 landscape PDF: title block, the table (header repeated on every page, ФИО wraps and the
/// row grows), the farm total row, the legend and the signature lines; «Страница N из M» on each page.
enum TimesheetPDFRenderer {
    static let pageSize = CGSize(width: 842, height: 595)
    static let margin: CGFloat = 24

    static func render(_ d: TimesheetDocument) -> Data {
        let layout = Layout(d)
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [
            kCGPDFContextTitle as String: exportFileName(d, ext: "pdf"),
            kCGPDFContextCreator as String: "Табель КФХ",
        ]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize), format: format)
        return renderer.pdfData { context in
            for (index, page) in layout.pages.enumerated() {
                context.beginPage()
                layout.draw(page, number: index + 1, of: layout.pages.count)
            }
        }
    }

    // MARK: - Layout

    private struct Page {
        var first: Bool
        var rows: [Int] = []
        var farmTotal = false
        /// Top of the legend and signature block, on the page that has it.
        var footerTop: CGFloat?
        var hasTable: Bool { !rows.isEmpty || farmTotal }
    }

    private struct Layout {
        let d: TimesheetDocument
        let calendar = Calendar.farm
        let content: CGRect
        let numberWidth: CGFloat = 18
        let nameWidth: CGFloat = 110
        /// Widths of the totals columns, «Часы» wider.
        let totalWidths: [CGFloat]
        let dayWidth: CGFloat
        let headerHeight: CGFloat = 20
        let rowHeights: [CGFloat]
        let farmHeight: CGFloat = 16
        let weekends: Set<Int>
        let leftInfo: [String]
        let rightInfo: [String]
        let firstTop: CGFloat
        let otherTop: CGFloat
        let bottom: CGFloat
        private(set) var pages: [Page] = []

        init(_ d: TimesheetDocument) {
            self.d = d
            let m = TimesheetPDFRenderer.margin, size = TimesheetPDFRenderer.pageSize
            content = CGRect(x: m, y: m, width: size.width - 2 * m, height: size.height - 2 * m)
            totalWidths = d.totalsTitles.enumerated().map { index, _ in d.isHours && index < 2 ? 26 : 19 }
            let days = max(1, d.daysInMonth)
            let fixed = numberWidth + nameWidth + totalWidths.reduce(0, +)
            dayWidth = (content.width - fixed) / CGFloat(days)
            let calendar = self.calendar
            weekends = Set((1...days).filter { day in
                calendar.isDateInWeekend(calendar.date(day: day, month: d.month, year: d.year))
            })

            leftInfo = [d.farmName, d.address, d.phone.isEmpty ? "" : "Тел.: \(d.phone)", d.extraInfo].filter { !$0.isEmpty }
            rightInfo = ["Месяц: \(calendar.monthName(d.month))", "Год: \(d.year)"]
            firstTop = m + 22 + CGFloat(max(leftInfo.count, rightInfo.count)) * Font.infoLine + 8
            otherTop = m + 14
            bottom = size.height - m - 14

            let nameWidth = self.nameWidth
            rowHeights = d.rows.map { row in
                let name = Text.height(row.employee.fullName, font: Font.body, width: nameWidth - 2 * Text.padX)
                let lines = row.cells.map { $0.text.isEmpty ? 0 : $0.text.components(separatedBy: "\n").count }.max() ?? 0
                let cell = CGFloat(lines) * Font.number.lineHeight
                return ceil(max(14, name + 2 * Text.padY + 1, cell + 2 * Text.padY + 1))
            }
            pages = paginate()
        }

        private func paginate() -> [Page] {
            var pages: [Page] = []
            var page = Page(first: true)
            var y = firstTop + headerHeight
            func next(table: Bool) {
                pages.append(page)
                page = Page(first: false)
                y = otherTop + (table ? headerHeight : 0)
            }
            for (index, height) in rowHeights.enumerated() {
                if y + height > bottom && !page.rows.isEmpty { next(table: true) }
                page.rows.append(index)
                y += height
            }
            if y + farmHeight > bottom && !page.rows.isEmpty { next(table: true) }
            page.farmTotal = true
            y += farmHeight + 8
            if y + footerHeight > bottom { next(table: false) }
            page.footerTop = y
            pages.append(page)
            return pages
        }

        // MARK: Drawing

        func draw(_ page: Page, number: Int, of count: Int) {
            if page.first { drawTitle() } else { drawRunningHeader() }
            var y = page.first ? firstTop : otherTop
            if page.hasTable {
                drawTableHeader(y: y)
                y += headerHeight
                for index in page.rows {
                    drawRow(d.rows[index], y: y, height: rowHeights[index])
                    y += rowHeights[index]
                }
                if page.farmTotal { drawFarmTotal(y: y) }
            }
            if let top = page.footerTop { drawFooter(y: top) }
            Text.draw("Страница \(number) из \(count)",
                      in: CGRect(x: content.minX, y: content.maxY - 9, width: content.width, height: 9),
                      font: Font.small, color: Color.secondary, align: .right)
        }

        private func drawTitle() {
            Text.draw("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", in: CGRect(x: content.minX, y: content.minY, width: content.width, height: 18),
                      font: Font.title, align: .center)
            let top = content.minY + 22
            for (i, line) in leftInfo.enumerated() {
                Text.draw(line, in: CGRect(x: content.minX, y: top + CGFloat(i) * Font.infoLine, width: content.width * 0.7,
                                           height: Font.infoLine),
                          font: i == 0 ? Font.infoBold : Font.info, align: .left, inset: false)
            }
            for (i, line) in rightInfo.enumerated() {
                Text.draw(line, in: CGRect(x: content.maxX - 200, y: top + CGFloat(i) * Font.infoLine, width: 200,
                                           height: Font.infoLine),
                          font: Font.info, align: .right, inset: false)
            }
        }

        private func drawRunningHeader() {
            let line = ["ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", d.farmName, calendar.monthTitle(month: d.month, year: d.year),
                        ].filter { !$0.isEmpty }.joined(separator: " · ")
            Text.draw(line, in: CGRect(x: content.minX, y: content.minY, width: content.width, height: 10),
                      font: Font.small, color: Color.secondary, align: .left, inset: false)
        }

        private func drawTableHeader(y: CGFloat) {
            var x = content.minX
            Cell.draw("№", CGRect(x: x, y: y, width: numberWidth, height: headerHeight), font: Font.bold, fill: Color.header)
            x += numberWidth
            Cell.draw("ФИО", CGRect(x: x, y: y, width: nameWidth, height: headerHeight), font: Font.bold, fill: Color.header,
                      align: .left)
            x += nameWidth
            let weekdays = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"]
            for day in 1...max(1, d.daysInMonth) {
                let rect = CGRect(x: x, y: y, width: dayWidth, height: headerHeight)
                let weekday = calendar.component(.weekday, from: calendar.date(day: day, month: d.month, year: d.year))
                Cell.fill(rect, weekends.contains(day) ? Color.weekendHeader : Color.header)
                Text.draw("\(day)", in: CGRect(x: x, y: y + 1, width: dayWidth, height: headerHeight * 0.55), font: Font.bold,
                          align: .center)
                Text.draw(weekdays[weekday - 1], in: CGRect(x: x, y: y + headerHeight * 0.5, width: dayWidth,
                                                            height: headerHeight * 0.5 - 1),
                          font: Font.weekday, color: Color.secondary, align: .center)
                Cell.stroke(rect)
                x += dayWidth
            }
            for (title, width) in zip(d.totalsTitles, totalWidths) {
                Cell.draw(title, CGRect(x: x, y: y, width: width, height: headerHeight), font: Font.bold, fill: Color.header)
                x += width
            }
        }

        private func drawRow(_ row: TimesheetRow, y: CGFloat, height: CGFloat) {
            var x = content.minX
            Cell.draw("\(row.number)", CGRect(x: x, y: y, width: numberWidth, height: height), font: Font.number)
            x += numberWidth
            Cell.draw(row.employee.fullName, CGRect(x: x, y: y, width: nameWidth, height: height), font: Font.body,
                      align: .left, wraps: true)
            x += nameWidth
            for (index, cell) in row.cells.enumerated() {
                let fill: UIColor? = !cell.employed ? Color.notEmployed : (weekends.contains(index + 1) ? Color.weekend : nil)
                Cell.draw(cell.text, CGRect(x: x, y: y, width: dayWidth, height: height),
                          font: Font.number, fill: fill, color: .black)
                x += dayWidth
            }
            for (value, width) in zip(d.totalsValues(row).map(String.init), totalWidths) {
                Cell.draw(value, CGRect(x: x, y: y, width: width, height: height), font: Font.number, fill: Color.totals)
                x += width
            }
        }

        private func drawFarmTotal(y: CGFloat) {
            var x = content.minX
            Cell.draw("Итого по хозяйству", CGRect(x: x, y: y, width: numberWidth + nameWidth, height: farmHeight),
                      font: Font.bold, fill: Color.header, align: .left)
            x += numberWidth + nameWidth
            let daysWidth = dayWidth * CGFloat(max(1, d.daysInMonth))
            Cell.draw("", CGRect(x: x, y: y, width: daysWidth, height: farmHeight), font: Font.bold, fill: Color.header)
            x += daysWidth
            for (value, width) in zip(d.farmTotalsValues.map(String.init), totalWidths) {
                Cell.draw(value, CGRect(x: x, y: y, width: width, height: farmHeight), font: Font.boldNumber,
                          fill: Color.header)
                x += width
            }
        }

        private var legend: String {
            d.legendText + " Серые ячейки — дни до приёма или после увольнения."
        }

        private var unfinishedText: String? {
            nil
        }

        private var signatures: [(String, String)] {
            [("Руководитель КФХ: ___________________", d.managerName),
             ("Ответственное лицо: ___________________", d.responsiblePerson),
             ("Дата формирования: \(d.createdDateText(calendar: calendar))", "")]
        }

        private static let signatureLine: CGFloat = 18

        private var footerHeight: CGFloat {
            var height = ceil(Text.height(legend, font: Font.small, width: content.width)) + 3
            if let unfinishedText { height += ceil(Text.height(unfinishedText, font: Font.small, width: content.width)) + 3 }
            return height + 8 + CGFloat(signatures.count) * Self.signatureLine
        }

        private func drawFooter(y top: CGFloat) {
            var y = top
            let legendHeight = ceil(Text.height(legend, font: Font.small, width: content.width))
            Text.draw(legend, in: CGRect(x: content.minX, y: y, width: content.width, height: legendHeight),
                      font: Font.small, color: Color.secondary, align: .left, wraps: true, inset: false)
            y += legendHeight + 3
            if let unfinishedText {
                let height = ceil(Text.height(unfinishedText, font: Font.small, width: content.width))
                Text.draw(unfinishedText, in: CGRect(x: content.minX, y: y, width: content.width, height: height),
                          font: Font.small, color: Color.unfinished, align: .left, wraps: true, inset: false)
                y += height + 3
            }
            y += 8
            for (label, name) in signatures {
                let width = Text.width(label, font: Font.signature)
                Text.draw(label, in: CGRect(x: content.minX, y: y, width: width + 2, height: 12), font: Font.signature,
                          align: .left, inset: false)
                if !name.isEmpty {
                    Text.draw(name, in: CGRect(x: content.minX + width + 8, y: y, width: 300, height: 12),
                              font: Font.signatureName, align: .left, inset: false)
                }
                y += Self.signatureLine
            }
        }
    }

    // MARK: - Drawing primitives

    /// Arial (built into iOS, full Cyrillic, tabular digits). Not the system font: in its PDF text the Cyrillic
    /// «к» comes out as «ĸ», which breaks search and copy («Руĸоводитель»).
    private enum Font {
        static func regular(_ size: CGFloat) -> UIFont { UIFont(name: "ArialMT", size: size) ?? .systemFont(ofSize: size) }
        static func heavy(_ size: CGFloat) -> UIFont { UIFont(name: "Arial-BoldMT", size: size) ?? .boldSystemFont(ofSize: size) }

        static let title = heavy(14)
        static let info = regular(9)
        static let infoBold = heavy(10)
        static let infoLine: CGFloat = 12
        static let body = regular(7)
        static let bold = heavy(7)
        static let number = regular(7)
        static let boldNumber = heavy(7)
        static let weekday = regular(5.5)
        static let small = regular(7)
        static let signature = regular(9)
        static let signatureName = regular(9)
    }

    private enum Color {
        static let border = UIColor(white: 0.6, alpha: 1)
        static let header = UIColor(red: 0.86, green: 0.92, blue: 0.88, alpha: 1)
        static let weekendHeader = UIColor(white: 0.84, alpha: 1)
        static let weekend = UIColor(white: 0.94, alpha: 1)
        static let notEmployed = UIColor(white: 0.82, alpha: 1)
        static let totals = UIColor(red: 0.95, green: 0.97, blue: 0.96, alpha: 1)
        static let unfinished = UIColor(red: 0.80, green: 0.42, blue: 0.08, alpha: 1)
        static let secondary = UIColor(white: 0.35, alpha: 1)
    }

    private enum Cell {
        static let borderWidth: CGFloat = 0.5

        static func draw(_ text: String, _ rect: CGRect, font: UIFont, fill fillColor: UIColor? = nil,
                         color: UIColor = .black, align: NSTextAlignment = .center, wraps: Bool = false) {
            if let fillColor { fill(rect, fillColor) }
            Text.draw(text, in: rect, font: font, color: color, align: align, wraps: wraps)
            stroke(rect)
        }

        static func fill(_ rect: CGRect, _ color: UIColor) {
            color.setFill()
            UIRectFill(rect)
        }

        static func stroke(_ rect: CGRect) {
            let path = UIBezierPath(rect: rect)
            path.lineWidth = borderWidth
            Color.border.setStroke()
            path.stroke()
        }
    }

    private enum Text {
        static let padX: CGFloat = 1.5
        static let padY: CGFloat = 1.5
        private static let options: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]

        static func attributed(_ text: String, font: UIFont, color: UIColor = .black,
                               align: NSTextAlignment = .left, wraps: Bool = true) -> NSAttributedString {
            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = align
            paragraph.lineBreakMode = wraps ? .byWordWrapping : .byClipping
            return NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color,
                                                                 .paragraphStyle: paragraph])
        }

        static func height(_ text: String, font: UIFont, width: CGFloat) -> CGFloat {
            guard !text.isEmpty else { return 0 }
            return attributed(text, font: font).boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                                             options: options, context: nil).height
        }

        static func width(_ text: String, font: UIFont) -> CGFloat {
            text.components(separatedBy: "\n").map { ($0 as NSString).size(withAttributes: [.font: font]).width }.max() ?? 0
        }

        /// Centred vertically in `rect`. A text that does not wrap is shrunk to fit the width (down to 4.5 pt).
        static func draw(_ text: String, in rect: CGRect, font: UIFont, color: UIColor = .black,
                         align: NSTextAlignment, wraps: Bool = false, inset: Bool = true) {
            guard !text.isEmpty else { return }
            let inner = inset ? rect.insetBy(dx: padX, dy: padY) : rect
            if !wraps {
                // Line by line, each shrunk to the width if needed: «Я» keeps its size above «7,75».
                let lines = text.components(separatedBy: "\n").map { line -> (String, UIFont) in
                    let widest = width(line, font: font)
                    guard widest > inner.width, widest > 0 else { return (line, font) }
                    return (line, font.withSize(max(4.5, font.pointSize * inner.width / widest)))
                }
                let total = lines.reduce(CGFloat(0)) { $0 + $1.1.lineHeight }
                var y = inner.minY + max(0, (inner.height - total) / 2)
                for (line, lineFont) in lines {
                    attributed(line, font: lineFont, color: color, align: align, wraps: false)
                        .draw(with: CGRect(x: inner.minX, y: y, width: inner.width, height: ceil(lineFont.lineHeight)),
                              options: options, context: nil)
                    y += lineFont.lineHeight
                }
                return
            }
            let string = attributed(text, font: font, color: color, align: align, wraps: wraps)
            let height = ceil(string.boundingRect(with: CGSize(width: inner.width, height: .greatestFiniteMagnitude),
                                                  options: options, context: nil).height)
            let y = inner.minY + max(0, (inner.height - height) / 2)
            string.draw(with: CGRect(x: inner.minX, y: y, width: inner.width, height: height), options: options, context: nil)
        }
    }
}
