package ru.kfh.core.timesheet

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee

/**
 * Port of the domain part of CalendarViewModelTests.swift (grid, counts, filter, day entries, month choices).
 * Skipped: the observable view model state (`month`/`year` vars, init from `now`) is UI; its month arithmetic is
 * YearMonth.plusMonths, covered below.
 */
class CalendarGridTest {
    private val farm = farmIn()
    private val today = day(6)
    private val nov = YearMonth.of(2026, 11)

    private fun cells(
        records: List<AttendanceRecord> = emptyList(), filter: Employee? = null, month: YearMonth = nov,
    ) = CalendarGrid.cells(month, today, records, filter, farm)

    private fun times(e: Employee, d: Int, from: Int, to: Int?) = farm.record(
        e, day(d), arrival = farm.at(d, from), departure = to?.let { farm.at(d, it) }, breakMinutes = 60,
        worked = to?.let { (it - from) * 60 - 60 },
    )

    @Test fun titleAndMonthMoves() {
        assertEquals("Ноябрь 2026", CalendarGrid.title(nov))
        assertEquals(YearMonth.of(2026, 12), CalendarGrid.previous(YearMonth.of(2027, 1)))
        assertEquals(YearMonth.of(2027, 1), CalendarGrid.next(YearMonth.of(2026, 12)))
    }

    // 1 November 2026 is a Sunday.
    @Test fun november2026HasSixLeadingBlanksAndThirtyDays() {
        val cells = cells()
        assertEquals(6, cells.take(6).count { it.isBlank })
        assertEquals(6, cells.count { it.isBlank })
        assertFalse(cells[6].isBlank)
        val days = cells.filter { !it.isBlank }
        assertEquals(30, days.size)
        assertEquals(day(1), days.first().day)
        assertEquals(1, days.first().number)
        assertEquals(day(30), days.last().day)
        assertEquals(cells.size, cells.map { it.id }.toSet().size)
    }

    // 1 June 2026 is a Monday.
    @Test fun mondayFirstMonthHasNoLeadingBlanks() {
        val cells = cells(month = YearMonth.of(2026, 6))
        assertEquals(0, cells.count { it.isBlank })
        assertEquals(30, cells.size)
    }

    @Test fun todayAndFutureFlags() {
        val days = cells().filter { !it.isBlank }
        assertTrue(days[5].isToday)
        assertFalse(days[5].isFuture)
        assertFalse(days[4].isFuture)
        assertFalse(days[4].isToday)
        assertTrue(days[6].isFuture)
        assertFalse(days[6].isToday)
    }

    @Test fun allEmployeesCellsCountStatuses() {
        val a = farm.employee("Иванов")
        val b = farm.employee("Петров")
        val c = farm.employee("Сидоров")
        val records = listOf(times(a, 2, 8, 17), times(b, 2, 9, 18), farm.record(c, day(2), status = "Б"),
            farm.record(a, day(1, 12), status = "В"))
        val days = cells(records).filter { !it.isBlank }
        assertEquals(mapOf(DayStatus.PRESENT to 2, DayStatus.SICK to 1), days[1].statusCounts)
        assertEquals(emptyMap<DayStatus, Int>(), days[0].statusCounts)
        assertNull(days[1].status)
        assertTrue("December marks stay in December", days.all { it.statusCounts[DayStatus.DAY_OFF] == null })
    }

    @Test fun filterShowsThatEmployeesStatusAndMinutesForEachDay() {
        val a = farm.employee("Иванов")
        val b = farm.employee("Петров")
        val records = listOf(times(a, 2, 8, 17), farm.record(a, day(3), status = "Б"), times(a, 4, 8, null),
            farm.record(b, day(5), status = "О"))
        val days = cells(records, a).filter { !it.isBlank }
        assertEquals(30, days.size)
        assertNull(days[0].status)
        assertEquals(DayStatus.PRESENT, days[1].status)
        assertEquals(480, days[1].minutes)
        assertEquals(DayStatus.SICK, days[2].status)
        assertNull(days[2].minutes)
        assertEquals(DayStatus.PRESENT, days[3].status)
        assertNull("an unfinished day has no total", days[3].minutes)
        assertNull("another employee's mark is not shown", days[4].status)
        assertTrue(days.all { it.statusCounts.isEmpty() })
    }

    @Test fun filterMarksDaysOutsideEmployment() {
        val a = farm.employee("Иванов", hire = day(10), dismissal = day(20))
        val days = cells(emptyList(), a).filter { !it.isBlank }
        assertFalse(days[8].isEmployed)
        assertTrue(days[9].isEmployed)
        assertTrue(days[19].isEmployed)
        assertFalse(days[20].isEmployed)
        assertTrue(cells().filter { !it.isBlank }.all { it.isEmployed })
    }

    @Test fun dayEntriesListEmployeesEmployedThatDayIncludingArchived() {
        val worker = farm.employee("Иванов")
        val all = listOf(
            worker, farm.employee("Архипов", hire = day(1, 1), dismissal = day(10), archived = true),
            farm.employee("Новиков", hire = day(20)), farm.employee("Уволенов", hire = day(1, 1), dismissal = day(3)),
        )
        val entries = CalendarGrid.entries(day(5), all, listOf(farm.record(worker, day(5), status = "Б")), farm)
        assertEquals(listOf("Архипов", "Иванов"), entries.map { it.employee.lastName })
        assertNull(entries[0].record)
        assertEquals("Б", entries[1].record?.status)
    }

    @Test fun employeeChoicesAreThoseEmployedDuringTheMonth() {
        val all = listOf(
            farm.employee("Иванов"), farm.employee("Архипов", dismissal = day(10), archived = true),
            farm.employee("Старов", dismissal = day(31, 10)), farm.employee("Будущев", hire = day(1, 12)),
        )
        assertEquals(listOf("Архипов", "Иванов"), CalendarGrid.employees(nov, all, farm).map { it.lastName })
    }

    @Test fun futureDaysAllowEverythingButPresence() {
        assertEquals(
            listOf(DayStatus.DAY_OFF, DayStatus.SICK, DayStatus.VACATION, DayStatus.TIME_OFF, DayStatus.ABSENT,
                DayStatus.BUSINESS_TRIP),
            DayStatus.statuses(day(7), day(6)),
        )
        assertEquals(DayStatus.entries.toList(), DayStatus.statuses(day(6), day(6)))
        assertEquals(DayStatus.entries.toList(), DayStatus.statuses(day(5), day(6)))
    }
}
