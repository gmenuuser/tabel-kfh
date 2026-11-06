import Foundation

/// A timesheet as a one-sheet Excel workbook («Табель»): inline strings, numbers as numbers, thin borders,
/// frozen № / ФИО columns and table header, landscape print fitted to the page width with the header repeated.
enum XLSXWriter {
    static func make(_ d: TimesheetDocument) -> Data {
        let sheet = Sheet(d)
        var zip = ZipWriter()
        zip.add(path: "[Content_Types].xml", data: Data(contentTypes.utf8))
        zip.add(path: "_rels/.rels", data: Data(rootRels.utf8))
        zip.add(path: "docProps/app.xml", data: Data(app.utf8))
        zip.add(path: "xl/workbook.xml", data: Data(workbook(headerRow: sheet.headerRow).utf8))
        zip.add(path: "xl/_rels/workbook.xml.rels", data: Data(workbookRels.utf8))
        zip.add(path: "xl/styles.xml", data: Data(styles.utf8))
        zip.add(path: "xl/worksheets/sheet1.xml", data: Data(sheet.xml.utf8))
        return zip.finish()
    }

    /// Cell style indexes into `cellXfs` of `styles`.
    private enum Style: Int {
        case plain = 0, title, bold, header, headerWeekend, name, cell, cellWeekend, notEmployed, unfinished,
             count, hours, farmLabel, farmCount, farmHours, farmEmpty
    }

    /// Builds sheet1.xml row by row.
    private struct Sheet {
        let d: TimesheetDocument
        /// Columns: №, ФИО, days, totals.
        let lastColumn: Int
        /// Row number of the table header (frozen and repeated on printed pages).
        private(set) var headerRow = 0
        private var rows: [String] = []
        private var merges: [String] = []
        private var row = 0

        init(_ d: TimesheetDocument) {
            self.d = d
            lastColumn = 2 + d.daysInMonth + TimesheetTotals.columnTitles.count
            build(contacts: [d.address, d.phone, d.extraInfo].filter { !$0.isEmpty })
        }

        var xml: String {
            let last = XLSXWriter.column(lastColumn)
            let widths = ["<col min=\"1\" max=\"1\" width=\"4\" customWidth=\"1\"/>",
                "<col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/>",
                "<col min=\"3\" max=\"\(2 + d.daysInMonth)\" width=\"5\" customWidth=\"1\"/>",
                "<col min=\"\(3 + d.daysInMonth)\" max=\"\(lastColumn)\" width=\"7\" customWidth=\"1\"/>"].joined()
            return [XLSXWriter.header,
                "<worksheet xmlns=\"\(XLSXWriter.mainNS)\" xmlns:r=\"\(XLSXWriter.relNS)\">",
                "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>",
                "<dimension ref=\"A1:\(last)\(row)\"/>",
                "<sheetViews><sheetView workbookViewId=\"0\">",
                "<pane xSplit=\"2\" ySplit=\"\(headerRow)\" topLeftCell=\"C\(headerRow + 1)\" activePane=\"bottomRight\" state=\"frozen\"/>",
                "<selection pane=\"bottomRight\" activeCell=\"C\(headerRow + 1)\" sqref=\"C\(headerRow + 1)\"/>",
                "</sheetView></sheetViews>",
                "<sheetFormatPr defaultRowHeight=\"15\"/>",
                "<cols>\(widths)</cols>",
                "<sheetData>\(rows.joined())</sheetData>",
                "<mergeCells count=\"\(merges.count)\">\(merges.map { "<mergeCell ref=\"\($0)\"/>" }.joined())</mergeCells>",
                "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.4\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>",
                "<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>",
                "<headerFooter><oddFooter>&amp;RСтраница &amp;P из &amp;N</oddFooter></headerFooter>",
                "</worksheet>"].joined()
        }

        private mutating func build(contacts: [String]) {
            let calendar = Calendar.farm
            let weekends = Set((1...max(1, d.daysInMonth)).filter { day in
                calendar.isDateInWeekend(calendar.date(day: day, month: d.month, year: d.year))
            })

            mergedLine("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", style: .title, height: 22)
            mergedLine(d.farmName, style: .bold)
            if !contacts.isEmpty { mergedLine(contacts.joined(separator: " · "), style: .plain) }
            mergedLine("Месяц: \(calendar.monthName(d.month))    Год: \(d.year)", style: .plain)
            addRow([])

            var header = [text("№", .header), text("ФИО", .header)]
            for day in 1...max(1, d.daysInMonth) {
                header.append(text("\(day)", weekends.contains(day) ? .headerWeekend : .header))
            }
            header += TimesheetTotals.columnTitles.map { text($0, .header) }
            addRow(header, height: 18)
            headerRow = row

            for r in d.rows {
                var cells = [number(String(r.number), .cell), text(r.employee.fullName, .name)]
                for (index, c) in r.cells.enumerated() {
                    let style: Style = !c.employed ? .notEmployed
                        : weekends.contains(index + 1) ? .cellWeekend : .cell
                    cells.append(c.text.isEmpty ? empty(style) : text(c.text, style))
                }
                cells += totals(r.totals, count: .count, hours: .hours)
                let nameLines = min(3, max(1, (r.employee.fullName.count + 27) / 28))
                addRow(cells, height: max(Double(nameLines) * 13 + 2, 15))
            }

            var farm = [text("Итого по хозяйству", .farmLabel), empty(.farmLabel)]
            farm += Array(repeating: empty(.farmEmpty), count: d.daysInMonth)
            farm += totals(d.farmTotals, count: .farmCount, hours: .farmHours)
            merges.append("A\(row + 1):B\(row + 1)")
            addRow(farm, height: 18)

            addRow([])
            addRow([text("Обозначения: " + TimesheetDocument.legend + ".", .plain)])
            addRow([])
            signature("Руководитель КФХ: ___________________", d.managerName)
            addRow([])
            signature("Ответственное лицо: ___________________", d.responsiblePerson)
            addRow([])
            addRow([text("Дата формирования: \(d.createdDateText())", .plain)])
        }

        private func totals(_ t: TimesheetTotals, count: Style, hours: Style) -> [String] {
            t.columnValues.map { number(String($0), count) }
        }

        /// The label in column A (it runs over the empty cells to the right), the name in the column of day 12.
        private mutating func signature(_ label: String, _ name: String) {
            var cells = [text(label, .bold)]
            if !name.isEmpty {
                cells += Array(repeating: "", count: 12)
                cells.append(text(name, .plain))
            }
            addRow(cells)
        }

        private mutating func mergedLine(_ value: String, style: Style, height: Double? = nil) {
            merges.append("A\(row + 1):\(XLSXWriter.column(lastColumn))\(row + 1)")
            addRow([text(value, style)], height: height)
        }

        /// `cells` are templates with «@» where the cell reference goes; "" skips a column.
        private mutating func addRow(_ cells: [String], height: Double? = nil) {
            row += 1
            let r = row
            let body = cells.enumerated().compactMap { index, template -> String? in
                template.isEmpty ? nil : template.replacingOccurrences(of: "@REF@", with: "\(XLSXWriter.column(index + 1))\(r)")
            }.joined()
            let ht = height.map { " ht=\"\($0)\" customHeight=\"1\"" } ?? ""
            rows.append("<row r=\"\(r)\"\(ht)>\(body)</row>")
        }

        private func text(_ value: String, _ style: Style) -> String {
            "<c r=\"@REF@\" s=\"\(style.rawValue)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(XLSXWriter.escape(value))</t></is></c>"
        }

        private func number(_ value: String, _ style: Style) -> String {
            "<c r=\"@REF@\" s=\"\(style.rawValue)\"><v>\(value)</v></c>"
        }

        private func empty(_ style: Style) -> String {
            "<c r=\"@REF@\" s=\"\(style.rawValue)\"/>"
        }
    }

    /// 1 → A, 27 → AA.
    static func column(_ index: Int) -> String {
        var n = index, name = ""
        while n > 0 {
            let r = (n - 1) % 26
            name = String(Character(Unicode.Scalar(UInt8(65 + r)))) + name
            n = (n - 1) / 26
        }
        return name
    }

    /// XML text/attribute escaping; characters XML 1.0 forbids are dropped.
    static func escape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for scalar in s.unicodeScalars {
            switch scalar {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            case "\t", "\n", "\r": out.unicodeScalars.append(scalar)
            default:
                if scalar.value >= 0x20 && scalar.value != 0xFFFE && scalar.value != 0xFFFF {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out
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

    private static func workbook(headerRow: Int) -> String {
        [header,
            "<workbook xmlns=\"\(mainNS)\" xmlns:r=\"\(relNS)\">",
            "<sheets><sheet name=\"Табель\" sheetId=\"1\" r:id=\"rId1\"/></sheets>",
            "<definedNames><definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">",
            "'Табель'!$\(headerRow):$\(headerRow)</definedName></definedNames>",
            "</workbook>"].joined()
    }

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
        // numFmt 164 = hours: «7.5», «8.0», «7.75».
        func xf(font: Int = 0, fill: Int = 0, border: Int = 0, format: Int = 0, _ alignment: String? = nil) -> String {
            var attributes = "numFmtId=\"\(format)\" fontId=\"\(font)\" fillId=\"\(fill)\" borderId=\"\(border)\" xfId=\"0\""
            if font != 0 { attributes += " applyFont=\"1\"" }
            if fill != 0 { attributes += " applyFill=\"1\"" }
            if border != 0 { attributes += " applyBorder=\"1\"" }
            if format != 0 { attributes += " applyNumberFormat=\"1\"" }
            guard let alignment else { return "<xf \(attributes)/>" }
            return "<xf \(attributes) applyAlignment=\"1\">\(alignment)</xf>"
        }
        // Fonts: 0 normal, 1 bold, 2 title, 3 orange. Fills: 2 header/total, 3 weekend, 4 not employed.
        let xfs = [
            xf(), // plain
            xf(font: 2), // title
            xf(font: 1), // bold
            xf(font: 1, fill: 2, border: 1, center), // header
            xf(font: 1, fill: 3, border: 1, center), // headerWeekend
            xf(border: 1, left), // name
            xf(border: 1, center), // cell
            xf(fill: 3, border: 1, center), // cellWeekend
            xf(fill: 4, border: 1, center), // notEmployed
            xf(font: 3, border: 1, center), // unfinished
            xf(border: 1, center), // count
            xf(border: 1, format: 164, center), // hours
            xf(font: 1, fill: 2, border: 1, left), // farmLabel
            xf(font: 1, fill: 2, border: 1, center), // farmCount
            xf(font: 1, fill: 2, border: 1, format: 164, center), // farmHours
            xf(fill: 2, border: 1), // farmEmpty
        ]
        return [header,
            "<styleSheet xmlns=\"\(mainNS)\">",
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0#\"/></numFmts>",
            "<fonts count=\"4\">",
            "<font><sz val=\"10\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
            "<font><b/><sz val=\"10\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
            "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
            "<font><b/><sz val=\"10\"/><color rgb=\"FFE8892B\"/><name val=\"Calibri\"/><family val=\"2\"/></font>",
            "</fonts>",
            "<fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>",
            fill("FFDCE9E0") + fill("FFEDEDED") + fill("FFC9C9C9") + "</fills>",
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>\(border)</border></borders>",
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>",
            "<cellXfs count=\"\(xfs.count)\">\(xfs.joined())</cellXfs>",
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>",
            "</styleSheet>"].joined()
    }()
}
