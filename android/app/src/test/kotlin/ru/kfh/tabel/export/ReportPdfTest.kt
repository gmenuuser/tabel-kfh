package ru.kfh.tabel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.report.ReportDocument

/** Port of the PDF assertions of CoalReportExportTests.swift over the recording canvas. */
class ReportPdfTest {
    private fun render(d: ReportDocument): FakePdfCanvas = FakePdfCanvas().also { ReportPdf(d).render(it) }

    private fun allText(c: FakePdfCanvas): String = (0 until c.pageCount).joinToString("\n") { c.text(it) }

    @Test fun dayPDFIsA4PortraitInRussian() {
        val s = CoalSpecDay()
        val c = render(CoalReportLayout.document(s.dayReport(), s.farm))
        assertEquals(595f, ReportPdf.WIDTH)
        assertEquals(842f, ReportPdf.HEIGHT)
        val text = allText(c)
        for (expected in listOf(
            "ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ", "КФХ Зубахин", "Новоцурухайтуй", "Приаргунск", "Мунгалов", "Кайдолов Пётр",
            "Взаиморасчёты с водителями", "ИТОГО К ВЫПЛАТЕ", "Остаток после выплат водителям", "Руководитель КФХ",
            "Зубахин Иван Петрович", "Страница 1 из",
        )) {
            assertTrue("PDF has «$expected»", text.contains(expected))
        }
    }

    @Test fun longTableContinuesOnTheNextPageWithItsHeader() {
        val s = CoalSpecDay(extra = 70)
        val c = render(CoalReportLayout.document(s.dayReport(), s.farm))
        assertTrue(c.pageCount > 1)
        val second = c.text(1)
        assertTrue("the header is repeated: ${second.take(400)}", second.contains("Водитель") && second.contains("Время"))
        assertTrue("running header: ${second.take(200)}", second.contains("Отчёт за день 06.11.2026"))
        assertTrue(allText(c).contains("Страница ${c.pageCount} из ${c.pageCount}"))
    }

    @Test fun monthReportRenders() {
        val s = CoalSpecDay()
        val text = allText(render(CoalReportLayout.document(s.monthReport(), s.farm)))
        assertTrue(text.contains("ОТЧЁТ ЗА МЕСЯЦ"))
        assertTrue(text.contains("Итоги по населённым пунктам"))
    }

    @Test fun paymentsReport() {
        val s = CoalSpecDay()
        val text = allText(render(CoalReportLayout.paymentsDocument(s.dayReport(), s.farm)))
        assertTrue(text.contains("ВЫПЛАТЫ ВОДИТЕЛЯМ"))
    }

    @Test fun emptyDayStillMakesAReport() {
        val s = CoalSpecDay()
        s.sales = emptyList()
        s.payments = emptyList()
        val c = render(CoalReportLayout.document(s.dayReport(), s.farm))
        assertEquals(1, c.pageCount)
        assertTrue(c.text(0).contains("Продаж нет."))
    }
}
