package ru.kfh.core.timesheet

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of WorkTimeCalculatorTests.swift. */
class WorkTimeCalculatorTest {
    private val moscow = farmIn(MOSCOW)
    private fun at(d: Int, h: Int, m: Int, month: Int = 11) = moscow.at(d, h, m, month)

    @Test fun fullDayWithBreak() =
        assertEquals(480, WorkTimeCalculator.workedMinutes(at(6, 8, 0), at(6, 17, 0), 60))

    @Test fun fullDayWithoutBreak() =
        assertEquals(540, WorkTimeCalculator.workedMinutes(at(6, 8, 0), at(6, 17, 0), 0))

    @Test fun partDay() = assertEquals(240, WorkTimeCalculator.workedMinutes(at(6, 8, 0), at(6, 12, 0), 0))

    @Test fun partialMinutesAreFloored() {
        val arrival = at(6, 8, 0)
        assertEquals(60, WorkTimeCalculator.workedMinutes(arrival, arrival.plusSeconds(60 * 60 + 59), 0))
    }

    @Test fun nightShiftViaDepartureDate() {
        val arrival = at(5, 22, 0)
        val departure = WorkTimeCalculator.departureDate(day(5), arrival, LocalTime.of(6, 0), moscow)
        assertEquals(at(6, 6, 0), departure)
        assertEquals(450, WorkTimeCalculator.workedMinutes(arrival, departure, 30))
    }

    @Test fun departureDateSameDay() {
        val departure = WorkTimeCalculator.departureDate(day(6), at(6, 8, 0), LocalTime.of(17, 30), moscow)
        assertEquals(at(6, 17, 30), departure)
    }

    /** Clocks go back 03:00 -> 02:00 in Berlin on 2026-10-25: a 22:00-06:00 night lasts 9 real hours. */
    @Test fun nightShiftAcrossDstUsesRealInterval() {
        val berlin = farmIn(java.time.ZoneId.of("Europe/Berlin"))
        val arrival = berlin.at(24, 22, 0, month = 10)
        val departure = WorkTimeCalculator.departureDate(day(24, 10), arrival, LocalTime.of(6, 0), berlin)
        assertEquals(berlin.at(25, 6, 0, month = 10), departure)
        assertEquals(510, WorkTimeCalculator.workedMinutes(arrival, departure, 30))
    }

    @Test fun breakLongerThanShift() {
        val arrival = at(6, 8, 0)
        val departure = at(6, 12, 0)
        assertEquals(0, WorkTimeCalculator.workedMinutes(arrival, departure, 600))
        assertEquals(
            listOf(WorkWarning.BREAK_LONGER_THAN_SHIFT),
            WorkTimeCalculator.warnings(DayStatus.PRESENT, arrival, departure, 600, at(6, 20, 0)),
        )
    }

    @Test fun normalDayHasNoWarnings() = assertEquals(
        emptyList<WorkWarning>(),
        WorkTimeCalculator.warnings(DayStatus.PRESENT, at(6, 8, 0), at(6, 17, 0), 60, at(6, 18, 0)),
    )

    @Test fun departureBeforeArrival() {
        val arrival = at(6, 17, 0)
        val departure = at(6, 8, 0)
        assertEquals(0, WorkTimeCalculator.workedMinutes(arrival, departure, 0))
        assertEquals(
            listOf(WorkWarning.DEPARTURE_BEFORE_ARRIVAL),
            WorkTimeCalculator.warnings(DayStatus.PRESENT, arrival, departure, 0, at(6, 18, 0)),
        )
    }

    @Test fun overSixteenHours() = assertEquals(
        listOf(WorkWarning.OVER_SIXTEEN_HOURS),
        WorkTimeCalculator.warnings(DayStatus.PRESENT, at(6, 5, 0), at(6, 22, 0), 60, at(6, 23, 0)),
    )

    @Test fun timesWithNonPresentStatus() = assertEquals(
        listOf(WorkWarning.TIMES_WITH_NON_PRESENT_STATUS),
        WorkTimeCalculator.warnings(DayStatus.DAY_OFF, at(6, 8, 0), null, 0, at(6, 18, 0)),
    )

    @Test fun notFinished() = assertEquals(
        listOf(WorkWarning.NOT_FINISHED),
        WorkTimeCalculator.warnings(DayStatus.PRESENT, at(6, 8, 0), null, 60, at(6, 12, 0)),
    )

    @Test fun arrivalInFuture() = assertEquals(
        listOf(WorkWarning.ARRIVAL_IN_FUTURE, WorkWarning.NOT_FINISHED),
        WorkTimeCalculator.warnings(DayStatus.PRESENT, at(7, 8, 0), null, 60, at(6, 12, 0)),
    )

    @Test fun departureInFuture() = assertEquals(
        listOf(WorkWarning.DEPARTURE_IN_FUTURE),
        WorkTimeCalculator.warnings(DayStatus.PRESENT, at(6, 8, 0), at(6, 17, 0), 60, at(6, 12, 0)),
    )

    @Test fun statusWithoutTimesHasNoWarnings() = assertEquals(
        emptyList<WorkWarning>(),
        WorkTimeCalculator.warnings(DayStatus.SICK, null, null, 0, at(6, 12, 0)),
    )

    @Test fun warningTexts() {
        assertEquals("Уход раньше прихода", WorkWarning.DEPARTURE_BEFORE_ARRIVAL.text)
        assertEquals("Перерыв длиннее смены", WorkWarning.BREAK_LONGER_THAN_SHIFT.text)
        assertEquals("Смена больше 16 часов — проверь время", WorkWarning.OVER_SIXTEEN_HOURS.text)
        assertEquals("Указано время, но статус не «Явка»", WorkWarning.TIMES_WITH_NON_PRESENT_STATUS.text)
        assertEquals("Приход в будущем", WorkWarning.ARRIVAL_IN_FUTURE.text)
        assertEquals("День не закрыт: нет времени ухода", WorkWarning.NOT_FINISHED.text)
        assertEquals("Уход в будущем", WorkWarning.DEPARTURE_IN_FUTURE.text)
    }

    @Test fun manualOverrideWins() {
        val e = moscow.employee("Зубахин")
        val r = moscow.record(e, day(6), arrival = at(6, 8, 0), departure = at(6, 17, 0), worked = 480, manual = 300)
        assertEquals(300, WorkTimeCalculator.effectiveMinutes(r))
    }

    @Test fun effectiveMinutesFallsBackToWorkedThenZero() {
        val e = moscow.employee("Зубахин")
        assertEquals(480, WorkTimeCalculator.effectiveMinutes(moscow.record(e, day(6), worked = 480)))
        assertEquals(0, WorkTimeCalculator.effectiveMinutes(moscow.record(e, day(6), arrival = at(6, 8, 0))))
    }
}
