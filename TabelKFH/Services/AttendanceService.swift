import Foundation
import SwiftData

/// What the big button on an employee's Home card does today.
enum TodayAction: Equatable {
    case markArrival
    case markDeparture(openSince: Date)
    case closed(minutes: Int)
    case status(DayStatus)
}

enum AttendanceError: LocalizedError, Equatable {
    case alreadyArrived
    case alreadyLeft
    case dayHasOtherStatus(DayStatus)
    case notArrived
    case outsideEmployment

    var errorDescription: String? {
        switch self {
        case .alreadyArrived: return "Приход уже отмечен"
        case .alreadyLeft: return "Уход уже отмечен"
        case .dayHasOtherStatus(let status): return "На этот день уже стоит статус «\(status.title)»"
        case .notArrived: return "Сначала отметьте приход"
        case .outsideEmployment: return "В этот день сотрудник не работал в хозяйстве (до приёма или после увольнения)"
        }
    }
}

/// Attendance marks. Keeps one record per (employee, day); the day of a record is the arrival day.
@MainActor
final class AttendanceService {
    private let context: ModelContext
    private let now: () -> Date
    private let calendar = Calendar.farm

    /// An arrival without a departure counts as «на работе» for at most this long.
    static let maxOpenShift: TimeInterval = 16 * 3600

    init(context: ModelContext, now: @escaping () -> Date = Date.init) {
        self.context = context
        self.now = now
    }

    private var today: Date { calendar.dayStart(now()) }

    /// The record of `day` (normalised to the start of the day).
    func record(for e: Employee, day: Date) -> AttendanceRecord? {
        let id = e.id
        let start = calendar.dayStart(day)
        var descriptor = FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.employeeId == id && $0.day == start })
        descriptor.fetchLimit = 1
        return (try? context.fetch(descriptor))?.first
    }

    /// The latest Я record from today or yesterday with an arrival no more than `maxOpenShift` ago
    /// and no departure. Yesterday counts so a night shift (or the app left open past midnight)
    /// still shows «Ушёл»; a departure forgotten long ago does not block today's arrival.
    func openRecord(for e: Employee) -> AttendanceRecord? {
        let moment = now()
        return unfinishedRecords(for: e).first { r in
            guard let arrival = r.arrivalTime else { return false }
            return moment.timeIntervalSince(arrival) <= Self.maxOpenShift
        }
    }

    /// Yesterday's Я record whose departure was never marked and that is too old to be open.
    /// It stays unfinished (0 hours) until the owner fixes it.
    func staleOpenRecord(for e: Employee) -> AttendanceRecord? {
        let moment = now()
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        return unfinishedRecords(for: e).first { r in
            guard r.day == yesterday, let arrival = r.arrivalTime else { return false }
            return moment.timeIntervalSince(arrival) > Self.maxOpenShift
        }
    }

    /// Я records of today and yesterday with an arrival and no departure, latest day first.
    private func unfinishedRecords(for e: Employee) -> [AttendanceRecord] {
        let id = e.id
        let from = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        let descriptor = FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.employeeId == id && $0.day >= from },
            sortBy: [SortDescriptor(\.day, order: .reverse)])
        let records = (try? context.fetch(descriptor)) ?? []
        return records.filter { $0.status == .present && $0.arrivalTime != nil && $0.departureTime == nil }
    }

    func todayAction(for e: Employee) -> TodayAction {
        if let open = openRecord(for: e), let since = open.arrivalTime {
            return .markDeparture(openSince: since)
        }
        guard let r = record(for: e, day: today) else { return .markArrival }
        if r.status == .present {
            return .closed(minutes: WorkTimeCalculator.effectiveMinutes(r))
        }
        return .status(r.status)
    }

    func isEmployed(_ e: Employee, on day: Date) -> Bool {
        let start = calendar.dayStart(day)
        if start < calendar.dayStart(e.hireDate) { return false }
        if let dismissal = e.dismissalDate, start > calendar.dayStart(dismissal) { return false }
        return true
    }

    /// Status Я, arrival = now, break = the employee's default.
    /// Without `force` it refuses when the employee already arrived or the day has another status;
    /// with `force` today's record is started again.
    @discardableResult
    func markArrival(_ e: Employee, force: Bool = false) throws -> AttendanceRecord {
        let moment = now()
        guard isEmployed(e, on: moment) else { throw AttendanceError.outsideEmployment }
        let existing = record(for: e, day: moment)
        if !force {
            if openRecord(for: e) != nil { throw AttendanceError.alreadyArrived }
            if let existing {
                throw existing.status == .present
                    ? AttendanceError.alreadyArrived
                    : AttendanceError.dayHasOtherStatus(existing.status)
            }
        }
        let r = existing ?? insertRecord(for: e, day: moment)
        r.status = .present
        r.arrivalTime = moment
        r.departureTime = nil
        r.workedMinutes = nil
        r.manualWorkedMinutes = nil
        r.breakMinutes = e.defaultBreakMinutes
        r.updatedAt = moment
        try context.save()
        return r
    }

    /// Closes the open record (today's or yesterday's) and computes the worked minutes.
    /// Without an open record: `force` moves the departure of today's closed day to now.
    @discardableResult
    func markDeparture(_ e: Employee, force: Bool = false) throws -> AttendanceRecord {
        let moment = now()
        let r: AttendanceRecord
        if let open = openRecord(for: e) {
            r = open
        } else if let closed = record(for: e, day: moment), closed.status == .present,
                  closed.arrivalTime != nil, closed.departureTime != nil {
            guard force else { throw AttendanceError.alreadyLeft }
            r = closed
        } else {
            throw AttendanceError.notArrived
        }
        guard let arrival = r.arrivalTime else { throw AttendanceError.notArrived }
        r.departureTime = moment
        r.workedMinutes = WorkTimeCalculator.workedMinutes(arrival: arrival, departure: moment,
                                                           breakMinutes: r.breakMinutes)
        r.manualWorkedMinutes = nil
        r.updatedAt = moment
        try context.save()
        return r
    }

    /// Creates or updates the record of (employee, day) — any day within employment.
    /// Times and the manual total are kept only for Я; other statuses are stored without them.
    @discardableResult
    func save(employee: Employee, day: Date, status: DayStatus, arrival: Date?, departure: Date?,
              breakMinutes: Int, manualMinutes: Int?, comment: String) throws -> AttendanceRecord {
        guard isEmployed(employee, on: day) else { throw AttendanceError.outsideEmployment }
        let r = record(for: employee, day: day) ?? insertRecord(for: employee, day: day)
        r.status = status
        r.breakMinutes = max(0, breakMinutes)
        r.comment = comment
        if status == .present {
            r.arrivalTime = arrival
            r.departureTime = arrival == nil ? nil : departure
            if let arrival, let departure = r.departureTime {
                r.workedMinutes = WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure,
                                                                   breakMinutes: r.breakMinutes)
            } else {
                r.workedMinutes = nil
            }
            r.manualWorkedMinutes = manualMinutes.map { max(0, $0) }
        } else {
            r.arrivalTime = nil
            r.departureTime = nil
            r.workedMinutes = nil
            r.manualWorkedMinutes = nil
        }
        r.updatedAt = now()
        try context.save()
        return r
    }

    func delete(_ r: AttendanceRecord) throws {
        context.delete(r)
        try context.save()
    }

    private func insertRecord(for e: Employee, day: Date) -> AttendanceRecord {
        let moment = now()
        let r = AttendanceRecord(employeeId: e.id, day: calendar.dayStart(day), breakMinutes: e.defaultBreakMinutes,
                                 createdAt: moment, updatedAt: moment)
        context.insert(r)
        r.employee = e
        return r
    }
}
