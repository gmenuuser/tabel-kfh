package ru.kfh.core.export

/**
 * The parts every one-sheet workbook shares (`XLSXWriter.swift` / `CoalReportXLSXWriter.swift`): the package parts,
 * column names, XML escaping and the style sheet skeleton. Inline strings, numbers as numbers; no shared strings table.
 */
object XlsxWriter {
    const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** The whole .xlsx: one sheet named [sheetName], [styles] and [sheetXml] as given, [definedNames] inside `<workbook>`. */
    fun make(sheetName: String, definedNames: String, styles: String, sheetXml: String): ByteArray {
        val zip = ZipWriter()
        zip.add("[Content_Types].xml", CONTENT_TYPES.toByteArray())
        zip.add("_rels/.rels", ROOT_RELS.toByteArray())
        zip.add("docProps/app.xml", APP.toByteArray())
        zip.add("xl/workbook.xml", workbook(sheetName, definedNames).toByteArray())
        zip.add("xl/_rels/workbook.xml.rels", WORKBOOK_RELS.toByteArray())
        zip.add("xl/styles.xml", styles.toByteArray())
        zip.add("xl/worksheets/sheet1.xml", sheetXml.toByteArray())
        return zip.finish()
    }

    /** 1 → A, 27 → AA. */
    fun column(index: Int): String {
        var n = index
        val name = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            name.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return name.toString()
    }

    /** XML text/attribute escaping; characters XML 1.0 forbids are dropped. */
    fun escape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (cp) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                '"'.code -> out.append("&quot;")
                '\''.code -> out.append("&apos;")
                '\t'.code, '\n'.code, '\r'.code -> out.appendCodePoint(cp)
                else -> if (cp >= 0x20 && cp != 0xFFFE && cp != 0xFFFF) out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** A cell holding [value] as text. */
    fun text(value: String, style: Int): String =
        "<c r=\"@REF@\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"

    /** A cell holding [value] as a number (already formatted for the sheet: «7.5», «16250.50»). */
    fun number(value: String, style: Int): String = "<c r=\"@REF@\" s=\"$style\"><v>$value</v></c>"

    fun empty(style: Int): String = "<c r=\"@REF@\" s=\"$style\"/>"

    /** A `<row>`: [cells] are templates with «@REF@» where the reference goes; "" skips a column. */
    fun row(number: Int, cells: List<String>, height: Double? = null): String {
        val body = StringBuilder()
        for ((index, template) in cells.withIndex()) {
            if (template.isEmpty()) continue
            body.append(template.replace("@REF@", "${column(index + 1)}$number"))
        }
        val ht = height?.let { " ht=\"${formatHeight(it)}\" customHeight=\"1\"" } ?: ""
        return "<row r=\"$number\"$ht>$body</row>"
    }

    /** «22.0», «15.5» — the way Swift prints a Double. */
    private fun formatHeight(h: Double): String = if (h == Math.floor(h)) "${h.toLong()}.0" else h.toString()

    private val CONTENT_TYPES = HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private val ROOT_RELS = HEADER +
        "<Relationships xmlns=\"$PACKAGE_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL_NS/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private val APP = HEADER +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
        "<Application>Табель КФХ</Application></Properties>"

    private fun workbook(sheetName: String, definedNames: String): String = HEADER +
        "<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">" +
        "<sheets><sheet name=\"${escape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        definedNames +
        "</workbook>"

    private val WORKBOOK_RELS = HEADER +
        "<Relationships xmlns=\"$PACKAGE_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    // MARK: Styles

    const val BORDER = "<left style=\"thin\"><color rgb=\"FF9A9A9A\"/></left><right style=\"thin\"><color rgb=\"FF9A9A9A\"/></right>" +
        "<top style=\"thin\"><color rgb=\"FF9A9A9A\"/></top><bottom style=\"thin\"><color rgb=\"FF9A9A9A\"/></bottom><diagonal/>"
    const val CENTER = "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>"
    const val LEFT = "<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/>"
    const val RIGHT = "<alignment horizontal=\"right\" vertical=\"center\"/>"

    fun fill(rgb: String): String =
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"$rgb\"/><bgColor indexed=\"64\"/></patternFill></fill>"

    /** One `<xf>` of `cellXfs`. */
    fun xf(font: Int = 0, fill: Int = 0, border: Int = 0, format: Int = 0, alignment: String? = null): String {
        var attributes = "numFmtId=\"$format\" fontId=\"$font\" fillId=\"$fill\" borderId=\"$border\" xfId=\"0\""
        if (font != 0) attributes += " applyFont=\"1\""
        if (fill != 0) attributes += " applyFill=\"1\""
        if (border != 0) attributes += " applyBorder=\"1\""
        if (format != 0) attributes += " applyNumberFormat=\"1\""
        if (alignment == null) return "<xf $attributes/>"
        return "<xf $attributes applyAlignment=\"1\">$alignment</xf>"
    }

    /** The style sheet: [numFmt] 164's format code, the `<font>` elements, the solid fills after the two fixed ones, the xfs. */
    fun styles(numFmt: String, fonts: List<String>, fills: List<String>, xfs: List<String>): String = HEADER +
        "<styleSheet xmlns=\"$MAIN_NS\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"$numFmt\"/></numFmts>" +
        "<fonts count=\"${fonts.size}\">" + fonts.joinToString("") + "</fonts>" +
        "<fills count=\"${fills.size + 2}\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        fills.joinToString("") + "</fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>$BORDER</border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"${xfs.size}\">" + xfs.joinToString("") + "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    fun font(size: Int, bold: Boolean = false, color: String? = null): String =
        "<font>" + (if (bold) "<b/>" else "") + "<sz val=\"$size\"/>" + (color?.let { "<color rgb=\"$it\"/>" } ?: "") +
            "<name val=\"Calibri\"/><family val=\"2\"/></font>"
}
