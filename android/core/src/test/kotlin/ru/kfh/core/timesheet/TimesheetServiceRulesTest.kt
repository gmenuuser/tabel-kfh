package ru.kfh.core.timesheet

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.kfh.core.model.TimesheetSnapshot

/**
 * Port of the domain part of TimesheetServiceTests.swift: which employees and records go into a document, the
 * unfinished-day rule, versions and the month check. Skipped (persistence): frozen documentData bytes, the
 * employeeData JSON, FarmSettings rows.
 */
class TimesheetServiceRulesTest {
    private val farm = farmIn()
    private val nov = YearMonth.of(2026, 11)
    private val clock = farm.at(1, 10, month = 12)

    private fun generate(
        employees: List<ru.kfh.core.model.Employee>, records: List<ru.kfh.core.model.AttendanceRecord>,
        only: String? = null, format: CellFormat = CellFormat.CODES, version: Int = 1, month: YearMonth = nov,
    ) = TimesheetBuilder.build(month, employees, emptyList(), records, only, FarmInfo.DEFAULT, format, version, clock,
        farm, TimesheetKind.CODES)

    @Test fun generatesFromRecordsWithDefaultFarm() {
        val e = farm.employee("Зубахин", patronymic = "Петрович", position = "Тракторист")
        val records = listOf(
            farm.record(e, day(2), arrival = farm.at(2, 8), departure = farm.at(2, 17), breakMinutes = 60, worked = 480),
            farm.record(e, day(3), arrival = farm.at(3, 8), departure = farm.at(3, 16), breakMinutes = 30, worked = 450),
            farm.record(e, day(4), status = "Б"),
            farm.record(e, day(5), arrival = farm.at(5, 8)),
            farm.record(e, day(6), arrival = farm.at(6, 8), manual = 240),
        )
        val doc = generate(listOf(e), records, format = CellFormat.CODES_AND_HOURS)
        assertEquals(1, doc.version)
        assertEquals(clock, doc.createdAt)
        assertEquals("КФХ Зубахин", doc.farmName)
        assertEquals("", doc.managerName)
        assertEquals("", doc.responsiblePerson)
        assertEquals(1, doc.rows.size)
        val cells = doc.rows[0].cells
        assertEquals(30, cells.size)
        assertEquals(listOf("", "Я", "Я", "Б", "Я", "Я"), cells.take(6).map { it.text })
        assertEquals(TimesheetTotals(presentDays = 4, minutes = 480 + 450 + 240, sick = 1), doc.rows[0].totals)
        assertEquals(listOf("Зубахин И. П. — 5 ноября"), doc.unfinished)
        assertEquals(doc.rows[0].totals, doc.farmTotals)
    }

    @Test fun snapshotOfARecord() {
        val e = farm.employee("Зубахин")
        val closed = TimesheetBuilder.snapshot(farm.record(e, day(2), arrival = farm.at(2, 8),
            departure = farm.at(2, 17), worked = 480), farm)
        assertEquals(RecordSnapshot(e.id, day(2), DayStatus.PRESENT, 480, false), closed)
        val open = TimesheetBuilder.snapshot(farm.record(e, day(5), arrival = farm.at(5, 8)), farm)
        assertEquals(RecordSnapshot(e.id, day(5), DayStatus.PRESENT, null, true), open)
        val manual = TimesheetBuilder.snapshot(farm.record(e, day(6), arrival = farm.at(6, 8), manual = 240), farm)
        assertEquals(RecordSnapshot(e.id, day(6), DayStatus.PRESENT, 240, false), manual)
        val sick = TimesheetBuilder.snapshot(farm.record(e, day(7), status = "Б"), farm)
        assertEquals(RecordSnapshot(e.id, day(7), DayStatus.SICK, null, false), sick)
    }

    @Test fun oneEmployeeTimesheetHasOneRow() {
        val a = farm.employee("Андреев")
        val b = farm.employee("Борисов")
        val records = listOf(
            farm.record(a, day(2), arrival = farm.at(2, 8), departure = farm.at(2, 17), worked = 480),
            farm.record(b, day(2), arrival = farm.at(2, 8), departure = farm.at(2, 12), breakMinutes = 60, worked = 180),
        )
        val doc = generate(listOf(a, b), records, only = b.id)
        assertEquals(1, doc.rows.size)
        assertEquals(1, doc.rows[0].number)
        assertEquals(b.id, doc.rows[0].employee.id)
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 180), doc.rows[0].totals)
        assertEquals(doc.rows[0].totals, doc.farmTotals)
    }

    @Test fun versionsAreNumberedPerMonthYearAndEmployee() {
        fun snap(v: Int, month: Int = 11, employee: String? = null) =
            TimesheetSnapshot(id = "s$v-$month-$employee", month = month, year = 2026, version = v, employeeId = employee)
        val all = listOf(snap(1), snap(2), snap(1, employee = "b"), snap(1, month = 10))
        assertEquals(listOf(2, 1), TimesheetVersions.versions(all, 11, 2026, null).map { it.version })
        assertEquals(listOf(1), TimesheetVersions.versions(all, 11, 2026, "b").map { it.version })
        assertEquals(0, TimesheetVersions.versions(all, 11, 2026, "a").size)
        assertEquals(0, TimesheetVersions.versions(all, 9, 2026, null).size)
        assertEquals(3, TimesheetVersions.next(all, 11, 2026, null))
        assertEquals(2, TimesheetVersions.next(all, 11, 2026, "b"))
        assertEquals(1, TimesheetVersions.next(all, 11, 2026, "a"))
    }

    @Test fun rowsAreEmployeesOfTheMonthIncludingArchived() {
        val archived = farm.employee("Архипов", dismissal = day(15), archived = true)
        val future = farm.employee("Будущий", hire = day(1, 12))
        val gone = farm.employee("Ушедший", dismissal = day(31, 10))
        val hired = farm.employee("Васильев", hire = day(10))
        val records = listOf(farm.record(archived, day(3), arrival = farm.at(3, 8), departure = farm.at(3, 17), worked = 480))
        val doc = generate(listOf(archived, future, gone, hired), records)
        assertEquals(listOf("Архипов Иван", "Васильев Иван"), doc.rows.map { it.employee.fullName })
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 480), doc.rows[0].totals)
        assertEquals(15, doc.rows[0].cells.count { it.employed })
        assertEquals(hired.id, doc.rows[1].employee.id)
        assertEquals(21, doc.rows[1].cells.count { it.employed })
    }

    @Test fun invalidMonthThrows() {
        assertEquals(TimesheetError.InvalidMonth, assertThrows(TimesheetError::class.java) { TimesheetVersions.checkMonth(13, 2026) })
        assertEquals("Неверный месяц", TimesheetError.InvalidMonth.message)
        assertThrows(TimesheetError::class.java) { TimesheetVersions.checkMonth(0, 2026) }
        assertThrows(TimesheetError::class.java) { TimesheetVersions.checkMonth(11, 0) }
        assertEquals(YearMonth.of(2026, 11), TimesheetVersions.checkMonth(11, 2026))
    }

    /** A last-day record whose `day` is not exactly midnight (old data, a restored backup) is kept. */
    @Test fun lastDayRecordAfterMidnightIsKept() {
        val e = farm.employee("Зубахин")
        val r = farm.record(e, day(30), status = "Б").copy(day = farm.at(30, 12))
        val doc = generate(listOf(e), listOf(r))
        assertEquals("Б", doc.rows[0].cells[29].text)
        assertEquals(TimesheetTotals(sick = 1), doc.rows[0].totals)
    }

    @Test fun twoRecordsOfOneDayTheLaterUpdateWins() {
        val e = farm.employee("Зубахин")
        val old = farm.record(e, day(4), status = "Б", id = "r1").copy(updatedAt = farm.at(4, 1))
        val newer = farm.record(e, day(4), status = "О", id = "r2").copy(updatedAt = farm.at(4, 2))
        assertEquals("О", generate(listOf(e), listOf(newer, old)).rows[0].cells[3].text)
        assertNull(generate(listOf(e), emptyList()).rows[0].cells[3].status)
    }
}
