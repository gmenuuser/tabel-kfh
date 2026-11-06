import Foundation

/// Status of an employee's day. Raw values are the timesheet codes.
enum DayStatus: String, Codable, CaseIterable {
    case present = "Я"
    case dayOff = "В"
    case sick = "Б"
    case vacation = "О"
    case timeOff = "ОТ"
    case absent = "ПР"
    case businessTrip = "К"

    var title: String {
        switch self {
        case .present: return "Явка"
        case .dayOff: return "Выходной"
        case .sick: return "Больничный"
        case .vacation: return "Отпуск"
        case .timeOff: return "Отгул"
        case .absent: return "Прогул"
        case .businessTrip: return "Командировка"
        }
    }
}

enum WorkSchedule: String, Codable, CaseIterable {
    case fiveTwo, sixOne, twoTwo, free

    var title: String {
        switch self {
        case .fiveTwo: return "5/2"
        case .sixOne: return "6/1"
        case .twoTwo: return "2/2"
        case .free: return "Свободный"
        }
    }
}
