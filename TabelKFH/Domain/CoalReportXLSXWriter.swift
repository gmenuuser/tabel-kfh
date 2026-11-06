import Foundation

/// A coal report as a one-sheet Excel workbook («Отчёт»): the same blocks as the PDF, amounts and tons as
/// numbers (so they can be summed), thin borders on tables, A4 portrait print fitted to the page width.
enum CoalReportXLSXWriter {
    static func make(_ d: CoalReportDocument) -> Data {
        let sheet = Sheet(d)
        var zip = ZipWriter()
        zip.add(path: "[Content_Types].xml", data: Data(contentTypes.utf8))
        zip.add(path: "_rels/.rels", data: Data(rootRels.utf8))
        zip.add(path: "docProps/app.xml", data: Data(app.utf8))
        zip.add(path: "xl/workbook.xml", data: Data(workbook.utf8))
        zip.add(path: "xl/_rels/workbook.xml.rels", data: Data(workbookRels.utf8))
        zip.add(path: "xl/styles.xml", data: Data(styles.utf8))
        zip.add(path: "xl/worksheets/sheet1.xml", data: Data(sheet.xml.utf8))
        return zip.finish()
    }

    /// Indexes into `cellXfs`.
    private enum Style: Int {
        case plain = 0, title, bold, note, heading, header, text, money, tons, count,
             totalText, totalMoney, totalTons, totalCount, pairLabel, pairLabelBold
    }

    private struct Sheet {
        /// Columns of the widest table (the sales table: 9).
        let columnCount: Int
        let widths: [Double]
        private var rows: [String] = []
        private var merges: [String] = []
        private var row = 0

        init(_ d: CoalReportDocument) {
            var tables: [ReportTable] = []
            for block in d.blocks { if case .table(let t) = block { tables.append(t) } }
            columnCount = max(6, tables.map(\.columns.count).max() ?? 6)
            var widths = Array(repeating: 10.0, count: columnCount)
            for t in tables {
                for (index, column) in t.columns.enumerated() {
                    widths[index] = max(widths[index], min(40, column.weight * 1.15 + 2))
                }
            }
            self.widths = widths
            for block in d.blocks { add(block) }
        }

        var xml: String {
            let last = XLSXWriter.column(columnCount)
            let cols = widths.enumerated().map { index, width in
                "<col min=\"\(index + 1)\" max=\"\(index + 1)\" width=\"\(String(format: "%.1f", width))\" customWidth=\"1\"/>"
            }.joined()
            return [CoalReportXLSXWriter.header,
                    "<worksheet xmlns=\"\(CoalReportXLSXWriter.mainNS)\" xmlns:r=\"\(CoalReportXLSXWriter.relNS)\">",
                    "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>",
                    "<dimension ref=\"A1:\(last)\(max(row, 1))\"/>",
                    "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>",
                    "<sheetFormatPr defaultRowHeight=\"15\"/>",
                    "<cols>\(cols)</cols>",
                    "<sheetData>\(rows.joined())</sheetData>",
                    merges.isEmpty ? "" : "<mergeCells count=\"\(merges.count)\">\(merges.map { "<mergeCell ref=\"\($0)\"/>" }.joined())</mergeCells>",
                    "<pageMargins left=\"0.5\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>",
                    "<pageSetup paperSize=\"9\" orientation=\"portrait\" fitToWidth=\"1\" fitToHeight=\"0\"/>",
                    "<headerFooter><oddFooter>&amp;RСтраница &amp;P из &amp;N</oddFooter></headerFooter>",
                    "</worksheet>"].joined()
        }

        private mutating func add(_ block: ReportBlock) {
            switch block {
            case .title(let s): mergedLine(s, .title, height: 22)
            case .text(let s): mergedLine(s, .plain)
            case .boldText(let s): mergedLine(s, .bold)
            case .note(let s): mergedLine(s, .note, height: s.count > 110 ? 30 : nil)
            case .heading(let s):
                addRow([])
                mergedLine(s, .heading, height: 18)
            case .table(let t):
                addRow(t.columns.map { cell(.text($0.title), nil, style: .header) }, height: 30)
                for r in t.rows { addRow(r.map { cell($0, nil) }) }
                if let total = t.total { addRow(total.map { cell($0, true) }) }
            case .pairs(let pairs):
                for p in pairs {
                    let labelEnd = XLSXWriter.column(columnCount - 1)
                    merges.append("A\(row + 1):\(labelEnd)\(row + 1)")
                    var cells = [cell(.text(p.label), nil, style: p.bold ? .pairLabelBold : .pairLabel)]
                    cells += Array(repeating: empty(p.bold ? .pairLabelBold : .pairLabel), count: columnCount - 2)
                    cells.append(cell(p.value, p.bold))
                    addRow(cells)
                }
            case .signature(let label, let name):
                addRow([])
                var cells = [cell(.text(label), nil, style: .bold)]
                if !name.isEmpty {
                    cells += Array(repeating: "", count: 4)
                    cells.append(cell(.text(name), nil, style: .plain))
                }
                addRow(cells)
            }
        }

        /// A cell of a table (`total` nil = a body row, true = the «Итого» row), or of the given style.
        private func cell(_ c: ReportCell, _ total: Bool?, style: Style? = nil) -> String {
            let s: Style
            if let style {
                s = style
            } else {
                let isTotal = total == true
                switch c.kind {
                case .text: s = isTotal ? .totalText : .text
                case .money: s = isTotal ? .totalMoney : .money
                case .tons: s = isTotal ? .totalTons : .tons
                case .count: s = isTotal ? .totalCount : .count
                }
            }
            if let number = c.number {
                return "<c r=\"@REF@\" s=\"\(s.rawValue)\"><v>\(number)</v></c>"
            }
            if c.text.isEmpty { return empty(s) }
            return "<c r=\"@REF@\" s=\"\(s.rawValue)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(XLSXWriter.escape(c.text))</t></is></c>"
        }

        private func empty(_ s: Style) -> String { "<c r=\"@REF@\" s=\"\(s.rawValue)\"/>" }

        private mutating func mergedLine(_ value: String, _ style: Style, height: Double? = nil) {
            merges.append("A\(row + 1):\(XLSXWriter.column(columnCount))\(row + 1)")
            addRow([cell(.text(value), nil, style: style)], height: height)
        }

        /// `cells` are templates with «@REF@» where the reference goes; "" skips a column.
        private mutating func addRow(_ cells: [String], height: Double? = nil) {
            row += 1
            let r = row
            let body = cells.enumerated().compactMap { index, template -> String? in
                template.isEmpty ? nil
                    : template.replacingOccurrences(of: "@REF@", with: "\(XLSXWriter.column(index + 1))\(r)")
            }.joined()
            let ht = height.map { " ht=\"\($0)\" customHeight=\"1\"" } ?? ""
            rows.append("<row r=\"\(r)\"\(ht)>\(body)</row>")
        }
    }

    // MARK: - Fixed parts

    private static let header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private static let mainNS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private static let relNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private static let packageRelNS = "http://schemas.openxmlformats.org/package/2006/relationships"

    private static let contentTypes = [header,
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>",
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>",
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>",
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>",
        "</Types>"].joined()

    private static let rootRels = [header,
        "<Relationships xmlns=\"\(packageRelNS)\">",
        "<Relationship Id=\"rId1\" Type=\"\(relNS)/officeDocument\" Target=\"xl/workbook.xml\"/>",
        "<Relationship Id=\"rId2\" Type=\"\(relNS)/extended-properties\" Target=\"docProps/app.xml\"/>",
        "</Relationships>"].joined()

    private static let app = [header,
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">",
        "<Application>Табель КФХ</Application></Properties>"].joined()

    private static let workbook = [header,
        "<workbook xmlns=\"\(mainNS)\" xmlns:r=\"\(relNS)\">",
        "<sheets><sheet name=\"Отчёт\" sheetId=\"1\" r:id=\"rId1\"/></sheets>",
        "</workbook>"].joined()

    private static let workbookRels = [header,
        "<Relationships xmlns=\"\(packageRelNS)\">",
        "<Relationship Id=\"rId1\" Type=\"\(relNS)/worksheet\" Target=\"worksheets/sheet1.xml\"/>",
        "<Relationship Id=\"rId2\" Type=\"\(relNS)/styles\" Target=\"styles.xml\"/>",
        "</Relationships>"].joined()

    private static let styles: String = {
        let border = ["<left style=\"thin\"><color rgb=\"FF9A9A9A\"/></left><right style=\"thin\"><color rgb=\"FF9A9A9A\"/></right>",
                      "<top style=\"thin\"><color rgb=\"FF9A9A9A\"/></top><bottom style=\"thin\"><color rgb=\"FF9A9A9A\"/></bottom><diagonal/>"].joined()
        func fill(_ rgb: String) -> String {
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"\(rgb)\"/><bgColor indexed=\"64\"/></patternFill></fill>"
        }
        let center = "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>"
        let left = "<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/>"
        let right = "<alignment horizontal=\"right\" vertical=\"center\"/>"

        func xf(font: Int = 0, fill: Int = 0, border: Int = 0, format: Int = 0, _ alignment: String? = nil) -> String {
            var attributes = "numFmtId=\"\(format)\" fontId=\"\(font)\" fillId=\"\(fill)\" borderId=\"\(border)\" xfId=\"0\""
            if font != 0 { attributes += " applyFont=\"1\"" }
            if fill != 0 { attributes += " applyFill=\"1\"" }
            if border != 0 { attributes += " applyBorder=\"1\"" }
            if format != 0 { attributes += " applyNumberFormat=\"1\"" }
            guard let alignment else { return "<xf \(attributes)/>" }
            return "<xf \(attributes) applyAlignment=\"1\">\(alignment)</xf>"
        }
        // Fonts: 0 normal, 1 bold, 2 title, 3 grey note, 4 heading. Fills: 2 header, 3 totals.
        let xfs = [
            xf(), // plain
            xf(font: 2), // title
            xf(font: 1), // bold
            xf(font: 3, left), // note
            xf(font: 4), // heading
            xf(font: 1, fill: 2, border: 1, center), // header
            xf(border: 1, left), // text
            xf(border: 1, format: 164, right), // money
            xf(border: 1, right), // tons
            xf(border: 1, center), // count
            xf(font: 1, fill: 3, border: 1, left), // totalText
            xf(font: 1, fill: 3, border: 1, format: 164, right), // totalMoney
            xf(font: 1, fill: 3, border: 1, right), // totalTons
            xf(font: 1, fill: 3, border: 1, center), // totalCount
            xf(border: 1, left), // pairLabel
            xf(font: 1, fill: 3, border: 1, left), // pairLabelBold
        ]
        return [header,
                "<styleSheet xmlns=\"\(mainNS)\">",
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/></numFmts>",
                "<fonts count=\"5\">",
                "<font><sz val=\"10\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
                "<font><b/><sz val=\"10\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
                "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
                "<font><sz val=\"9\"/><color rgb=\"FF595959\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
                "<font><b/><sz val=\"12\"/><color rgb=\"FF1F5D3A\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
                "</fonts>",
                "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>",
                fill("FFDCE9E0") + fill("FFF2F7F4") + "</fills>",
                "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>\(border)</border></borders>",
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>",
                "<cellXfs count=\"\(xfs.count)\">\(xfs.joined())</cellXfs>",
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>",
                "</styleSheet>"].joined()
    }()
}
