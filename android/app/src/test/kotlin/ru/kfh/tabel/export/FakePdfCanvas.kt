package ru.kfh.tabel.export

/**
 * A canvas that only records the text of each page, with Arial-like metrics, so the renderers' layout (page count,
 * header repeated, page numbers) is tested on the JVM; the real `PdfDocument` output is checked on the device.
 */
class FakePdfCanvas : PdfCanvas {
    val pages = mutableListOf<MutableList<String>>()
    val pageCount: Int get() = pages.size

    /** The text drawn on page [index] (0-based), lines joined by `\n`. */
    fun text(index: Int): String = pages.getOrNull(index)?.joinToString("\n") ?: ""

    override fun beginPage() {
        pages.add(mutableListOf())
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {}

    override fun strokeRect(x: Float, y: Float, w: Float, h: Float, color: Int, lineWidth: Float) {}

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, lineWidth: Float) {}

    override fun drawLine(text: String, x: Float, y: Float, w: Float, font: PdfFont, color: Int, align: PdfAlign) {
        check(pages.isNotEmpty()) { "drawLine before beginPage" }
        pages.last().add(text)
    }

    override fun width(text: String, font: PdfFont): Float {
        var w = 0f
        for (c in text) {
            w += when {
                c == ' ' -> 0.28f
                c.isDigit() -> 0.56f
                c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё' -> 0.6f
                c.isUpperCase() -> 0.68f
                else -> 0.52f
            } * font.size
        }
        return if (font.bold) w * 1.06f else w
    }

    override fun lineHeight(font: PdfFont): Float = font.size * 1.15f
}
