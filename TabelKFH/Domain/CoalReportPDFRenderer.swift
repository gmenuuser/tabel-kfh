import UIKit

/// A coal report as an A4 portrait PDF: blocks flow down the page, a table that does not fit continues on the
/// next page with its header repeated; «Страница N из M» on each page. Arial, as in the timesheet (the system
/// font breaks the Cyrillic «к» in PDF text).
enum CoalReportPDFRenderer {
    static let pageSize = CGSize(width: 595, height: 842)
    static let margin: CGFloat = 30

    static func render(_ d: CoalReportDocument) -> Data {
        let pageCount = Flow(d, drawing: false).run(beginPage: {}, pageCount: 0)
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [
            kCGPDFContextTitle as String: d.fileName,
            kCGPDFContextCreator as String: "Табель КФХ",
        ]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize), format: format)
        return renderer.pdfData { context in
            _ = Flow(d, drawing: true).run(beginPage: { context.beginPage() }, pageCount: pageCount)
        }
    }

    /// Lays the blocks out page by page; with `drawing` false it only counts the pages.
    private final class Flow {
        let d: CoalReportDocument
        let drawing: Bool
        let content: CGRect
        let bottom: CGFloat
        private var y: CGFloat = 0
        private var page = 0
        private var pageCount = 0
        private var beginPage: () -> Void = {}

        init(_ d: CoalReportDocument, drawing: Bool) {
            self.d = d
            self.drawing = drawing
            let m = CoalReportPDFRenderer.margin, size = CoalReportPDFRenderer.pageSize
            content = CGRect(x: m, y: m, width: size.width - 2 * m, height: size.height - 2 * m)
            bottom = content.maxY - 16
        }

        func run(beginPage: @escaping () -> Void, pageCount: Int) -> Int {
            self.beginPage = beginPage
            self.pageCount = pageCount
            newPage()
            for block in d.blocks { place(block) }
            drawPageNumber()
            return page
        }

        private func newPage() {
            if page > 0 { drawPageNumber() }
            beginPage()
            page += 1
            y = content.minY
            if page > 1 {
                text(d.runningHeader, font: Font.small, color: Color.secondary, align: .left, height: 10)
                y += 14
            }
        }

        private func drawPageNumber() {
            guard drawing else { return }
            Text.draw("Страница \(page) из \(pageCount)",
                      in: CGRect(x: content.minX, y: content.maxY - 10, width: content.width, height: 10),
                      font: Font.small, color: Color.secondary, align: .right)
        }

        /// Starts a new page when `height` does not fit below the cursor.
        private func ensure(_ height: CGFloat) {
            if y + height > bottom && y > content.minY + 20 { newPage() }
        }

        private func place(_ block: ReportBlock) {
            switch block {
            case .title(let s):
                ensure(24)
                text(s, font: Font.title, align: .center, height: 20)
                y += 26
            case .text(let s):
                paragraph(s, font: Font.body, color: .black, after: 3)
            case .boldText(let s):
                paragraph(s, font: Font.bodyBold, color: .black, after: 3)
            case .note(let s):
                paragraph(s, font: Font.small, color: Color.secondary, after: 4)
            case .heading(let s):
                y += 8
                ensure(16 + 40)
                text(s, font: Font.heading, align: .left, height: 15)
                y += 18
            case .table(let t):
                table(t)
                y += 4
            case .pairs(let pairs):
                for p in pairs { pair(p) }
                y += 4
            case .signature(let label, let name):
                y += 18
                ensure(18)
                let width = Text.width(label, font: Font.signature)
                if drawing {
                    Text.draw(label, in: CGRect(x: content.minX, y: y, width: width + 2, height: 13), font: Font.signature,
                              align: .left, inset: false)
                    if !name.isEmpty {
                        Text.draw(name, in: CGRect(x: content.minX + width + 8, y: y, width: content.width - width - 8,
                                                   height: 13), font: Font.signature, align: .left, inset: false)
                    }
                }
                y += 22
            }
        }

        private func text(_ s: String, font: UIFont, color: UIColor = .black, align: NSTextAlignment, height: CGFloat) {
            guard drawing else { return }
            Text.draw(s, in: CGRect(x: content.minX, y: y, width: content.width, height: height), font: font,
                      color: color, align: align, inset: false)
        }

        private func paragraph(_ s: String, font: UIFont, color: UIColor, after: CGFloat) {
            let height = ceil(Text.height(s, font: font, width: content.width))
            ensure(height)
            if drawing {
                Text.draw(s, in: CGRect(x: content.minX, y: y, width: content.width, height: height), font: font,
                          color: color, align: .left, wraps: true, inset: false)
            }
            y += height + after
        }

        private func pair(_ p: ReportPair) {
            let valueWidth: CGFloat = 130
            let labelFont = p.bold ? Font.bodyBold : Font.body
            let height = max(16, ceil(Text.height(p.label, font: labelFont, width: content.width - valueWidth - 8)) + 4)
            ensure(height)
            if drawing {
                let label = CGRect(x: content.minX, y: y, width: content.width - valueWidth, height: height)
                let value = CGRect(x: content.maxX - valueWidth, y: y, width: valueWidth, height: height)
                if p.bold { Cell.fill(label.union(value), Color.totals) }
                Text.draw(p.label, in: label, font: labelFont, align: .left, wraps: true)
                Text.draw(p.value.text, in: value, font: p.bold ? Font.bodyBold : Font.body, align: .right)
                Cell.line(from: CGPoint(x: content.minX, y: y + height), to: CGPoint(x: content.maxX, y: y + height))
            }
            y += height
        }

        // MARK: Table

        private func widths(_ t: ReportTable) -> [CGFloat] {
            let total = t.columns.reduce(0) { $0 + $1.weight }
            return t.columns.map { CGFloat($0.weight / max(total, 1)) * content.width }
        }

        private func rowHeight(_ cells: [ReportCell], widths: [CGFloat], font: UIFont) -> CGFloat {
            let heights = zip(cells, widths).map { cell, width in
                Text.height(cell.text, font: font, width: width - 2 * Text.padX)
            }
            return ceil(max(14, (heights.max() ?? 0) + 2 * Text.padY + 2))
        }

        private func table(_ t: ReportTable) {
            let widths = widths(t)
            let headerHeight = rowHeight(t.columns.map { .text($0.title) }, widths: widths, font: Font.header)
            func header() {
                if drawing {
                    var x = content.minX
                    for (column, width) in zip(t.columns, widths) {
                        Cell.draw(column.title, CGRect(x: x, y: y, width: width, height: headerHeight), font: Font.header,
                                  fill: Color.header, align: .center, wraps: true)
                        x += width
                    }
                }
                y += headerHeight
            }
            let first = t.rows.first.map { rowHeight($0, widths: widths, font: Font.cell) } ?? 14
            ensure(headerHeight + first)
            header()
            var lines = t.rows.map { ($0, false) }
            if let total = t.total { lines.append((total, true)) }
            for (cells, isTotal) in lines {
                let font = isTotal ? Font.cellBold : Font.cell
                let height = rowHeight(cells, widths: widths, font: font)
                if y + height > bottom {
                    newPage()
                    header()
                }
                if drawing {
                    var x = content.minX
                    for (index, (cell, width)) in zip(cells, widths).enumerated() {
                        let align: NSTextAlignment
                        switch t.columns[index].align {
                        case .left: align = .left
                        case .center: align = .center
                        case .right: align = .right
                        }
                        Cell.draw(cell.text, CGRect(x: x, y: y, width: width, height: height), font: font,
                                  fill: isTotal ? Color.totals : nil, align: align, wraps: cell.kind == .text)
                        x += width
                    }
                }
                y += height
            }
        }
    }

    // MARK: - Primitives

    private enum Font {
        static func regular(_ size: CGFloat) -> UIFont { UIFont(name: "ArialMT", size: size) ?? .systemFont(ofSize: size) }
        static func heavy(_ size: CGFloat) -> UIFont { UIFont(name: "Arial-BoldMT", size: size) ?? .boldSystemFont(ofSize: size) }

        static let title = heavy(15)
        static let heading = heavy(11)
        static let body = regular(9.5)
        static let bodyBold = heavy(9.5)
        static let header = heavy(8)
        static let cell = regular(8)
        static let cellBold = heavy(8)
        static let small = regular(7.5)
        static let signature = regular(10)
    }

    private enum Color {
        static let border = UIColor(white: 0.6, alpha: 1)
        static let header = UIColor(red: 0.86, green: 0.92, blue: 0.88, alpha: 1)
        static let totals = UIColor(red: 0.95, green: 0.97, blue: 0.96, alpha: 1)
        static let secondary = UIColor(white: 0.35, alpha: 1)
    }

    private enum Cell {
        static func draw(_ text: String, _ rect: CGRect, font: UIFont, fill fillColor: UIColor? = nil,
                         align: NSTextAlignment, wraps: Bool) {
            if let fillColor { fill(rect, fillColor) }
            Text.draw(text, in: rect, font: font, align: align, wraps: wraps)
            let path = UIBezierPath(rect: rect)
            path.lineWidth = 0.5
            Color.border.setStroke()
            path.stroke()
        }

        static func fill(_ rect: CGRect, _ color: UIColor) {
            color.setFill()
            UIRectFill(rect)
        }

        static func line(from a: CGPoint, to b: CGPoint) {
            let path = UIBezierPath()
            path.move(to: a)
            path.addLine(to: b)
            path.lineWidth = 0.5
            Color.border.setStroke()
            path.stroke()
        }
    }

    private enum Text {
        static let padX: CGFloat = 3
        static let padY: CGFloat = 2
        private static let options: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]

        static func attributed(_ text: String, font: UIFont, color: UIColor = .black, align: NSTextAlignment = .left,
                               wraps: Bool = true) -> NSAttributedString {
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
            (text as NSString).size(withAttributes: [.font: font]).width
        }

        /// Centred vertically; a line that does not wrap is shrunk to the width (down to 5 pt).
        static func draw(_ text: String, in rect: CGRect, font: UIFont, color: UIColor = .black, align: NSTextAlignment,
                         wraps: Bool = false, inset: Bool = true) {
            guard !text.isEmpty else { return }
            let inner = inset ? rect.insetBy(dx: padX, dy: padY) : rect
            var drawFont = font
            if !wraps {
                let widest = width(text, font: font)
                if widest > inner.width, widest > 0 {
                    drawFont = font.withSize(max(5, font.pointSize * inner.width / widest))
                }
            }
            let string = attributed(text, font: drawFont, color: color, align: align, wraps: wraps)
            let height = ceil(string.boundingRect(with: CGSize(width: inner.width, height: .greatestFiniteMagnitude),
                                                  options: options, context: nil).height)
            let top = inner.minY + max(0, (inner.height - height) / 2)
            string.draw(with: CGRect(x: inner.minX, y: top, width: inner.width, height: height), options: options,
                        context: nil)
        }
    }
}
