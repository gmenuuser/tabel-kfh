import XCTest
import SwiftData
@testable import TabelKFH

@MainActor
final class LivestockTests: XCTestCase {
    private let calendar = Calendar.farm
    private var context: ModelContext!
    private var clock: Date!
    private var service: LivestockService!

    override func setUpWithError() throws {
        context = ModelContext(try makeModelContainer(inMemory: true))
        clock = calendar.date(day: 6, month: 11, year: 2026).addingTimeInterval(10 * 3600)
        service = LivestockService(context: context, calendar: calendar, now: { [unowned self] in self.clock })
    }

    private func category(_ species: Species, _ name: String) throws -> LivestockCategory {
        try XCTUnwrap(try service.categories(species).first { $0.name == name }, name)
    }

    private func heads(_ c: LivestockCategory) throws -> Int { try service.balances()[c.id] ?? 0 }

    /// Saves an operation at `clock`, then moves the clock on a minute.
    @discardableResult
    private func record(_ kind: LivestockOperationKind, _ c: LivestockCategory, _ n: Int, to target: LivestockCategory? = nil,
                        configure: (inout LivestockDraft) -> Void = { _ in }) throws -> LivestockOperation {
        var d = LivestockDraft(kind: kind, date: clock, categoryId: c.id)
        d.headsText = "\(n)"
        d.targetCategoryId = target?.id
        configure(&d)
        let o = try service.save(d)
        clock = clock.addingTimeInterval(60)
        return o
    }

    func testDefaultCategoriesAreMadeOnce() throws {
        XCTAssertEqual(try service.categories(.cattle).map(\.name), ["Коровы", "Быки", "Телята", "Молодняк", "Другие"])
        XCTAssertEqual(try service.categories(.goats).map(\.name), ["Козы", "Козлы", "Козлята", "Молодняк", "Другие"])
        XCTAssertEqual(try service.categories(.pigs).map(\.name), ["Свиноматки", "Хряки", "Поросята", "Откормочные", "Другие"])
        service.ensureDefaults()
        XCTAssertEqual(try service.categories().count, 15)
        let own = try service.addCategory(" Нетели ", species: .cattle)
        XCTAssertEqual(own.name, "Нетели")
        XCTAssertThrowsError(try service.addCategory("нетели", species: .cattle))
        XCTAssertEqual(try service.categories(.cattle).last?.name, "Нетели")
        try service.deleteCategory(own)
        XCTAssertThrowsError(try service.deleteCategory(try category(.cattle, "Коровы")), "default categories stay")
    }

    /// Own categories get random ids (as the site), so deleting one and adding it again makes a new record instead
    /// of reviving the server's deleted one; default categories keep their stable ids.
    func testOwnCategoryGetsANewIdWhenAddedAgain() throws {
        let first = try service.addCategory("Нетели", species: .cattle)
        let firstId = first.id
        XCTAssertNotEqual(firstId, StableId.livestockCategory(species: "cattle", name: "Нетели"))
        try service.deleteCategory(first)
        let again = try service.addCategory("Нетели", species: .cattle)
        XCTAssertNotEqual(again.id, firstId)
        XCTAssertEqual(try service.categories(.cattle).filter { $0.name == "Нетели" }.count, 1)
        XCTAssertThrowsError(try service.addCategory(" нетели", species: .cattle), "the name still finds the duplicate")
        XCTAssertEqual(try category(.cattle, "Коровы").id, StableId.livestockCategory(species: "cattle", name: "Коровы"))
    }

    // Сценарий 1.
    func testScenario1() throws {
        let cows = try category(.cattle, "Коровы")
        try record(.initial, cows, 20)
        try record(.slaughter, cows, 2)
        XCTAssertEqual(try heads(cows), 18)
        XCTAssertEqual(try service.total(.cattle), 18)
    }

    // Сценарий 2.
    func testScenario2() throws {
        let goats = try category(.goats, "Козы"), kids = try category(.goats, "Козлята")
        try record(.initial, goats, 15)
        try record(.birth, kids, 3)
        try record(.slaughter, goats, 2)
        XCTAssertEqual(try heads(goats), 13)
        XCTAssertEqual(try heads(kids), 3)
        XCTAssertEqual(try service.total(.goats), 16)
    }

    // Сценарий 3.
    func testScenario3() throws {
        let pigs = try category(.pigs, "Откормочные")
        try record(.initial, pigs, 30)
        try record(.sale, pigs, 5)
        try record(.slaughter, pigs, 3)
        XCTAssertEqual(try service.total(.pigs), 22)
        // A sale is not a slaughter.
        let (start, end) = service.period(.day, containing: clock)
        XCTAssertEqual(try service.slaughterSummary(from: start, to: end).map(\.heads), [3])
    }

    // Сценарий 4.
    func testScenario4SlaughterAboveTheHerdIsRefused() throws {
        let cows = try category(.cattle, "Коровы")
        try record(.initial, cows, 10)
        XCTAssertThrowsError(try record(.slaughter, cows, 11)) { error in
            guard case .notEnough(let name, _, let available)? = error as? LivestockError else {
                return XCTFail("\(error)")
            }
            XCTAssertEqual(name, "Коровы")
            XCTAssertEqual(available, 10)
        }
        XCTAssertEqual(try heads(cows), 10)
        XCTAssertEqual(try service.operations().count, 1, "nothing was saved")
    }

    // Сценарий 5.
    func testScenario5CorrectingTheHeadsRecounts() throws {
        let bulls = try category(.cattle, "Быки")
        try record(.initial, bulls, 10)
        let o = try record(.slaughter, bulls, 2)
        XCTAssertEqual(try heads(bulls), 8)
        var d = try service.draft(for: o)
        d.headsText = "1"
        try service.update(o, with: d)
        XCTAssertEqual(try heads(bulls), 9, "only one head is written off")
        try service.update(o, with: d)
        XCTAssertEqual(try heads(bulls), 9, "saving the correction again does not write off twice")
        XCTAssertEqual(try service.operations().count, 2)
    }

    // Сценарий 6.
    func testScenario6DeletingASlaughterRestoresTheHerd() throws {
        let sows = try category(.pigs, "Свиноматки")
        try record(.initial, sows, 7)
        let o = try record(.slaughter, sows, 2) { $0.meatWeightText = "150" }
        XCTAssertEqual(try heads(sows), 5)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SlaughterRecord>()), 1)
        try service.delete(o)
        XCTAssertEqual(try heads(sows), 7)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<SlaughterRecord>()), 0, "the details go with it")
    }

    func testSecondSaveOfTheSameFormIsRefused() throws {
        let cows = try category(.cattle, "Коровы")
        try record(.initial, cows, 5)
        var d = LivestockDraft(kind: .slaughter, date: clock, categoryId: cows.id)
        d.headsText = "1"
        try service.save(d)
        XCTAssertThrowsError(try service.save(d)) { XCTAssertEqual($0 as? LivestockError, .alreadySaved) }
        XCTAssertEqual(try heads(cows), 4)
    }

    func testTransferKeepsTheTotal() throws {
        let calves = try category(.cattle, "Телята"), young = try category(.cattle, "Молодняк")
        try record(.birth, calves, 6)
        try record(.transfer, calves, 4, to: young)
        XCTAssertEqual(try heads(calves), 2)
        XCTAssertEqual(try heads(young), 4)
        XCTAssertEqual(try service.total(.cattle), 6)
        XCTAssertThrowsError(try record(.transfer, calves, 3, to: young))
        XCTAssertThrowsError(try record(.transfer, calves, 1, to: calves), "same category")
    }

    /// A correction of the past may not leave a later decrease without animals.
    func testHistoryNeverGoesBelowZero() throws {
        let cows = try category(.cattle, "Коровы")
        let purchase = try record(.purchase, cows, 5)
        try record(.slaughter, cows, 4)
        XCTAssertThrowsError(try service.delete(purchase), "the slaughter relies on it")
        var d = try service.draft(for: purchase)
        d.headsText = "3"
        XCTAssertThrowsError(try service.update(purchase, with: d))
        XCTAssertEqual(try heads(cows), 1)
        // A slaughter dated before the animals came is refused too.
        var early = LivestockDraft(kind: .slaughter, date: clock.addingTimeInterval(-86_400), categoryId: cows.id)
        early.headsText = "1"
        XCTAssertThrowsError(try service.save(early))
    }

    func testSlaughterWeightsAreOptionalAndSummed() throws {
        let cows = try category(.cattle, "Коровы"), bulls = try category(.cattle, "Быки")
        try record(.initial, cows, 10)
        try record(.initial, bulls, 4)
        try record(.slaughter, cows, 2) {
            $0.liveWeightText = "900,5"
            $0.carcassWeightText = "520"
            $0.meatWeightText = "410,25"
            $0.reason = .forced
        }
        try record(.slaughter, cows, 1)
        try record(.slaughter, bulls, 1) { $0.meatWeightText = "300" }
        let (start, end) = service.period(.month, containing: clock)
        let rows = try service.slaughterSummary(from: start, to: end)
        XCTAssertEqual(rows.map(\.name), ["Коровы", "Быки"])
        XCTAssertEqual(rows[0].heads, 3)
        XCTAssertEqual(rows[0].operations, 2)
        XCTAssertEqual(rows[0].liveGrams, 900_500)
        XCTAssertEqual(rows[0].carcassGrams, 520_000)
        XCTAssertEqual(rows[0].meatGrams, 410_250)
        XCTAssertEqual(rows[1].meatGrams, 300_000)
        var bad = LivestockDraft(kind: .slaughter, date: clock, categoryId: cows.id)
        bad.headsText = "1"
        bad.liveWeightText = "много"
        XCTAssertFalse(bad.isValid)
        bad.headsText = "1,5"
        XCTAssertTrue(bad.validationErrors.contains("Количество голов — целое число"))
    }

    func testMovementReport() throws {
        let goats = try category(.goats, "Козы"), kids = try category(.goats, "Козлята")
        try record(.initial, goats, 15)
        clock = calendar.date(day: 3, month: 12, year: 2026).addingTimeInterval(9 * 3600)
        try record(.birth, kids, 3)
        try record(.purchase, goats, 2)
        try record(.slaughter, goats, 2)
        try record(.death, kids, 1)
        try record(.transfer, kids, 1, to: goats)
        try record(.other, goats, 1)
        try record(.sale, goats, 1)
        let (start, end) = service.period(.month, containing: clock)
        let rows = try service.movement(from: start, to: end)
        let g = try XCTUnwrap(rows.first { $0.name == "Козы" })
        XCTAssertEqual(g.start, 15)
        XCTAssertEqual(g.purchase, 2)
        XCTAssertEqual(g.transferIn, 1)
        XCTAssertEqual(g.slaughter, 2)
        XCTAssertEqual(g.sale, 1)
        XCTAssertEqual(g.other, 1)
        XCTAssertEqual(g.end, 14)
        let k = try XCTUnwrap(rows.first { $0.name == "Козлята" })
        XCTAssertEqual(k.birth, 3)
        XCTAssertEqual(k.death, 1)
        XCTAssertEqual(k.transferOut, 1)
        XCTAssertEqual(k.end, 1)
        XCTAssertEqual(try service.total(.goats), 15)

        let farm = FarmSettingsStore.current(in: context).info
        let doc = LivestockReportLayout.movement(rows, period: .month, start: start, farm: farm, createdAt: clock)
        XCTAssertEqual(doc.fileName, "Движение_поголовья_2026-12")
        XCTAssertTrue(doc.blocks.contains(.title("ДВИЖЕНИЕ ПОГОЛОВЬЯ")))
        XCTAssertTrue(doc.blocks.contains(.text("Период: Декабрь 2026")))
        XCTAssertFalse(CoalReportPDFRenderer.render(doc).isEmpty)
        let zip = try MiniZipReader(CoalReportXLSXWriter.make(doc))
        let sheet = String(decoding: try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml")), as: UTF8.self)
        XCTAssertTrue(sheet.contains("Остаток на конец периода"))
    }

    func testHerdAndSlaughterDocuments() throws {
        let cows = try category(.cattle, "Коровы")
        try record(.initial, cows, 20)
        try record(.slaughter, cows, 2) { $0.meatWeightText = "300" }
        let farm = FarmSettingsStore.current(in: context).info
        let lines = try service.categories().map {
            LivestockReportLayout.HerdLine(species: $0.species, name: $0.name, heads: try service.balances()[$0.id] ?? 0)
        }
        let herd = LivestockReportLayout.herd(lines, on: clock, farm: farm, createdAt: clock)
        XCTAssertEqual(herd.fileName, "Поголовье_2026-11-06")
        XCTAssertTrue(herd.blocks.contains(.pairs([
            ReportPair(label: "КРС", value: .count(18), bold: false), ReportPair(label: "Козы", value: .count(0), bold: false),
            ReportPair(label: "Свиньи", value: .count(0), bold: false),
            ReportPair(label: "Всего животных", value: .count(18), bold: true)])))
        let (start, end) = service.period(.year, containing: clock)
        let doc = LivestockReportLayout.slaughter(try service.slaughterSummary(from: start, to: end), lines: [],
                                                  period: .year, start: start, farm: farm, createdAt: clock)
        XCTAssertEqual(doc.fileName, "Забой_2026")
        let zip = try MiniZipReader(CoalReportXLSXWriter.make(doc))
        let sheet = String(decoding: try XCTUnwrap(zip.data("xl/worksheets/sheet1.xml")), as: UTF8.self)
        XCTAssertTrue(sheet.contains("<v>300</v>"), "meat weight as a number")
        XCTAssertFalse(CoalReportPDFRenderer.render(doc).isEmpty)
    }

    func testBackupRoundTripKeepsTheHerd() throws {
        let cows = try category(.cattle, "Коровы"), calves = try category(.cattle, "Телята")
        try record(.initial, cows, 20)
        try record(.birth, calves, 3)
        try record(.transfer, calves, 1, to: cows)
        try record(.slaughter, cows, 2) { $0.liveWeightText = "800" }
        let backup = BackupService(context: context, now: { [unowned self] in self.clock }, adoptTimeZone: { _ in })
        let data = try backup.makeBackup()
        let before = try backup.payload().livestock

        try service.delete(try XCTUnwrap(try service.operations().first { $0.kind == .slaughter }))
        try service.addCategory("Лишняя", species: .pigs)
        try backup.restore(data)
        XCTAssertEqual(try backup.payload().livestock, before)
        XCTAssertEqual(try heads(cows), 19)
        XCTAssertEqual(try heads(calves), 2)

        // A file made before the module keeps the current herd.
        var old = try backup.payload()
        old.livestock = nil
        let oldData = try BackupCodec.encode(old, createdAt: clock, appVersion: "1.0")
        try record(.sale, cows, 1)
        try backup.restore(oldData)
        XCTAssertEqual(try heads(cows), 18)

        // A broken herd (more written off than there was) is not restored.
        var broken = try XCTUnwrap(try backup.payload().livestock)
        broken.operations.append(LivestockBackup.Operation(id: UUID(), kind: .slaughter, date: clock, categoryId: cows.id,
                                                           targetCategoryId: nil, heads: 100, comment: "",
                                                           createdAt: clock, updatedAt: clock))
        var p = try backup.payload()
        p.livestock = broken
        XCTAssertThrowsError(try BackupCodec.decode(try BackupCodec.encode(p, createdAt: clock, appVersion: "1.0"))) {
            XCTAssertEqual($0 as? BackupError, .invalidData)
        }
    }

    /// The phone's version 2 store (timesheet + coal) opens with the livestock module and keeps its data.
    func testVersion2StoreOpensWithLivestock() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("LivestockTests-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("tabel.store")
        do {
            let schema = Schema(versionedSchema: SchemaV2.self)
            let container = try ModelContainer(for: schema, migrationPlan: nil,
                                               configurations: [ModelConfiguration(schema: schema, url: url,
                                                                                   cloudKitDatabase: .none)])
            let ctx = ModelContext(container)
            ctx.insert(Employee(lastName: "Зубахин", firstName: "Иван", hireDate: clock))
            ctx.insert(Driver(lastName: "Мунгалов", createdAt: clock))
            try ctx.save()
        }
        let ctx = ModelContext(try makeModelContainer(url: url))
        XCTAssertEqual(try ctx.fetch(FetchDescriptor<Employee>()).first?.lastName, "Зубахин")
        XCTAssertEqual(try ctx.fetch(FetchDescriptor<Driver>()).first?.lastName, "Мунгалов")
        let s = LivestockService(context: ctx, calendar: calendar, now: { [unowned self] in self.clock })
        var d = LivestockDraft(kind: .initial, date: clock, categoryId: try XCTUnwrap(try s.categories(.cattle).first).id)
        d.headsText = "20"
        try s.save(d)
        XCTAssertEqual(try s.total(.cattle), 20)
    }

    func testDefaultCategoryIdsAreStable() throws {
        let otherContext = ModelContext(try makeModelContainer(inMemory: true))
        let other = LivestockService(context: otherContext, calendar: calendar, now: { [unowned self] in self.clock })
        let mine = try service.categories().map(\.id)
        let theirs = try other.categories().map(\.id)
        XCTAssertEqual(Set(mine), Set(theirs))
        XCTAssertEqual(Set(mine).count, 15)
        XCTAssertEqual(try category(.cattle, "Коровы").id, StableId.livestockCategory(species: "cattle", name: "Коровы"))
        XCTAssertEqual(try context.fetch(FetchDescriptor<LivestockSettings>()).first?.id, StableId.livestockSettings)
    }
}
