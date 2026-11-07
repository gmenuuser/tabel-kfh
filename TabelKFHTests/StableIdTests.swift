import XCTest
@testable import TabelKFH

/// The same record ids as the site (`web/src/lib/ids.ts`): two devices writing offline produce one record.
final class StableIdTests: XCTestCase {
    func testMatchesSite() {
        let expected: [(String, String)] = [
            ("coalSettings", "95dedd13-e1ca-8a22-b8f7-dd380554b776"),
            ("farmSettings", "6f0e626d-d54f-8197-8901-1a4905a531de"),
            ("livestockSettings", "0a989983-1107-8b34-84d3-14c31f0c93ae"),
            ("hoursMark:6f1c2a3e-0000-4000-8000-000000000001:2026-11-07", "5337ef8b-89b1-8bd3-96e9-a27e67205ff7"),
            ("coalWorkDay:2026-11-07", "267e507b-3bc5-81c8-b4ad-a44028534672"),
            ("deliveryLocation:Луговое", "3f9d5530-0b6d-86ce-9ec5-b392829d9df5"),
            ("livestockCategory:cattle:Коровы", "37093837-97e0-8515-af51-f22c98ee7499"),
        ]
        for (name, id) in expected {
            XCTAssertEqual(StableId.make(name).uuidString.lowercased(), id, name)
        }
    }

    func testNamedHelpersMatchSite() {
        let employee = UUID(uuidString: "6F1C2A3E-0000-4000-8000-000000000001")!
        var calendar = Calendar.farm(timeZone: TimeZone(identifier: "Asia/Chita")!)
        calendar.locale = Locale(identifier: "ru_RU")
        let day = calendar.date(day: 7, month: 11, year: 2026)
        XCTAssertEqual(StableId.hoursMark(employeeId: employee, day: day, calendar: calendar).uuidString.lowercased(),
                       "5337ef8b-89b1-8bd3-96e9-a27e67205ff7", "the employee id is lowercased in the name")
        XCTAssertEqual(StableId.coalWorkDay(day, calendar: calendar).uuidString.lowercased(), "267e507b-3bc5-81c8-b4ad-a44028534672")
        XCTAssertEqual(StableId.deliveryLocation("Луговое").uuidString.lowercased(), "3f9d5530-0b6d-86ce-9ec5-b392829d9df5")
        XCTAssertEqual(StableId.livestockCategory(species: "cattle", name: "Коровы").uuidString.lowercased(),
                       "37093837-97e0-8515-af51-f22c98ee7499")
        XCTAssertEqual(StableId.coalSettings.uuidString.lowercased(), "95dedd13-e1ca-8a22-b8f7-dd380554b776")
    }

    func testHoursMarkUsesFarmDay() {
        let chita = Calendar.farm(timeZone: TimeZone(identifier: "Asia/Chita")!)
        let moment = ISO8601DateFormatter().date(from: "2026-11-06T16:30:00Z")!   // 01:30 on the 7th in Chita (UTC+9)
        XCTAssertEqual(StableId.dayKey(moment, calendar: chita), "2026-11-07")
        let employee = UUID()
        XCTAssertEqual(StableId.hoursMark(employeeId: employee, day: moment, calendar: chita),
                       StableId.make("hoursMark:\(employee.uuidString.lowercased()):2026-11-07"))
    }
}
