package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.time.FarmTime

/** Contradictions in a day's data, shown to the owner as warnings (never blocking). */
enum class WorkWarning(val text: String) {
    DEPARTURE_BEFORE_ARRIVAL("Уход раньше прихода"),
    BREAK_LONGER_THAN_SHIFT("Перерыв длиннее смены"),
    OVER_SIXTEEN_HOURS("Смена больше 16 часов — проверь время"),
    TIMES_WITH_NON_PRESENT_STATUS("Указано время, но статус не «Явка»"),
    ARRIVAL_IN_FUTURE("Приход в будущем"),
    DEPARTURE_IN_FUTURE("Уход в будущем"),
    NOT_FINISHED("День не закрыт: нет времени ухода"),
}

/** Worked time = departure - arrival - break, computed on real instants. */
object WorkTimeCalculator {
    const val MAX_SHIFT_MINUTES = 16 * 60

    /** Whole minutes between two instants, rounded down; negative when `to` is before `from`. */
    fun intervalMinutes(from: Instant, to: Instant): Int =
        Math.floorDiv(to.toEpochMilli() - from.toEpochMilli(), 60_000L).toInt()

    /** max(0, delta - break), delta in whole minutes (floor). */
    fun workedMinutes(arrival: Instant, departure: Instant, breakMinutes: Int): Int =
        maxOf(0, intervalMinutes(arrival, departure) - breakMinutes)

    /**
     * The departure instant for a clock time: on the arrival day, or on the next day when that clock time is not
     * after the arrival (night shift).
     */
    fun departureDate(arrivalDay: LocalDate, arrival: Instant, departureClock: LocalTime, farm: FarmTime): Instant {
        val sameDay = clock(departureClock, arrivalDay, farm)
        if (!sameDay.isBefore(arrival)) return sameDay
        return clock(departureClock, arrivalDay.plusDays(1), farm)
    }

    /** `day`'s date with the hour and minute of `clock`, on the farm's clock face. */
    fun clock(clock: LocalTime, day: LocalDate, farm: FarmTime): Instant =
        day.atTime(clock.hour, clock.minute).atZone(farm.zone).toInstant()

    fun warnings(
        status: DayStatus, arrival: Instant?, departure: Instant?, breakMinutes: Int, now: Instant,
    ): List<WorkWarning> {
        val result = mutableListOf<WorkWarning>()
        if (status != DayStatus.PRESENT && (arrival != null || departure != null)) {
            result += WorkWarning.TIMES_WITH_NON_PRESENT_STATUS
        }
        if (arrival != null && arrival.isAfter(now)) {
            result += WorkWarning.ARRIVAL_IN_FUTURE
        } else if (departure != null && departure.isAfter(now)) {
            // A future arrival already says the times are ahead; a past arrival with a later departure is
            // today's shift entered ahead of time.
            result += WorkWarning.DEPARTURE_IN_FUTURE
        }
        if (arrival != null && departure != null) {
            val shift = intervalMinutes(arrival, departure)
            if (departure.isBefore(arrival)) {
                result += WorkWarning.DEPARTURE_BEFORE_ARRIVAL
            } else {
                if (breakMinutes > 0 && breakMinutes >= shift) result += WorkWarning.BREAK_LONGER_THAN_SHIFT
                if (shift > MAX_SHIFT_MINUTES) result += WorkWarning.OVER_SIXTEEN_HOURS
            }
        }
        if (status == DayStatus.PRESENT && arrival != null && departure == null) result += WorkWarning.NOT_FINISHED
        return result
    }

    /** manualWorkedMinutes ?: workedMinutes ?: 0. An unfinished day counts 0. */
    fun effectiveMinutes(r: AttendanceRecord): Int = r.manualWorkedMinutes ?: r.workedMinutes ?: 0
}
