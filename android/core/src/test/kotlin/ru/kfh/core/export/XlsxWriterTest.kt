package ru.kfh.core.export

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.timesheet.CellFormat
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.codesDoc
import ru.kfh.core.timesheet.day
import ru.kfh.core.timesheet.employee
import ru.kfh.core.timesheet.farmIn
import ru.kfh.core.timesheet.record

/** Port of XLSXWriterTests.swift (the timesheet workbook). */
class XlsxWriterTest {
    private val farm = farmIn()

    private fun december(): TimesheetDocument {
        val e = farm.employee("Зубахин", firstName = "Иван", patronymic = "Петрович", position = "Тракторист", hire = day(1, 1, 2020))
        val records = listOf(
            farm.record(e, day(1, 12), status = "Я", manual = 450),
            farm.record(e, day(2, 12), status = "Б"),
        )
        val settings = FarmInfo(
            farmName = "ООО \"Тест\" & Ко", managerName = "Зубахин И. П.", responsiblePerson = "Иванова А. А.",
            address = "х. Зубахин <центр>", phone = "+7 900 000-00-00", extraInfo = "",
        )
        return farm.codesDoc(
            YearMonth.of(2026, 12), listOf(e), records, settings = settings, format = CellFormat.CODES_AND_HOURS, version = 2,
            createdAt = farm.startOf(day(6)),
        )
    }

    private fun sheet(): String {
        val zip = MiniZipReader(TimesheetXlsx.make(december(), farm))
        return String(zip.data("xl/worksheets/sheet1.xml")!!, Charsets.UTF_8)
    }

    // Spec 15.
    @Test fun packageHasAllPartsAndEachIsWellFormedXML() {
        val zip = MiniZipReader(TimesheetXlsx.make(december(), farm))
        assertEquals(PARTS.toSet(), zip.entries.map { it.name }.toSet())
        for (part in PARTS) SheetReader.check(zip.data(part) ?: error(part))
        val workbook = String(zip.data("xl/workbook.xml")!!, Charsets.UTF_8)
        assertTrue(workbook.contains("name=\"Табель\""))
    }

    @Test fun sheetHasTitleEscapedTextDayHeadersAndDayCounts() {
        val xml = sheet()
        assertTrue(xml.contains("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ"))
        assertTrue(xml.contains("ООО &quot;Тест&quot; &amp; Ко"))
        assertFalse(xml.contains("\"Тест\" & Ко"))
        assertTrue(xml.contains("&lt;центр&gt;"))
        assertFalse("no hours in the timesheet", xml.contains("<v>7.5</v>"))
        assertTrue(xml.contains("t=\"inlineStr\""))

        val rows = SheetReader.rows(xml)
        val all = rows.flatten()
        assertTrue(all.contains("ООО \"Тест\" & Ко"))
        val header = rows.first { it.contains("ФИО") }
        assertEquals(2 + 31 + 7, header.size)
        assertEquals("31 day header cells for December", (1..31).map { it.toString() }, header.subList(2, 33))
        assertEquals(listOf("Я", "В", "Б", "О", "ОТ", "ПР", "К"), header.subList(33, header.size))

        val row = rows.first { it.contains("Зубахин Иван Петрович") }
        assertEquals("1", row[0])
        assertEquals("Я", row[2])
        assertEquals("Б", row[3])
        assertEquals("days, no hours", listOf("1", "0", "1", "0", "0", "0", "0"), row.subList(row.size - 7, row.size))

        assertTrue(all.contains("Руководитель КФХ: ___________________"))
        assertTrue(all.contains("Ответственное лицо: ___________________"))
        assertTrue(all.contains("Дата формирования: 06.11.2026"))
        assertTrue(all.any { it.startsWith("Итого по хозяйству") })
    }

    @Test fun sheetFreezesNamesAndHeaderAndSetsColumnWidths() {
        val xml = sheet()
        assertTrue(xml.contains("state=\"frozen\""))
        assertTrue(xml.contains("xSplit=\"2\""))
        assertTrue(xml.contains("<col min=\"1\" max=\"1\" width=\"4\" customWidth=\"1\"/>"))
        assertTrue(xml.contains("<col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/>"))
        assertTrue(xml.contains("<mergeCell ref=\"A1:"))
    }

    private companion object {
        val PARTS = listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/app.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
            "xl/styles.xml", "xl/worksheets/sheet1.xml",
        )
    }
}
