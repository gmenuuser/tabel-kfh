package ru.kfh.core.fields

import ru.kfh.core.model.Field
import java.text.Collator
import java.util.Locale

/**
 * The rules of «Поля», the same as the site's `web/src/lib/fields.ts`: the area is whole square metres, typed in
 * hectares with a comma or a dot and at most four decimals; the name is 1..60 characters.
 */
object FieldText {
    const val NAME_MAX = 60
    const val CULTURE_MAX = 60
    const val NOTE_MAX = 200

    /** A farm field is at most 100 000 га (the site's `AREA_MAX_SQ_M`); the cap keeps m² a whole wide number. */
    const val AREA_MAX_SQ_M = 1_000_000_000L
    const val AREA_ERROR = "Площадь — целое число м² больше нуля, не больше 100 000 га"

    private val SHAPE = Regex("^\\d*\\.?\\d*$")
    private val SPACES = Regex("[\\s\\u00a0\\u202f]")

    /** «12,5» → 125000 м²; «0,0001» → 1; пусто, «0», мусор и больше четырёх знаков — null. */
    fun parseAreaSqM(text: String): Long? {
        val clean = text.replace(SPACES, "").replace(',', '.')
        if (clean.isEmpty() || clean == "." || !SHAPE.matches(clean)) return null
        val dot = clean.indexOf('.')
        val whole = if (dot >= 0) clean.substring(0, dot) else clean
        val frac = if (dot >= 0) clean.substring(dot + 1) else ""
        if (frac.length > 4 || whole.length > 12) return null
        val sqM = whole.ifEmpty { "0" }.toLong() * 10000 + (frac + "0000").take(4).toLong()
        return if (sqM > 0 && sqM <= AREA_MAX_SQ_M) sqM else null
    }

    /** 125000 → «12,5»; 10000 → «1»; 12345 → «1,2345». */
    fun areaHa(sqM: Long): String {
        val whole = sqM / 10000
        var frac = (sqM % 10000).toString().padStart(4, '0')
        while (frac.endsWith("0")) frac = frac.dropLast(1)
        return if (frac.isEmpty()) whole.toString() else "$whole,$frac"
    }

    /** «Поле 3 — 12,5 га». */
    fun fieldLabel(name: String, sqM: Long): String = "$name — ${areaHa(sqM)} га"

    /** The same texts as the server's `validate('field')`, in the same order. */
    fun errors(name: String, areaSqM: Long, culture: String?, note: String?): List<String> = buildList {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > NAME_MAX) add("Название поля — от 1 до $NAME_MAX символов")
        if (areaSqM <= 0 || areaSqM > AREA_MAX_SQ_M) add(AREA_ERROR)
        if (culture != null && culture.length > CULTURE_MAX) add("Культура — до $CULTURE_MAX символов")
        if (note != null && note.length > NOTE_MAX) add("Заметка — до $NOTE_MAX символов")
    }

    private fun collator(): Collator = Collator.getInstance(Locale("ru"))

    /** By name, in Russian — the order the owner reads a list of fields in. */
    fun sort(fields: List<Field>): List<Field> {
        val c = collator()
        return fields.sortedWith(Comparator { a, b -> c.compare(a.name, b.name) })
    }

    /** The cultures already in use, for the form's hint: unique, trimmed, in Russian order. */
    fun cultures(fields: List<Field>): List<String> {
        val c = collator()
        return fields.mapNotNull { it.culture?.trim()?.ifEmpty { null } }.distinct()
            .sortedWith(Comparator { a, b -> c.compare(a, b) })
    }
}
