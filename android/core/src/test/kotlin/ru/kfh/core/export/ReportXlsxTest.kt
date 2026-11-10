package ru.kfh.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.coal.CoalSpecDay
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.timesheet.day

/** Port of CoalReportExportTests.swift: the document's blocks and the workbook (the PDF parts live in the app's tests). */
class ReportXlsxTest {
    private fun sheetRows(data: ByteArray): List<List<String>> {
        val zip = MiniZipReader(data)
        return SheetReader.rows(String(zip.data("xl/worksheets/sheet1.xml")!!, Charsets.UTF_8))
    }

    private fun document(s: CoalSpecDay): ReportDocument = CoalReportLayout.document(s.dayReport(), s.w.farm)

    @Test fun dayDocumentHasEveryPartOfTheReport() {
        val s = CoalSpecDay()
        s.close()
        val doc = document(s)
        assertEquals("Уголь_день_2026-11-06", doc.fileName)
        assertEquals(ReportBlock.Title("ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ"), doc.blocks.first())
        assertTrue(ReportBlock.BoldText("КФХ Зубахин") in doc.blocks)
        assertTrue(ReportBlock.Text("Дата: 06.11.2026 (пятница)") in doc.blocks)
        assertTrue(ReportBlock.Signature("Руководитель КФХ: ___________________", "Зубахин Иван Петрович") in doc.blocks)
        assertTrue(ReportBlock.Note(CoalReportLayout.REMAINDER_NOTE) in doc.blocks)
        val pairs = doc.blocks.filterIsInstance<ReportBlock.Pairs>().flatMap { it.pairs }
        fun value(prefix: String) = pairs.firstOrNull { it.label.startsWith(prefix) }?.value?.number
        assertEquals("61350", value("А."))
        assertEquals("54750", value("Б."))
        assertEquals("6600", value("В."))
        assertEquals("5400", value("Г."))
        assertEquals("1200", value("Д."))
        assertEquals("55950", value("Е."))
        assertFalse("Е is not called a profit", pairs.any { it.label.lowercase().contains("прибыль") })
    }

    @Test fun dayXLSXHasNumbersAndTotals() {
        val s = CoalSpecDay()
        val rows = sheetRows(ReportXlsx.make(document(s)))
        assertEquals(listOf("ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ"), rows.first())
        val header = rows.first { it.firstOrNull() == "№" }
        assertEquals(listOf("№", "Время", "Тонн", "Населённый пункт", "Машина", "Водитель", "Уголь, ₽", "Доставка, ₽", "Итого, ₽"), header)
        val first = rows.first { it.firstOrNull() == "1" }
        assertEquals(listOf("1", "08:00", "2", "Новоцурухайтуй", "Наёмная", "Мунгалов Сергей Иванович", "13000", "1000", "14000"), first)
        val own = rows.first { it.firstOrNull() == "6" }
        assertEquals("Своя", own[4])
        assertEquals("—", own[5])
        assertEquals("the corrected amount, as a number", "7200", own[8])
        assertTrue(rows.any { it.size > 1 && it[1] == "Итого" && it.last() == "61350" })
        assertTrue(rows.any { it.firstOrNull()?.startsWith("Е. Остаток после выплат водителям") == true && it.last() == "55950" })
        val m = rows.first { it.firstOrNull() == "Мунгалов Сергей Иванович" && it.size == 6 }
        assertEquals(listOf("Мунгалов Сергей Иванович", "3", "0", "3200", "2000", "1200"), m)
        assertTrue(rows.any { it.firstOrNull() == "ИТОГО К ВЫПЛАТЕ" && it.last() == "3400" })
    }

    @Test fun packageOfTheReportIsWellFormed() {
        val s = CoalSpecDay()
        val zip = MiniZipReader(ReportXlsx.make(document(s)))
        for (part in listOf("[Content_Types].xml", "_rels/.rels", "docProps/app.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                            "xl/styles.xml", "xl/worksheets/sheet1.xml")) {
            SheetReader.check(zip.data(part) ?: error(part))
        }
        assertTrue(String(zip.data("xl/workbook.xml")!!, Charsets.UTF_8).contains("name=\"Отчёт\""))
    }

    @Test fun monthReportHasVillagesAndDrivers() {
        val s = CoalSpecDay()
        s.w.clock = s.w.farm.startOf(day(20)).plusSeconds(10 * 3600)
        val doc = CoalReportLayout.document(s.monthReport(), s.w.farm)
        assertEquals("Уголь_месяц_2026-11", doc.fileName)
        assertEquals(ReportBlock.Title("ОТЧЁТ ЗА МЕСЯЦ"), doc.blocks.first())
        assertTrue(ReportBlock.Text("Месяц: Ноябрь 2026") in doc.blocks)
        assertTrue(ReportBlock.Heading("Итоги по населённым пунктам") in doc.blocks)
        assertTrue(ReportBlock.Heading("Выплаты водителям") in doc.blocks)
        val rows = sheetRows(ReportXlsx.make(doc))
        assertTrue(rows.any { it == listOf("Новоцурухайтуй", "3", "5", "3000", "35500") })
        assertTrue(rows.any { it == listOf("Приаргунск", "3", "3.5", "3600", "25850") })
    }

    @Test fun paymentsReport() {
        val s = CoalSpecDay()
        val doc = CoalReportLayout.paymentsDocument(s.dayReport(), s.w.farm)
        assertEquals("Выплаты_водителям_2026-11-06", doc.fileName)
        assertEquals(ReportBlock.Title("ВЫПЛАТЫ ВОДИТЕЛЯМ"), doc.blocks.first())
        val rows = sheetRows(ReportXlsx.make(doc))
        assertTrue(rows.any { it == listOf("Мунгалов Сергей Иванович", "3", "0", "3200", "2000", "1200") })
        assertTrue(rows.any { it.firstOrNull() == "ИТОГО К ВЫПЛАТЕ" && it.last() == "3400" })
        assertTrue("the payment is listed", rows.any { it.size > 2 && it[1] == "Мунгалов Сергей Иванович" && it[2] == "2000" })
        val month = CoalReportLayout.paymentsDocument(s.monthReport(), s.w.farm)
        assertEquals("Выплаты_водителям_2026-11", month.fileName)
        assertTrue(ReportBlock.Text("Месяц: Ноябрь 2026") in month.blocks)
    }

    @Test fun emptyDayStillMakesAReport() {
        val s = CoalSpecDay()
        s.w.sales = emptyList()
        s.w.payments = emptyList()
        val doc = document(s)
        assertTrue(ReportBlock.Text("Продаж нет.") in doc.blocks)
        assertTrue(ReportBlock.Text("Начислений наёмным водителям нет.") in doc.blocks)
        assertFalse(sheetRows(ReportXlsx.make(doc)).isEmpty())
    }
}
