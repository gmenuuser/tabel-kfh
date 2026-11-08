package ru.kfh.core.timesheet

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of DayMarkTests.swift: the timesheet of statuses (one tap for Я, a menu for the others, no times). */
class DayMarkTest {
    private val farm = farmIn()
    private val h = AttendanceHarness(farm, farm.at(6, 9, 0))
    private val nov = YearMonth.of(2026, 11)

    private fun doc(employees: List<ru.kfh.core.model.Employee>, month: YearMonth = nov) =
        farm.codesDoc(month, employees, h.records)

    @Test fun canSetRule() {
        assertFalse(DayMarkRules.canSet(DayStatus.PRESENT, day(7), day(6)))
        assertTrue(DayMarkRules.canSet(DayStatus.PRESENT, day(6), day(6)))
        assertTrue(DayMarkRules.canSet(DayStatus.PRESENT, day(5), day(6)))
        for (s in DayStatus.futureStatuses) assertTrue(DayMarkRules.canSet(s, day(7), day(6)))
    }

    @Test fun oneTapMarksPresenceAndASecondTapCancelsIt() {
        val e = farm.employee("Иванов")
        assertNull(h.status(e, day(6)))
        assertEquals(DayStatus.PRESENT, h.togglePresence(e, day(6)))
        assertEquals(DayStatus.PRESENT, h.status(e, day(6)))
        val r = h.record(e, day(6))!!
        assertNull(r.arrivalTime)
        assertNull(r.departureTime)
        assertNull(h.togglePresence(e, day(6)))
        assertNull(h.status(e, day(6)))
        assertEquals(0, h.records.size)
    }

    @Test fun statusesFromTheMenu() {
        val e = farm.employee("Петров")
        for ((d, status) in listOf(2 to DayStatus.DAY_OFF, 3 to DayStatus.SICK, 4 to DayStatus.VACATION,
            5 to DayStatus.TIME_OFF, 9 to DayStatus.BUSINESS_TRIP)) {
            h.setStatus(e, day(d), status)
            assertEquals(status, h.status(e, day(d)))
        }
        h.setStatus(e, day(6), DayStatus.PRESENT)
        h.setStatus(e, day(6), DayStatus.SICK)
        assertEquals(DayStatus.SICK, h.status(e, day(6)))
        assertEquals(DayStatus.PRESENT, h.togglePresence(e, day(6)))
        assertEquals(6, h.records.size)
        h.setStatus(e, day(6), null)
        assertNull(h.status(e, day(6)))
    }

    @Test fun pastDaysAndOnlyThatDayChanges() {
        val e = farm.employee("Сидоров")
        h.setStatus(e, day(5), DayStatus.PRESENT)
        h.setStatus(e, day(6), DayStatus.PRESENT)
        h.setStatus(e, day(20, 10), DayStatus.SICK)
        h.setStatus(e, day(6), DayStatus.DAY_OFF)
        assertEquals(DayStatus.PRESENT, h.status(e, day(5)))
        assertEquals(DayStatus.SICK, h.status(e, day(20, 10)))
        assertEquals(DayStatus.DAY_OFF, h.status(e, day(6)))
    }

    @Test fun futureDayTakesStatusesButNotPresence() {
        val e = farm.employee("Козлов")
        assertEquals(AttendanceError.FuturePresence,
            assertThrows(AttendanceError::class.java) { h.togglePresence(e, day(7)) })
        h.setStatus(e, day(7), DayStatus.VACATION)
        assertEquals(DayStatus.VACATION, h.status(e, day(7)))
        assertThrows(AttendanceError::class.java) { h.markAllPresent(listOf(e), day(7)) }
    }

    @Test fun outsideEmploymentIsRefused() {
        val e = farm.employee("Новиков", hire = day(10))
        assertEquals(AttendanceError.OutsideEmployment,
            assertThrows(AttendanceError::class.java) { h.setStatus(e, day(9), DayStatus.PRESENT) })
    }

    @Test fun markAllPresentMarksOnlyTheUnmarked() {
        val a = farm.employee("Алексеев")
        val b = farm.employee("Борисов")
        val c = farm.employee("Васильев")
        h.setStatus(b, day(6), DayStatus.SICK)
        assertEquals(2, h.markAllPresent(listOf(a, b, c), day(6)))
        assertEquals(DayStatus.PRESENT, h.status(a, day(6)))
        assertEquals(DayStatus.SICK, h.status(b, day(6)))
        assertEquals(DayStatus.PRESENT, h.status(c, day(6)))
        assertEquals(0, h.markAllPresent(listOf(a, b, c), day(6)))
    }

    /** An old record with arrival and departure keeps them while it stays Я; the timesheet shows only «Я». */
    @Test fun oldTimedRecordsShowAsPresence() {
        val e = farm.employee("Старый")
        val r = farm.record(e, day(3), arrival = farm.at(3, 8), departure = farm.at(3, 17), worked = 480)
        h.put(r)
        h.setStatus(e, day(3), DayStatus.PRESENT)
        assertNotNull(h.records.single().arrivalTime)
        val d = doc(listOf(e))
        assertEquals("Я", d.rows[0].cells[2].text)
        assertEquals(1, d.rows[0].totals.presentDays)
        assertEquals(listOf("Я", "В", "Б", "О", "ОТ", "ПР", "К"), TimesheetTotals.columnTitles)
        h.setStatus(e, day(3), DayStatus.DAY_OFF)
        assertNull(h.records.single().arrivalTime)
    }

    @Test fun monthTimesheetOfEveryLength() {
        val e = farm.employee("Иванов", hire = day(1, 1, 2026))
        h.clock = farm.at(1, 0, month = 3, year = 2029)
        for ((month, year, days) in listOf(Triple(2, 2026, 28), Triple(2, 2028, 29), Triple(11, 2026, 30),
            Triple(12, 2026, 31))) {
            h.setStatus(e, day(1, month, year), DayStatus.PRESENT)
            h.setStatus(e, day(2, month, year), DayStatus.DAY_OFF)
            h.setStatus(e, day(days, month, year), DayStatus.SICK)
            val d = doc(listOf(e), YearMonth.of(year, month))
            assertEquals(days, d.daysInMonth)
            assertEquals(days, d.rows[0].cells.size)
            assertEquals("Б", d.rows[0].cells[days - 1].text)
            assertEquals("$month.$year", listOf(1, 1, 1, 0, 0, 0, 0), d.rows[0].totals.columnValues)
            assertEquals(1, d.farmTotals.days(DayStatus.PRESENT))
        }
    }

    @Test fun archivedEmployeeStaysInOldTimesheetsAndNewOneFromHireDate() {
        val old = farm.employee("Архипов", dismissal = day(15), archived = true)
        h.setStatus(old, day(3), DayStatus.PRESENT)
        val new = farm.employee("Новенький", hire = day(20))
        val d = doc(listOf(new, old))
        assertEquals(listOf("Архипов Иван", "Новенький Иван"), d.rows.map { it.employee.fullName })
        assertEquals("Я", d.rows[0].cells[2].text)
        assertFalse(d.rows[0].cells[20].employed)
        assertFalse(d.rows[1].cells[18].employed)
        assertTrue(d.rows[1].cells[19].employed)
        val december = doc(listOf(new, old), YearMonth.of(2026, 12))
        assertEquals(listOf("Новенький Иван"), december.rows.map { it.employee.fullName })
    }

    @Test fun exportFileNameHasMonthAndYear() {
        val e = farm.employee("Иванов")
        h.setStatus(e, day(2), DayStatus.PRESENT)
        val d = doc(listOf(e))
        assertEquals("Табель_Ноябрь_2026.pdf", exportFileName(d, ext = "pdf"))
        assertEquals("Табель_Ноябрь_2026_Зубахин.xlsx", exportFileName(d, surname = "Зубахин", ext = "xlsx"))
    }
}
