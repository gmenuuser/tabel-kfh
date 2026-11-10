package ru.kfh.tabel.export

import java.time.LocalDate
import kotlin.math.ceil
import ru.kfh.core.export.TimesheetXlsx
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.TimesheetRow

/**
 * A timesheet as an A4 landscape PDF (port of `TimesheetPDFRenderer.swift`): title block, the table (header repeated on
 * every page, ФИО wraps and the row grows), the farm total row, the legend and the signature lines; «Страница N из M»
 * on each page.
 */
class TimesheetPdf(private val d: TimesheetDocument, private val farm: FarmTime) {
    private class Page(val first: Boolean) {
        val rows = mutableListOf<Int>()
        var farmTotal = false
        /** Top of the legend and signature block, on the page that has it. */
        var footerTop: Float? = null
        val hasTable: Boolean get() = rows.isNotEmpty() || farmTotal
    }

    fun render(c: PdfCanvas) {
        val layout = Layout(c)
        for ((index, page) in layout.pages.withIndex()) {
            c.beginPage()
            layout.draw(page, index + 1, layout.pages.size)
        }
    }

    private inner class Layout(private val c: PdfCanvas) {
        private val text = PdfText(c, PAD_X, PAD_Y, 4.5f)
        private val content = PdfRect(MARGIN, MARGIN, WIDTH - 2 * MARGIN, HEIGHT - 2 * MARGIN)
        private val numberWidth = 18f
        private val nameWidth = 110f
        /** Widths of the totals columns, «Часы» wider. */
        private val totalWidths: List<Float> = d.totalsTitles.mapIndexed { index, _ -> if (d.isHours && index < 2) 26f else 19f }
        private val days = maxOf(1, d.daysInMonth)
        private val dayWidth = (content.w - (numberWidth + nameWidth + totalWidths.sum())) / days
        private val headerHeight = 20f
        private val farmHeight = 16f
        private val weekends: Set<Int> = TimesheetXlsx.weekends(d)
        private val leftInfo = listOf(d.farmName, d.address, if (d.phone.isEmpty()) "" else "Тел.: ${d.phone}", d.extraInfo).filter { it.isNotEmpty() }
        private val rightInfo = listOf("Месяц: ${RuMonths.name(d.month)}", "Год: ${d.year}")
        private val firstTop = MARGIN + 22 + maxOf(leftInfo.size, rightInfo.size) * INFO_LINE + 8
        private val otherTop = MARGIN + 14
        private val bottom = HEIGHT - MARGIN - 14
        private val rowHeights: List<Float> = d.rows.map { row ->
            val name = text.height(row.employee.fullName, BODY, nameWidth - 2 * PAD_X)
            val lines = row.cells.maxOfOrNull { if (it.text.isEmpty()) 0 else it.text.split("\n").size } ?: 0
            val cell = lines * c.lineHeight(NUMBER)
            ceil(maxOf(14f, name + 2 * PAD_Y + 1, cell + 2 * PAD_Y + 1))
        }
        val pages: List<Page> = paginate()

        private fun paginate(): List<Page> {
            val pages = mutableListOf<Page>()
            var page = Page(first = true)
            var y = firstTop + headerHeight
            fun next(table: Boolean) {
                pages += page
                page = Page(first = false)
                y = otherTop + (if (table) headerHeight else 0f)
            }
            for ((index, height) in rowHeights.withIndex()) {
                if (y + height > bottom && page.rows.isNotEmpty()) next(table = true)
                page.rows += index
                y += height
            }
            if (y + farmHeight > bottom && page.rows.isNotEmpty()) next(table = true)
            page.farmTotal = true
            y += farmHeight + 8
            if (y + footerHeight > bottom) next(table = false)
            page.footerTop = y
            pages += page
            return pages
        }

        // MARK: Drawing

        fun draw(page: Page, number: Int, of: Int) {
            if (page.first) drawTitle() else drawRunningHeader()
            var y = if (page.first) firstTop else otherTop
            if (page.hasTable) {
                drawTableHeader(y)
                y += headerHeight
                for (index in page.rows) {
                    drawRow(d.rows[index], y, rowHeights[index])
                    y += rowHeights[index]
                }
                if (page.farmTotal) drawFarmTotal(y)
            }
            page.footerTop?.let { drawFooter(it) }
            text.draw("Страница $number из $of", PdfRect(content.x, content.maxY - 9, content.w, 9f), SMALL, PdfColors.SECONDARY, PdfAlign.RIGHT)
        }

        private fun drawTitle() {
            text.draw("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", PdfRect(content.x, content.y, content.w, 18f), TITLE, align = PdfAlign.CENTER)
            val top = content.y + 22
            for ((i, line) in leftInfo.withIndex()) {
                text.draw(line, PdfRect(content.x, top + i * INFO_LINE, content.w * 0.7f, INFO_LINE), if (i == 0) INFO_BOLD else INFO, align = PdfAlign.LEFT, inset = false)
            }
            for ((i, line) in rightInfo.withIndex()) {
                text.draw(line, PdfRect(content.maxX - 200, top + i * INFO_LINE, 200f, INFO_LINE), INFO, align = PdfAlign.RIGHT, inset = false)
            }
        }

        private fun drawRunningHeader() {
            val line = listOf("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", d.farmName, "${RuMonths.name(d.month)} ${d.year}").filter { it.isNotEmpty() }.joinToString(" · ")
            text.draw(line, PdfRect(content.x, content.y, content.w, 10f), SMALL, PdfColors.SECONDARY, PdfAlign.LEFT, inset = false)
        }

        private fun drawTableHeader(y: Float) {
            var x = content.x
            cell("№", PdfRect(x, y, numberWidth, headerHeight), BOLD, PdfColors.HEADER)
            x += numberWidth
            cell("ФИО", PdfRect(x, y, nameWidth, headerHeight), BOLD, PdfColors.HEADER, align = PdfAlign.LEFT)
            x += nameWidth
            val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
            for (day in 1..days) {
                val rect = PdfRect(x, y, dayWidth, headerHeight)
                val weekday = LocalDate.of(d.year, d.month, minOf(day, d.daysInMonth.coerceAtLeast(1))).dayOfWeek.value
                c.fillRect(rect.x, rect.y, rect.w, rect.h, if (day in weekends) PdfColors.WEEKEND_HEADER else PdfColors.HEADER)
                text.draw("$day", PdfRect(x, y + 1, dayWidth, headerHeight * 0.55f), BOLD, align = PdfAlign.CENTER)
                text.draw(weekdays[weekday - 1], PdfRect(x, y + headerHeight * 0.5f, dayWidth, headerHeight * 0.5f - 1), WEEKDAY, PdfColors.SECONDARY, PdfAlign.CENTER)
                stroke(rect)
                x += dayWidth
            }
            for ((title, width) in d.totalsTitles.zip(totalWidths)) {
                cell(title, PdfRect(x, y, width, headerHeight), BOLD, PdfColors.HEADER)
                x += width
            }
        }

        private fun drawRow(row: TimesheetRow, y: Float, height: Float) {
            var x = content.x
            cell("${row.number}", PdfRect(x, y, numberWidth, height), NUMBER)
            x += numberWidth
            cell(row.employee.fullName, PdfRect(x, y, nameWidth, height), BODY, align = PdfAlign.LEFT, wraps = true)
            x += nameWidth
            for ((index, cell) in row.cells.withIndex()) {
                val fill = if (!cell.employed) PdfColors.NOT_EMPLOYED else if ((index + 1) in weekends) PdfColors.WEEKEND else null
                cell(cell.text, PdfRect(x, y, dayWidth, height), NUMBER, fill)
                x += dayWidth
            }
            for ((value, width) in d.totalsValues(row).map { it.toString() }.zip(totalWidths)) {
                cell(value, PdfRect(x, y, width, height), NUMBER, PdfColors.TOTALS)
                x += width
            }
        }

        private fun drawFarmTotal(y: Float) {
            var x = content.x
            cell("Итого по хозяйству", PdfRect(x, y, numberWidth + nameWidth, farmHeight), BOLD, PdfColors.HEADER, align = PdfAlign.LEFT)
            x += numberWidth + nameWidth
            val daysWidth = dayWidth * days
            cell("", PdfRect(x, y, daysWidth, farmHeight), BOLD, PdfColors.HEADER)
            x += daysWidth
            for ((value, width) in d.farmTotalsValues.map { it.toString() }.zip(totalWidths)) {
                cell(value, PdfRect(x, y, width, farmHeight), BOLD_NUMBER, PdfColors.HEADER)
                x += width
            }
        }

        private val legend: String get() = d.legendText + " Серые ячейки — дни до приёма или после увольнения."

        private val signatures: List<Pair<String, String>>
            get() = listOf(
                "Руководитель КФХ: ___________________" to d.managerName,
                "Ответственное лицо: ___________________" to d.responsiblePerson,
                "Дата формирования: ${d.createdDateText(farm)}" to "",
            )

        private val footerHeight: Float
            get() = ceil(text.height(legend, SMALL, content.w)) + 3 + 8 + signatures.size * SIGNATURE_LINE

        private fun drawFooter(top: Float) {
            var y = top
            val legendHeight = ceil(text.height(legend, SMALL, content.w))
            text.draw(legend, PdfRect(content.x, y, content.w, legendHeight), SMALL, PdfColors.SECONDARY, PdfAlign.LEFT, wraps = true, inset = false)
            y += legendHeight + 3
            y += 8
            for ((label, name) in signatures) {
                val width = text.width(label, SIGNATURE)
                text.draw(label, PdfRect(content.x, y, width + 2, 12f), SIGNATURE, align = PdfAlign.LEFT, inset = false)
                if (name.isNotEmpty()) {
                    text.draw(name, PdfRect(content.x + width + 8, y, 300f, 12f), SIGNATURE_NAME, align = PdfAlign.LEFT, inset = false)
                }
                y += SIGNATURE_LINE
            }
        }

        // MARK: Cells

        private fun cell(s: String, rect: PdfRect, font: PdfFont, fill: Int? = null, color: Int = PdfColors.BLACK, align: PdfAlign = PdfAlign.CENTER, wraps: Boolean = false) {
            if (fill != null) c.fillRect(rect.x, rect.y, rect.w, rect.h, fill)
            text.draw(s, rect, font, color, align, wraps)
            stroke(rect)
        }

        private fun stroke(rect: PdfRect) = c.strokeRect(rect.x, rect.y, rect.w, rect.h, PdfColors.BORDER, BORDER_WIDTH)
    }

    companion object {
        const val WIDTH = 842f
        const val HEIGHT = 595f
        const val MARGIN = 24f
        private const val PAD_X = 1.5f
        private const val PAD_Y = 1.5f
        private const val BORDER_WIDTH = 0.5f
        private const val INFO_LINE = 12f
        private const val SIGNATURE_LINE = 18f

        private val TITLE = PdfFont(14f, bold = true)
        private val INFO = PdfFont(9f)
        private val INFO_BOLD = PdfFont(10f, bold = true)
        private val BODY = PdfFont(7f)
        private val BOLD = PdfFont(7f, bold = true)
        private val NUMBER = PdfFont(7f)
        private val BOLD_NUMBER = PdfFont(7f, bold = true)
        private val WEEKDAY = PdfFont(5.5f)
        private val SMALL = PdfFont(7f)
        private val SIGNATURE = PdfFont(9f)
        private val SIGNATURE_NAME = PdfFont(9f)
    }
}
