import Foundation
import Observation
import SwiftData

/// Home screen: today's header and one card per active employee.
@MainActor
@Observable
final class HomeViewModel {
    struct Row: Identifiable {
        let employee: Employee
        let action: TodayAction
        let statusText: String
        let arrivalText: String
        let departureText: String
        let hoursText: String
        /// The day the editor opens: the open record's day (yesterday for a night shift) or today.
        let editorDay: Date
        /// The day (today or yesterday) whose departure was forgotten.
        let staleOpenDay: Date?
        /// «Вчера не отмечен уход» / «Не отмечен уход» when `staleOpenDay` is set.
        let staleNoteText: String?
        var id: UUID { employee.id }
    }

    /// A repeated mark waiting for the owner's confirmation.
    struct Confirmation: Identifiable {
        enum Kind { case arrival, departure }
        let id = UUID()
        let employee: Employee
        let kind: Kind
        let message: String
        var confirmTitle: String { kind == .arrival ? "Отметить приход" : "Отметить уход" }
    }

    private(set) var farmName = "КФХ Зубахин"
    private(set) var dateTitle = ""
    private(set) var rows: [Row] = []
    private(set) var atWorkCount = 0
    var confirmation: Confirmation?
    var errorMessage: String?
    private var busy: Set<UUID> = []

    private let context: ModelContext
    private let now: () -> Date
    private let service: AttendanceService
    private let calendar = Calendar.farm

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
        self.service = AttendanceService(context: context, now: now)
    }

    var totalCount: Int { rows.count }
    var absentCount: Int { totalCount - atWorkCount }
    var employeesTitle: String { "Сотрудников: \(totalCount)" }
    var atWorkTitle: String { "На работе: \(atWorkCount)" }
    var absentTitle: String { "Отсутствуют: \(absentCount)" }

    func isBusy(_ e: Employee) -> Bool { busy.contains(e.id) }

    /// Yesterday's record with a forgotten departure, if any.
    func staleOpenRecord(for e: Employee) -> AttendanceRecord? { service.staleOpenRecord(for: e) }

    func reload() {
        let moment = now()
        let today = calendar.dayStart(moment)
        farmName = FarmSettingsStore.current(in: context).farmName
        dateTitle = Self.dateFormatter.string(from: moment)

        let employees = EmployeeService(context: context).active(on: today)

        var atWork = 0
        rows = employees.map { e in
            let open = service.openRecord(for: e)
            let todays = service.record(for: e, day: today)
            if open != nil || todays?.status == .present { atWork += 1 }
            return makeRow(e, open: open, todays: todays, today: today)
        }
        atWorkCount = atWork
    }

    /// The card's big button: acts on what the card shows («Пришёл» / «Ушёл»).
    func tapPrimary(_ e: Employee) {
        let action = rows.first { $0.employee.id == e.id }?.action ?? service.todayAction(for: e)
        switch action {
        case .markArrival, .status: tapArrival(e)
        case .markDeparture: tapDeparture(e)
        case .closed: break
        }
    }

    func tapArrival(_ e: Employee) {
        guard begin(e) else { return }
        do {
            try service.markArrival(e)
        } catch AttendanceError.alreadyArrived {
            let since = (service.openRecord(for: e) ?? service.record(for: e, day: now()))?.arrivalTime
            let message = since.map { "Приход уже отмечен в \(Self.time($0)). Отметить заново?" }
                ?? "Явка уже отмечена. Отметить заново?"
            confirmation = Confirmation(employee: e, kind: .arrival, message: message)
        } catch AttendanceError.dayHasOtherStatus(let status) {
            confirmation = Confirmation(employee: e, kind: .arrival,
                                        message: "У сотрудника сегодня «\(status.title)». Отметить приход?")
        } catch {
            errorMessage = error.localizedDescription
        }
        reload()
    }

    /// A departure this soon after the arrival is probably a mistaken tap and needs confirmation.
    static let shortShift: TimeInterval = 5 * 60

    func tapDeparture(_ e: Employee) {
        guard begin(e) else { return }
        if let since = service.openRecord(for: e)?.arrivalTime, now().timeIntervalSince(since) < Self.shortShift {
            let minutes = max(0, WorkTimeCalculator.intervalMinutes(from: since, to: now()))
            confirmation = Confirmation(employee: e, kind: .departure,
                                        message: "Уход через \(minutes) мин после прихода. Отметить уход?")
            return
        }
        do {
            try service.markDeparture(e)
        } catch AttendanceError.alreadyLeft {
            let left = service.record(for: e, day: now())?.departureTime
            let message = left.map { "Уход уже отмечен в \(Self.time($0)). Отметить заново?" }
                ?? "Уход уже отмечен. Отметить заново?"
            confirmation = Confirmation(employee: e, kind: .departure, message: message)
        } catch {
            errorMessage = error.localizedDescription
        }
        reload()
    }

    func confirm(_ c: Confirmation) {
        confirmation = nil
        do {
            switch c.kind {
            case .arrival: try service.markArrival(c.employee, force: true)
            case .departure: try service.markDeparture(c.employee, force: true)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        reload()
    }

    /// Double-tap guard: the button is disabled while the action runs and for 1 s after.
    private func begin(_ e: Employee) -> Bool {
        let id = e.id
        guard !busy.contains(id) else { return false }
        busy.insert(id)
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            self?.busy.remove(id)
        }
        return true
    }

    private func makeRow(_ e: Employee, open: AttendanceRecord?, todays: AttendanceRecord?, today: Date) -> Row {
        let shown = open ?? todays
        let action: TodayAction
        let status: String
        if let open, let since = open.arrivalTime {
            action = .markDeparture(openSince: since)
            status = "На работе"
        } else if let todays {
            if todays.status == .present {
                action = .closed(minutes: WorkTimeCalculator.effectiveMinutes(todays))
                status = todays.departureTime != nil ? "Ушёл" : todays.status.title
            } else {
                action = .status(todays.status)
                status = todays.status.title
            }
        } else {
            action = .markArrival
            status = "Не отмечен"
        }
        var hours = "—"
        if let shown, shown.status == .present, shown.workedMinutes != nil || shown.manualWorkedMinutes != nil {
            hours = durationText(minutes: WorkTimeCalculator.effectiveMinutes(shown))
        }
        let staleDay = service.staleOpenRecord(for: e)?.day
        return Row(employee: e, action: action, statusText: status,
                   arrivalText: timeText(shown?.arrivalTime, today: today),
                   departureText: timeText(shown?.departureTime, today: today),
                   hoursText: hours, editorDay: shown?.day ?? today,
                   staleOpenDay: staleDay,
                   staleNoteText: staleDay.map { $0 < today ? "Вчера не отмечен уход" : "Не отмечен уход" })
    }

    /// «08:02», or «05.11 22:00» for a time on another day.
    private func timeText(_ date: Date?, today: Date) -> String {
        guard let date else { return "—" }
        if calendar.dayStart(date) == today { return Self.time(date) }
        return Self.dayTimeFormatter.string(from: date)
    }

    static func time(_ date: Date) -> String { timeFormatter.string(from: date) }

    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = format
        return f
    }

    private static let timeFormatter = formatter("HH:mm")
    private static let dayTimeFormatter = formatter("dd.MM HH:mm")
    /// «пятница, 6 ноября 2026».
    private static let dateFormatter = formatter("EEEE, d MMMM y")
}
