import XCTest
@testable import TabelKFH

/// Collects the rows of a worksheet: for each `<row>`, the text of each `<c>` (its `<t>` or `<v>`).
private final class SheetReader: NSObject, XMLParserDelegate {
    var rows: [[String]] = []
    private var cell: String?
    private var inValue = false

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        switch name {
        case "row": rows.append([])
        case "c": cell = ""
        case "t", "v": inValue = true
        default: break
        }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        switch name {
        case "c":
            if let cell, !rows.isEmpty { rows[rows.count - 1].append(cell) }
            cell = nil
        case "t", "v": inValue = false
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inValue { cell? += string }
    }
}

final class XLSXWriterTests: XCTestCase {
    private let calendar = Calendar.farm
    private static let parts = ["[Content_Types].xml", "_rels/.rels", "docProps/app.xml", "xl/workbook.xml",
                                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"]

    private func december() -> TimesheetDocument {
        let e = EmployeeSnapshot(id: UUID(), fullName: "Зубахин Иван Петрович", position: "Тракторист",
                                 hireDate: calendar.date(day: 1, month: 1, year: 2020), dismissalDate: nil)
        let records = [
            RecordSnapshot(employeeId: e.id, day: calendar.date(day: 1, month: 12, year: 2026), status: .present,
                           minutes: 450, unfinished: false),
            RecordSnapshot(employeeId: e.id, day: calendar.date(day: 2, month: 12, year: 2026), status: .sick,
                           minutes: nil, unfinished: false),
        ]
        let settings = FarmInfo(farmName: "ООО \"Тест\" & Ко", managerName: "Зубахин И. П.",
                                responsiblePerson: "Иванова А. А.", address: "х. Зубахин <центр>",
                                phone: "+7 900 000-00-00", extraInfo: "")
        return TimesheetBuilder.build(month: 12, year: 2026, employees: [e], records: records, settings: settings,
                                      format: .codesAndHours, version: 2,
                                      createdAt: calendar.date(day: 6, month: 11, year: 2026))
    }

    private func sheet() throws -> String {
        let zip = try MiniZipReader(XLSXWriter.make(december()))
        let data = try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml"))
        return try XCTUnwrap(String(data: data, encoding: .utf8))
    }

    private func rows(_ xml: String) throws -> [[String]] {
        let reader = SheetReader()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.delegate = reader
        XCTAssertTrue(parser.parse(), String(describing: parser.parserError))
        return reader.rows
    }

    // Spec 15.
    func testPackageHasAllPartsAndEachIsWellFormedXML() throws {
        let zip = try MiniZipReader(XLSXWriter.make(december()))
        XCTAssertEqual(Set(zip.entries.map(\.name)), Set(Self.parts))
        for part in Self.parts {
            let parser = XMLParser(data: try XCTUnwrap(zip.data(part), part))
            XCTAssertTrue(parser.parse(), "\(part): \(String(describing: parser.parserError))")
        }
        let workbook = String(decoding: try XCTUnwrap(zip.data("xl/workbook.xml")), as: UTF8.self)
        XCTAssertTrue(workbook.contains("name=\"Табель\""))
    }

    func testSheetHasTitleEscapedTextDayHeadersAndDayCounts() throws {
        let xml = try sheet()
        XCTAssertTrue(xml.contains("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ"))
        XCTAssertTrue(xml.contains("ООО &quot;Тест&quot; &amp; Ко"))
        XCTAssertFalse(xml.contains("\"Тест\" & Ко"))
        XCTAssertTrue(xml.contains("&lt;центр&gt;"))
        XCTAssertFalse(xml.contains("<v>7.5</v>"), "no hours in the timesheet")
        XCTAssertTrue(xml.contains("t=\"inlineStr\""))

        let rows = try rows(xml)
        let all = rows.flatMap { $0 }
        XCTAssertTrue(all.contains("ООО \"Тест\" & Ко"))
        let header = try XCTUnwrap(rows.first { $0.contains("ФИО") })
        XCTAssertEqual(header.count, 2 + 31 + 7)
        XCTAssertEqual(Array(header[2..<33]), (1...31).map(String.init), "31 day header cells for December")
        XCTAssertEqual(Array(header[33...]), ["Я", "В", "Б", "О", "ОТ", "ПР", "К"])

        let row = try XCTUnwrap(rows.first { $0.contains("Зубахин Иван Петрович") })
        XCTAssertEqual(row[0], "1")
        XCTAssertEqual(row[2], "Я")
        XCTAssertEqual(row[3], "Б")
        XCTAssertEqual(Array(row[(row.count - 7)...]), ["1", "0", "1", "0", "0", "0", "0"], "days, no hours")

        XCTAssertTrue(all.contains("Руководитель КФХ: ___________________"))
        XCTAssertTrue(all.contains("Ответственное лицо: ___________________"))
        XCTAssertTrue(all.contains("Дата формирования: 06.11.2026"))
        XCTAssertTrue(all.contains { $0.hasPrefix("Итого по хозяйству") })
    }

    func testSheetFreezesNamesAndHeaderAndSetsColumnWidths() throws {
        let xml = try sheet()
        XCTAssertTrue(xml.contains("state=\"frozen\""))
        XCTAssertTrue(xml.contains("xSplit=\"2\""))
        XCTAssertTrue(xml.contains("<col min=\"1\" max=\"1\" width=\"4\" customWidth=\"1\"/>"))
        XCTAssertTrue(xml.contains("<col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/>"))
        XCTAssertTrue(xml.contains("<mergeCell ref=\"A1:"))
    }
}
