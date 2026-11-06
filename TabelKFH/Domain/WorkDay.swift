import Foundation

extension Calendar {
    /// Gregorian, Russian locale, weeks start on Monday, device time zone.
    static let farm: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "ru_RU")
        calendar.firstWeekday = 2
        calendar.timeZone = .current
        return calendar
    }()

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

    /// «Ноябрь 2026».
    func monthTitle(month: Int, year: Int) -> String {
        let names = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
                     "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]
        guard (1...12).contains(month) else { return "\(year)" }
        return "\(names[month - 1]) \(year)"
    }
}
