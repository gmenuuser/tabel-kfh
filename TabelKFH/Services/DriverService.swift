import Foundation
import SwiftData

/// The fields of the driver form; trimmed when saved.
struct DriverDraft: Equatable {
    var lastName = ""
    var firstName = ""
    var middleName = ""
    var phone = ""
    var comment = ""

    var validationErrors: [String] {
        Self.trimmed(lastName).isEmpty ? ["Укажите фамилию водителя"] : []
    }

    var isValid: Bool { validationErrors.isEmpty }

    static func trimmed(_ s: String) -> String {
        s.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}

extension DriverDraft {
    init(_ d: Driver) {
        self.init(lastName: d.lastName, firstName: d.firstName, middleName: d.middleName, phone: d.phone,
                  comment: d.comment)
    }
}

/// Hired drivers: add, edit, archive, restore, delete (only without history), and their accounts.
@MainActor
final class DriverService {
    private let context: ModelContext
    private let now: () -> Date

    init(context: ModelContext, now: @escaping () -> Date = AppClock.now) {
        self.context = context
        self.now = now
    }

    @discardableResult
    func add(_ draft: DriverDraft) throws -> Driver {
        guard draft.isValid else { throw CoalError.invalid(draft.validationErrors) }
        let d = Driver(lastName: "", createdAt: now())
        apply(draft, to: d)
        context.insert(d)
        try context.save()
        return d
    }

    /// A new name is written into the driver's sales and payments too: it is the same person.
    func update(_ d: Driver, with draft: DriverDraft) throws {
        guard draft.isValid else { throw CoalError.invalid(draft.validationErrors) }
        apply(draft, to: d)
        let name = d.fullName
        for s in try sales(of: d) where s.driverNameSnapshot != name { s.driverNameSnapshot = name }
        for p in try payments(of: d) where p.driverNameSnapshot != name { p.driverNameSnapshot = name }
        try context.save()
    }

    func archive(_ d: Driver) throws {
        d.isArchived = true
        try context.save()
    }

    func restore(_ d: Driver) throws {
        d.isArchived = false
        try context.save()
    }

    func hasHistory(_ d: Driver) throws -> Bool {
        try !sales(of: d).isEmpty || !payments(of: d).isEmpty
    }

    /// Only a driver without deliveries and payments is deleted; the others are archived instead.
    func delete(_ d: Driver) throws {
        if try hasHistory(d) { throw CoalError.driverHasHistory }
        context.delete(d)
        try context.save()
    }

    /// Drivers for the picker: not archived, by surname; `search` matches the start of any name part
    /// («мун» → Мунгалов).
    func active(search: String = "") throws -> [Driver] {
        try all().filter { !$0.isArchived && Self.matches($0, search) }
    }

    /// Everyone, archived ones last, each group by surname.
    func all() throws -> [Driver] {
        try context.fetch(FetchDescriptor<Driver>()).sorted {
            if $0.isArchived != $1.isArchived { return !$0.isArchived }
            return $0.fullName.compare($1.fullName, options: [.caseInsensitive],
                                       locale: Locale(identifier: "ru_RU")) == .orderedAscending
        }
    }

    func driver(id: UUID) throws -> Driver? {
        try context.fetch(FetchDescriptor<Driver>(predicate: #Predicate { $0.id == id })).first
    }

    func balance(of d: Driver) throws -> DriverBalance {
        DriverBalance(driverId: d.id, sales: try sales(of: d), payments: try payments(of: d))
    }

    /// The driver's deliveries, newest first.
    func sales(of d: Driver) throws -> [CoalSale] {
        let id: UUID? = d.id
        let descriptor = FetchDescriptor<CoalSale>(predicate: #Predicate { $0.driverId == id },
                                                   sortBy: [SortDescriptor(\.saleDate, order: .reverse)])
        return try context.fetch(descriptor)
    }

    /// The driver's payments, newest first.
    func payments(of d: Driver) throws -> [DriverPayment] {
        let id = d.id
        let descriptor = FetchDescriptor<DriverPayment>(predicate: #Predicate { $0.driverId == id },
                                                        sortBy: [SortDescriptor(\.paymentDate, order: .reverse)])
        return try context.fetch(descriptor)
    }

    static func matches(_ d: Driver, _ search: String) -> Bool {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return true }
        return [d.lastName, d.firstName, d.middleName].contains {
            $0.range(of: query, options: [.caseInsensitive, .anchored], locale: Locale(identifier: "ru_RU")) != nil
        }
    }

    private func apply(_ draft: DriverDraft, to d: Driver) {
        d.lastName = DriverDraft.trimmed(draft.lastName)
        d.firstName = DriverDraft.trimmed(draft.firstName)
        d.middleName = DriverDraft.trimmed(draft.middleName)
        d.phone = draft.phone.trimmingCharacters(in: .whitespacesAndNewlines)
        d.comment = draft.comment.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
