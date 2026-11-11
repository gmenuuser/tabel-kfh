package ru.kfh.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime
import ru.kfh.core.time.Iso
import ru.kfh.core.time.durationText

class FarmTimeTest {
    private val saved = TimeZone.getDefault()

    @After
    fun restore() = TimeZone.setDefault(saved)

    @Test
    fun phoneInMoscowFarmInChita() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        val clock = Clock.fixed(Instant.parse("2026-11-06T16:30:00Z"), ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 11, 7), FarmTime(ZoneId.of("Asia/Chita"), clock).today())
        // On Moscow time (UTC+3) it is still the 6th at 19:30.
        assertEquals(LocalDate.of(2026, 11, 6), FarmTime(ZoneId.of("Europe/Moscow"), clock).today())
    }

    @Test
    fun defaultZoneIsChita() = assertEquals(ZoneId.of("Asia/Chita"), DEFAULT_FARM_ZONE)

    @Test
    fun dayAndStartAreInTheFarmZoneWhateverThePhoneZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        val ft = FarmTime(ZoneId.of("Asia/Yekaterinburg"))
        val d = LocalDate.of(2026, 11, 6)
        // Midnight of the 6th at the farm (UTC+5) = 19:00 UTC on the 5th.
        assertEquals(Instant.parse("2026-11-05T19:00:00Z"), ft.startOf(d))
        assertEquals(d, ft.dayOf(ft.startOf(d)))
        assertEquals(d, ft.dayOf(Instant.parse("2026-11-06T18:59:59Z")))
        assertEquals(d.plusDays(1), ft.dayOf(Instant.parse("2026-11-06T19:00:00Z")))
    }

    @Test
    fun isoFormatAndParse() {
        assertEquals("2026-11-07T03:04:05.000Z", Iso.format(Instant.parse("2026-11-07T03:04:05Z")))
        assertEquals("2026-11-07T03:04:05.120Z", Iso.format(Instant.parse("2026-11-07T03:04:05.12Z")))
        assertEquals(Instant.parse("2026-11-07T03:04:05Z"), Iso.parse("2026-11-07T03:04:05Z"))
        assertEquals(Instant.parse("2026-11-07T03:04:05.123Z"), Iso.parse("2026-11-07T03:04:05.123Z"))
        assertNull(Iso.parse("вчера"))
        assertEquals("2026-11-07", Iso.day(LocalDate.of(2026, 11, 7)))
        assertEquals(LocalDate.of(2026, 11, 7), Iso.parseDay("2026-11-07"))
        assertNull(Iso.parseDay("2026-13-40"))
    }

    @Test
    fun durationText() {
        assertEquals("7 ч 30 мин", durationText(450))
        assertEquals("8 ч", durationText(480))
        assertEquals("45 мин", durationText(45))
        assertEquals("0 мин", durationText(0))
    }

    /** A picked day as the site saves it: this moment for today, the farm's midnight for an earlier day. */
    @Test
    fun momentOfPickedDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        // 16:30 UTC on the 6th = 01:30 on the 7th at the farm (UTC+9).
        val now = Instant.parse("2026-11-06T16:30:00Z")
        val ft = FarmTime(ZoneId.of("Asia/Chita"), Clock.fixed(now, ZoneId.of("UTC")))
        assertEquals(now, ft.momentOf(LocalDate.of(2026, 11, 7)))
        // Yesterday at the farm: its midnight at the farm, 15:00 UTC on the 5th — not the phone's midnight.
        assertEquals(Instant.parse("2026-11-05T15:00:00Z"), ft.momentOf(LocalDate.of(2026, 11, 6)))
        assertEquals(ft.startOf(LocalDate.of(2026, 10, 1)), ft.momentOf(LocalDate.of(2026, 10, 1)))
    }
}
