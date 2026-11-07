import Foundation
import SwiftData

/// «Не принятые изменения»: the changes the server refused (`SyncOp.state == "error"`), kept to be looked at and
/// removed.
@MainActor
enum RefusedChanges {
    static func list(in context: ModelContext) -> [SyncOp] {
        let refused = "error"
        return (try? context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.state == refused },
                                                           sortBy: [SortDescriptor(\.seq)]))) ?? []
    }

    /// What was changed, in Russian (as the site's list).
    nonisolated static func title(entityName: String) -> String {
        switch entityName {
        case "employee": return "Сотрудник"
        case "hoursMark": return "Часы"
        case "attendanceRecord": return "Отметка"
        case "timesheetSnapshot": return "Версия табеля"
        case "farmSettings": return "Настройки хозяйства"
        case "coalSettings": return "Цена угля"
        case "deliveryLocation": return "Населённый пункт"
        case "driver": return "Водитель"
        case "coalSale": return "Продажа угля"
        case "driverPayment": return "Выплата"
        case "coalWorkDay": return "Рабочий день"
        case "livestockCategory": return "Категория скота"
        case "livestockOperation": return "Операция со скотом"
        case "slaughterRecord": return "Забой"
        case "livestockSettings": return "Настройки скота"
        case "agroCalculation": return "Расчёт"
        default: return entityName
        }
    }

    static func title(_ op: SyncOp) -> String {
        let what = title(entityName: op.entityName)
        return op.kind == "delete" ? "\(what): удаление" : what
    }

    /// «Удалить»: the refused change goes. A record the server never had (no shadow, or version 0) goes from the
    /// phone with all its changes, and so do the records that cannot be without it (`dependents`: an employee's
    /// hours marks, attendance and timesheet versions, a driver's payments, a category's operations, an operation's
    /// slaughter record), which would otherwise reach the server pointing at nothing. A new driver with coal sales
    /// is not removed (`RefusedChangeError.driverHasSales`). For a record the server has, the next pull starts over
    /// (cursor 0), so the server's values come back — also for any removed dependent the server has.
    static func discard(_ op: SyncOp, in context: ModelContext) throws {
        if context.hasChanges { try context.syncedSave() }
        let entityName = op.entityName
        let recordId = op.recordId
        let entity = SyncEntity(rawValue: entityName)
        var neverOnServer = false
        if let entity {
            let key = SyncShadow.makeKey(entity, recordId)
            var d = FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })
            d.fetchLimit = 1
            let shadow = try context.fetch(d).first
            neverOnServer = shadow == nil || shadow?.version == 0
        }
        if neverOnServer, entity == .driver {
            // Sales are the farm's money and a hired sale needs its driver (the server refuses one without): the
            // person moves them to another driver first, nothing is unlinked here.
            let driverId: UUID? = recordId
            let sales = try context.fetchCount(FetchDescriptor<CoalSale>(predicate: #Predicate { $0.driverId == driverId }))
            if sales > 0 { throw RefusedChangeError.driverHasSales(sales) }
        }
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        do {
            if neverOnServer, let entity {
                for link in dependents[entity] ?? [] {
                    for childId in try linkedIds(link.entity, field: link.field, to: recordId, in: context) {
                        try purge(link.entity, id: childId, in: context)
                    }
                }
                // Singletons are never deleted (as in SyncEngine.apply): the screens would recreate and re-queue them.
                if !entity.isSingleton, let model = try SyncCodec.find(entity, id: recordId, in: context) {
                    context.delete(model)
                }
                try dropQueue(entity, id: recordId, except: op.opId, in: context)
            }
            context.delete(op)
            SyncState.current(in: context).cursor = 0
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    /// The records that cannot be without a record of the entity: the child entity and its link field.
    static let dependents: [SyncEntity: [(entity: SyncEntity, field: String)]] = [
        .employee: [(.hoursMark, "employeeId"), (.attendanceRecord, "employeeId"), (.timesheetSnapshot, "employeeId")],
        .driver: [(.driverPayment, "driverId")],
        .livestockCategory: [(.livestockOperation, "categoryId"), (.livestockOperation, "targetCategoryId")],
        .livestockOperation: [(.slaughterRecord, "operationId")],
    ]

    /// The record, its dependents, all their queued changes and shadows go (the caller has set isApplyingRemote).
    private static func purge(_ entity: SyncEntity, id: UUID, in context: ModelContext) throws {
        for link in dependents[entity] ?? [] {
            for childId in try linkedIds(link.entity, field: link.field, to: id, in: context) {
                try purge(link.entity, id: childId, in: context)
            }
        }
        if let model = try SyncCodec.find(entity, id: id, in: context) { context.delete(model) }
        try dropQueue(entity, id: id, except: nil, in: context)
    }

    private static func dropQueue(_ entity: SyncEntity, id: UUID, except kept: UUID?, in context: ModelContext) throws {
        let key = SyncShadow.makeKey(entity, id)
        for shadow in try context.fetch(FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })) {
            context.delete(shadow)
        }
        let name = entity.rawValue
        let ops = try context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.entityName == name && $0.recordId == id }))
        for op in ops where op.opId != kept { context.delete(op) }
    }

    /// The ids of the records of `entity` whose `field` is `parent`: the rows on the phone, and the records known
    /// only by their shadow or queued change (an hours mark removed before it was sent has no row).
    static func linkedIds(_ entity: SyncEntity, field: String, to parent: UUID, in context: ModelContext) throws -> Set<UUID> {
        let link = SyncValue.string(parent.uuidString.lowercased())
        var ids = Set<UUID>()
        let prefix = entity.rawValue + ":"
        for shadow in try context.fetch(FetchDescriptor<SyncShadow>()) where shadow.key.hasPrefix(prefix) {
            if shadow.fields[field] == link, let id = UUID(uuidString: String(shadow.key.dropFirst(prefix.count))) {
                ids.insert(id)
            }
        }
        let name = entity.rawValue
        for op in try context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate { $0.entityName == name }))
        where op.fields[field] == link {
            ids.insert(op.recordId)
        }
        let id = parent
        let some: UUID? = parent
        switch (entity, field) {
        case (.hoursMark, "employeeId"):
            ids.formUnion(try context.fetch(FetchDescriptor<WorkHoursMark>(predicate: #Predicate { $0.employeeId == id })).map(\.id))
        case (.attendanceRecord, "employeeId"):
            ids.formUnion(try context.fetch(FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.employeeId == id })).map(\.id))
        case (.timesheetSnapshot, "employeeId"):
            ids.formUnion(try context.fetch(FetchDescriptor<TimesheetSnapshot>(predicate: #Predicate { $0.employeeId == some })).map(\.id))
        case (.driverPayment, "driverId"):
            ids.formUnion(try context.fetch(FetchDescriptor<DriverPayment>(predicate: #Predicate { $0.driverId == id })).map(\.id))
        case (.livestockOperation, "categoryId"):
            ids.formUnion(try context.fetch(FetchDescriptor<LivestockOperation>(predicate: #Predicate { $0.categoryId == id })).map(\.id))
        case (.livestockOperation, "targetCategoryId"):
            ids.formUnion(try context.fetch(FetchDescriptor<LivestockOperation>(predicate: #Predicate { $0.targetCategoryId == some })).map(\.id))
        case (.slaughterRecord, "operationId"):
            ids.formUnion(try context.fetch(FetchDescriptor<SlaughterRecord>(predicate: #Predicate { $0.operationId == id })).map(\.id))
        default:
            break
        }
        return ids
    }
}

enum RefusedChangeError: LocalizedError, Equatable {
    case driverHasSales(Int)

    var errorDescription: String? {
        switch self {
        case .driverHasSales(let n):
            return "У этого водителя \(CoalText.sales(n)) угля. "
                + "Сначала выберите в них другого водителя, потом удалите."
        }
    }
}
