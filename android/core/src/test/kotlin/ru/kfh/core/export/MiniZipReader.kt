package ru.kfh.core.export

/** Test-side reader of a STORE zip: walks the central directory and reads each local entry (`MiniZipReader` of ZipWriterTests.swift). */
class MiniZipReader(data: ByteArray) {
    class Entry(val name: String, val flags: Int, val data: ByteArray)

    val entries: List<Entry>

    /** Entry count stated by the end-of-central-directory record. */
    val statedCount: Int

    init {
        val b = data
        fun u16(o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
        val eocd = b.size - 22
        require(eocd >= 0 && u32(eocd) == 0x06054B50) { "no end of central directory" }
        statedCount = u16(eocd + 10)
        var offset = u32(eocd + 16)
        val result = mutableListOf<Entry>()
        repeat(statedCount) {
            require(offset + 46 <= b.size && u32(offset) == 0x02014B50) { "bad central directory entry" }
            val flags = u16(offset + 8)
            val method = u16(offset + 10)
            val size = u32(offset + 20)
            val nameLength = u16(offset + 28)
            val extraLength = u16(offset + 30)
            val commentLength = u16(offset + 32)
            val local = u32(offset + 42)
            require(method == 0) { "not STORE" }
            val name = String(b, offset + 46, nameLength, Charsets.UTF_8)
            require(u32(local) == 0x04034B50) { "bad local header of $name" }
            val start = local + 30 + u16(local + 26) + u16(local + 28)
            require(start + size <= b.size) { "entry $name runs past the end" }
            result += Entry(name, flags, b.copyOfRange(start, start + size))
            offset += 46 + nameLength + extraLength + commentLength
        }
        entries = result
    }

    fun data(name: String): ByteArray? = entries.firstOrNull { it.name == name }?.data
}

/** The rows of a worksheet: for each `<row>`, the text of each `<c>` (its `<t>` or `<v>`) — `SheetReader` of the Swift tests. */
object SheetReader {
    fun rows(xml: String): List<List<String>> {
        val factory = javax.xml.parsers.SAXParserFactory.newInstance()
        val rows = mutableListOf<MutableList<String>>()
        var cell: StringBuilder? = null
        var inValue = false
        factory.newSAXParser().parse(xml.byteInputStream(), object : org.xml.sax.helpers.DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: org.xml.sax.Attributes?) {
                when (qName) {
                    "row" -> rows.add(mutableListOf())
                    "c" -> cell = StringBuilder()
                    "t", "v" -> inValue = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "c" -> { cell?.let { if (rows.isNotEmpty()) rows.last().add(it.toString()) }; cell = null }
                    "t", "v" -> inValue = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) cell?.append(ch, start, length)
            }
        })
        return rows
    }

    /** Throws when [xml] is not well-formed. */
    fun check(xml: ByteArray) {
        javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().parse(xml.inputStream(), org.xml.sax.helpers.DefaultHandler())
    }
}
