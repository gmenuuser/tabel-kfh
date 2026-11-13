package ru.kfh.core.roles

/** The roles of the farm (server/src/entities.ts, `web/src/lib/roles.ts`, `TabelKFH/Domain/Roles.swift`). */
enum class Role(val title: String) {
    owner("Владелец"),
    manager("Управляющий"),
    timesheet("Только табель"),
    coal("Только уголь"),
    livestock("Только скот"),
    viewer("Только просмотр"),

    /**
     * A phone that connected itself (`POST /devices/register`, `GET /me` → `kind: "app"`): no account, every module,
     * everything but the farm's settings (the owner changes those on the site).
     */
    app("Приложение");

    companion object {
        /** The roles the owner can give a helper, in the order of the site's list. */
        val helperRoles = listOf(manager, timesheet, coal, livestock, viewer)

        fun fromWire(s: String): Role? = entries.firstOrNull { it.name == s }
    }
}

/** `settings` is open to everyone, so `modules()` never contains it; it is here for navigation. */
enum class Module { timesheet, coal, livestock, agro, reports, settings }

/** What the role sees. Reports exist when the role sees a module other than agro. */
fun Role.modules(): Set<Module> = when (this) {
    Role.owner, Role.manager, Role.viewer, Role.app ->
        setOf(Module.timesheet, Module.coal, Module.livestock, Module.agro, Module.reports)
    Role.timesheet -> setOf(Module.timesheet, Module.reports)
    Role.coal -> setOf(Module.coal, Module.reports)
    Role.livestock -> setOf(Module.livestock, Module.reports)
}

val Role.isReadOnly: Boolean get() = this == Role.viewer

private val timesheetEntities = setOf("employee", "hoursMark", "attendanceRecord", "timesheetSnapshot")
private val coalEntities = setOf("coalSettings", "deliveryLocation", "driver", "coalSale", "driverPayment", "coalWorkDay")
private val livestockEntities = setOf("livestockCategory", "livestockOperation", "slaughterRecord", "livestockSettings")
private val otherEntities = setOf("farmSettings", "agroCalculation")
/** The server refuses these to an app device: only the owner sets them, on the site. */
private val settingsEntities = setOf("farmSettings", "coalSettings", "livestockSettings")

/** The server's `canWrite`: an unknown entity is refused for everyone. */
fun Role.canWrite(entity: String): Boolean {
    val known = entity in timesheetEntities || entity in coalEntities || entity in livestockEntities || entity in otherEntities
    if (!known) return false
    return when (this) {
        Role.owner, Role.manager -> true
        Role.timesheet -> entity in timesheetEntities
        Role.coal -> entity in coalEntities
        Role.livestock -> entity in livestockEntities
        Role.viewer -> false
        Role.app -> entity !in settingsEntities
    }
}
