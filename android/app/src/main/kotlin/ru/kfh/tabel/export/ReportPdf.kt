package ru.kfh.tabel.export

import kotlin.math.ceil
import ru.kfh.core.report.ReportAlign
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.report.ReportPair
import ru.kfh.core.report.ReportTable

/**
 * A block report (coal, livestock, agro) as an A4 portrait PDF (port of `CoalReportPDFRenderer.swift`): blocks flow down
 * the page, a table that does not fit continues on the next page with its header repeated; «Страница N из M» on each
 * page.
 */
class ReportPdf(private val d: ReportDocument) {
    fun render(c: PdfCanvas) {
        val pageCount = Flow(c, drawing = false).run(0)
        Flow(c, drawing = true).run(pageCount)
    }

    /** Lays the blocks out page by page; with [drawing] false it only counts the pages. */
    private inner class Flow(private val c: PdfCanvas, private val drawing: Boolean) {
        private val text = PdfText(c, PAD_X, PAD_Y, 5f)
        private val content = PdfRect(MARGIN, MARGIN, WIDTH - 2 * MARGIN, HEIGHT - 2 * MARGIN)
        private val bottom = content.maxY - 16
        private var y = 0f
        private var page = 0
        private var pageCount = 0

        fun run(pageCount: Int): Int {
            this.pageCount = pageCount
            newPage()
            for (block in d.blocks) place(block)
            drawPageNumber()
            return page
        }

        private fun newPage() {
            if (page > 0) drawPageNumber()
            if (drawing) c.beginPage()
            page += 1
            y = content.y
            if (page > 1) {
                line(d.runningHeader, SMALL, PdfColors.SECONDARY, PdfAlign.LEFT, 10f)
                y += 14
            }
        }

        private fun drawPageNumber() {
            if (!drawing) return
            text.draw("Страница $page из $pageCount", PdfRect(content.x, content.maxY - 10, content.w, 10f), SMALL, PdfColors.SECONDARY, PdfAlign.RIGHT)
        }

        /** Starts a new page when [height] does not fit below the cursor. */
        private fun ensure(height: Float) {
            if (y + height > bottom && y > content.y + 20) newPage()
        }

        private fun place(block: ReportBlock) {
            when (block) {
                is ReportBlock.Title -> {
                    ensure(24f)
                    line(block.text, TITLE, PdfColors.BLACK, PdfAlign.CENTER, 20f)
                    y += 26
                }
                is ReportBlock.Text -> paragraph(block.text, BODY, PdfColors.BLACK, after = 3f)
                is ReportBlock.BoldText -> paragraph(block.text, BODY_BOLD, PdfColors.BLACK, after = 3f)
                is ReportBlock.Note -> paragraph(block.text, SMALL, PdfColors.SECONDARY, after = 4f)
                is ReportBlock.Heading -> {
                    y += 8
                    ensure(16f + 40f)
                    line(block.text, HEADING, PdfColors.BLACK, PdfAlign.LEFT, 15f)
                    y += 18
                }
                is ReportBlock.Table -> {
                    table(block.table)
                    y += 4
                }
                is ReportBlock.Pairs -> {
                    for (p in block.pairs) pair(p)
                    y += 4
                }
                is ReportBlock.Signature -> {
                    y += 18
                    ensure(18f)
                    val width = text.width(block.label, SIGNATURE)
                    if (drawing) {
                        text.draw(block.label, PdfRect(content.x, y, width + 2, 13f), SIGNATURE, align = PdfAlign.LEFT, inset = false)
                        if (block.name.isNotEmpty()) {
                            text.draw(block.name, PdfRect(content.x + width + 8, y, content.w - width - 8, 13f), SIGNATURE, align = PdfAlign.LEFT, inset = false)
                        }
                    }
                    y += 22
                }
            }
        }

        private fun line(s: String, font: PdfFont, color: Int, align: PdfAlign, height: Float) {
            if (!drawing) return
            text.draw(s, PdfRect(content.x, y, content.w, height), font, color, align, inset = false)
        }

        private fun paragraph(s: String, font: PdfFont, color: Int, after: Float) {
            val height = ceil(text.height(s, font, content.w))
            ensure(height)
            if (drawing) text.draw(s, PdfRect(content.x, y, content.w, height), font, color, PdfAlign.LEFT, wraps = true, inset = false)
            y += height + after
        }

        private fun pair(p: ReportPair) {
            val valueWidth = 130f
            val labelFont = if (p.bold) BODY_BOLD else BODY
            val height = maxOf(16f, ceil(text.height(p.label, labelFont, content.w - valueWidth - 8)) + 4)
            ensure(height)
            if (drawing) {
                val label = PdfRect(content.x, y, content.w - valueWidth, height)
                val value = PdfRect(content.maxX - valueWidth, y, valueWidth, height)
                if (p.bold) c.fillRect(label.x, y, content.w, height, PdfColors.TOTALS)
                text.draw(p.label, label, labelFont, align = PdfAlign.LEFT, wraps = true)
                text.draw(p.value.text, value, if (p.bold) BODY_BOLD else BODY, align = PdfAlign.RIGHT)
                c.line(content.x, y + height, content.maxX, y + height, PdfColors.BORDER, 0.5f)
            }
            y += height
        }

        // MARK: Table

        private fun widths(t: ReportTable): List<Float> {
            val total = t.columns.sumOf { it.weight }
            return t.columns.map { (it.weight / maxOf(total, 1.0)).toFloat() * content.w }
        }

        private fun rowHeight(cells: List<ReportCell>, widths: List<Float>, font: PdfFont): Float {
            val heights = cells.zip(widths).map { (cell, width) -> text.height(cell.text, font, width - 2 * PAD_X) }
            return ceil(maxOf(14f, (heights.maxOrNull() ?: 0f) + 2 * PAD_Y + 2))
        }

        private fun table(t: ReportTable) {
            val widths = widths(t)
            val headerHeight = rowHeight(t.columns.map { ReportCell.text(it.title) }, widths, HEADER)
            fun header() {
                if (drawing) {
                    var x = content.x
                    for ((column, width) in t.columns.zip(widths)) {
                        cell(column.title, PdfRect(x, y, width, headerHeight), HEADER, PdfColors.HEADER, PdfAlign.CENTER, wraps = true)
                        x += width
                    }
                }
                y += headerHeight
            }
            val first = t.rows.firstOrNull()?.let { rowHeight(it, widths, CELL) } ?: 14f
            ensure(headerHeight + first)
            header()
            val lines = t.rows.map { it to false } + (t.total?.let { listOf(it to true) } ?: emptyList())
            for ((cells, isTotal) in lines) {
                val font = if (isTotal) CELL_BOLD else CELL
                val height = rowHeight(cells, widths, font)
                if (y + height > bottom) {
                    newPage()
                    header()
                }
                if (drawing) {
                    var x = content.x
                    for ((index, pair) in cells.zip(widths).withIndex()) {
                        val (cell, width) = pair
                        val align = when (t.columns[index].align) {
                            ReportAlign.LEFT -> PdfAlign.LEFT
                            ReportAlign.CENTER -> PdfAlign.CENTER
                            ReportAlign.RIGHT -> PdfAlign.RIGHT
                        }
                        cell(cell.text, PdfRect(x, y, width, height), font, if (isTotal) PdfColors.TOTALS else null, align, wraps = cell.kind == ReportCell.Kind.TEXT)
                        x += width
                    }
                }
                y += height
            }
        }

        private fun cell(s: String, rect: PdfRect, font: PdfFont, fill: Int?, align: PdfAlign, wraps: Boolean) {
            if (fill != null) c.fillRect(rect.x, rect.y, rect.w, rect.h, fill)
            text.draw(s, rect, font, align = align, wraps = wraps)
            c.strokeRect(rect.x, rect.y, rect.w, rect.h, PdfColors.BORDER, 0.5f)
        }
    }

    companion object {
        const val WIDTH = 595f
        const val HEIGHT = 842f
        const val MARGIN = 30f
        private const val PAD_X = 3f
        private const val PAD_Y = 2f

        private val TITLE = PdfFont(15f, bold = true)
        private val HEADING = PdfFont(11f, bold = true)
        private val BODY = PdfFont(9.5f)
        private val BODY_BOLD = PdfFont(9.5f, bold = true)
        private val HEADER = PdfFont(8f, bold = true)
        private val CELL = PdfFont(8f)
        private val CELL_BOLD = PdfFont(8f, bold = true)
        private val SMALL = PdfFont(7.5f)
        private val SIGNATURE = PdfFont(10f)
    }
}
