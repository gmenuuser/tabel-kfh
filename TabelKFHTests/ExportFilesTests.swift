import XCTest
@testable import TabelKFH

final class ExportFilesTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ExportFilesTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private static func november() -> TimesheetDocument {
        let calendar = Calendar.farm
        let e = EmployeeSnapshot(id: UUID(), fullName: "Зубахин Иван Петрович", position: "Тракторист",
                                 hireDate: calendar.date(day: 1, month: 1, year: 2020), dismissalDate: nil)
        let records = (1...5).map { day in
            RecordSnapshot(employeeId: e.id, day: calendar.date(day: day, month: 11, year: 2026),
                           status: .present, minutes: 480, unfinished: false)
        }
        return TimesheetBuilder.build(month: 11, year: 2026, employees: [e], records: records,
                                      settings: .default, format: .codes, version: 2,
                                      createdAt: calendar.date(day: 6, month: 11, year: 2026))
    }

    private func size(_ url: URL) throws -> Int {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes[.size] as? NSNumber)?.intValue ?? 0
    }

    func testWritesPDFAndXLSXWithExportNames() throws {
        let files = try ExportFiles.write(Self.november(), in: directory)
        XCTAssertEqual(files.pdf.lastPathComponent, "Табель_Ноябрь_2026_v2.pdf")
        XCTAssertEqual(files.xlsx.lastPathComponent, "Табель_Ноябрь_2026_v2.xlsx")
        XCTAssertEqual(files.pdf.deletingLastPathComponent().standardizedFileURL, directory.standardizedFileURL)
        XCTAssertGreaterThan(try size(files.pdf), 0)
        XCTAssertGreaterThan(try size(files.xlsx), 0)
        let pdf = try Data(contentsOf: files.pdf)
        XCTAssertEqual(String(decoding: pdf.prefix(4), as: UTF8.self), "%PDF")
        let xlsx = try Data(contentsOf: files.xlsx)
        XCTAssertEqual(Array(xlsx.prefix(2)), [0x50, 0x4B], "XLSX is a ZIP")
        XCTAssertEqual(files.all, [files.pdf, files.xlsx])
    }

    func testWritingAgainReplacesTheFiles() throws {
        _ = try ExportFiles.write(Self.november(), in: directory)
        let files = try ExportFiles.write(Self.november(), in: directory)
        XCTAssertGreaterThan(try size(files.pdf), 0)
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        XCTAssertEqual(names, ["Табель_Ноябрь_2026_v2.pdf", "Табель_Ноябрь_2026_v2.xlsx"])
    }

    func testDefaultDirectoryIsExportInTemporaryDirectory() {
        XCTAssertEqual(ExportFiles.directory.lastPathComponent, "Export")
        XCTAssertEqual(ExportFiles.directory.deletingLastPathComponent().standardizedFileURL,
                       FileManager.default.temporaryDirectory.standardizedFileURL)
    }
}
