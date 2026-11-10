package ru.kfh.core.export

import java.util.Locale
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.report.ReportTable

/**
 * A block report (coal, livestock, agro) as a one-sheet Excel workbook («Отчёт»), port of `CoalReportXLSXWriter.swift`:
 * the same blocks as the PDF, amounts and tons as numbers (so they can be summed), thin borders on tables, A4 portrait
 * print fitted to the page width.
 */
object ReportXlsx {
    fun make(d: ReportDocument): ByteArray = XlsxWriter.make("Отчёт", "", STYLES, Sheet(d).xml)

    /** Indexes into `cellXfs`. */
    private object Style {
        const val PLAIN = 0
        const val TITLE = 1
        const val BOLD = 2
        const val NOTE = 3
        const val HEADING = 4
        const val HEADER = 5
        const val TEXT = 6
        const val MONEY = 7
        const val TONS = 8
        const val COUNT = 9
        const val TOTAL_TEXT = 10
        const val TOTAL_MONEY = 11
        const val TOTAL_TONS = 12
        const val TOTAL_COUNT = 13
        const val PAIR_LABEL = 14
        const val PAIR_LABEL_BOLD = 15
    }

    private class Sheet(d: ReportDocument) {
        /** Columns of the widest table (the sales table: 9). */
        private val columnCount: Int
        private val widths: DoubleArray
        private val rows = mutableListOf<String>()
        private val merges = mutableListOf<String>()
        private var row = 0

        init {
            val tables = d.blocks.filterIsInstance<ReportBlock.Table>().map { it.table }
            columnCount = maxOf(6, tables.maxOfOrNull { it.columns.size } ?: 6)
            widths = DoubleArray(columnCount) { 10.0 }
            for (t in tables) {
                for ((index, column) in t.columns.withIndex()) {
                    widths[index] = maxOf(widths[index], minOf(40.0, column.weight * 1.15 + 2))
                }
            }
            for (block in d.blocks) add(block)
        }

        val xml: String
            get() {
                val last = XlsxWriter.column(columnCount)
                val cols = widths.withIndex().joinToString("") { (index, width) ->
                    "<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${String.format(Locale.ROOT, "%.1f", width)}\" customWidth=\"1\"/>"
                }
                return XlsxWriter.HEADER +
                    "<worksheet xmlns=\"${XlsxWriter.MAIN_NS}\" xmlns:r=\"${XlsxWriter.REL_NS}\">" +
                    "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>" +
                    "<dimension ref=\"A1:$last${maxOf(row, 1)}\"/>" +
                    "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
                    "<sheetFormatPr defaultRowHeight=\"15\"/>" +
                    "<cols>$cols</cols>" +
                    "<sheetData>${rows.joinToString("")}</sheetData>" +
                    (if (merges.isEmpty()) "" else "<mergeCells count=\"${merges.size}\">${merges.joinToString("") { "<mergeCell ref=\"$it\"/>" }}</mergeCells>") +
                    "<pageMargins left=\"0.5\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>" +
                    "<pageSetup paperSize=\"9\" orientation=\"portrait\" fitToWidth=\"1\" fitToHeight=\"0\"/>" +
                    "<headerFooter><oddFooter>&amp;RСтраница &amp;P из &amp;N</oddFooter></headerFooter>" +
                    "</worksheet>"
            }

        private fun add(block: ReportBlock) {
            when (block) {
                is ReportBlock.Title -> mergedLine(block.text, Style.TITLE, height = 22.0)
                is ReportBlock.Text -> mergedLine(block.text, Style.PLAIN)
                is ReportBlock.BoldText -> mergedLine(block.text, Style.BOLD)
                is ReportBlock.Note -> mergedLine(block.text, Style.NOTE, height = if (block.text.length > 110) 30.0 else null)
                is ReportBlock.Heading -> {
                    addRow(emptyList())
                    mergedLine(block.text, Style.HEADING, height = 18.0)
                }
                is ReportBlock.Table -> table(block.table)
                is ReportBlock.Pairs -> for (p in block.pairs) {
                    val labelEnd = XlsxWriter.column(columnCount - 1)
                    merges += "A${row + 1}:$labelEnd${row + 1}"
                    val style = if (p.bold) Style.PAIR_LABEL_BOLD else Style.PAIR_LABEL
                    val cells = mutableListOf(cell(ReportCell.text(p.label), null, style))
                    repeat(columnCount - 2) { cells += XlsxWriter.empty(style) }
                    cells += cell(p.value, p.bold)
                    addRow(cells)
                }
                is ReportBlock.Signature -> {
                    addRow(emptyList())
                    val cells = mutableListOf(cell(ReportCell.text(block.label), null, Style.BOLD))
                    if (block.name.isNotEmpty()) {
                        repeat(4) { cells += "" }
                        cells += cell(ReportCell.text(block.name), null, Style.PLAIN)
                    }
                    addRow(cells)
                }
            }
        }

        private fun table(t: ReportTable) {
            addRow(t.columns.map { cell(ReportCell.text(it.title), null, Style.HEADER) }, height = 30.0)
            for (r in t.rows) addRow(r.map { cell(it, null) })
            t.total?.let { total -> addRow(total.map { cell(it, true) }) }
        }

        /** A cell of a table (`total` null = a body row, true = the «Итого» row), or of the given style. */
        private fun cell(c: ReportCell, total: Boolean?, style: Int? = null): String {
            val s = style ?: run {
                val isTotal = total == true
                when (c.kind) {
                    ReportCell.Kind.TEXT -> if (isTotal) Style.TOTAL_TEXT else Style.TEXT
                    ReportCell.Kind.MONEY -> if (isTotal) Style.TOTAL_MONEY else Style.MONEY
                    ReportCell.Kind.TONS -> if (isTotal) Style.TOTAL_TONS else Style.TONS
                    ReportCell.Kind.COUNT -> if (isTotal) Style.TOTAL_COUNT else Style.COUNT
                }
            }
            c.number?.let { return XlsxWriter.number(it, s) }
            if (c.text.isEmpty()) return XlsxWriter.empty(s)
            return XlsxWriter.text(c.text, s)
        }

        private fun mergedLine(value: String, style: Int, height: Double? = null) {
            merges += "A${row + 1}:${XlsxWriter.column(columnCount)}${row + 1}"
            addRow(listOf(cell(ReportCell.text(value), null, style)), height)
        }

        private fun addRow(cells: List<String>, height: Double? = null) {
            row += 1
            rows += XlsxWriter.row(row, cells, height)
        }
    }

    // Fonts: 0 normal, 1 bold, 2 title, 3 grey note, 4 heading. Fills: 2 header, 3 totals.
    private val STYLES: String = XlsxWriter.styles(
        numFmt = "#,##0.00",
        fonts = listOf(
            XlsxWriter.font(10), XlsxWriter.font(10, bold = true), XlsxWriter.font(14, bold = true),
            XlsxWriter.font(9, color = "FF595959"), XlsxWriter.font(12, bold = true, color = "FF1F5D3A"),
        ),
        fills = listOf(XlsxWriter.fill("FFDCE9E0"), XlsxWriter.fill("FFF2F7F4")),
        xfs = listOf(
            XlsxWriter.xf(), // plain
            XlsxWriter.xf(font = 2), // title
            XlsxWriter.xf(font = 1), // bold
            XlsxWriter.xf(font = 3, alignment = XlsxWriter.LEFT), // note
            XlsxWriter.xf(font = 4), // heading
            XlsxWriter.xf(font = 1, fill = 2, border = 1, alignment = XlsxWriter.CENTER), // header
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.LEFT), // text
            XlsxWriter.xf(border = 1, format = 164, alignment = XlsxWriter.RIGHT), // money
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.RIGHT), // tons
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.CENTER), // count
            XlsxWriter.xf(font = 1, fill = 3, border = 1, alignment = XlsxWriter.LEFT), // totalText
            XlsxWriter.xf(font = 1, fill = 3, border = 1, format = 164, alignment = XlsxWriter.RIGHT), // totalMoney
            XlsxWriter.xf(font = 1, fill = 3, border = 1, alignment = XlsxWriter.RIGHT), // totalTons
            XlsxWriter.xf(font = 1, fill = 3, border = 1, alignment = XlsxWriter.CENTER), // totalCount
            XlsxWriter.xf(border = 1, alignment = XlsxWriter.LEFT), // pairLabel
            XlsxWriter.xf(font = 1, fill = 3, border = 1, alignment = XlsxWriter.LEFT), // pairLabelBold
        ),
    )
}
