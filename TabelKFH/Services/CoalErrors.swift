import Foundation

/// Every refusal of the coal module, in Russian.
enum CoalError: LocalizedError, Equatable {
    case invalid([String])
    /// The day of the sale is closed; it must be opened for editing first.
    case dayClosed(Date)
    case dayAlreadyClosed
    case dayNotClosed
    /// A second tap on «Сохранить» for the same form.
    case alreadySaved
    case driverNotFound
    case driverArchived
    /// A driver with deliveries or payments is only archived.
    case driverHasHistory
    case duplicateLocation(String)
    case nothingToPay
    case paymentTooLarge(debt: Int)

    var errorDescription: String? {
        switch self {
        case .invalid(let messages):
            return messages.joined(separator: "\n")
        case .dayClosed(let day):
            return "Рабочий день \(CoalText.date(day)) завершён. Чтобы изменить его продажи, откройте день для редактирования."
        case .dayAlreadyClosed:
            return "Этот рабочий день уже завершён"
        case .dayNotClosed:
            return "Этот рабочий день не завершён"
        case .alreadySaved:
            return "Эта продажа уже сохранена"
        case .driverNotFound:
            return "Водитель не найден"
        case .driverArchived:
            return "Водитель в архиве. Верните его из архива, чтобы записывать на него доставки."
        case .driverHasHistory:
            return "У водителя есть доставки или выплаты, поэтому его можно только убрать в архив. История сохранится."
        case .duplicateLocation(let name):
            return "Населённый пункт «\(name)» уже есть"
        case .nothingToPay:
            return "Долга перед водителем нет"
        case .paymentTooLarge(let debt):
            return "Сумма больше долга перед водителем (\(CoalMath.rubles(debt)))"
        }
    }
}

/// Dates and times of the coal module on the farm calendar.
enum CoalText {
    /// «06.11.2026».
    static func date(_ d: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year], from: d)
        return String(format: "%02d.%02d.%04d", c.day ?? 0, c.month ?? 0, c.year ?? 0)
    }

    /// «14:05».
    static func time(_ d: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.hour, .minute], from: d)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// «06.11.2026 14:05».
    static func dateTime(_ d: Date, calendar: Calendar = .farm) -> String {
        date(d, calendar: calendar) + " " + time(d, calendar: calendar)
    }

    /// «пятница, 6 ноября 2026».
    static func longDate(_ d: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.day, .month, .year, .weekday], from: d)
        let weekdays = ["воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"]
        let months = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                      "октября", "ноября", "декабря"]
        let weekday = (1...7).contains(c.weekday ?? 0) ? weekdays[(c.weekday ?? 1) - 1] : ""
        let month = (1...12).contains(c.month ?? 0) ? months[(c.month ?? 1) - 1] : ""
        return "\(weekday), \(c.day ?? 0) \(month) \(c.year ?? 0)"
    }

    /// «3 доставки».
    static func deliveries(_ n: Int) -> String {
        "\(n) " + plural(n, "доставка", "доставки", "доставок")
    }

    /// «5 продаж».
    static func sales(_ n: Int) -> String {
        "\(n) " + plural(n, "продажа", "продажи", "продаж")
    }

    /// Russian plural: 1 доставка, 2 доставки, 5 доставок, 11 доставок, 21 доставка.
    static func plural(_ n: Int, _ one: String, _ few: String, _ many: String) -> String {
        let tens = abs(n) % 100, units = abs(n) % 10
        if (11...14).contains(tens) { return many }
        if units == 1 { return one }
        if (2...4).contains(units) { return few }
        return many
    }
}
