import Foundation

/// Contradictions in a day's data, shown to the owner as warnings (never blocking).
enum WorkWarning: Equatable {
    case departureBeforeArrival
    case breakLongerThanShift
    case overSixteenHours
    case timesWithNonPresentStatus
    case arrivalInFuture
    case departureInFuture
    case notFinished

    var text: String {
        switch self {
        case .departureBeforeArrival: return "Уход раньше прихода"
        case .breakLongerThanShift: return "Перерыв длиннее смены"
        case .overSixteenHours: return "Смена больше 16 часов — проверь время"
        case .timesWithNonPresentStatus: return "Указано время, но статус не «Явка»"
        case .arrivalInFuture: return "Приход в будущем"
        case .departureInFuture: return "Уход в будущем"
        case .notFinished: return "День не закрыт: нет времени ухода"
        }
    }
}

/// Worked time = departure − arrival − break, computed on real `Date` intervals.
enum WorkTimeCalculator {
    static let maxShiftMinutes = 16 * 60

    /// Whole minutes between two instants, rounded down; negative when `to` is before `from`.
    static func intervalMinutes(from: Date, to: Date) -> Int {
        Int((to.timeIntervalSince(from) / 60).rounded(.down))
    }

    /// max(0, Δ − break), Δ in whole minutes (floor).
    static func workedMinutes(arrival: Date, departure: Date, breakMinutes: Int) -> Int {
        max(0, intervalMinutes(from: arrival, to: departure) - breakMinutes)
    }

    /// The departure instant for a clock time: on the arrival day, or on the next day
    /// when that clock time is earlier than the arrival (night shift).
    static func departureDate(arrivalDay: Date, arrival: Date, departureClock: DateComponents,
                              calendar: Calendar = .farm) -> Date {
        let sameDay = clock(departureClock, on: arrivalDay, calendar: calendar)
        guard sameDay < arrival else { return sameDay }
        let nextDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: arrivalDay)) ?? arrivalDay
        return clock(departureClock, on: nextDay, calendar: calendar)
    }

    /// `day`'s date with the hour and minute of `clock`.
    static func clock(_ clock: DateComponents, on day: Date, calendar: Calendar = .farm) -> Date {
        var parts = calendar.dateComponents([.year, .month, .day], from: day)
        parts.hour = clock.hour ?? 0
        parts.minute = clock.minute ?? 0
        parts.second = 0
        return calendar.date(from: parts) ?? day
    }

    static func warnings(status: DayStatus, arrival: Date?, departure: Date?, breakMinutes: Int,
                         now: Date) -> [WorkWarning] {
        var result: [WorkWarning] = []
        if status != .present, arrival != nil || departure != nil {
            result.append(.timesWithNonPresentStatus)
        }
        if let arrival, arrival > now {
            result.append(.arrivalInFuture)
        } else if let departure, departure > now {
            // A future arrival already says the times are ahead; a past arrival with a later departure is today's
            // shift entered ahead of time.
            result.append(.departureInFuture)
        }
        if let arrival, let departure {
            let shift = intervalMinutes(from: arrival, to: departure)
            if departure < arrival {
                result.append(.departureBeforeArrival)
            } else {
                if breakMinutes > 0, breakMinutes >= shift {
                    result.append(.breakLongerThanShift)
                }
                if shift > maxShiftMinutes {
                    result.append(.overSixteenHours)
                }
            }
        }
        if status == .present, arrival != nil, departure == nil {
            result.append(.notFinished)
        }
        return result
    }

    /// manualWorkedMinutes ?? workedMinutes ?? 0. An unfinished day counts 0.
    static func effectiveMinutes(_ r: AttendanceRecord) -> Int {
        r.manualWorkedMinutes ?? r.workedMinutes ?? 0
    }
}
