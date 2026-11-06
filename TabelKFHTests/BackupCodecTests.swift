import XCTest
@testable import TabelKFH

final class BackupCodecTests: XCTestCase {
    private let calendar = Calendar.farm
    private let created = Date(timeIntervalSinceReferenceDate: 815_000_000.123_456)

    private func day(_ d: Int, _ m: Int = 11, _ y: Int = 2026) -> Date {
        calendar.date(day: d, month: m, year: y)
    }

    /// An archived employee with a photo, an active one, records of both, one snapshot.
    private func payload() -> BackupPayload {
        let archived = EmployeeBackup(id: UUID(), lastName: "Старов", firstName: "Пётр", patronymic: "Ильич",
                                      position: "Сторож", hireDate: day(1, 1, 2020), dismissalDate: day(31, 10),
                                      isArchived: true, workSchedule: .twoTwo, standardWorkHours: 12,
                                      defaultBreakMinutes: 30, photo: Data([0x89, 0x50, 0x4E, 0x47, 0, 1, 2, 255]))
        let active = EmployeeBackup(id: UUID(), lastName: "Зубахин", firstName: "Иван", patronymic: "Петрович",
                                    position: "Тракторист", hireDate: day(1, 3, 2025), dismissalDate: nil,
                                    isArchived: false, workSchedule: .fiveTwo, standardWorkHours: 7.5,
                                    defaultBreakMinutes: 60, photo: nil)
        let arrival = day(5).addingTimeInterval(8 * 3600)
        let records = [
            RecordBackup(id: UUID(), employeeId: active.id, day: day(5), arrivalTime: arrival,
                         departureTime: arrival.addingTimeInterval(9 * 3600), breakMinutes: 60, workedMinutes: 480,
                         manualWorkedMinutes: 470, status: .present, comment: "Пахота «у реки»\nвторая строка",
                         createdAt: created, updatedAt: created.addingTimeInterval(60.5)),
            RecordBackup(id: UUID(), employeeId: active.id, day: day(6), arrivalTime: arrival.addingTimeInterval(86_400),
                         departureTime: nil, breakMinutes: 0, workedMinutes: nil, manualWorkedMinutes: nil,
                         status: .present, comment: "", createdAt: created, updatedAt: created),
            RecordBackup(id: UUID(), employeeId: archived.id, day: day(3, 10), arrivalTime: nil, departureTime: nil,
                         breakMinutes: 0, workedMinutes: nil, manualWorkedMinutes: nil, status: .timeOff,
                         comment: "", createdAt: created, updatedAt: created),
        ]
        let snapshot = SnapshotBackup(id: UUID(), month: 10, year: 2026, version: 2, employeeId: nil,
                                      createdAt: created, documentData: Data("{\"a\":1}".utf8),
                                      employeeData: Data("[]".utf8))
        let settings = FarmInfo(farmName: "КФХ Зубахин", managerName: "Зубахин И. П.", responsiblePerson: "Иванова А. А.",
                                address: "х. Зубахин", phone: "+7 928 123-45-67", extraInfo: "ИНН 123")
        return BackupPayload(settings: settings, employees: [archived, active], records: records, snapshots: [snapshot])
    }

    private func encoded(_ p: BackupPayload? = nil) throws -> Data {
        try BackupCodec.encode(p ?? payload(), createdAt: created, appVersion: "1.0")
    }

    /// The file as a JSON object, changed by `edit`, then serialized again.
    private func edited(_ data: Data, _ edit: (inout [String: Any]) -> Void) throws -> Data {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        edit(&object)
        return try JSONSerialization.data(withJSONObject: object)
    }

    private func assertRejected(_ data: Data, _ expected: BackupError,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try BackupCodec.decode(data), file: file, line: line) { error in
            XCTAssertEqual(error as? BackupError, expected, file: file, line: line)
        }
    }

    // Spec 16.
    func testRoundTripKeepsEverything() throws {
        let p = payload()
        let data = try encoded(p)
        XCTAssertEqual(try BackupCodec.decode(data), p)

        let file = try BackupCodec.decodeFile(data)
        XCTAssertEqual(file.format, "tabel-kfh-backup")
        XCTAssertEqual(file.schemaVersion, 3)
        XCTAssertEqual(file.createdAt, created)
        XCTAssertEqual(file.appVersion, "1.0")
        XCTAssertEqual(file.payloadSHA256.count, 64)
    }

    // Spec 17.
    func testChangedPayloadIsDamaged() throws {
        let text = String(decoding: try encoded(), as: UTF8.self)
        XCTAssertTrue(text.contains("Тракторист"))
        assertRejected(Data(text.replacingOccurrences(of: "Тракторист", with: "Тракторисд").utf8), .damaged)
        XCTAssertTrue(text.contains("\"defaultBreakMinutes\" : 60"))
        assertRejected(Data(text.replacingOccurrences(of: "\"defaultBreakMinutes\" : 60",
                                                      with: "\"defaultBreakMinutes\" : 61").utf8), .damaged)
    }

    func testChangedChecksumIsDamaged() throws {
        let data = try edited(try encoded()) { $0["payloadSHA256"] = String(repeating: "0", count: 64) }
        assertRejected(data, .damaged)
    }

    func testTruncatedFileIsDamaged() throws {
        let data = try encoded()
        assertRejected(data.prefix(data.count / 2), .damaged)
    }

    func testForeignFormatIsNotABackup() throws {
        assertRejected(try edited(try encoded()) { $0["format"] = "x" }, .notABackup)
        assertRejected(try edited(try encoded()) { $0.removeValue(forKey: "format") }, .notABackup)
        assertRejected(Data("{\"name\":\"Табель\"}".utf8), .notABackup)
        assertRejected(Data("[1, 2, 3]".utf8), .notABackup)
    }

    func testNewerSchemaIsUnsupported() throws {
        assertRejected(try edited(try encoded()) { $0["schemaVersion"] = 4 }, .unsupportedVersion(4))
    }

    func testRandomBytesAreNotABackup() {
        var generator = SystemRandomNumberGenerator()
        let bytes = Data((0..<2048).map { _ in UInt8.random(in: 0...255, using: &generator) })
        assertRejected(bytes, .notABackup)
        assertRejected(Data(), .notABackup)
        assertRejected(Data("%PDF-1.7".utf8), .notABackup)
    }

    func testUnknownStatusOrScheduleCodeIsInvalidData() throws {
        let text = String(decoding: try encoded(), as: UTF8.self)
        XCTAssertTrue(text.contains("\"status\" : \"ОТ\""))
        assertRejected(Data(text.replacingOccurrences(of: "\"status\" : \"ОТ\"", with: "\"status\" : \"Ж\"").utf8),
                       .invalidData)
        XCTAssertTrue(text.contains("\"workSchedule\" : \"twoTwo\""))
        assertRejected(Data(text.replacingOccurrences(of: "\"workSchedule\" : \"twoTwo\"",
                                                      with: "\"workSchedule\" : \"threeOne\"").utf8), .invalidData)
    }

    func testTwoRecordsOfOneEmployeeAndDayAreInvalidData() throws {
        var p = payload()
        let first = p.records[0]
        p.records.append(RecordBackup(id: UUID(), employeeId: first.employeeId, day: first.day, arrivalTime: nil,
                                      departureTime: nil, breakMinutes: 0, workedMinutes: nil, manualWorkedMinutes: nil,
                                      status: .sick, comment: "", createdAt: created, updatedAt: created))
        assertRejected(try encoded(p), .invalidData)
    }

    func testRecordOfAMissingEmployeeIsInvalidData() throws {
        var p = payload()
        p.employees.removeFirst()
        assertRejected(try encoded(p), .invalidData)
    }

    func testRussianMessages() {
        XCTAssertEqual(BackupError.notABackup.errorDescription, "Это не резервная копия Табеля КФХ")
        XCTAssertEqual(BackupError.unsupportedVersion(2).errorDescription,
                       "Копия сделана более новой версией приложения (схема 2)")
        XCTAssertEqual(BackupError.damaged.errorDescription, "Файл копии повреждён: контрольная сумма не совпадает")
        XCTAssertEqual(BackupError.invalidData.errorDescription, "Файл копии содержит неверные данные")
    }
}
