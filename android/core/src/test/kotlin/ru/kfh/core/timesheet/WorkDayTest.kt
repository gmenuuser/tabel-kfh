package ru.kfh.core.timesheet

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kfh.core.time.durationText

/** Port of WorkDayTests.swift (calendar helpers become YearMonth and RuMonths). */
class WorkDayTest {
    @Test fun daysInMonth() {
        assertEquals(28, YearMonth.of(2026, 2).lengthOfMonth())
        assertEquals(29, YearMonth.of(2028, 2).lengthOfMonth())
        assertEquals(30, YearMonth.of(2026, 11).lengthOfMonth())
        assertEquals(31, YearMonth.of(2026, 12).lengthOfMonth())
    }

    @Test fun monthTitle() {
        assertEquals("Ноябрь 2026", RuMonths.title(YearMonth.of(2026, 11)))
        assertEquals("Январь 2027", RuMonths.title(YearMonth.of(2027, 1)))
        assertEquals("ноября", RuMonths.genitive(11))
        assertEquals("", RuMonths.name(13))
    }

    @Test fun employeeFullName() {
        val farm = farmIn()
        assertEquals("Зубахин Иван Петрович", farm.employee("Зубахин", patronymic = "Петрович").fullName)
        assertEquals("Зубахин Иван", farm.employee("Зубахин").fullName)
        val messy = farm.employee("  Зубахин ", firstName = "Иван  ", patronymic = " ")
        assertEquals("Зубахин Иван", messy.fullName)
        assertEquals("ИЗ", farm.employee("Зубахин", patronymic = "Петрович").initials)
    }

    @Test fun statusCodesAndTitles() {
        assertEquals(listOf("Я", "В", "Б", "О", "ОТ", "ПР", "К"), DayStatus.entries.map { it.code })
        assertEquals(
            listOf("Явка", "Выходной", "Больничный", "Отпуск", "Отгул", "Прогул", "Командировка"),
            DayStatus.entries.map { it.title },
        )
        assertEquals(listOf("5/2", "6/1", "2/2", "Свободный"), WorkSchedule.entries.map { it.title })
    }

    @Test fun oldStatusWithTimesReadsAsPresentAndUnknownCodeToo() {
        val farm = farmIn()
        val e = farm.employee("Старый")
        val r = farm.record(e, day(3), arrival = farm.at(3, 8, 0), departure = farm.at(3, 17, 0), worked = 480)
        assertEquals(DayStatus.PRESENT, r.dayStatus())
        assertEquals(DayStatus.SICK, farm.record(e, day(3), status = "Б").dayStatus())
        assertEquals(DayStatus.PRESENT, farm.record(e, day(3), status = "???").dayStatus())
    }

    @Test fun durationTextIsReused() {
        assertEquals("7 ч 30 мин", durationText(450))
    }
}
