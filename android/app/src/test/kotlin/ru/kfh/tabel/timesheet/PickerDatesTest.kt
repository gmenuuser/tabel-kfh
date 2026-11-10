package ru.kfh.tabel.timesheet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.kfh.tabel.ui.timesheet.PickerDates
import ru.kfh.tabel.ui.timesheet.dateText
import ru.kfh.tabel.ui.timesheet.ruWeekday
import java.time.LocalDate
import java.util.TimeZone

/** Material's date picker speaks in UTC midnights: a farm day must round-trip whatever zone the phone is in. */
class PickerDatesTest {
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    @Test
    fun roundTripsInEveryPhoneZone() {
        val days = listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2024, 2, 29), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        for (zone in listOf("Europe/Moscow", "Asia/Chita", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            for (d in days) {
                val millis = PickerDates.millis(d)
                assertEquals("$zone $d", 0L, millis % 86_400_000L)
                assertEquals("$zone $d", d, PickerDates.date(millis))
            }
        }
    }

    @Test
    fun knownMillis() {
        // 2026-11-07T00:00:00Z = Date.UTC(2026, 10, 7) in JavaScript = 20764 days since the epoch.
        val midnight = 20_764L * 86_400_000L
        assertEquals(1_794_009_600_000L, midnight)
        assertEquals(midnight, PickerDates.millis(LocalDate.of(2026, 11, 7)))
        assertEquals(LocalDate.of(2026, 11, 7), PickerDates.date(midnight))
        // A millisecond before the next UTC midnight is still the same day.
        assertEquals(LocalDate.of(2026, 11, 7), PickerDates.date(midnight + 86_399_999L))
    }

    @Test
    fun texts() {
        assertEquals("06.11.2026", dateText(LocalDate.of(2026, 11, 6)))
        assertEquals("пт", ruWeekday(LocalDate.of(2026, 11, 6)))
        assertEquals("пн", ruWeekday(LocalDate.of(2026, 11, 2)))
        assertEquals("вс", ruWeekday(LocalDate.of(2026, 11, 1)))
    }
}
