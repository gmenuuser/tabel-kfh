package ru.kfh.core.coal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Every refusal of the coal module, in Russian. Port of `CoalErrors.swift`. */
sealed class CoalError(message: String) : Exception(message) {
    data class Invalid(val messages: List<String>) : CoalError(messages.joinToString("\n"))

    /** The day of the sale is closed; it must be opened for editing first. */
    data class DayClosed(val day: LocalDate) : CoalError(
        "Рабочий день ${CoalText.date(day)} завершён. Чтобы изменить его продажи, откройте день для редактирования.",
    )

    data object DayAlreadyClosed : CoalError("Этот рабочий день уже завершён")
    data object DayNotClosed : CoalError("Этот рабочий день не завершён")

    /** A second tap on «Сохранить» for the same form. */
    data object AlreadySaved : CoalError("Эта продажа уже сохранена")
    data object DriverNotFound : CoalError("Водитель не найден")
    data object DriverArchived : CoalError("Водитель в архиве. Верните его из архива, чтобы записывать на него доставки.")

    /** A driver with deliveries or payments is only archived. */
    data object DriverHasHistory :
        CoalError("У водителя есть доставки или выплаты, поэтому его можно только убрать в архив. История сохранится.")

    data class DuplicateLocation(val name: String) : CoalError("Населённый пункт «$name» уже есть")
    data object NothingToPay : CoalError("Долга перед водителем нет")
    data class PaymentTooLarge(val debt: Long) : CoalError("Сумма больше долга перед водителем (${CoalMath.rubles(debt)})")
}

/** Dates, times and plurals of the coal module, on the farm's calendar (port of `CoalText` in CoalErrors.swift). */
object CoalText {
    /** «06.11.2026». */
    fun date(d: LocalDate): String = String.format(Locale.ROOT, "%02d.%02d.%04d", d.dayOfMonth, d.monthValue, d.year)

    /** «06.11.2026»: the moment's day at the farm ([zone]). */
    fun date(i: Instant, zone: ZoneId): String = date(i.atZone(zone).toLocalDate())

    /** «14:05» on the farm's clock. */
    fun time(i: Instant, zone: ZoneId): String {
        val t = i.atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }

    /** «06.11.2026 14:05». */
    fun dateTime(i: Instant, zone: ZoneId): String = date(i, zone) + " " + time(i, zone)

    /** «пятница, 6 ноября 2026» (lowercase, as the Swift original; screens capitalise the first letter). */
    fun longDate(d: LocalDate): String {
        val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
        val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                            "октября", "ноября", "декабря")
        return "${weekdays[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}"
    }

    /** «3 доставки». */
    fun deliveries(n: Int): String = "$n " + plural(n, "доставка", "доставки", "доставок")

    /** «5 продаж». */
    fun sales(n: Int): String = "$n " + plural(n, "продажа", "продажи", "продаж")

    /** Russian plural: 1 доставка, 2 доставки, 5 доставок, 11 доставок, 21 доставка. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val tens = Math.abs(n) % 100
        val units = Math.abs(n) % 10
        if (tens in 11..14) return many
        if (units == 1) return one
        if (units in 2..4) return few
        return many
    }
}
