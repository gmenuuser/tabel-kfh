package ru.kfh.core.export

import java.time.DayOfWeek
import java.time.LocalDate
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.TimesheetRow

/**
 * A timesheet as a one-sheet Excel workbook («Табель»), port of `XLSXWriter.swift`: inline strings, numbers as
 * numbers, thin borders, frozen № / ФИО columns and table header, landscape print fitted to the page width with the
 * header repeated.
 */
object TimesheetXlsx {
    fun make(d: TimesheetDocument, farm: FarmTime): ByteArray {
        val sheet = Sheet(d, farm)
        val definedNames = "<definedNames><definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">" +
            "'Табель'!$${sheet.headerRow}:$${sheet.headerRow}</definedName></definedNames>"
        return XlsxWriter.make("Табель", definedNames, STYLES, sheet.xml)
    }

    /** Cell style indexes into `cellXfs` of [STYLES]. */
    private object Style {
        const val PLAIN = 0
        const val TITLE = 1
        const val BOLD = 2
        const val HEADER = 3
        const val HEADER_WEEKEND = 4
        const val NAME = 5
        const val CELL = 6
        const val CELL_WEEKEND = 7
        const val NOT_EMPLOYED = 8
        const val UNFINISHED = 9
        const val COUNT = 10
        const val HOURS = 11
        const val FARM_LABEL = 12
        const val FARM_COUNT = 13
        const val FARM_HOURS = 14
        const val FARM_EMPTY = 15
    }

    /** Saturday and Sunday of the farm's calendar. */
    fun weekends(d: TimesheetDocument): Set<Int> =
        (1..maxOf(1, d.daysInMonth)).filter { day ->
            val w = LocalDate.of(d.year, d.month, day).dayOfWeek
            w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY
        }.toSet()

    /** Builds sheet1.xml row by row. */
    private class Sheet(val d: TimesheetDocument, farm: FarmTime) {
        /** Columns: №, ФИО, days, totals. */
        val lastColumn = 2 + d.daysInMonth + d.totalsTitles.size
        /** Row number of the table header (frozen and repeated on printed pages). */
        var headerRow = 0
            private set
        private val rows = mutableListOf<String>()
        private val merges = mutableListOf<String>()
        private var row = 0

        init {
            build(listOf(d.address, d.phone, d.extraInfo).filter { it.isNotEmpty() }, farm)
        }

        val xml: String
            get() {
                val last = XlsxWriter.column(lastColumn)
                val widths = "<col min=\"1\" max=\"1\" width=\"4\" customWidth=\"1\"/>" +
                    "<col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/>" +
                    "<col min=\"3\" max=\"${2 + d.daysInMonth}\" width=\"5\" customWidth=\"1\"/>" +
                    "<col min=\"${3 + d.daysInMonth}\" max=\"$lastColumn\" width=\"7\" customWidth=\"1\"/>"
                return XlsxWriter.HEADER +
                    "<worksheet xmlns=\"${XlsxWriter.MAIN_NS}\" xmlns:r=\"${XlsxWriter.REL_NS}\">" +
                    "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>" +
                    "<dimension ref=\"A1:$last$row\"/>" +
                    "<sheetViews><sheetView workbookViewId=\"0\">" +
                    "<pane xSplit=\"2\" ySplit=\"$headerRow\" topLeftCell=\"C${headerRow + 1}\" activePane=\"bottomRight\" state=\"frozen\"/>" +
                    "<selection pane=\"bottomRight\" activeCell=\"C${headerRow + 1}\" sqref=\"C${headerRow + 1}\"/>" +
                    "</sheetView></sheetViews>" +
                    "<sheetFormatPr defaultRowHeight=\"15\"/>" +
                    "<cols>$widths</cols>" +
                    "<sheetData>${rows.joinToString("")}</sheetData>" +
                    "<mergeCells count=\"${merges.size}\">${merges.joinToString("") { "<mergeCell ref=\"$it\"/>" }}</mergeCells>" +
                    "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.4\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>" +
                    "<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>" +
                    "<headerFooter><oddFooter>&amp;RСтраница &amp;P из &amp;N</oddFooter></headerFooter>" +
                    "</worksheet>"
            }

        private fun build(contacts: List<String>, farm: FarmTime) {
            val weekends = weekends(d)

            mergedLine("ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ", Style.TITLE, height = 22.0)
            mergedLine(d.farmName, Style.BOLD)
            if (contacts.isNotEmpty()) mergedLine(contacts.joinToString(" · "), Style.PLAIN)
            mergedLine("Месяц: ${RuMonths.name(d.month)}    Год: ${d.year}", Style.PLAIN)
            addRow(emptyList())

            val header = mutableListOf(text("№", Style.HEADER), text("ФИО", Style.HEADER))
            for (day in 1..maxOf(1, d.daysInMonth)) {
                header += text("$day", if (day in weekends) Style.HEADER_WEEKEND else Style.HEADER)
            }
            header += d.totalsTitles.map { text(it, Style.HEADER) }
            addRow(header, height = 18.0)
            headerRow = row

            for (r in d.rows) {
                val cells = mutableListOf(number(r.number.toString(), Style.CELL), text(r.employee.fullName, Style.NAME))
                for ((index, c) in r.cells.withIndex()) {
                    val style = if (!c.employed) Style.NOT_EMPLOYED else if ((index + 1) in weekends) Style.CELL_WEEKEND else Style.CELL
                    cells += if (c.text.isEmpty()) XlsxWriter.empty(style) else text(c.text, style)
                }
                cells += d.totalsValues(r).map { number(it.toString(), Style.COUNT) }
                addRow(cells, height = rowHeight(r))
            }

            val farmRow = mutableListOf(text("Итого по хозяйству", Style.FARM_LABEL), XlsxWriter.empty(Style.FARM_LABEL))
            repeat(d.daysInMonth) { farmRow += XlsxWriter.empty(Style.FARM_EMPTY) }
            farmRow += d.farmTotalsValues.map { number(it.toString(), Style.FARM_COUNT) }
            merges += "A${row + 1}:B${row + 1}"
            addRow(farmRow, height = 18.0)

            addRow(emptyList())
            addRow(listOf(text(d.legendText, Style.PLAIN)))
            addRow(emptyList())
            signature("Руководитель КФХ: ___________________", d.managerName)
            addRow(emptyList())
            signature("Ответственное лицо: ___________________", d.responsiblePerson)
            addRow(emptyList())
            addRow(listOf(text("Дата формирования: ${d.createdDateText(farm)}", Style.PLAIN)))
        }

        /** Up to three lines of 28 characters of the name, 13 pt each, at least 15. */
        private fun rowHeight(r: TimesheetRow): Double {
            val nameLines = minOf(3, maxOf(1, (r.employee.fullName.length + 27) / 28))
            return maxOf(nameLines * 13.0 + 2, 15.0)
        }

        /** The label in column A (it runs over the empty cells to the right), the name in the column of day 12. */
        private fun signature(label: String, name: String) {
            val cells = mutableListOf(text(label, Style.BOLD))
            if (name.isNotEmpty()) {
                repeat(12) { cells += "" }
                cells += text(name, Style.PLAIN)
            }
            addRow(cells)
        }

        private fun mergedLine(value: String, style: Int, height: Double? = null) {
            merges += "A${row + 1}:${XlsxWriter.column(lastColumn)}${row + 1}"
            addRow(listOf(text(value, style)), height)
        }

        private fun addRow(cells: List<String>, height: Double? = null) {
            row += 1
            rows += XlsxWriter.row(row, cells, height)
        }

        private fun text(value: String, style: Int) = XlsxWriter.text(value, style)

        private fun number(value: String, style: Int) = XlsxWriter.number(value, style)
    }

    // numFmt 164 = hours: «7.5», «8.0», «7.75».
    // Fonts: 0 normal, 1 bold, 2 title, 3 orange. Fills: 2 header/total, 3 weekend, 4 not employed.
    private val STYLES: String = XlsxWriter.styles(
        numFmt = "0.0#",
        fonts = listOf(
            XlsxWriter.font(10), XlsxWriter.font(10, bold = true), XlsxWriter.font(14, bold = true),
            XlsxWriter.font(10, bold = true, color = "FFE8892B"),
        ),
        fills = listOf(XlsxWriter.fill("FFDCE9E0"), XlsxWriter.fill("FFEDEDED"), XlsxWriter.fill("FFC9C9C9")),
        xfs = listOf(
            XlsxWriter.xf(), // plain
            XlsxWriter.xf(font = 2), // title
            XlsxWriter.xf(font = 1), // bold
            XlsxWriter.xf(font = 1, fill = 2, border = 1, alignment = XlsxWriter.CENTER), // header
            XlsxWriter.xf(font = 1, fill = 3, border = 1, alignment = XlsxWriter.CENTER), // headerWeekend
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.LEFT), // name
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.CENTER), // cell
            XlsxWriter.xf(fill = 3, border = 1, alignment = XlsxWriter.CENTER), // cellWeekend
            XlsxWriter.xf(fill = 4, border = 1, alignment = XlsxWriter.CENTER), // notEmployed
            XlsxWriter.xf(font = 3, border = 1, alignment = XlsxWriter.CENTER), // unfinished
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.CENTER), // count
            XlsxWriter.xf(border = 1, format = 164, alignment = XlsxWriter.CENTER), // hours
            XlsxWriter.xf(font = 1, fill = 2, border = 1, alignment = XlsxWriter.LEFT), // farmLabel
            XlsxWriter.xf(font = 1, fill = 2, border = 1, alignment = XlsxWriter.CENTER), // farmCount
            XlsxWriter.xf(font = 1, fill = 2, border = 1, format = 164, alignment = XlsxWriter.CENTER), // farmHours
            XlsxWriter.xf(fill = 2, border = 1), // farmEmpty
        ),
    )
}
