package ru.kfh.core.money

import java.util.Locale

/** Money in kopecks (`Long`), never floating point. */
object Money {
    private const val NBSP = " "

    /** 6 500 ₽, 16 250,50 ₽, −1 200 ₽ (no-break spaces). */
    fun rubles(kopecks: Long): String = number(kopecks) + NBSP + "₽"

    private fun number(kopecks: Long): String {
        val sign = if (kopecks < 0) "−" else ""
        val value = Math.abs(kopecks)
        val cents = (value % 100).toInt()
        return sign + grouped(value / 100) + if (cents == 0) "" else String.format(Locale.ROOT, ",%02d", cents)
    }

    private fun grouped(n: Long): String {
        val digits = n.toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(NBSP)
            sb.append(c)
        }
        return sb.toString()
    }

    /** 6500, 6 500, 6500,5, 1200.00 -> kopecks; null for empty, negative, text or more than 2 decimals. */
    fun parseRubles(text: String): Long? {
        val cleaned = text.filter { !it.isWhitespace() && it != ' ' && it != ' ' }.replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0]
        val fraction = if (parts.size == 2) parts[1] else ""
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (fraction.length > 2 || whole.length > 12) return null
        if (!whole.all { it in '0'..'9' } || !fraction.all { it in '0'..'9' }) return null
        val w = if (whole.isEmpty()) 0L else whole.toLong()
        return w * 100 + (fraction + "0".repeat(2 - fraction.length)).toLong()
    }
}
