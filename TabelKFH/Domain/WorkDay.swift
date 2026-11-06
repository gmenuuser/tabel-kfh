import Foundation

extension Calendar {
    /// Gregorian, Russian locale, weeks start on Monday, in the farm's time zone (`FarmTimeZone`):
    /// every day key of the app — records, hire and dismissal dates, the timesheet — goes through it.
    static var farm: Calendar { farmCalendar }

    /// Set at launch and after a restore; the device's zone only until the app has started.
    private static var farmCalendar = Calendar.farm(timeZone: .current)

    /// The farm calendar in `timeZone` (tests build services with an explicit one).
    static func farm(timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "ru_RU")
        calendar.firstWeekday = 2
        calendar.timeZone = timeZone
        return calendar
    }

    /// Makes `Calendar.farm` use `timeZone` from now on.
    static func useFarmTimeZone(_ timeZone: TimeZone) {
        farmCalendar = farm(timeZone: timeZone)
    }

    func dayStart(_ d: Date) -> Date {
        startOfDay(for: d)
    }

    /// Midnight of the given day in this calendar's time zone.
    func date(day: Int, month: Int, year: Int) -> Date {
        let components = DateComponents(year: year, month: month, day: day)
        guard let result = self.date(from: components) else {
            preconditionFailure("Invalid date \(day).\(month).\(year)")
        }
        return startOfDay(for: result)
    }

    func daysInMonth(month: Int, year: Int) -> Int {
        let first = date(day: 1, month: month, year: year)
        return range(of: .day, in: .month, for: first)?.count ?? 0
    }

    /// «Ноябрь» for 11; "" outside 1...12.
    func monthName(_ month: Int) -> String {
        let names = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
                     "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]
        return (1...12).contains(month) ? names[month - 1] : ""
    }

    /// «Ноябрь 2026».
    func monthTitle(month: Int, year: Int) -> String {
        guard (1...12).contains(month) else { return "\(year)" }
        return "\(monthName(month)) \(year)"
    }
}
