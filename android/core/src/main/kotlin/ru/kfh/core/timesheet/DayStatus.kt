package ru.kfh.core.timesheet

import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee

/** Status of an employee's day. `code` is the timesheet code and the wire value of `AttendanceRecord.status`. */
enum class DayStatus(val code: String, val title: String) {
    PRESENT("Я", "Явка"),
    DAY_OFF("В", "Выходной"),
    SICK("Б", "Больничный"),
    VACATION("О", "Отпуск"),
    TIME_OFF("ОТ", "Отгул"),
    ABSENT("ПР", "Прогул"),
    BUSINESS_TRIP("К", "Командировка");

    companion object {
        /** Everything but Я can be set ahead on a future day (ПР too). */
        val futureStatuses: List<DayStatus> = listOf(DAY_OFF, SICK, VACATION, TIME_OFF, ABSENT, BUSINESS_TRIP)

        fun fromCode(code: String): DayStatus? = entries.firstOrNull { it.code == code }

        /** The statuses that may be set on `day`: all of them up to `today`, `futureStatuses` after it. */
        fun statuses(day: LocalDate, today: LocalDate): List<DayStatus> =
            if (day.isAfter(today)) futureStatuses else entries.toList()
    }
}

/** `raw` is what the site and the iPhone write into `Employee.workSchedule`. */
enum class WorkSchedule(val raw: String, val title: String) {
    FIVE_TWO("fiveTwo", "5/2"),
    SIX_ONE("sixOne", "6/1"),
    TWO_TWO("twoTwo", "2/2"),
    FREE("free", "Свободный");

    companion object {
        fun fromRaw(raw: String): WorkSchedule = entries.firstOrNull { it.raw == raw } ?: FIVE_TWO
    }
}

/**
 * The status of a stored record. An old record with arrival and departure times is a plain Я (the times are history);
 * an unknown code reads as Я as well, like the iPhone's default.
 */
fun AttendanceRecord.dayStatus(): DayStatus = DayStatus.fromCode(status) ?: DayStatus.PRESENT

object DayMarkRules {
    /** Я on a future day cannot be set; every other status can. */
    fun canSet(status: DayStatus, day: LocalDate, today: LocalDate): Boolean =
        !(status == DayStatus.PRESENT && day.isAfter(today))

    fun statuses(day: LocalDate, today: LocalDate): List<DayStatus> = DayStatus.statuses(day, today)
}

/** «Ноябрь», «ноября» and «Ноябрь 2026». */
object RuMonths {
    private val names = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )
    private val genitives = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** "" outside 1..12. */
    fun name(month: Int): String = names.getOrElse(month - 1) { "" }

    fun genitive(month: Int): String = genitives.getOrElse(month - 1) { "" }

    fun title(month: YearMonth): String = "${name(month.monthValue)} ${month.year}"
}

/** «Фамилия Имя Отчество»: trimmed, single spaces, empty parts skipped. */
val Employee.fullName: String
    get() = listOf(lastName, firstName, patronymic).joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ")

/** First letters of the first and last name, e.g. «ИЗ». */
val Employee.initials: String
    get() = listOf(firstName, lastName).mapNotNull { it.trim().firstOrNull()?.toString() }.joinToString("")
        .uppercase()
