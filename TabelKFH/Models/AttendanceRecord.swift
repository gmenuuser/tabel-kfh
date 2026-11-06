import Foundation
import SwiftData

/// One employee's mark for one day. Uniqueness of (employeeId, day) is kept by AttendanceService.
@Model
final class AttendanceRecord {
    @Attribute(.unique) var id: UUID
    var employeeId: UUID
    /// Set after both objects are in the same context; the inverse is `Employee.records`.
    var employee: Employee?
    /// Start of the local day of the arrival, or of the day the status applies to.
    var day: Date
    var arrivalTime: Date?
    var departureTime: Date?
    var breakMinutes: Int
    /// nil = незавершён (no departure yet).
    var workedMinutes: Int?
    /// Owner's override of the worked minutes.
    var manualWorkedMinutes: Int?
    var statusRaw: String
    var comment: String
    var createdAt: Date
    var updatedAt: Date

    init(id: UUID = UUID(),
         employeeId: UUID,
         day: Date,
         arrivalTime: Date? = nil,
         departureTime: Date? = nil,
         breakMinutes: Int = 0,
         workedMinutes: Int? = nil,
         manualWorkedMinutes: Int? = nil,
         status: DayStatus = .present,
         comment: String = "",
         createdAt: Date = .now,
         updatedAt: Date = .now) {
        self.id = id
        self.employeeId = employeeId
        self.employee = nil
        self.day = day
        self.arrivalTime = arrivalTime
        self.departureTime = departureTime
        self.breakMinutes = breakMinutes
        self.workedMinutes = workedMinutes
        self.manualWorkedMinutes = manualWorkedMinutes
        self.statusRaw = status.rawValue
        self.comment = comment
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var status: DayStatus {
        get { DayStatus(rawValue: statusRaw) ?? .present }
        set { statusRaw = newValue.rawValue }
    }
}
