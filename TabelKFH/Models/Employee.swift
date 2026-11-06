import Foundation
import SwiftData

@Model
final class Employee {
    @Attribute(.unique) var id: UUID
    var lastName: String
    var firstName: String
    var patronymic: String
    var position: String
    var hireDate: Date
    var dismissalDate: Date?
    var isArchived: Bool
    var workScheduleRaw: String
    /// Norm of working hours per day.
    var standardWorkHours: Double
    var defaultBreakMinutes: Int
    @Attribute(.externalStorage) var photo: Data?
    @Relationship(deleteRule: .cascade, inverse: \AttendanceRecord.employee)
    var records: [AttendanceRecord] = []

    init(id: UUID = UUID(),
         lastName: String,
         firstName: String,
         patronymic: String = "",
         position: String = "",
         hireDate: Date,
         dismissalDate: Date? = nil,
         isArchived: Bool = false,
         workSchedule: WorkSchedule = .fiveTwo,
         standardWorkHours: Double = 8,
         defaultBreakMinutes: Int = 60,
         photo: Data? = nil) {
        self.id = id
        self.lastName = lastName
        self.firstName = firstName
        self.patronymic = patronymic
        self.position = position
        self.hireDate = hireDate
        self.dismissalDate = dismissalDate
        self.isArchived = isArchived
        self.workScheduleRaw = workSchedule.rawValue
        self.standardWorkHours = standardWorkHours
        self.defaultBreakMinutes = defaultBreakMinutes
        self.photo = photo
    }

    var workSchedule: WorkSchedule {
        get { WorkSchedule(rawValue: workScheduleRaw) ?? .fiveTwo }
        set { workScheduleRaw = newValue.rawValue }
    }

    /// «Фамилия Имя Отчество», trimmed, single spaces, empty parts skipped.
    var fullName: String {
        [lastName, firstName, patronymic]
            .joined(separator: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    /// First letters of the first and last name, e.g. «ИЗ».
    var initials: String {
        let letters = [firstName, lastName].compactMap { part in
            part.trimmingCharacters(in: .whitespacesAndNewlines).first.map(String.init)
        }
        return letters.joined().uppercased()
    }
}
