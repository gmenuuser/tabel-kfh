package ru.kfh.core.coal

import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import java.util.Locale

private const val NBSP = " "

/** Money and mass of the coal module in whole units: kopecks and kilograms (`Long`). Never floating point. */
object CoalMath {
    const val KILOGRAMS_PER_TON = 1000L

    /** The largest sale the form accepts, 100 т (a typo guard). */
    const val MAX_KILOGRAMS = 100_000L

    /** The largest price the forms accept, 10 000 000 ₽. */
    const val MAX_KOPECKS = 1_000_000_000L

    /** Tons × price per ton, rounded half up to a kopeck: 2,5 т × 6500 ₽ = 16 250 ₽. */
    fun coalAmount(kilograms: Long, pricePerTon: Long): Long {
        if (kilograms <= 0 || pricePerTon <= 0) return 0
        return (kilograms * pricePerTon + KILOGRAMS_PER_TON / 2) / KILOGRAMS_PER_TON
    }

    /** «2», «2,5», «0.125», « 1,50 » -> kilograms; null for empty, negative, text or more than 3 decimals. */
    fun parseTons(text: String): Long? = parseFixed(text, 3)

    /** «6500», «6 500», «6500,5», «1200.00» -> kopecks; null for empty, negative, text or more than 2 decimals. */
    fun parseRubles(text: String): Long? = parseFixed(text, 2)

    private fun parseFixed(text: String, decimals: Int): Long? {
        val cleaned = text.filter { !it.isWhitespace() && it != ' ' && it != ' ' }.replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0]
        val fraction = if (parts.size == 2) parts[1] else ""
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (fraction.length > decimals || whole.length > 12) return null
        if (!whole.all { it in '0'..'9' } || !fraction.all { it in '0'..'9' }) return null
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val wholeValue = if (whole.isEmpty()) 0L else whole.toLong()
        val padded = fraction + "0".repeat(decimals - fraction.length)
        return wholeValue * scale + (if (padded.isEmpty()) 0L else padded.toLong())
    }

    /** «6 500 ₽», «16 250,50 ₽», «−1 200 ₽». */
    fun rubles(kopecks: Long): String = rublesNumber(kopecks) + NBSP + "₽"

    /** «6 500», «16 250,50» (thousands separated by a no-break space). */
    fun rublesNumber(kopecks: Long): String {
        val sign = if (kopecks < 0) "−" else ""
        val value = Math.abs(kopecks)
        val cents = value % 100
        return sign + grouped(value / 100) + if (cents == 0L) "" else String.format(Locale.ROOT, ",%02d", cents)
    }

    /** What a price field shows to be edited: «6500», «1200,50». */
    fun rublesField(kopecks: Long): String {
        val value = Math.abs(kopecks)
        val cents = value % 100
        return "${value / 100}" + if (cents == 0L) "" else String.format(Locale.ROOT, ",%02d", cents)
    }

    /** «2», «2,5», «0,125». */
    fun tons(kilograms: Long): String {
        val sign = if (kilograms < 0) "−" else ""
        val value = Math.abs(kilograms)
        val fraction = String.format(Locale.ROOT, "%03d", value % KILOGRAMS_PER_TON).trimEnd('0')
        return sign + grouped(value / KILOGRAMS_PER_TON) + if (fraction.isEmpty()) "" else ",$fraction"
    }

    /** «2,5 т». */
    fun tonsText(kilograms: Long): String = tons(kilograms) + NBSP + "т"

    /** The amount as a number for a spreadsheet cell: «16250.50», «6500». */
    fun spreadsheetRubles(kopecks: Long): String {
        val sign = if (kopecks < 0) "-" else ""
        val value = Math.abs(kopecks)
        val cents = value % 100
        if (cents == 0L) return sign + "${value / 100}"
        return sign + "${value / 100}." + String.format(Locale.ROOT, "%02d", cents)
    }

    /** Tons as a number for a spreadsheet cell: «2.5». */
    fun spreadsheetTons(kilograms: Long): String =
        tons(kilograms).replace(',', '.').replace(NBSP, "").replace('−', '-')

    /** 1234567 -> «1 234 567» (no-break spaces). */
    private fun grouped(n: Long): String {
        val digits = n.toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(NBSP)
            sb.append(c)
        }
        return sb.toString()
    }
}

/** `vehicleType` as the wire spells it. */
object VehicleType {
    const val HIRED = "hired"
    const val OWN = "own"
}

/** The amount at the list prices of the moment of the sale. */
val CoalSale.listTotal: Long
    get() = CoalMath.coalAmount(kilograms, coalPricePerTonSnapshot) + deliveryPriceSnapshot

/** The amounts differ from the list prices. */
val CoalSale.isPriceAdjusted: Boolean get() = totalAmount != listTotal

/** The hired driver's pay for one delivery here. */
val DeliveryLocation.effectiveDriverPaymentKopecks: Long get() = driverPaymentKopecks ?: deliveryPriceKopecks

/**
 * Runs of any Unicode whitespace (a no-break space U+00A0 included, which `\s` misses) become one space; none
 * stays at the ends: « Мун галов » -> «Мун галов».
 */
fun collapseSpaces(s: String): String {
    val sb = StringBuilder(s.length)
    var pendingSpace = false
    for (c in s) {
        if (c.isWhitespace()) {
            pendingSpace = sb.isNotEmpty()
        } else {
            if (pendingSpace) sb.append(' ')
            sb.append(c)
            pendingSpace = false
        }
    }
    return sb.toString()
}

/** «Мунгалов Сергей Иванович», empty parts skipped. */
val Driver.fullName: String
    get() = collapseSpaces(listOf(lastName, firstName, middleName).joinToString(" "))

/** The first letter of the surname, «М». */
val Driver.letter: String
    get() = lastName.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
