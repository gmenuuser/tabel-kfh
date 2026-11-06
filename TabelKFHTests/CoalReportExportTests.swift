import XCTest
import PDFKit
import SwiftData
@testable import TabelKFH

/// Values of each `<row>` of a worksheet (text of `<t>` or `<v>`), for the coal report.
private final class CoalSheetReader: NSObject, XMLParserDelegate {
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

@MainActor
final class CoalReportExportTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(8 * 3600)
        let farm = FarmSettingsStore.current(in: context)
        farm.managerName = "Зубахин Иван Петрович"
    }

    private var now: () -> Date { { [unowned self] in self.clock } }
    private var days: CoalDayService { CoalDayService(context: context, calendar: calendar, now: now) }

    /// The spec's day: Мунгалов 3 deliveries, Кайдолов 2, one own-vehicle sale with a hand-corrected price,
    /// a payment of 2000 ₽ to Мунгалов; `extra` more own-vehicle sales for a long table.
    private func fillDay(extra: Int = 0) throws {
        let settings = CoalSettingsService(context: context, now: now)
        let drivers = DriverService(context: context, now: now)
        let m = try drivers.add(DriverDraft(lastName: "Мунгалов", firstName: "Сергей", middleName: "Иванович"))
        let k = try drivers.add(DriverDraft(lastName: "Кайдолов", firstName: "Пётр"))
        let sales = CoalSaleService(context: context, calendar: calendar, now: now)
        let plan: [(String, String, Driver?)] = [
            ("2", "Новоцурухайтуй", m), ("1,5", "Приаргунск", m), ("0,5", "Новоцурухайтуй", m),
            ("1", "Приаргунск", k), ("2,5", "Новоцурухайтуй", k), ("1", "Приаргунск", nil),
        ] + Array(repeating: ("1", "Новоцурухайтуй", nil), count: extra)
        for (index, (tons, place, driver)) in plan.enumerated() {
            var d = sales.newDraft()
            d.tonsText = tons
            d.choose(try XCTUnwrap(settings.activeLocations().first { $0.name == place }))
            if let driver { d.choose(driver) } else { d.vehicle = .own }
            if index == 5 {
                d.startManual()
                d.manualCoalText = "6000"
                d.comment = "постоянный покупатель"
            }
            try sales.save(d)
            clock = clock.addingTimeInterval(20 * 60)
        }
        try DriverPaymentService(context: context, now: now).pay(m, amount: 200_000, comment: "наличными")
    }

    private func pdfText(_ data: Data) throws -> (PDFDocument, String) {
        let pdf = try XCTUnwrap(PDFDocument(data: data))
        let text = (0..<pdf.pageCount).compactMap { pdf.page(at: $0)?.string }.joined(separator: "\n")
        return (pdf, text)
    }

    private func sheetRows(_ data: Data) throws -> [[String]] {
        let zip = try MiniZipReader(data)
        let xml = try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml"))
        let reader = CoalSheetReader()
        let parser = XMLParser(data: xml)
        parser.delegate = reader
        XCTAssertTrue(parser.parse(), String(describing: parser.parserError))
        return reader.rows
    }

    func testDayDocumentHasEveryPartOfTheReport() throws {
        try fillDay()
        try days.close(clock)
        let doc = CoalReportLayout.document(try days.dayReport(clock))
        XCTAssertEqual(doc.fileName, "Уголь_день_2026-11-06")
        XCTAssertEqual(doc.blocks.first, .title("ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ"))
        XCTAssertTrue(doc.blocks.contains(.boldText("КФХ Зубахин")))
        XCTAssertTrue(doc.blocks.contains(.text("Дата: 06.11.2026 (пятница)")))
        XCTAssertTrue(doc.blocks.contains(.signature(label: "Руководитель КФХ: ___________________",
                                                     name: "Зубахин Иван Петрович")))
        XCTAssertTrue(doc.blocks.contains(.note(CoalReportLayout.remainderNote)))
        let pairs = doc.blocks.compactMap { block -> [ReportPair]? in
            if case .pairs(let p) = block { return p } else { return nil }
        }.flatMap { $0 }
        func value(_ prefix: String) -> String? { pairs.first { $0.label.hasPrefix(prefix) }?.value.number }
        XCTAssertEqual(value("А."), "61350")
        XCTAssertEqual(value("Б."), "54750")
        XCTAssertEqual(value("В."), "6600")
        XCTAssertEqual(value("Г."), "5400")
        XCTAssertEqual(value("Д."), "1200")
        XCTAssertEqual(value("Е."), "55950")
        XCTAssertFalse(pairs.contains { $0.label.lowercased().contains("прибыль") }, "Е is not called a profit")
    }

    func testDayPDFIsA4PortraitInRussian() throws {
        try fillDay()
        try days.close(clock)
        let (pdf, text) = try pdfText(CoalReportPDFRenderer.render(CoalReportLayout.document(try days.dayReport(clock))))
        let bounds = try XCTUnwrap(pdf.page(at: 0)).bounds(for: .mediaBox)
        XCTAssertEqual(bounds.width, 595, accuracy: 1)
        XCTAssertEqual(bounds.height, 842, accuracy: 1)
        for expected in ["ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ", "КФХ Зубахин", "Новоцурухайтуй", "Приаргунск", "Мунгалов",
                         "Кайдолов Пётр", "Взаиморасчёты с водителями", "ИТОГО К ВЫПЛАТЕ", "Остаток после выплат водителям",
                         "Руководитель КФХ", "Зубахин Иван Петрович", "Страница 1 из"] {
            XCTAssertTrue(text.contains(expected), "PDF has «\(expected)»")
        }
    }

    func testLongTableContinuesOnTheNextPageWithItsHeader() throws {
        try fillDay(extra: 70)
        let (pdf, text) = try pdfText(CoalReportPDFRenderer.render(CoalReportLayout.document(try days.dayReport(clock))))
        XCTAssertGreaterThan(pdf.pageCount, 1)
        let second = try XCTUnwrap(pdf.page(at: 1)?.string)
        XCTAssertTrue(second.contains("Населённый пункт"), "the header is repeated")
        XCTAssertTrue(second.contains("КФХ Зубахин · Отчёт за день 06.11.2026"))
        XCTAssertTrue(text.contains("Страница \(pdf.pageCount) из \(pdf.pageCount)"))
    }

    func testDayXLSXHasNumbersAndTotals() throws {
        try fillDay()
        let rows = try sheetRows(CoalReportXLSXWriter.make(CoalReportLayout.document(try days.dayReport(clock))))
        XCTAssertEqual(rows.first, ["ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ"])
        let header = try XCTUnwrap(rows.first { $0.first == "№" })
        XCTAssertEqual(header, ["№", "Время", "Тонн", "Населённый пункт", "Машина", "Водитель", "Уголь, ₽",
                                "Доставка, ₽", "Итого, ₽"])
        let first = try XCTUnwrap(rows.first { $0.first == "1" })
        XCTAssertEqual(first, ["1", "08:00", "2", "Новоцурухайтуй", "Наёмная", "Мунгалов Сергей Иванович",
                               "13000", "1000", "14000"])
        let own = try XCTUnwrap(rows.first { $0.first == "6" })
        XCTAssertEqual(own[4], "Своя")
        XCTAssertEqual(own[5], "—")
        XCTAssertEqual(own[8], "7200", "the corrected amount, as a number")
        XCTAssertTrue(rows.contains { $0.first == "Итого" && $0.last == "61350" })
        XCTAssertTrue(rows.contains { $0.first?.hasPrefix("Е. Остаток после выплат водителям") == true && $0.last == "55950" })
        let m = try XCTUnwrap(rows.first { $0.first == "Мунгалов Сергей Иванович" && $0.count == 6 })
        XCTAssertEqual(m, ["Мунгалов Сергей Иванович", "3", "0", "3200", "2000", "1200"])
        XCTAssertTrue(rows.contains { $0.first == "ИТОГО К ВЫПЛАТЕ" && $0.last == "3400" })
    }

    func testMonthReportHasVillagesAndDrivers() throws {
        try fillDay()
        clock = calendar.date(day: 20, month: 11, year: 2026).addingTimeInterval(10 * 3600)
        let doc = CoalReportLayout.document(try days.monthReport(month: 11, year: 2026))
        XCTAssertEqual(doc.fileName, "Уголь_месяц_2026-11")
        XCTAssertEqual(doc.blocks.first, .title("ОТЧЁТ ЗА МЕСЯЦ"))
        XCTAssertTrue(doc.blocks.contains(.text("Месяц: Ноябрь 2026")))
        XCTAssertTrue(doc.blocks.contains(.heading("Итоги по населённым пунктам")))
        XCTAssertTrue(doc.blocks.contains(.heading("Выплаты водителям")))
        let rows = try sheetRows(CoalReportXLSXWriter.make(doc))
        XCTAssertTrue(rows.contains { $0 == ["Новоцурухайтуй", "3", "5", "3000", "35500"] })
        XCTAssertTrue(rows.contains { $0 == ["Приаргунск", "3", "3,5".replacingOccurrences(of: ",", with: "."), "3600", "25850"] })
        let (_, text) = try pdfText(CoalReportPDFRenderer.render(doc))
        XCTAssertTrue(text.contains("ОТЧЁТ ЗА МЕСЯЦ"))
        XCTAssertTrue(text.contains("Итоги по населённым пунктам"))
    }

    func testEmptyDayStillMakesAReport() throws {
        let doc = CoalReportLayout.document(try days.dayReport(clock))
        XCTAssertTrue(doc.blocks.contains(.text("Продаж нет.")))
        XCTAssertTrue(doc.blocks.contains(.text("Начислений наёмным водителям нет.")))
        XCTAssertFalse(CoalReportPDFRenderer.render(doc).isEmpty)
        XCTAssertFalse(try sheetRows(CoalReportXLSXWriter.make(doc)).isEmpty)
    }

    /// coal-day.pdf, coal-month.pdf, coal-day.xlsx and PNGs of their pages into $SAMPLES_DIR/ReviewSamples.
    func testWriteCoalSamplesForReview() throws {
        try fillDay()
        try days.close(clock)
        let root = ProcessInfo.processInfo.environment["SAMPLES_DIR"].map { URL(fileURLWithPath: $0) }
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let folder = root.appendingPathComponent("ReviewSamples", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let day = CoalReportLayout.document(try days.dayReport(clock))
        let month = CoalReportLayout.document(try days.monthReport(month: 11, year: 2026))
        try CoalReportXLSXWriter.make(day).write(to: folder.appendingPathComponent("coal-day.xlsx"))
        try CoalReportXLSXWriter.make(month).write(to: folder.appendingPathComponent("coal-month.xlsx"))
        for (name, doc) in [("coal-day", day), ("coal-month", month)] {
            let data = CoalReportPDFRenderer.render(doc)
            try data.write(to: folder.appendingPathComponent("\(name).pdf"))
            let pdf = try XCTUnwrap(PDFDocument(data: data))
            for index in 0..<pdf.pageCount {
                let page = try XCTUnwrap(pdf.page(at: index))
                let size = CGSize(width: 1190, height: 1684)
                let thumbnail = page.thumbnail(of: size, for: .mediaBox)
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                let png = UIGraphicsImageRenderer(size: size, format: format).pngData { context in
                    UIColor.white.setFill()
                    context.fill(CGRect(origin: .zero, size: size))
                    thumbnail.draw(in: CGRect(origin: .zero, size: size))
                }
                try png.write(to: folder.appendingPathComponent("\(name)-p\(index + 1).png"))
            }
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("coal-day-p1.png").path))
    }
}
