package ru.kfh.core.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The farm's zone when the server has not said otherwise (`/me` -> `farm.timeZone`). */
val DEFAULT_FARM_ZONE: ZoneId = ZoneId.of("Asia/Chita")

/** Days are calendar days of the farm's zone, never of the phone's. */
class FarmTime(val zone: ZoneId, private val clock: Clock = Clock.systemUTC()) {
    fun today(): LocalDate = dayOf(clock.instant())

    fun dayOf(i: Instant): LocalDate = i.atZone(zone).toLocalDate()

    /** Midnight of the day at the farm. */
    fun startOf(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()

    fun now(): Instant = clock.instant()

    /**
     * The moment a day picked for a record stands for, as the site saves it (`livestock.tsx`): this moment for
     * today, the farm's midnight for any other day. A backdated operation then sorts before the same day's later
     * ones everywhere, and the server's herd check sees the same order the phone did.
     */
    fun momentOf(day: LocalDate): Instant = if (day == today()) now() else startOf(day)
}

object Iso {
    private val instantFormat =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** 2026-11-07T03:04:05.000Z */
    fun format(i: Instant): String = instantFormat.format(i)

    /** With or without fractions of a second; null when it is not a date. */
    fun parse(s: String): Instant? = try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun day(d: LocalDate): String = d.toString()

    fun parseDay(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** 7 ч 30 мин, 8 ч, 45 мин. */
fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    if (h == 0) return "$m мин"
    return if (m == 0) "$h ч" else "$h ч $m мин"
}
