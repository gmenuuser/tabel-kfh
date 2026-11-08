package ru.kfh.core.coal

import java.time.LocalDate
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

/** Dates and plurals of the coal module. */
object CoalText {
    /** «06.11.2026». */
    fun date(d: LocalDate): String = String.format(Locale.ROOT, "%02d.%02d.%04d", d.dayOfMonth, d.monthValue, d.year)

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
