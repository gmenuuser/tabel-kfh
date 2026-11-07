import Foundation

/// A part of the farm a role may see (as `web/src/lib/roles.ts`).
enum AppModule: String, CaseIterable {
    case timesheet, coal, livestock, agro
}

/// What each role of the farm sees and may change. The same rules as the server (`canRead` / `canWrite`,
/// server/src/entities.ts) and the site: owner, manager and viewer see everything, a helper sees the module of
/// its role; the viewer changes nothing.
enum Roles {
    /// The helper roles the owner can give, in the order of the site's list.
    static let helperRoles = ["manager", "timesheet", "coal", "livestock", "viewer"]

    static func title(_ role: String) -> String {
        switch role {
        case "owner": return "Владелец"
        case "manager": return "Управляющий"
        case "timesheet": return "Только табель"
        case "coal": return "Только уголь"
        case "livestock": return "Только скот"
        case "viewer": return "Только просмотр"
        default: return role
        }
    }

    static func modules(role: String) -> [AppModule] {
        switch role {
        case "owner", "manager", "viewer": return AppModule.allCases
        case "timesheet": return [.timesheet]
        case "coal": return [.coal]
        case "livestock": return [.livestock]
        default: return []
        }
    }

    /// The big buttons of the main screen. «Отчёты» when the role sees a module that has reports (not only agro).
    static func destinations(role: String) -> [MainDestination] {
        let modules = modules(role: role)
        var result: [MainDestination] = []
        if modules.contains(.timesheet) { result.append(.timesheet) }
        if modules.contains(.coal) { result.append(.coal) }
        if modules.contains(.livestock) { result.append(.livestock) }
        if modules.contains(.agro) { result.append(.agro) }
        if modules.contains(where: { $0 != .agro }) { result.append(.reports) }
        return result
    }

    /// May the role change records of `entity` (`canWrite`).
    static func canEdit(role: String, _ entity: SyncEntity) -> Bool {
        switch role {
        case "owner", "manager": return true
        case "timesheet": return [.employee, .hoursMark, .attendanceRecord, .timesheetSnapshot].contains(entity)
        case "coal": return [.coalSettings, .deliveryLocation, .driver, .coalSale, .driverPayment, .coalWorkDay].contains(entity)
        case "livestock": return [.livestockCategory, .livestockOperation, .slaughterRecord, .livestockSettings].contains(entity)
        default: return false
        }
    }
}

/// The one line about the sync on the main screen (the texts of the site's line, `web/src/app.tsx`).
enum SyncStatusText {
    static let action = "Синхронизировать сейчас"

    static func line(_ s: SyncStatus, role: String) -> String {
        if s.syncing { return "Синхронизация…" }
        if !s.online { return "Нет связи. Ждут отправки: \(s.pending)" }
        if s.failed > 0 || s.lastError != nil { return "Ошибка синхронизации" }
        if s.conflicts > 0 {
            // Only the owner chooses the version; the others see the number.
            return role == "owner" ? "Конфликты: \(s.conflicts)" : "Конфликты: \(s.conflicts) — обратитесь к владельцу"
        }
        if s.pending > 0 { return "Ждут отправки: \(s.pending)" }
        return "Все данные синхронизированы"
    }

    /// Something is wrong (shown in the accent colour).
    static func isProblem(_ s: SyncStatus) -> Bool {
        !s.syncing && (!s.online || s.failed > 0 || s.lastError != nil || s.conflicts > 0)
    }
}
