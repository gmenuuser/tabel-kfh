package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of TimesheetBuilderTests.swift (the Codable round trip is persistence, see the report). */
class TimesheetBuilderTest {
    private val farm = farmIn()
    private val createdAt = Instant.ofEpochSecond(1_793_000_000)

    private fun emp(
        name: String = "Зубахин Иван Петрович", id: String = "id-$name", hire: LocalDate = day(1, 3, 2025),
        dismissal: LocalDate? = null,
    ) = EmployeeSnapshot(id, name, "Тракторист", hire, dismissal)

    private fun mark(
        e: EmployeeSnapshot, d: Int, status: DayStatus, minutes: Int? = null, unfinished: Boolean = false,
        month: Int = 11,
    ) = RecordSnapshot(
        e.id, day(d, month), status,
        if (status == DayStatus.PRESENT && !unfinished) (minutes ?: 480) else null, unfinished,
    )

    private fun build(
        employees: List<EmployeeSnapshot>, records: List<RecordSnapshot> = emptyList(), month: Int = 11,
        year: Int = 2026, format: CellFormat = CellFormat.CODES, settings: FarmInfo = FarmInfo.DEFAULT,
        version: Int = 1,
    ) = TimesheetBuilder.buildFromSnapshots(YearMonth.of(year, month), employees, records, settings, format, version,
        createdAt, farm)

    @Test fun dayColumnsFollowTheMonthLength() {
        val e = emp(hire = day(1, 1, 2020))
        for ((month, year, days) in listOf(Triple(2, 2026, 28), Triple(2, 2028, 29), Triple(11, 2026, 30),
            Triple(12, 2026, 31))) {
            val doc = build(listOf(e), month = month, year = year)
            assertEquals("$month.$year", days, doc.daysInMonth)
            assertEquals(1, doc.rows.size)
            assertEquals(days, doc.rows[0].cells.size)
            assertTrue(doc.rows[0].cells.all { it.employed })
        }
    }

    @Test fun daysBeforeHireAreBlankAndNotCounted() {
        val e = emp(hire = day(10))
        val doc = build(listOf(e), listOf(mark(e, 5, DayStatus.PRESENT), mark(e, 9, DayStatus.SICK),
            mark(e, 10, DayStatus.PRESENT), mark(e, 11, DayStatus.DAY_OFF)))
        val cells = doc.rows[0].cells
        for (i in 0 until 9) {
            assertFalse("day ${i + 1}", cells[i].employed)
            assertEquals("day ${i + 1}", "", cells[i].text)
        }
        assertNull(cells[4].status)
        assertTrue(cells[9].employed)
        assertEquals("Я", cells[9].text)
        assertEquals("В", cells[10].text)
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 480, dayOff = 1), doc.rows[0].totals)
    }

    @Test fun daysAfterDismissalAreBlankAndNotCounted() {
        val e = emp(dismissal = day(20))
        val doc = build(listOf(e), listOf(mark(e, 20, DayStatus.PRESENT), mark(e, 21, DayStatus.PRESENT),
            mark(e, 25, DayStatus.SICK)))
        val cells = doc.rows[0].cells
        assertTrue(cells[19].employed)
        assertEquals("Я", cells[19].text)
        for (i in 20 until 30) {
            assertFalse("day ${i + 1}", cells[i].employed)
            assertEquals("day ${i + 1}", "", cells[i].text)
        }
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 480), doc.rows[0].totals)
    }

    @Test fun employeesOutsideTheMonthAreNotInTheTimesheet() {
        val december = emp("Петров Пётр", hire = day(1, 12))
        val october = emp("Сидоров Сидор", hire = day(1, 1), dismissal = day(31, 10))
        val lastDay = emp("Алексеев Алексей", hire = day(30))
        val firstDay = emp("Борисов Борис", hire = day(1, 1), dismissal = day(1))
        val doc = build(listOf(december, october, lastDay, firstDay))
        assertEquals(listOf("Алексеев Алексей", "Борисов Борис"), doc.rows.map { it.employee.fullName })
        assertEquals(1, doc.rows[0].cells.count { it.employed })
        assertTrue(doc.rows[0].cells[29].employed)
        assertEquals(1, doc.rows[1].cells.count { it.employed })
        assertTrue(doc.rows[1].cells[0].employed)
    }

    @Test fun totalsOfAFullMonth() {
        val e = emp()
        val records = mutableListOf<RecordSnapshot>()
        var d = 1
        fun add(status: DayStatus, count: Int) = repeat(count) { records += mark(e, d++, status) }
        add(DayStatus.PRESENT, 20); add(DayStatus.DAY_OFF, 2); add(DayStatus.SICK, 3); add(DayStatus.VACATION, 1)
        add(DayStatus.TIME_OFF, 1); add(DayStatus.ABSENT, 1); add(DayStatus.BUSINESS_TRIP, 2)
        assertEquals(31, d)
        val doc = build(listOf(e), records)
        assertEquals(
            TimesheetTotals(presentDays = 20, minutes = 9600, dayOff = 2, sick = 3, vacation = 1, timeOff = 1,
                absent = 1, businessTrip = 2),
            doc.rows[0].totals,
        )
        assertEquals(
            (List(20) { "Я" } + listOf("В", "В", "Б", "Б", "Б", "О", "ОТ", "ПР", "К", "К")).joinToString(" "),
            doc.rows[0].cells.joinToString(" ") { it.text },
        )
        assertEquals(doc.rows[0].totals, doc.farmTotals)
    }

    @Test fun dayWithoutRecordIsBlankNeverAbsent() {
        val e = emp()
        val doc = build(listOf(e), listOf(mark(e, 2, DayStatus.PRESENT)))
        val cell = doc.rows[0].cells[0]
        assertTrue(cell.employed)
        assertNull(cell.status)
        assertEquals("", cell.text)
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 480), doc.rows[0].totals)
    }

    @Test fun unfinishedDayIsMarkedCountedAsPresenceWithoutHoursAndListed() {
        val e = emp()
        val doc = build(listOf(e), listOf(mark(e, 5, DayStatus.PRESENT, unfinished = true),
            mark(e, 6, DayStatus.PRESENT, minutes = 450)), format = CellFormat.CODES_AND_HOURS)
        val cell = doc.rows[0].cells[4]
        assertEquals("Я", cell.text)
        assertTrue(cell.unfinished)
        assertNull(cell.minutes)
        assertEquals(TimesheetTotals(presentDays = 2, minutes = 450), doc.rows[0].totals)
        assertEquals(listOf("Зубахин И. П. — 5 ноября"), doc.unfinished)
        assertEquals("Я", build(listOf(e), listOf(mark(e, 5, DayStatus.PRESENT, unfinished = true)))
            .rows[0].cells[4].text)
    }

    @Test fun farmTotalsAreTheSumOfRows() {
        val a = emp("Андреев Андрей")
        val b = emp("Борисов Борис", hire = day(15))
        val records = listOf(
            mark(a, 1, DayStatus.PRESENT, minutes = 450), mark(a, 2, DayStatus.SICK), mark(a, 3, DayStatus.ABSENT),
            mark(b, 15, DayStatus.PRESENT, minutes = 300), mark(b, 16, DayStatus.PRESENT, unfinished = true),
            mark(b, 17, DayStatus.DAY_OFF), mark(b, 18, DayStatus.BUSINESS_TRIP), mark(b, 19, DayStatus.VACATION),
            mark(b, 20, DayStatus.TIME_OFF), mark(b, 1, DayStatus.PRESENT),
        )
        val doc = build(listOf(b, a), records)
        assertEquals(listOf(1, 2), doc.rows.map { it.number })
        assertEquals(listOf("Андреев Андрей", "Борисов Борис"), doc.rows.map { it.employee.fullName })
        assertEquals(TimesheetTotals(presentDays = 1, minutes = 450, sick = 1, absent = 1), doc.rows[0].totals)
        assertEquals(
            TimesheetTotals(presentDays = 2, minutes = 300, dayOff = 1, vacation = 1, timeOff = 1, businessTrip = 1),
            doc.rows[1].totals,
        )
        assertEquals(
            TimesheetTotals(presentDays = 3, minutes = 750, dayOff = 1, sick = 1, vacation = 1, timeOff = 1,
                absent = 1, businessTrip = 1),
            doc.farmTotals,
        )
        assertEquals(listOf("Борисов Б. — 16 ноября"), doc.unfinished)
    }

    @Test fun codesAndHoursFormat() {
        val e = emp()
        val records = listOf(mark(e, 1, DayStatus.PRESENT, minutes = 450), mark(e, 2, DayStatus.PRESENT, minutes = 480),
            mark(e, 3, DayStatus.SICK), mark(e, 4, DayStatus.PRESENT, minutes = 0))
        val withHours = build(listOf(e), records, format = CellFormat.CODES_AND_HOURS).rows[0].cells
        assertEquals("Я", withHours[0].text)
        assertEquals("Я", withHours[1].text)
        assertEquals("Б", withHours[2].text)
        assertEquals("Я", withHours[3].text)
        val codesOnly = build(listOf(e), records, format = CellFormat.CODES).rows[0].cells
        assertEquals("Я", codesOnly[0].text)
        assertEquals(450, codesOnly[0].minutes)
    }

    @Test fun decimalHoursText() {
        assertEquals("0", decimalHoursText(0))
        assertEquals("8", decimalHoursText(480))
        assertEquals("7,5", decimalHoursText(450))
        assertEquals("7,75", decimalHoursText(465))
        assertEquals("7,83", decimalHoursText(470))
        assertEquals("1,02", decimalHoursText(61))
        assertEquals("160", decimalHoursText(9600))
    }

    @Test fun recordsOfOtherMonthsAndEmployeesAreIgnored() {
        val e = emp()
        val stranger = emp("Чужой Человек")
        val doc = build(listOf(e), listOf(mark(e, 5, DayStatus.PRESENT, month = 10), mark(stranger, 5, DayStatus.PRESENT)))
        assertEquals(1, doc.rows.size)
        assertEquals(TimesheetTotals(), doc.rows[0].totals)
    }

    @Test fun headerFields() {
        val settings = FarmInfo("КФХ Тест", "Зубахин И. П.", "Иванова А. А.", "с. Луговое", "+7 900 000-00-00", "ИНН 123")
        val e = emp()
        val doc = build(listOf(e), listOf(mark(e, 5, DayStatus.PRESENT, unfinished = true), mark(e, 6, DayStatus.SICK)),
            format = CellFormat.CODES_AND_HOURS, settings = settings, version = 3)
        assertEquals(11, doc.month)
        assertEquals(2026, doc.year)
        assertEquals(3, doc.version)
        assertEquals(CellFormat.CODES_AND_HOURS, doc.format)
        assertEquals("КФХ Тест", doc.farmName)
        assertEquals("Зубахин И. П.", doc.managerName)
        assertEquals("Иванова А. А.", doc.responsiblePerson)
        assertEquals("с. Луговое", doc.address)
        assertEquals("+7 900 000-00-00", doc.phone)
        assertEquals("ИНН 123", doc.extraInfo)
        assertEquals(createdAt, doc.createdAt)
    }

    @Test fun shortNameAndDefaultFarm() {
        assertEquals("Зубахин И. П.", TimesheetBuilder.shortName("Зубахин Иван Петрович"))
        assertEquals("Зубахин И.", TimesheetBuilder.shortName("Зубахин Иван"))
        assertEquals("Зубахин", TimesheetBuilder.shortName("Зубахин"))
        assertEquals("КФХ Зубахин", FarmInfo.DEFAULT.farmName)
        assertEquals("", FarmInfo.DEFAULT.responsiblePerson)
    }

    @Test fun equalNamesAreOrderedByEmployeeId() {
        val low = EmployeeSnapshot("00000000-0000-0000-0000-000000000001", "Иванов Иван", "", day(1, 1), null)
        val high = EmployeeSnapshot("00000000-0000-0000-0000-000000000002", "Иванов Иван", "", day(1, 1), null)
        for (input in listOf(listOf(high, low), listOf(low, high))) {
            val doc = build(input)
            assertEquals(listOf(low.id, high.id), doc.rows.map { it.employee.id })
            assertEquals(listOf(1, 2), doc.rows.map { it.number })
        }
    }

    @Test fun createdDateText() {
        val doc = build(listOf(emp()))
        assertEquals("06.11.2026", TimesheetBuilder.build(YearMonth.of(2026, 11), emptyList(), emptyList(), emptyList(),
            null, createdAt = farm.at(6, 12), farm = farm).createdDateText(farm))
        assertEquals(30, doc.daysInMonth)
    }

    @Test fun legendOfCodes() {
        assertEquals(
            "Я — явка, В — выходной, Б — больничный, О — отпуск, ОТ — отгул, ПР — прогул, К — командировка",
            TimesheetDocument.LEGEND,
        )
    }
}
