import Foundation
import SwiftData

/// A generated timesheet frozen at the moment of generation. Regenerating makes a new version.
@Model
final class TimesheetSnapshot {
    @Attribute(.unique) var id: UUID
    var month: Int
    var year: Int
    var version: Int
    /// nil = всё хозяйство.
    var employeeId: UUID?
    var createdAt: Date
    /// JSON of TimesheetDocument.
    var documentData: Data
    /// JSON of [EmployeeSnapshot].
    var employeeData: Data

    init(id: UUID = UUID(),
         month: Int,
         year: Int,
         version: Int,
         employeeId: UUID?,
         createdAt: Date = .now,
         documentData: Data,
         employeeData: Data) {
        self.id = id
        self.month = month
        self.year = year
        self.version = version
        self.employeeId = employeeId
        self.createdAt = createdAt
        self.documentData = documentData
        self.employeeData = employeeData
    }
}
