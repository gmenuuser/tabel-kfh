package ru.kfh.tabel.export

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.CellFormat
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.TimesheetBuilder
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.TimesheetKind
import ru.kfh.core.timesheet.exportFileName

/** Port of PDFRendererTests.swift over the recording canvas (the real PDF bytes and page size: ReportSamplesTest on the device). */
class TimesheetPdfTest {
    private val farm = FarmTime(DEFAULT_FARM_ZONE)

    private fun november(count: Int, settings: FarmInfo = FarmInfo.DEFAULT): TimesheetDocument {
        val employees = (0 until count).map { i ->
            Employee(
                id = "e$i", lastName = "Сотрудник", firstName = "${i + 1}", patronymic = "Иванович", position = "Рабочий",
                hireDate = farm.startOf(LocalDate.of(2020, 1, 1)), dismissalDate = null,
            )
        }
        val records = employees.flatMap { e ->
            (1..5).map { day ->
                AttendanceRecord(id = "r-${e.id}-$day", employeeId = e.id, day = farm.startOf(LocalDate.of(2026, 11, day)), status = "Я", manualWorkedMinutes = 480)
            }
        }
        return TimesheetBuilder.build(
            YearMonth.of(2026, 11), employees, emptyList(), records, null, settings, CellFormat.CODES, 2,
            farm.startOf(LocalDate.of(2026, 11, 6)), farm, TimesheetKind.CODES,
        )
    }

    private fun render(d: TimesheetDocument): FakePdfCanvas = FakePdfCanvas().also { TimesheetPdf(d, farm).render(it) }

    // Spec 14.
    @Test fun outputIsAnA4LandscapePDF() {
        val c = render(november(3))
        assertEquals(1, c.pageCount)
        assertEquals(842f, TimesheetPdf.WIDTH)
        assertEquals(595f, TimesheetPdf.HEIGHT)
    }

    @Test fun firstPageHasTitleFarmMonthYearAndVersion() {
        val settings = FarmInfo(
            farmName = "КФХ Зубахин", managerName = "Зубахин И. П.", responsiblePerson = "Иванова А. А.",
            address = "х. Зубахин, ул. Степная, 12", phone = "+7 928 123-45-67", extraInfo = "",
        )
        val page = render(november(3, settings)).text(0)
        for (expected in listOf(
            "ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", "КФХ Зубахин", "Ноябрь", "2026", "ул. Степная", "123-45-67", "ФИО", "ОТ",
            "Сотрудник 1 Иванович", "Итого по хозяйству", "Страница 1 из 1", "Руководитель КФХ: ___________________",
            "Ответственное лицо: ___________________", "Дата формирования: 06.11.2026",
        )) {
            assertTrue("page 1 has «$expected» in: $page", page.contains(expected))
        }
    }

    @Test fun longTimesheetRepeatsTheHeaderAndNumbersPages() {
        val c = render(november(60))
        assertTrue(c.pageCount > 1)
        val second = c.text(1)
        assertTrue("header repeated on page 2", second.contains("ФИО"))
        assertTrue(second.contains("Страница 2 из ${c.pageCount}"))
        val last = c.text(c.pageCount - 1)
        assertTrue(last, last.contains("Руководитель КФХ:"))
        assertTrue(last, last.contains("Ответственное лицо:"))
        assertTrue(last, last.contains("Страница ${c.pageCount} из ${c.pageCount}"))
        assertFalse(c.text(0).contains("Руководитель КФХ:"))
    }

    @Test fun exportFileName() {
        assertEquals("Табель_Ноябрь_2026.pdf", exportFileName(november(1), ext = "pdf"))
        assertEquals("Табель_Ноябрь_2026.xlsx", exportFileName(november(1), ext = "xlsx"))
    }

    @Test fun exportFileNameOfOneEmployeeHasTheSurname() {
        assertEquals("Табель_Ноябрь_2026_Зубахин.pdf", exportFileName(november(1), surname = "Зубахин", ext = "pdf"))
        assertEquals("Табель_Ноябрь_2026_Иванова-Петрова.xlsx", exportFileName(november(1), surname = "Иванова-Петрова", ext = "xlsx"))
        assertEquals("a slash would make a folder", "Табель_Ноябрь_2026_А-Б.pdf", exportFileName(november(1), surname = "А/Б", ext = "pdf"))
    }

    @Test fun hoursTimesheetHasTheHoursColumnsAndLegend() {
        val e = Employee(id = "e1", lastName = "Зубахин", firstName = "Иван", hireDate = farm.startOf(LocalDate.of(2020, 1, 1)))
        val marks = (1..3).map { day ->
            ru.kfh.core.model.HoursMark(id = "m$day", employeeId = "e1", day = farm.startOf(LocalDate.of(2026, 11, day)), hours = 8, updatedAt = Instant.EPOCH)
        }
        val d = TimesheetBuilder.build(YearMonth.of(2026, 11), listOf(e), marks, emptyList(), null, createdAt = Instant.EPOCH, farm = farm)
        val page = render(d).text(0)
        assertTrue(page.contains("Часов"))
        assertTrue(page.contains("Раб. дней"))
        assertTrue(page.contains("число — отработанные часы за день"))
    }
}
