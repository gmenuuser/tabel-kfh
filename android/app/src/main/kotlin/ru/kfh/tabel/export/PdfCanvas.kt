package ru.kfh.tabel.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** A font of the PDF: size in points, regular or bold. The face is the system's sans-serif (Roboto, full Cyrillic). */
data class PdfFont(val size: Float, val bold: Boolean = false) {
    fun withSize(s: Float) = copy(size = s)
}

enum class PdfAlign { LEFT, CENTER, RIGHT }

/**
 * What the renderers draw on, in points with y down: one implementation over [PdfDocument] ([AndroidPdfCanvas]), one
 * recording fake in the tests. Measuring needs no page; drawing happens between [beginPage] calls.
 */
interface PdfCanvas {
    fun beginPage()
    fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun strokeRect(x: Float, y: Float, w: Float, h: Float, color: Int, lineWidth: Float)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, lineWidth: Float)

    /** One line of text in the box from ([x], [y]) of width [w] and height `lineHeight(font)`, aligned in the width. */
    fun drawLine(text: String, x: Float, y: Float, w: Float, font: PdfFont, color: Int, align: PdfAlign)

    fun width(text: String, font: PdfFont): Float

    fun lineHeight(font: PdfFont): Float
}

/** A rectangle in points. */
data class PdfRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val maxX: Float get() = x + w
    val maxY: Float get() = y + h
    fun inset(dx: Float, dy: Float) = PdfRect(x + dx, y + dy, w - 2 * dx, h - 2 * dy)
}

/** Colours of the reports as ARGB ints (the `Color` enums of the Swift renderers). */
object PdfColors {
    const val BLACK = 0xFF000000.toInt()
    const val BORDER = 0xFF999999.toInt()
    const val HEADER = 0xFFDBEBE0.toInt()
    const val WEEKEND_HEADER = 0xFFD6D6D6.toInt()
    const val WEEKEND = 0xFFF0F0F0.toInt()
    const val NOT_EMPLOYED = 0xFFD1D1D1.toInt()
    const val TOTALS = 0xFFF2F7F5.toInt()
    const val UNFINISHED = 0xFFCC6B14.toInt()
    const val SECONDARY = 0xFF595959.toInt()
}

/** Text layout over a [PdfCanvas]: word wrapping, heights, centred drawing with shrink-to-fit (the `Text` enums of the Swift renderers). */
class PdfText(private val c: PdfCanvas, private val padX: Float, private val padY: Float, private val minSize: Float) {
    /** The lines of [text] wrapped by words into [width]; a word wider than the width is broken by characters. */
    fun wrap(text: String, font: PdfFont, width: Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var line = StringBuilder()
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (c.width(candidate, font) <= width || line.isEmpty() && c.width(word, font) <= width) {
                    line = StringBuilder(candidate)
                    continue
                }
                if (line.isNotEmpty()) {
                    out += line.toString()
                    line = StringBuilder()
                }
                // The word alone is wider than the line: after a hyphen («Поступ-ло» → «Поступ-», «ло»), else character by character.
                var piece = StringBuilder()
                for (part in hyphenParts(word)) {
                    if (piece.isNotEmpty() && c.width(piece.toString() + part, font) > width) {
                        out += piece.toString()
                        piece = StringBuilder()
                    }
                    if (c.width(part, font) <= width) {
                        piece.append(part)
                        continue
                    }
                    for (ch in part) {
                        if (piece.isNotEmpty() && c.width(piece.toString() + ch, font) > width) {
                            out += piece.toString()
                            piece = StringBuilder()
                        }
                        piece.append(ch)
                    }
                }
                line = piece
            }
            out += line.toString()
        }
        return out
    }

    /** «Поступ-ло» → [«Поступ-», «ло»]; a word without a hyphen inside stays whole. */
    private fun hyphenParts(word: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for ((i, ch) in word.withIndex()) {
            if ((ch == '-' || ch == '‑') && i > start && i < word.length - 1) {
                parts += word.substring(start, i + 1)
                start = i + 1
            }
        }
        parts += word.substring(start)
        return parts
    }

    /** The height of [text] wrapped into [width]; 0 for an empty text. */
    fun height(text: String, font: PdfFont, width: Float): Float {
        if (text.isEmpty()) return 0f
        return wrap(text, font, width).size * c.lineHeight(font)
    }

    /** The widest line of [text]. */
    fun width(text: String, font: PdfFont): Float = text.split("\n").maxOf { c.width(it, font) }

    /**
     * Centred vertically in [rect]. A text that does not wrap is drawn line by line, each shrunk to fit the width
     * (down to [minSize]): «Я» keeps its size above «7,75».
     */
    fun draw(
        text: String, rect: PdfRect, font: PdfFont, color: Int = PdfColors.BLACK, align: PdfAlign,
        wraps: Boolean = false, inset: Boolean = true,
    ) {
        if (text.isEmpty()) return
        val inner = if (inset) rect.inset(padX, padY) else rect
        if (!wraps) {
            val lines = text.split("\n").map { line ->
                val widest = c.width(line, font)
                if (widest > inner.w && widest > 0) line to font.withSize(maxOf(minSize, font.size * inner.w / widest)) else line to font
            }
            val total = lines.sumOf { c.lineHeight(it.second).toDouble() }.toFloat()
            var y = inner.y + maxOf(0f, (inner.h - total) / 2)
            for ((line, lineFont) in lines) {
                c.drawLine(line, inner.x, y, inner.w, lineFont, color, align)
                y += c.lineHeight(lineFont)
            }
            return
        }
        val lines = wrap(text, font, inner.w)
        val lh = c.lineHeight(font)
        var y = inner.y + maxOf(0f, (inner.h - lines.size * lh) / 2)
        for (line in lines) {
            c.drawLine(line, inner.x, y, inner.w, font, color, align)
            y += lh
        }
    }
}

/** A [PdfCanvas] over Android's [PdfDocument]: pages of [width] × [height] points, Roboto for the text. */
class AndroidPdfCanvas(private val width: Int, private val height: Int) : PdfCanvas {
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private val paints = HashMap<PdfFont, Paint>()
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun paint(font: PdfFont): Paint = paints.getOrPut(font) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, if (font.bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = font.size
            color = Color.BLACK
        }
    }

    private val canvas get() = page?.canvas ?: error("drawing outside a page")

    override fun beginPage() {
        page?.let { document.finishPage(it) }
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
    }

    /** Writes the document to [out] and closes it. */
    fun finish(out: OutputStream) {
        page?.let { document.finishPage(it) }
        page = null
        document.writeTo(out)
        document.close()
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        shape.style = Paint.Style.FILL
        shape.color = color
        canvas.drawRect(x, y, x + w, y + h, shape)
    }

    override fun strokeRect(x: Float, y: Float, w: Float, h: Float, color: Int, lineWidth: Float) {
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = lineWidth
        shape.color = color
        canvas.drawRect(x, y, x + w, y + h, shape)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, lineWidth: Float) {
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = lineWidth
        shape.color = color
        canvas.drawLine(x1, y1, x2, y2, shape)
    }

    override fun drawLine(text: String, x: Float, y: Float, w: Float, font: PdfFont, color: Int, align: PdfAlign) {
        val p = paint(font)
        p.color = color
        val tw = p.measureText(text)
        val left = when (align) {
            PdfAlign.LEFT -> x
            PdfAlign.CENTER -> x + (w - tw) / 2
            PdfAlign.RIGHT -> x + w - tw
        }
        canvas.drawText(text, left, y - p.fontMetrics.ascent, p)
    }

    override fun width(text: String, font: PdfFont): Float = paint(font).measureText(text)

    override fun lineHeight(font: PdfFont): Float {
        val m = paint(font).fontMetrics
        return m.descent - m.ascent
    }
}
