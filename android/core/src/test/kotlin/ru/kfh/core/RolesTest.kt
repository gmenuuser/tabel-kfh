package ru.kfh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.roles.Module
import ru.kfh.core.roles.Role
import ru.kfh.core.roles.canWrite
import ru.kfh.core.roles.isReadOnly
import ru.kfh.core.roles.modules

class RolesTest {
    private val everything = setOf(Module.timesheet, Module.coal, Module.livestock, Module.agro, Module.reports)

    @Test
    fun modulesPerRole() {
        assertEquals(setOf(Module.timesheet, Module.reports), Role.timesheet.modules())
        assertEquals(setOf(Module.coal, Module.reports), Role.coal.modules())
        assertEquals(setOf(Module.livestock, Module.reports), Role.livestock.modules())
        for (r in listOf(Role.owner, Role.manager, Role.viewer)) {
            assertEquals(r.name, everything, r.modules() - Module.settings)
        }
    }

    @Test
    fun titles() {
        assertEquals("Владелец", Role.owner.title)
        assertEquals("Управляющий", Role.manager.title)
        assertEquals("Только табель", Role.timesheet.title)
        assertEquals("Только уголь", Role.coal.title)
        assertEquals("Только скот", Role.livestock.title)
        assertEquals("Только просмотр", Role.viewer.title)
    }

    @Test
    fun viewerCannotWriteAnything() {
        assertTrue(Role.viewer.isReadOnly)
        assertFalse(Role.owner.isReadOnly)
        for (e in listOf("coalSale", "employee", "farmSettings", "agroCalculation")) assertFalse(e, Role.viewer.canWrite(e))
        for (r in listOf(Role.viewer, Role.timesheet, Role.livestock)) assertFalse(r.name, r.canWrite("coalSale"))
        assertTrue(Role.coal.canWrite("coalSale"))
    }

    @Test
    fun writeRulesLikeTheServer() {
        val timesheet = listOf("employee", "hoursMark", "attendanceRecord", "timesheetSnapshot")
        val coal = listOf("coalSettings", "deliveryLocation", "driver", "coalSale", "driverPayment", "coalWorkDay")
        val livestock = listOf("livestockCategory", "livestockOperation", "slaughterRecord", "livestockSettings")
        val others = listOf("farmSettings", "agroCalculation")
        for (e in timesheet + coal + livestock + others) {
            assertTrue(e, Role.owner.canWrite(e))
            assertTrue(e, Role.manager.canWrite(e))
            assertEquals(e, e in timesheet, Role.timesheet.canWrite(e))
            assertEquals(e, e in coal, Role.coal.canWrite(e))
            assertEquals(e, e in livestock, Role.livestock.canWrite(e))
        }
        assertFalse(Role.owner.canWrite("нечто"))
    }

    /** A phone connected by itself: every module; everything but the three settings rows (the owner's, on the site). */
    @Test
    fun appSeesEverythingButWritesNoSettings() {
        assertEquals(everything, Role.app.modules() - Module.settings)
        assertFalse(Role.app.isReadOnly)
        for (e in listOf("farmSettings", "coalSettings", "livestockSettings")) assertFalse(e, Role.app.canWrite(e))
        for (e in listOf("employee", "hoursMark", "attendanceRecord", "timesheetSnapshot", "deliveryLocation", "driver",
                         "coalSale", "driverPayment", "coalWorkDay", "livestockCategory", "livestockOperation",
                         "slaughterRecord", "agroCalculation")) assertTrue(e, Role.app.canWrite(e))
        assertFalse(Role.app.canWrite("нечто"))
        assertFalse(Role.app in Role.helperRoles)
    }
}
