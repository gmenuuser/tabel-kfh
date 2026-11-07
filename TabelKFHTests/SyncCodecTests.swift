import XCTest
import SwiftData
@testable import TabelKFH

/// Every model ↔ the server's field dictionary, with the field names and value shapes the site writes
/// (`web/src/pages/*.tsx`, `engine.save('<entity>', id, {...})`).
@MainActor
final class SyncCodecTests: XCTestCase {
    private let t1 = SyncCodec.date(from: "2026-11-06T15:00:00.123Z")!
    private let t2 = SyncCodec.date(from: "2026-11-07T01:30:45.678Z")!
    private let day = SyncCodec.date(from: "2026-11-06T15:00:00.000Z")!
    private let uuidA = UUID(uuidString: "6F1C2A3E-0000-4000-8000-00000000000A")!
    private let uuidB = UUID(uuidString: "6F1C2A3E-0000-4000-8000-00000000000B")!

    private func freshContext() throws -> ModelContext { ModelContext(try makeModelContainer(inMemory: true)) }

    /// Fields → insert into an empty store → the found row gives the same fields back.
    @discardableResult
    private func roundTrip<T: SyncRecordable>(_ model: T, file: StaticString = #filePath,
                                              line: UInt = #line) throws -> T {
        let source = try freshContext()
        source.insert(model)
        let fields = model.syncFields()
        XCTAssertFalse(fields.isEmpty, file: file, line: line)
        let target = try freshContext()
        try SyncCodec.insert(T.syncEntity, id: model.syncId, fields: fields, into: target)
        try target.save()
        let found = try XCTUnwrap(try SyncCodec.find(T.syncEntity, id: model.syncId, in: target), file: file, line: line)
        let typed = try XCTUnwrap(found as? T, "found a \(type(of: found))", file: file, line: line)
        XCTAssertEqual(typed.syncId, model.syncId, file: file, line: line)
        XCTAssertEqual(typed.syncFields(), fields, file: file, line: line)
        return typed
    }

    private func assertKeys<T: SyncRecordable>(_ model: T, contain keys: [String], file: StaticString = #filePath,
                                               line: UInt = #line) {
        let missing = Set(keys).subtracting(model.syncFields().keys)
        XCTAssertTrue(missing.isEmpty, "\(T.syncEntity.rawValue) lacks \(missing.sorted())", file: file, line: line)
    }

    // MARK: - Samples

    private func employee() -> Employee {
        Employee(id: uuidA, lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович", position: "Тракторист",
                 hireDate: day, dismissalDate: t2, isArchived: true, workSchedule: .twoTwo, standardWorkHours: 7.5,
                 defaultBreakMinutes: 45, photo: Data([1, 2, 3]))
    }

    private func sale() -> CoalSale {
        CoalSale(id: UUID(), saleDate: t1, kilograms: 1500, locationNameSnapshot: "Приаргунск",
                 coalPricePerTonSnapshot: 650_000, deliveryPriceSnapshot: 120_000, coalAmount: 975_000,
                 deliveryPrice: 120_000, vehicleType: .hired, driverId: uuidB, driverNameSnapshot: "Мунгалов Сергей",
                 driverPaymentAmount: 100_000, comment: "до обеда", createdAt: t1, updatedAt: t2)
    }

    private func agro() -> AgroCalculation {
        let lines = [AgroLine(title: "Нужно семян", text: "1 200 кг", main: true), AgroLine(title: "В центнерах", text: "12 ц")]
        return AgroCalculation(kind: .seeding, title: "Поле у реки", inputsData: AgroService.encode(["area": "5", "rate": "240"]),
                               resultData: try! JSONEncoder().encode(lines), createdAt: t1, updatedAt: t2)
    }

    // MARK: - Round trips, one per entity

    func testEntityList() {
        XCTAssertEqual(Set(SyncEntity.allCases.map(\.rawValue)), [
            "employee", "hoursMark", "attendanceRecord", "timesheetSnapshot", "farmSettings", "coalSettings",
            "deliveryLocation", "driver", "coalSale", "driverPayment", "coalWorkDay", "livestockCategory",
            "livestockOperation", "slaughterRecord", "livestockSettings", "agroCalculation"])
    }

    func testRoundTripEmployee() throws {
        let e = try roundTrip(employee())
        XCTAssertEqual(e.workSchedule, .twoTwo)
        XCTAssertEqual(e.standardWorkHours, 7.5)
        XCTAssertNil(e.photo, "the photo is not synced")
    }

    func testRoundTripHoursMark() throws {
        let m = try roundTrip(WorkHoursMark(id: StableId.hoursMark(employeeId: uuidA, day: day), employeeId: uuidA,
                                            day: day, hours: 12, createdAt: t1, updatedAt: t2))
        XCTAssertEqual(m.hours, 12)
        XCTAssertEqual(m.employeeId, uuidA)
    }

    func testRoundTripAttendanceRecord() throws {
        let r = try roundTrip(AttendanceRecord(employeeId: uuidA, day: day, arrivalTime: t1, departureTime: t2,
                                               breakMinutes: 30, workedMinutes: 600, manualWorkedMinutes: 540,
                                               status: .businessTrip, comment: "Чита", createdAt: t1, updatedAt: t2))
        XCTAssertEqual(r.status, .businessTrip)
        XCTAssertEqual(r.manualWorkedMinutes, 540)
    }

    func testRoundTripTimesheetSnapshot() throws {
        let s = try roundTrip(TimesheetSnapshot(month: 11, year: 2026, version: 3, employeeId: uuidA, createdAt: t1,
                                                documentData: Data("{\"a\":1}".utf8), employeeData: Data([0, 255, 7])))
        XCTAssertEqual(s.employeeData, Data([0, 255, 7]))
        XCTAssertEqual(s.documentData, Data("{\"a\":1}".utf8))
    }

    func testRoundTripFarmSettings() throws {
        let f = try roundTrip(FarmSettings(farmName: "КФХ Иванов", managerName: "Иванов И. И.", responsiblePerson: "Петрова",
                                           address: "с. Новоцурухайтуй", phone: "+7 914 000-00-00", extraInfo: "ИНН 1"))
        XCTAssertEqual(f.address, "с. Новоцурухайтуй")
        XCTAssertEqual(f.farmName, "КФХ Зубахин", "the name goes through /farm, not the record")
    }

    func testRoundTripCoalSettings() throws {
        let s = try roundTrip(CoalSettings(pricePerTonKopecks: 700_000, createdAt: t1, updatedAt: t2))
        XCTAssertEqual(s.pricePerTonKopecks, 700_000)
    }

    func testRoundTripDeliveryLocation() throws {
        try roundTrip(DeliveryLocation(id: StableId.deliveryLocation("Луговое"), name: "Луговое",
                                       deliveryPriceKopecks: 90_000, driverPaymentKopecks: 80_000, isActive: false,
                                       createdAt: t1))
        let l = try roundTrip(DeliveryLocation(name: "Приаргунск", deliveryPriceKopecks: 120_000, createdAt: t1))
        XCTAssertNil(l.driverPaymentKopecks)
    }

    func testRoundTripDriver() throws {
        try roundTrip(Driver(lastName: "Мунгалов", firstName: "Сергей", middleName: "Иванович", phone: "8 914",
                             comment: "КамАЗ", isArchived: true, createdAt: t1))
    }

    func testRoundTripCoalSale() throws {
        let s = try roundTrip(sale())
        XCTAssertEqual(s.vehicleType, .hired)
        XCTAssertEqual(s.totalAmount, 1_095_000)
        XCTAssertEqual(s.driverId, uuidB)
        try roundTrip(CoalSale(saleDate: t1, kilograms: 500, locationNameSnapshot: "Луговое", coalPricePerTonSnapshot: 650_000,
                               deliveryPriceSnapshot: 0, coalAmount: 325_000, deliveryPrice: 0, vehicleType: .own,
                               createdAt: t1, updatedAt: t1))
    }

    func testRoundTripDriverPayment() throws {
        try roundTrip(DriverPayment(driverId: uuidB, driverNameSnapshot: "Мунгалов Сергей", paymentDate: t2,
                                    amount: 50_000, comment: "наличными", createdAt: t1))
    }

    func testRoundTripCoalWorkDay() throws {
        try roundTrip(CoalWorkDay(id: StableId.coalWorkDay(day), date: day, isClosed: true, closedAt: t2,
                                  totalRevenue: 1_095_000, totalKilograms: 1500, totalDriverPayments: 100_000,
                                  ownVehicleDeliveryRevenue: 0, createdAt: t1))
        try roundTrip(CoalWorkDay(date: day, createdAt: t1))
    }

    func testRoundTripLivestockCategory() throws {
        let c = try roundTrip(LivestockCategory(id: StableId.livestockCategory(species: "goats", name: "Козлята"),
                                                species: .goats, name: "Козлята", sortOrder: 2, isBuiltIn: true,
                                                createdAt: t1))
        XCTAssertEqual(c.species, .goats)
    }

    func testRoundTripLivestockOperation() throws {
        let o = try roundTrip(LivestockOperation(kind: .transfer, date: t1, categoryId: uuidA, targetCategoryId: uuidB,
                                                 heads: 3, comment: "подросли", createdAt: t1, updatedAt: t2))
        XCTAssertEqual(o.kind, .transfer)
        XCTAssertEqual(o.targetCategoryId, uuidB)
    }

    func testRoundTripSlaughterRecord() throws {
        let s = try roundTrip(SlaughterRecord(operationId: uuidA, reason: .forced, liveWeightGrams: 450_500,
                                              carcassWeightGrams: 250_000, meatWeightGrams: nil, createdAt: t1))
        XCTAssertEqual(s.reason, .forced)
        XCTAssertNil(s.meatWeightGrams)
    }

    func testRoundTripLivestockSettings() throws {
        try roundTrip(LivestockSettings(id: StableId.livestockSettings, defaultsCreated: true, createdAt: t1))
    }

    func testRoundTripAgroCalculation() throws {
        let c = try roundTrip(agro())
        XCTAssertEqual(c.inputs, ["area": "5", "rate": "240"])
        XCTAssertEqual(c.lines.first, AgroLine(title: "Нужно семян", text: "1 200 кг", main: true))
        XCTAssertEqual(c.kind, .seeding)
    }

    // MARK: - The site's field names and shapes

    func testFieldNamesMatchSite() {
        assertKeys(sale(), contain: ["saleDate", "kilograms", "locationNameSnapshot", "coalPricePerTonSnapshot",
                                     "deliveryPriceSnapshot", "coalAmount", "deliveryPrice", "totalAmount", "vehicleType",
                                     "driverId", "driverNameSnapshot", "driverPaymentAmount", "comment", "createdAt"])
        assertKeys(employee(), contain: ["lastName", "firstName", "patronymic", "position", "hireDate", "dismissalDate",
                                         "isArchived", "workSchedule", "standardWorkHours", "defaultBreakMinutes"])
        assertKeys(WorkHoursMark(employeeId: uuidA, day: day, hours: 8, createdAt: t1, updatedAt: t1),
                   contain: ["employeeId", "day", "hours"])
        assertKeys(LivestockOperation(kind: .birth, date: t1, categoryId: uuidA, heads: 1, createdAt: t1, updatedAt: t1),
                   contain: ["kind", "date", "categoryId", "targetCategoryId", "heads", "comment", "createdAt"])
        assertKeys(SlaughterRecord(operationId: uuidA, reason: .meat, createdAt: t1),
                   contain: ["operationId", "reason", "liveWeightGrams", "carcassWeightGrams", "meatWeightGrams", "createdAt"])
        assertKeys(agro(), contain: ["kind", "title", "inputs", "lines", "createdAt", "updatedAt"])
        assertKeys(CoalSettings(createdAt: t1, updatedAt: t1), contain: ["pricePerTonKopecks"])
        assertKeys(DeliveryLocation(name: "Луговое", deliveryPriceKopecks: 1, createdAt: t1),
                   contain: ["name", "deliveryPriceKopecks", "driverPaymentKopecks", "isActive", "createdAt"])
        assertKeys(Driver(lastName: "М", createdAt: t1),
                   contain: ["lastName", "firstName", "middleName", "phone", "comment", "isArchived", "createdAt"])
        assertKeys(DriverPayment(driverId: uuidB, driverNameSnapshot: "М", paymentDate: t1, amount: 1, createdAt: t1),
                   contain: ["driverId", "driverNameSnapshot", "amount", "paymentDate", "comment", "createdAt"])
        assertKeys(CoalWorkDay(date: day, createdAt: t1),
                   contain: ["date", "isClosed", "closedAt", "totalRevenue", "totalKilograms", "totalDriverPayments",
                             "ownVehicleDeliveryRevenue", "createdAt"])
        assertKeys(LivestockCategory(species: .pigs, name: "Хряки", sortOrder: 1, createdAt: t1),
                   contain: ["species", "name", "sortOrder", "isBuiltIn", "createdAt"])
    }

    func testValueShapes() {
        let s = sale().syncFields()
        XCTAssertEqual(s["vehicleType"], .string("hired"), "…Raw goes without Raw")
        XCTAssertNil(s["vehicleTypeRaw"])
        XCTAssertEqual(s["driverId"], .string("6f1c2a3e-0000-4000-8000-00000000000b"), "UUIDs in lowercase")
        XCTAssertEqual(s["saleDate"], .string("2026-11-06T15:00:00.123Z"), "ISO 8601 with milliseconds, as toISOString")
        XCTAssertEqual(s["kilograms"], .int(1500))

        let own = CoalSale(saleDate: t1, kilograms: 1, locationNameSnapshot: "", coalPricePerTonSnapshot: 0,
                           deliveryPriceSnapshot: 0, coalAmount: 0, deliveryPrice: 0, vehicleType: .own,
                           createdAt: t1, updatedAt: t1).syncFields()
        XCTAssertEqual(own["driverId"], .null, "an empty optional is sent as null, as the site does")

        let e = employee().syncFields()
        XCTAssertEqual(e["workSchedule"], .string("twoTwo"))
        XCTAssertEqual(e["hireDate"], .string("2026-11-06T15:00:00.000Z"))
        XCTAssertNil(e["photo"])
        XCTAssertNil(e["records"])
        XCTAssertEqual(e["standardWorkHours"], .double(7.5))

        let a = agro().syncFields()
        XCTAssertEqual(a["inputs"], .object(["area": .string("5"), "rate": .string("240")]))
        XCTAssertEqual(a["lines"], .array([
            .object(["title": .string("Нужно семян"), "text": .string("1 200 кг"), "main": .bool(true)]),
            .object(["title": .string("В центнерах"), "text": .string("12 ц"), "main": .bool(false)])]))
        XCTAssertNil(a["inputsData"])
        XCTAssertNil(a["resultData"])

        let t = TimesheetSnapshot(month: 1, year: 2026, version: 1, employeeId: nil, documentData: Data([1, 2]),
                                  employeeData: Data()).syncFields()
        XCTAssertEqual(t["documentData"], .string("AQI="))
        XCTAssertEqual(t["employeeData"], .string(""))
        XCTAssertEqual(t["employeeId"], .null)

        let f = FarmSettings(farmName: "X", managerName: "Y").syncFields()
        XCTAssertEqual(Set(f.keys), ["responsiblePerson", "address", "phone", "extraInfo"])
    }

    func testSingletonIds() throws {
        XCTAssertEqual(CoalSettings(createdAt: t1, updatedAt: t1).syncId, StableId.coalSettings)
        XCTAssertEqual(FarmSettings().syncId, StableId.farmSettings)
        XCTAssertEqual(LivestockSettings(createdAt: t1).syncId, StableId.livestockSettings)
        XCTAssertEqual(CoalSettings.syncEntity, .coalSettings)

        // One row per store: a pulled record goes onto the existing row, whatever its id.
        let context = try freshContext()
        let farm = FarmSettingsStore.current(in: context)
        farm.farmName = "КФХ Иванов"
        try SyncCodec.insert(.farmSettings, id: StableId.farmSettings, fields: ["phone": .string("123")], into: context)
        try SyncCodec.insert(.coalSettings, id: UUID(), fields: ["pricePerTonKopecks": .int(1)], into: context)
        try SyncCodec.insert(.coalSettings, id: StableId.coalSettings, fields: ["pricePerTonKopecks": .int(2)], into: context)
        try context.save()
        XCTAssertEqual(try context.fetch(FetchDescriptor<FarmSettings>()).count, 1)
        XCTAssertEqual(farm.phone, "123")
        XCTAssertEqual(farm.farmName, "КФХ Иванов")
        let coal = try context.fetch(FetchDescriptor<CoalSettings>())
        XCTAssertEqual(coal.map(\.pricePerTonKopecks), [2])
        XCTAssertTrue(try SyncCodec.find(.coalSettings, id: UUID(), in: context) === coal.first)
    }

    // MARK: - Reading what the site writes

    func testDecodesSiteDate() {
        let expected = Date(timeIntervalSince1970: 1_793_977_200)   // 2026-11-06 15:00:00 UTC
        XCTAssertEqual(SyncCodec.date(from: "2026-11-06T15:00:00.000Z"), expected)
        XCTAssertEqual(SyncCodec.date(from: "2026-11-06T15:00:00Z"), expected)
        XCTAssertNil(SyncCodec.date(from: "06.11.2026"))
        XCTAssertEqual(SyncCodec.string(from: expected), "2026-11-06T15:00:00.000Z")
    }

    func testDecodesSiteRecordJSON() throws {
        let json = """
        {"lastName":"Иванов","firstName":"Пётр","patronymic":"","position":"","hireDate":"2026-11-01T00:00:00Z",
         "dismissalDate":null,"isArchived":false,"workSchedule":"fiveTwo","standardWorkHours":8,"defaultBreakMinutes":60}
        """
        let fields = try JSONDecoder().decode(SyncFields.self, from: Data(json.utf8))
        XCTAssertEqual(fields["dismissalDate"], .null)
        XCTAssertEqual(fields["standardWorkHours"], .int(8))
        XCTAssertEqual(fields["standardWorkHours"], .double(8), "a whole number equals the same double")
        let context = try freshContext()
        let id = UUID()
        try SyncCodec.insert(.employee, id: id, fields: fields, into: context)
        let e = try XCTUnwrap(try SyncCodec.find(.employee, id: id, in: context) as? Employee)
        XCTAssertEqual(e.lastName, "Иванов")
        XCTAssertEqual(e.standardWorkHours, 8)
        XCTAssertEqual(e.hireDate, SyncCodec.date(from: "2026-11-01T00:00:00.000Z"))
        XCTAssertNil(e.dismissalDate)

        // The site's agro lines may omit `main`.
        let agroJSON = """
        {"kind":"fertilizer","title":"","inputs":{"area":"2"},"lines":[{"title":"Нужно удобрения","text":"100 кг","main":true},
         {"title":"В тоннах","text":"0,1 т"}],"createdAt":"2026-11-06T15:00:00.000Z","updatedAt":"2026-11-06T15:00:00.000Z"}
        """
        let agroId = UUID()
        try SyncCodec.insert(.agroCalculation, id: agroId,
                             fields: try JSONDecoder().decode(SyncFields.self, from: Data(agroJSON.utf8)), into: context)
        let c = try XCTUnwrap(try SyncCodec.find(.agroCalculation, id: agroId, in: context) as? AgroCalculation)
        XCTAssertEqual(c.kind, .fertilizer)
        XCTAssertEqual(c.inputs, ["area": "2"])
        XCTAssertEqual(c.lines, [AgroLine(title: "Нужно удобрения", text: "100 кг", main: true),
                                 AgroLine(title: "В тоннах", text: "0,1 т", main: false)])

        // And back to JSON: the same values.
        let encoded = try JSONEncoder().encode(fields)
        XCTAssertEqual(try JSONDecoder().decode(SyncFields.self, from: encoded), fields)
    }

    func testMissingFieldsTakeModelDefaults() throws {
        let context = try freshContext()
        let id = UUID()
        try SyncCodec.insert(.employee, id: id, fields: ["lastName": .string("Иванов"), "firstName": .string("Пётр")],
                             into: context)
        let e = try XCTUnwrap(try SyncCodec.find(.employee, id: id, in: context) as? Employee)
        XCTAssertEqual(e.id, id)
        XCTAssertEqual(e.workSchedule, .fiveTwo)
        XCTAssertEqual(e.standardWorkHours, 8)
        XCTAssertEqual(e.defaultBreakMinutes, 60)
        XCTAssertFalse(e.isArchived)
        XCTAssertNil(e.dismissalDate)

        // A partial record changes only its fields.
        try SyncCodec.insert(.employee, id: id, fields: ["isArchived": .bool(true)], into: context)
        try context.save()
        XCTAssertEqual(try context.fetch(FetchDescriptor<Employee>()).count, 1, "inserting a known id updates it")
        XCTAssertTrue(e.isArchived)
        XCTAssertEqual(e.lastName, "Иванов")
    }

    func testAttendanceRecordLinksEmployee() throws {
        let context = try freshContext()
        let e = employee()
        context.insert(e)
        try context.save()
        let id = UUID()
        try SyncCodec.insert(.attendanceRecord, id: id, fields: ["employeeId": .string(uuidA.uuidString.lowercased()),
                                                                  "day": .string("2026-11-06T15:00:00.000Z")], into: context)
        try context.save()
        let r = try XCTUnwrap(try SyncCodec.find(.attendanceRecord, id: id, in: context) as? AttendanceRecord)
        XCTAssertTrue(r.employee === e)
        XCTAssertEqual(r.status, .present)
    }

    func testRemovedHoursMarkIsTolerated() throws {
        let removed: SyncFields = ["employeeId": .string(uuidA.uuidString.lowercased()),
                                   "day": .string("2026-11-06T15:00:00.000Z"), "hours": .null]
        XCTAssertTrue(SyncCodec.isRemovedHoursMark(removed))
        XCTAssertFalse(SyncCodec.isRemovedHoursMark(["hours": .int(8)]))
        XCTAssertFalse(SyncCodec.isRemovedHoursMark(["hours": .int(0)]))

        let context = try freshContext()
        let id = StableId.hoursMark(employeeId: uuidA, day: day)
        try SyncCodec.insert(.hoursMark, id: id, fields: removed, into: context)
        XCTAssertNil(try SyncCodec.find(.hoursMark, id: id, in: context), "a removed mark is no row on the phone")

        let mark = WorkHoursMark(id: id, employeeId: uuidA, day: day, hours: 8, createdAt: t1, updatedAt: t1)
        context.insert(mark)
        mark.apply(removed)
        XCTAssertEqual(mark.hours, 8, "apply leaves the hours; removing the row is the caller's job")
    }
}
