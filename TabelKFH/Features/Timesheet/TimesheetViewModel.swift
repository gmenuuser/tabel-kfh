import Foundation
import Observation
import SwiftData

/// Табель: choose month, year and employee, generate a new version, look at any saved version.
@MainActor
@Observable
final class TimesheetViewModel {
    /// Titles of the totals columns, in the order of `totalsValues`.
    static let totalsTitles = ["Явки", "Часы", "В", "Б", "О", "ОТ", "ПР", "К"]

    private(set) var month: Int
    private(set) var year: Int
    /// nil = все сотрудники.
    private(set) var employee: Employee?
    /// Used by the next «Сформировать табель»; saved versions keep their own format.
    var format: CellFormat = .codes
    /// Versions of (month, year, employee), newest first.
    private(set) var versions: [TimesheetSnapshot] = []
    private(set) var selected: TimesheetSnapshot?
    /// The selected version's document.
    private(set) var document: TimesheetDocument?
    /// Russian message of the last failed action; the view shows it and clears it.
    var errorMessage: String?

    private let context: ModelContext
    private let now: () -> Date
    private let calendar = Calendar.farm

    init(context: ModelContext, now: @escaping () -> Date = Date.init) {
        self.context = context
        self.now = now
        let parts = Calendar.farm.dateComponents([.month, .year], from: now())
        self.month = parts.month ?? 1
        self.year = parts.year ?? 2026
        reload()
    }

    /// «Ноябрь 2026».
    var title: String { calendar.monthTitle(month: month, year: year) }

    /// Shown while the chosen month has no version yet.
    var emptyText: String { "Табель за \(title) ещё не сформирован" }

    func next() { shift(by: 1) }

    func previous() { shift(by: -1) }

    func show(month: Int, year: Int) {
        self.month = month
        self.year = year
        reload()
    }

    /// nil = все сотрудники.
    func choose(_ e: Employee?) {
        employee = e
        reload()
    }

    /// Choices of the employee menu: everyone employed during the chosen month, archived ones too.
    func employees() -> [Employee] {
        let first = calendar.date(day: 1, month: month, year: year)
        let last = calendar.date(day: calendar.daysInMonth(month: month, year: year), month: month, year: year)
        return EmployeeService(context: context).employed(from: first, through: last)
    }

    /// «Сформировать табель»: a new version from the current records, then shows it.
    func generate() {
        do {
            let snapshot = try service().generate(month: month, year: year, employee: employee, format: format)
            versions = service().versions(month: month, year: year, employee: employee)
            select(snapshot)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func select(_ s: TimesheetSnapshot) {
        do {
            document = try service().document(s)
            selected = s
        } catch {
            errorMessage = "Не удалось открыть версию \(s.version)"
        }
    }

    /// Reads the versions of the current choice and shows the newest. An employee who did not work
    /// in the chosen month falls back to «Все сотрудники».
    func reload() {
        if let e = employee, !employees().contains(where: { $0.id == e.id }) {
            employee = nil
        }
        versions = service().versions(month: month, year: year, employee: employee)
        selected = nil
        document = nil
        if let newest = versions.first { select(newest) }
    }

    /// «Версия 2 · 06.11.2026 14:05».
    func versionTitle(_ s: TimesheetSnapshot) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ru_RU")
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "dd.MM.yyyy HH:mm"
        return "Версия \(s.version) · \(formatter.string(from: s.createdAt))"
    }

    /// Values of the totals columns, matching `totalsTitles`; hours as a decimal with a comma.
    static func totalsValues(_ t: TimesheetTotals) -> [String] {
        [String(t.presentDays), decimalHoursText(minutes: t.minutes), String(t.dayOff), String(t.sick),
         String(t.vacation), String(t.timeOff), String(t.absent), String(t.businessTrip)]
    }

    private func service() -> TimesheetService {
        TimesheetService(context: context, now: now)
    }

    private func shift(by delta: Int) {
        let first = calendar.date(day: 1, month: month, year: year)
        guard let moved = calendar.date(byAdding: .month, value: delta, to: first) else { return }
        let parts = calendar.dateComponents([.month, .year], from: moved)
        show(month: parts.month ?? month, year: parts.year ?? year)
    }
}
