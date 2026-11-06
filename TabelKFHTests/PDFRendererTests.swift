import PDFKit
import UIKit
import XCTest
@testable import TabelKFH

final class PDFRendererTests: XCTestCase {
    private static let calendar = Calendar.farm

    private static func november(employees count: Int, settings: FarmInfo = .default) -> TimesheetDocument {
        let employees = (0..<count).map { i in
            EmployeeSnapshot(id: UUID(), fullName: "Сотрудник \(i + 1) Иванович", position: "Рабочий",
                             hireDate: calendar.date(day: 1, month: 1, year: 2020), dismissalDate: nil)
        }
        let records = employees.flatMap { e in
            (1...5).map { day in
                RecordSnapshot(employeeId: e.id, day: calendar.date(day: day, month: 11, year: 2026),
                               status: .present, minutes: 480, unfinished: false)
            }
        }
        return TimesheetBuilder.build(month: 11, year: 2026, employees: employees, records: records,
                                      settings: settings, format: .codes, version: 2,
                                      createdAt: calendar.date(day: 6, month: 11, year: 2026))
    }

    private func text(_ page: PDFPage?) -> String { page?.string ?? "" }

    // Spec 14.
    func testOutputIsAnA4LandscapePDF() throws {
        let data = TimesheetPDFRenderer.render(Self.november(employees: 3))
        XCTAssertEqual(String(decoding: data.prefix(4), as: UTF8.self), "%PDF")
        let pdf = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertEqual(pdf.pageCount, 1)
        let bounds = try XCTUnwrap(pdf.page(at: 0)).bounds(for: .mediaBox)
        XCTAssertEqual(bounds.width, 842, accuracy: 0.5)
        XCTAssertEqual(bounds.height, 595, accuracy: 0.5)
    }

    func testFirstPageHasTitleFarmMonthYearAndVersion() throws {
        let settings = FarmInfo(farmName: "КФХ Зубахин", managerName: "Зубахин И. П.", responsiblePerson: "Иванова А. А.",
                                address: "х. Зубахин, ул. Степная, 12", phone: "+7 928 123-45-67", extraInfo: "")
        let pdf = try XCTUnwrap(PDFDocument(data: TimesheetPDFRenderer.render(Self.november(employees: 3, settings: settings))))
        let page = text(pdf.page(at: 0))
        for expected in ["ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", "КФХ Зубахин", "Ноябрь", "2026",
                         "ул. Степная", "123-45-67", "ФИО", "ОТ", "Сотрудник 1 Иванович",
                         "Итого по хозяйству", "Страница 1 из 1",
                         "Руководитель КФХ: ___________________", "Ответственное лицо: ___________________",
                         "Дата формирования: 06.11.2026"] {
            XCTAssertTrue(page.contains(expected), "page 1 has «\(expected)» in: \(page)")
        }
    }

    func testLongTimesheetRepeatsTheHeaderAndNumbersPages() throws {
        let pdf = try XCTUnwrap(PDFDocument(data: TimesheetPDFRenderer.render(Self.november(employees: 60))))
        XCTAssertGreaterThan(pdf.pageCount, 1)
        let second = text(pdf.page(at: 1))
        XCTAssertTrue(second.contains("ФИО"), "header repeated on page 2")
        XCTAssertTrue(second.contains("Страница 2 из \(pdf.pageCount)"))
        let last = text(pdf.page(at: pdf.pageCount - 1))
        XCTAssertTrue(last.contains("Руководитель КФХ:"), last)
        XCTAssertTrue(last.contains("Ответственное лицо:"), last)
        XCTAssertTrue(last.contains("Страница \(pdf.pageCount) из \(pdf.pageCount)"), last)
        XCTAssertFalse(text(pdf.page(at: 0)).contains("Руководитель КФХ:"))
    }

    func testExportFileName() {
        XCTAssertEqual(exportFileName(Self.november(employees: 1), ext: "pdf"), "Табель_Ноябрь_2026.pdf")
        XCTAssertEqual(exportFileName(Self.november(employees: 1), ext: "xlsx"), "Табель_Ноябрь_2026.xlsx")
    }

    func testExportFileNameOfOneEmployeeHasTheSurname() {
        XCTAssertEqual(exportFileName(Self.november(employees: 1), surname: "Зубахин", ext: "pdf"),
                       "Табель_Ноябрь_2026_Зубахин.pdf")
        XCTAssertEqual(exportFileName(Self.november(employees: 1), surname: "Иванова-Петрова", ext: "xlsx"),
                       "Табель_Ноябрь_2026_Иванова-Петрова.xlsx")
        XCTAssertEqual(exportFileName(Self.november(employees: 1), surname: "А/Б", ext: "pdf"),
                       "Табель_Ноябрь_2026_А-Б.pdf", "a slash would make a folder")
    }

    // MARK: - Samples for a human look (CI artifact «review-samples»)

    private static let surnames = ["Константинопольский", "Зубахин", "Преображенский", "Ковальчук", "Иванов",
                                   "Вознесенский", "Шереметьев", "Ли", "Сидоренко", "Кривошеев", "Новиков",
                                   "Белоусов", "Ястржембский", "Орлов", "Петров"]
    private static let givenNames = ["Александр Владиславович", "Иван Петрович", "Вячеслав Константинович",
                                     "Пётр Ильич", "Максимилиан Святославович", "Олег Юрьевич",
                                     "Дмитрий Александрович", "Ян"]

    /// December 2026, codes and hours: weekends off, varied hours, sick leave, vacation, a day off, an absence,
    /// a business trip, an unfinished day, one dismissed on the 15th, one hired on the 10th, blanks at the end.
    static func sampleDocument(employees count: Int) -> TimesheetDocument {
        var employees: [EmployeeSnapshot] = []
        var records: [RecordSnapshot] = []
        for i in 0..<count {
            let kind = i % 12
            let name = surnames[i % surnames.count] + " " + givenNames[(i + i / surnames.count * 3) % givenNames.count]
            let e = EmployeeSnapshot(
                id: UUID(), fullName: name, position: "Рабочий",
                hireDate: kind == 7 ? calendar.date(day: 10, month: 12, year: 2026) : calendar.date(day: 1, month: 1, year: 2020),
                dismissalDate: kind == 6 ? calendar.date(day: 15, month: 12, year: 2026) : nil)
            employees.append(e)
            for day in 1...31 {
                let date = calendar.date(day: day, month: 12, year: 2026)
                let weekend = calendar.isDateInWeekend(date)
                var status: DayStatus = weekend ? .dayOff : .present
                if kind == 4 && weekend && calendar.component(.weekday, from: date) == 7 { status = .present }
                if kind == 2 && (7...11).contains(day) { status = .sick }
                if kind == 3 && (14...25).contains(day) { status = .vacation }
                if kind == 5 && day == 4 { status = .timeOff }
                if kind == 5 && day == 18 { status = .absent }
                if kind == 8 && (8...10).contains(day) { status = .businessTrip }
                if kind == 9 && day >= 30 { continue }
                let unfinished = kind == 1 && day == 22
                let minutes = 480 + ((i * 13 + day * 7) % 5 - 2) * 30 + (day % 4 == 0 ? 15 : 0)
                records.append(RecordSnapshot(employeeId: e.id, day: date, status: status,
                                              minutes: status == .present && !unfinished ? minutes : nil,
                                              unfinished: status == .present && unfinished))
            }
        }
        let settings = FarmInfo(farmName: "КФХ Зубахин", managerName: "Зубахин Иван Петрович",
                                responsiblePerson: "Кривошеева Мария Алексеевна",
                                address: "Ростовская обл., Сальский р-н, х. Зубахин, ул. Степная, 12",
                                phone: "+7 (928) 123-45-67", extraInfo: "")
        let createdAt = calendar.date(day: 31, month: 12, year: 2026).addingTimeInterval(18 * 3600)
        return TimesheetBuilder.build(month: 12, year: 2026, employees: employees, records: records,
                                      settings: settings, format: .codesAndHours, version: 2, createdAt: createdAt)
    }

    /// Writes timesheet.pdf, timesheet-60.pdf, timesheet.xlsx and a PNG per PDF page into
    /// $SAMPLES_DIR/ReviewSamples (CI passes it as TEST_RUNNER_SAMPLES_DIR), else into the temp directory.
    func testWriteSamplesForReview() throws {
        let root = ProcessInfo.processInfo.environment["SAMPLES_DIR"].map { URL(fileURLWithPath: $0) }
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let folder = root.appendingPathComponent("ReviewSamples", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

        let small = Self.sampleDocument(employees: 12)
        try XLSXWriter.make(small).write(to: folder.appendingPathComponent("timesheet.xlsx"))
        for (name, document) in [("timesheet", small), ("timesheet-60", Self.sampleDocument(employees: 60))] {
            let data = TimesheetPDFRenderer.render(document)
            try data.write(to: folder.appendingPathComponent("\(name).pdf"))
            let pdf = try XCTUnwrap(PDFDocument(data: data))
            for index in 0..<pdf.pageCount {
                let page = try XCTUnwrap(pdf.page(at: index))
                let size = CGSize(width: 1684, height: 1190)
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
        print("Review samples: \(folder.path)")
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("timesheet-p1.png").path))
    }
}
