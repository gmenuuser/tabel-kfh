package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.model.HoursMark

class HoursHarness(val farm: ru.kfh.core.time.FarmTime, var clock: Instant) {
    val marks = mutableListOf<HoursMark>()
    val rules = HoursMarkRules(farm) { clock }

    fun apply(change: HoursChange) {
        marks.removeAll { m -> change.delete.any { it.id == m.id } }
        change.put?.let { p ->
            val i = marks.indexOfFirst { it.id == p.id }
            if (i >= 0) marks[i] = p else marks.add(p)
        }
    }

    fun set(hours: Int?, e: Employee, d: LocalDate) = apply(rules.set(hours, e, marks, d))
    fun tap(hours: Int, e: Employee, d: LocalDate): Int? {
        val change = rules.tap(hours, e, marks, d)
        apply(change)
        return change.hours
    }
    fun hours(e: Employee, d: LocalDate) = rules.hoursOf(e, marks, d)
}

/** Port of HoursTests.swift (the domain part: marks, totals, timesheet of hours). */
class HoursTest {
    private val farm = farmIn()
    private val h = HoursHarness(farm, farm.at(20, 9, 0))
    private val nov = YearMonth.of(2026, 11)

    private fun month(employees: List<Employee>, attendance: List<AttendanceRecord> = emptyList()) =
        TimesheetBuilder.build(nov, employees, h.marks, attendance, null, farm = farm, createdAt = h.clock)

    @Test fun allowedValues() {
        assertEquals(listOf(0, 4, 8, 12), HoursRules.allowed)
        assertTrue(HoursRules.isValid(12))
        assertFalse(HoursRules.isValid(6))
    }

    @Test fun eightIsSavedAndFourReplacesIt() {
        val e = farm.employee("Иванов")
        h.set(8, e, day(5))
        assertEquals(8, h.hours(e, day(5)))
        h.set(4, e, day(5))
        assertEquals(4, h.hours(e, day(5)))
        assertEquals(1, h.marks.size)
        assertEquals(HoursError.InvalidHours, assertThrows(HoursError::class.java) { h.set(6, e, day(5)) })
    }

    @Test fun theButtonsReplaceAndTheSelectedOneRemoves() {
        val e = farm.employee("Петров")
        assertEquals(8, h.tap(8, e, day(5)))
        assertEquals(12, h.tap(12, e, day(5)))
        assertNull(h.tap(12, e, day(5)))
        assertNull(h.hours(e, day(5)))
        assertEquals(0, h.marks.size)
    }

    @Test fun monthTotalsAndEmptyIsNotZero() {
        val e = farm.employee("Сидоров")
        h.set(8, e, day(2)); h.set(8, e, day(3)); h.set(4, e, day(4)); h.set(12, e, day(5)); h.set(0, e, day(6))
        val doc = month(listOf(e))
        val t = doc.rows[0].hoursTotals!!
        assertEquals(32, t.hours)
        assertEquals(4, t.workedDays)
        assertEquals(1, t.zero)
        assertEquals(1, t.four)
        assertEquals(2, t.eight)
        assertEquals(1, t.twelve)
        assertEquals("0", doc.rows[0].cells[5].text)
        assertEquals("", doc.rows[0].cells[6].text)
        assertNull(doc.rows[0].cells[6].hours)
        assertEquals(32, doc.farmHoursTotals?.hours)
        assertEquals(listOf("Часов", "Раб. дней", "0 ч", "4 ч", "8 ч", "12 ч"), doc.totalsTitles)
        assertEquals(listOf(32, 4, 1, 1, 2, 1), doc.totalsValues(doc.rows[0]))
        assertTrue(doc.isHours)
    }

    @Test fun editingAPastDayRecountsTheMonth() {
        val e = farm.employee("Козлов")
        h.set(8, e, day(2)); h.set(8, e, day(3))
        assertEquals(16, month(listOf(e)).rows[0].hoursTotals?.hours)
        h.set(12, e, day(2))
        assertEquals(20, month(listOf(e)).rows[0].hoursTotals?.hours)
        h.set(null, e, day(3))
        assertEquals(1, month(listOf(e)).rows[0].hoursTotals?.workedDays)
    }

    @Test fun archivedEmployeeKeepsTheTimesheetAndNewOneStartsAtHire() {
        val old = farm.employee("Архипов", archived = true)
        h.set(8, old, day(3))
        val new = farm.employee("Новиков", hire = day(10))
        assertEquals(HoursError.OutsideEmployment, assertThrows(HoursError::class.java) { h.set(8, new, day(9)) })
        val doc = month(listOf(new, old))
        assertEquals(listOf("Архипов Иван", "Новиков Иван"), doc.rows.map { it.employee.fullName })
        assertEquals(8, doc.rows[0].hoursTotals?.hours)
        assertFalse(doc.rows[1].cells[8].employed)
        assertTrue(doc.rows[1].cells[9].employed)
    }

    @Test fun oldStatusRecordsStayAndAreNotHours() {
        val e = farm.employee("Старый")
        val r = farm.record(e, day(4), arrival = farm.at(4, 8), departure = farm.at(4, 17), worked = 480)
        val sick = farm.record(e, day(5), status = "Б")
        val doc = month(listOf(e), listOf(r, sick))
        assertEquals("", doc.rows[0].cells[3].text)
        assertEquals("", doc.rows[0].cells[4].text)
        assertEquals(0, doc.rows[0].hoursTotals?.hours)
        h.set(8, e, day(4))
        assertEquals(480, r.workedMinutes)
        assertEquals("8", month(listOf(e), listOf(r, sick)).rows[0].cells[3].text)
    }

    @Test fun markUnmarkedFillsOnlyEmptyDaysAndSkipsOutsideEmployment() {
        val a = farm.employee("Алексеев")
        val b = farm.employee("Борисов")
        val late = farm.employee("Поздний", hire = day(10))
        h.set(4, a, day(6))
        val made = h.rules.markUnmarked(listOf(a, b, late), h.marks, 8, day(6))
        assertEquals(1, made.size)
        made.forEach { m -> h.apply(HoursChange(m, emptyList())) }
        assertEquals(4, h.hours(a, day(6)))
        assertEquals(8, h.hours(b, day(6)))
        assertNull(h.hours(late, day(6)))
        assertEquals(HoursError.InvalidHours,
            assertThrows(HoursError::class.java) { h.rules.markUnmarked(listOf(a), h.marks, 5, day(6)) })
    }

    @Test fun markIdIsStable() {
        val e = farm.employee("Сидоров")
        h.set(8, e, day(5)); h.set(null, e, day(5)); h.set(4, e, day(5))
        assertEquals(StableId.hoursMark(e.id, day(5)), h.marks.single().id)
        assertEquals(farm.startOf(day(5)), h.marks.single().day)
    }

    @Test fun extraRowsOfOneDayAreRemovedAndTheLatestWins() {
        val e = farm.employee("Двойной")
        val early = HoursMark("m1", e.id, farm.startOf(day(5)), 4, farm.at(5, 1), farm.at(5, 1))
        val late = HoursMark("m2", e.id, farm.startOf(day(5)), 12, farm.at(5, 2), farm.at(5, 2))
        h.marks += listOf(early, late)
        assertEquals(12, h.hours(e, day(5)))
        val change = h.rules.set(8, e, h.marks, day(5))
        assertEquals("m2", change.put?.id)
        assertEquals(listOf("m1"), change.delete.map { it.id })
        assertEquals(12, month(listOf(e)).rows[0].hoursTotals?.hours)
    }

    @Test fun legendTextOfHoursTimesheet() {
        val doc = month(listOf(farm.employee("Иванов")))
        assertEquals(
            "Обозначения: число — отработанные часы за день (0, 4, 8 или 12); пустая клетка — отметки нет " +
                "(это не 0 часов). «Раб. дней» — дни с ненулевыми часами.",
            doc.legendText,
        )
    }
}
