package ru.kfh.core.report

import ru.kfh.core.coal.CoalMath

/**
 * A printable report as a list of blocks (port of `CoalReportDocument.swift`); the PDF and the XLSX are drawn from the
 * same blocks, so both files always say the same thing. The coal, livestock and agro reports are all made of these.
 */
data class ReportDocument(
    /** «Уголь_день_2026-11-06» (no extension). */
    val fileName: String,
    /** Repeated small at the top of the following pages: «КФХ Зубахин · Отчёт за день 06.11.2026». */
    val runningHeader: String,
    val blocks: List<ReportBlock>,
)

sealed class ReportBlock {
    data class Title(val text: String) : ReportBlock()
    data class Text(val text: String) : ReportBlock()
    data class BoldText(val text: String) : ReportBlock()
    /** Grey, smaller. */
    data class Note(val text: String) : ReportBlock()
    data class Heading(val text: String) : ReportBlock()
    data class Table(val table: ReportTable) : ReportBlock()
    data class Pairs(val pairs: List<ReportPair>) : ReportBlock()
    data class Signature(val label: String, val name: String) : ReportBlock()
}

enum class ReportAlign { LEFT, CENTER, RIGHT }

data class ReportColumn(
    val title: String,
    /** Share of the page width (the shares of a table are scaled to the full width). */
    val weight: Double,
    val align: ReportAlign,
)

data class ReportCell(
    val text: String,
    val kind: Kind,
    /** The value written as a number into the spreadsheet; null for text. */
    val number: String?,
) {
    enum class Kind { TEXT, MONEY, TONS, COUNT }

    companion object {
        fun text(s: String) = ReportCell(s, Kind.TEXT, null)
        fun money(kopecks: Long) = ReportCell(CoalMath.rublesNumber(kopecks), Kind.MONEY, CoalMath.spreadsheetRubles(kopecks))
        /** Kilograms shown as tons («2,5»); the same for grams shown as kilograms. */
        fun tons(kilograms: Long) = ReportCell(CoalMath.tons(kilograms), Kind.TONS, CoalMath.spreadsheetTons(kilograms))
        fun count(n: Long) = ReportCell(n.toString(), Kind.COUNT, n.toString())
        fun count(n: Int) = count(n.toLong())
    }
}

data class ReportTable(
    val columns: List<ReportColumn>,
    val rows: List<List<ReportCell>>,
    /** The bold «Итого» row. */
    val total: List<ReportCell>?,
)

data class ReportPair(val label: String, val value: ReportCell, val bold: Boolean)
