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
    /// phone with all its changes; for a record the server has, the next pull starts over (cursor 0), so the server's
    /// values come back. Queues nothing.
    static func discard(_ op: SyncOp, in context: ModelContext) throws {
        if context.hasChanges { try context.syncedSave() }
        SyncedSave.isApplyingRemote = true
        defer { SyncedSave.isApplyingRemote = false }
        do {
            let entityName = op.entityName
            let recordId = op.recordId
            if let entity = SyncEntity(rawValue: entityName) {
                let key = SyncShadow.makeKey(entity, recordId)
                var d = FetchDescriptor<SyncShadow>(predicate: #Predicate { $0.key == key })
                d.fetchLimit = 1
                let shadow = try context.fetch(d).first
                if shadow == nil || shadow?.version == 0 {
                    // Singletons are never deleted (as in SyncEngine.apply): the screens would recreate and re-queue them.
                    if !entity.isSingleton, let model = try SyncCodec.find(entity, id: recordId, in: context) {
                        context.delete(model)
                    }
                    if let shadow { context.delete(shadow) }
                    let all = try context.fetch(FetchDescriptor<SyncOp>(predicate: #Predicate {
                        $0.entityName == entityName && $0.recordId == recordId
                    }))
                    for other in all where other.opId != op.opId { context.delete(other) }
                }
            }
            context.delete(op)
            SyncState.current(in: context).cursor = 0
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }
}
