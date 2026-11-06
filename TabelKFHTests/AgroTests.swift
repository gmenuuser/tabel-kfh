import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class AgroTests: XCTestCase {
    private func line(_ r: AgroResult, _ title: String) -> String? { r.lines.first { $0.title == title }?.text }

    private func nb(_ s: String) -> String { s.replacingOccurrences(of: " ", with: "\u{00A0}") }

    // Посев: 10 га × 200 кг/га = 2000 кг.
    func testSeeding() {
        var r = AgroEngine.compute(.seeding, ["area": "10", "rate": "200"])
        XCTAssertTrue(r.isComplete)
        XCTAssertEqual(line(r, "Нужно семян"), nb("2 000 кг"))
        XCTAssertEqual(line(r, "В центнерах"), nb("20 ц"))
        XCTAssertEqual(line(r, "В тоннах"), nb("2 т"))
        r = AgroEngine.compute(.seeding, ["area": "10", "rate": "200", "reserve": "5", "bag": "50"])
        XCTAssertEqual(line(r, "Нужно семян"), nb("2 100 кг"))
        XCTAssertEqual(line(r, "Мешков по 50 кг"), "42")
        r = AgroEngine.compute(.seeding, ["area": "2,5", "rate": "180", "bag": "40"])
        XCTAssertEqual(line(r, "Нужно семян"), nb("450 кг"))
        XCTAssertEqual(line(r, "Мешков по 40 кг"), "12", "11,25 bags → 12")
    }

    // Удобрения: 20 га × 100 кг/га = 2000 кг.
    func testFertilizer() {
        var r = AgroEngine.compute(.fertilizer, ["area": "20", "rate": "100", "package": "50", "price": "35"])
        XCTAssertEqual(line(r, "Нужно удобрения"), nb("2 000 кг"))
        XCTAssertEqual(line(r, "Упаковок по 50 кг"), "40")
        XCTAssertEqual(line(r, "Стоимость"), nb("70 000 ₽"))
        r = AgroEngine.compute(.fertilizer, ["area": "20", "rate": "1", "rateUnit": "c", "price": "30000", "priceUnit": "t"])
        XCTAssertEqual(line(r, "Нужно удобрения"), nb("2 000 кг"))
        XCTAssertEqual(line(r, "Стоимость"), nb("60 000 ₽"))
    }

    // Опрыскивание: 10 га × 2 л/га = 20 л, 200 л/га воды = 2000 л, бак 1000 л = 2 заправки.
    func testSpraying() {
        var r = AgroEngine.compute(.spraying, ["area": "10", "rate": "2", "water": "200", "tank": "1000"])
        XCTAssertEqual(line(r, "Препарата"), nb("20 л"))
        XCTAssertEqual(line(r, "Воды"), nb("2 000 л"))
        XCTAssertEqual(line(r, "Рабочего раствора"), nb("2 020 л"))
        XCTAssertEqual(line(r, "Полных заправок"), "2")
        XCTAssertEqual(line(r, "Остаток на последнюю заправку"), nb("20 л"))
        XCTAssertTrue(r.warnings.contains { $0.contains("этикетки") })
        // A powder is not added to the volume.
        r = AgroEngine.compute(.spraying, ["area": "10", "rate": "0,5", "productUnit": "kg", "water": "200", "tank": "1000"])
        XCTAssertEqual(line(r, "Препарата"), nb("5 кг"))
        XCTAssertEqual(line(r, "Рабочего раствора"), nb("2 000 л"))
        XCTAssertEqual(line(r, "Полных заправок"), "2")
        XCTAssertEqual(line(r, "Остаток на последнюю заправку"), "нет")
        XCTAssertTrue(r.warnings.contains { $0.contains("плотность") })
    }

    // Урожайность: 30 т / 10 га = 3 т/га = 30 ц/га.
    func testYield() {
        var r = AgroEngine.compute(.yield, ["area": "10", "weight": "30"])
        XCTAssertEqual(r.lines.filter { $0.title == "Урожайность" }.map(\.text), [nb("30 ц/га"), nb("3 т/га")])
        r = AgroEngine.compute(.yield, ["area": "10", "weight": "300", "weightUnit": "c"])
        XCTAssertEqual(r.lines.first?.text, nb("30 ц/га"))
        r = AgroEngine.compute(.yield, ["area": "4", "weight": "9500", "weightUnit": "kg"])
        XCTAssertEqual(r.lines.first?.text, nb("23,75 ц/га"))
    }

    // Влажность: 10000 кг, 20 % → 14 %: 9302,33 кг, потеря 697,67 кг.
    func testMoisture() {
        let r = AgroEngine.compute(.moisture, ["weight": "10000", "actual": "20", "target": "14"])
        XCTAssertEqual(line(r, "Вес после сушки"), nb("9 302,33 кг"))
        XCTAssertEqual(line(r, "Потеря массы"), nb("697,67 кг"))
        XCTAssertEqual(line(r, "Исходный вес"), nb("10 000 кг"))
        XCTAssertTrue(r.warnings.first?.contains("теоретической массы") == true)
        XCTAssertEqual(AgroEngine.compute(.moisture, ["weight": "100", "actual": "14", "target": "20"]).errors,
                       ["Целевая влажность должна быть меньше фактической"])
        XCTAssertFalse(AgroEngine.compute(.moisture, ["weight": "100", "actual": "120", "target": "14"]).isComplete)
    }

    // Топливо: 10 га × 15 л/га = 150 л.
    func testFuel() {
        var r = AgroEngine.compute(.fuel, ["area": "10", "rate": "15", "price": "68,5"])
        XCTAssertEqual(line(r, "Нужно топлива"), nb("150 л"))
        XCTAssertEqual(line(r, "Стоимость топлива"), nb("10 275 ₽"))
        r = AgroEngine.compute(.fuel, ["mode": "stock", "fuel": "400", "rate": "15"])
        XCTAssertEqual(line(r, "Можно обработать"), nb("26,67 га"))
    }

    // Площадь: 500 м × 200 м = 10 га, and plots add up.
    func testArea() {
        var r = AgroEngine.compute(.area, ["plot.1.length": "500", "plot.1.width": "200"])
        XCTAssertEqual(line(r, "Площадь поля"), nb("10 га"))
        XCTAssertEqual(line(r, "Всего"), nb("100 000 м²"))
        r = AgroEngine.compute(.area, ["plots": "3", "plot.1.length": "500", "plot.1.width": "200",
                                       "plot.2.area": "2500", "plot.3.length": "100", "plot.3.width": "50,5"])
        XCTAssertEqual(line(r, "Площадь поля"), nb("10,755 га"))
        XCTAssertTrue(r.warnings.first?.contains("прямоугольника") == true)
        XCTAssertFalse(AgroEngine.compute(.area, ["plot.1.length": "500"]).isComplete)
        XCTAssertFalse(AgroEngine.compute(.area, [:]).isComplete)
    }

    // Перевозка: 30 т, машина 10 т = 3 рейса.
    func testTransport() {
        var r = AgroEngine.compute(.transport, ["grain": "30", "truck": "10", "bag": "50"])
        XCTAssertEqual(line(r, "Полных рейсов"), "3")
        XCTAssertEqual(line(r, "Остаток на последний рейс"), "нет")
        XCTAssertEqual(line(r, "Полных мешков по 50 кг"), "600")
        r = AgroEngine.compute(.transport, ["grain": "32,5", "truck": "10000", "truckUnit": "kg", "bag": "40"])
        XCTAssertEqual(line(r, "Полных рейсов"), "3")
        XCTAssertEqual(line(r, "Остаток на последний рейс"), nb("2 500 кг"))
        XCTAssertEqual(line(r, "Всего рейсов"), "4")
        XCTAssertEqual(line(r, "Полных мешков по 40 кг"), "812")
        XCTAssertEqual(line(r, "Остаток зерна"), nb("20 кг"))
    }

    func testWrongValues() {
        XCTAssertEqual(AgroEngine.compute(.seeding, ["area": "10"]).missing, ["Норма высева"])
        XCTAssertEqual(AgroEngine.compute(.seeding, ["area": "0", "rate": "200"]).errors, ["«Площадь поля» должно быть больше нуля"])
        XCTAssertEqual(AgroEngine.compute(.seeding, ["area": "-5", "rate": "200"]).errors.count, 1)
        XCTAssertEqual(AgroEngine.compute(.seeding, ["area": "десять", "rate": "200"]).errors,
                       ["«Площадь поля»: введите число, например 12,5"])
        XCTAssertTrue(AgroEngine.compute(.seeding, ["area": "10", "rate": "200", "reserve": "0"]).isComplete, "0 % запаса")
        XCTAssertEqual(AgroNumber.parse("1 200,5"), 1200.5)
        XCTAssertNil(AgroNumber.parse("1,2,3"))
        XCTAssertEqual(AgroNumber.format(9302.325581), nb("9 302,33"))
        XCTAssertEqual(AgroNumber.format(2.0051), "2,01")
        XCTAssertEqual(AgroNumber.format(2.5, decimals: 0), "3", "half away from zero")
        XCTAssertEqual(AgroNumber.format(0.004), "0")
    }

    func testHistoryAndBackup() throws {
        let context = ModelContext(try makeModelContainer(inMemory: true))
        var clock = Calendar.farm.date(day: 6, month: 11, year: 2026).addingTimeInterval(9 * 3600)
        let service = AgroService(context: context, now: { clock })
        let seeding = try service.save(.seeding, inputs: ["area": "10", "rate": "200", "reserve": ""], title: "Поле у реки")
        XCTAssertEqual(seeding.inputs, ["area": "10", "rate": "200"], "empty fields are not stored")
        XCTAssertEqual(seeding.lines.first?.text, nb("2 000 кг"))
        clock = clock.addingTimeInterval(86_400)
        try service.save(.fuel, inputs: ["area": "10", "rate": "15"], title: "")
        XCTAssertThrowsError(try service.save(.fuel, inputs: ["area": "10"], title: ""), "an incomplete one is not saved")

        // Correct the saved one: the result follows.
        try service.save(.seeding, inputs: ["area": "20", "rate": "200"], title: "Поле у реки", over: seeding)
        XCTAssertEqual(seeding.lines.first?.text, nb("4 000 кг"))
        XCTAssertEqual(try service.history().count, 2)
        XCTAssertEqual(try service.history(kind: .fuel).count, 1)
        XCTAssertEqual(try service.history(search: "реки").count, 1)
        let day = Calendar.farm.date(day: 6, month: 11, year: 2026)
        XCTAssertEqual(try service.history(from: day, to: day.addingTimeInterval(86_400)).map(\.kind), [.seeding])

        let doc = AgroReportLayout.history(try service.history(), farm: .default, createdAt: clock)
        XCTAssertEqual(doc.fileName, "Агрорасчёты_2026-11-07")
        let zip = try MiniZipReader(CoalReportXLSXWriter.make(doc))
        let sheet = String(decoding: try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml")), as: UTF8.self)
        XCTAssertTrue(sheet.contains("Поле у реки"))
        XCTAssertFalse(CoalReportPDFRenderer.render(doc).isEmpty)

        let backup = BackupService(context: context, now: { clock }, adoptTimeZone: { _ in })
        let data = try backup.makeBackup()
        try service.delete(seeding)
        XCTAssertEqual(try service.history().count, 1)
        try backup.restore(data)
        let restored = try service.history()
        XCTAssertEqual(restored.count, 2)
        XCTAssertEqual(restored.first { $0.kind == .seeding }?.title, "Поле у реки")
        XCTAssertEqual(restored.first { $0.kind == .seeding }?.inputs, ["area": "20", "rate": "200"])
    }
}
