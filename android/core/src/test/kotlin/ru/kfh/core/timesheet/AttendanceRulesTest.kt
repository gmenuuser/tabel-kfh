package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee

/** A store of records standing in for the database: applies what the rules return. */
class AttendanceHarness(val farm: ru.kfh.core.time.FarmTime, start: Instant) {
    var clock: Instant = start
    val records = mutableListOf<AttendanceRecord>()
    private var counter = 0
    val rules = AttendanceRules(farm, { clock }, { "new-${++counter}" })

    fun put(r: AttendanceRecord): AttendanceRecord {
        val i = records.indexOfFirst { it.id == r.id }
        if (i >= 0) records[i] = r else records.add(r)
        return r
    }

    fun markArrival(e: Employee, force: Boolean = false) = put(rules.markArrival(e, records, force))
    fun markDeparture(e: Employee, force: Boolean = false) = put(rules.markDeparture(e, records, force))
    fun openRecord(e: Employee) = rules.openRecord(e, records)
    fun staleOpenRecord(e: Employee) = rules.staleOpenRecord(e, records)
    fun todayAction(e: Employee) = rules.todayAction(e, records)
    fun record(e: Employee, d: LocalDate) = rules.record(e, records, d)

    fun save(
        e: Employee, d: LocalDate, status: DayStatus, arrival: Instant? = null, departure: Instant? = null,
        breakMinutes: Int = 0, manualMinutes: Int? = null, comment: String = "",
    ) = put(rules.save(e, records, d, status, arrival, departure, breakMinutes, manualMinutes, comment))

    fun apply(change: DayMarkChange): DayStatus? {
        when (change) {
            is DayMarkChange.Put -> put(change.record)
            is DayMarkChange.Delete -> change.record?.let { r -> records.removeAll { it.id == r.id } }
        }
        return change.status
    }

    fun setStatus(e: Employee, d: LocalDate, status: DayStatus?) = apply(rules.setStatus(e, records, d, status))
    fun togglePresence(e: Employee, d: LocalDate) = apply(rules.togglePresence(e, records, d))
    fun status(e: Employee, d: LocalDate) = rules.status(e, records, d)
    fun markAllPresent(es: List<Employee>, d: LocalDate): Int {
        val made = rules.markAllPresent(es, records, d)
        made.forEach { put(it) }
        return made.size
    }
}

/** Port of the domain part of AttendanceServiceTests.swift (SwiftData persistence is the repository's job). */
class AttendanceRulesTest {
    private val farm = farmIn()
    private fun at(d: Int, h: Int, m: Int = 0, month: Int = 11) = farm.at(d, h, m, month)
    private fun harness(now: Instant) = AttendanceHarness(farm, now)

    @Test fun arrivalCreatesPresentRecordWithNowAndDefaultBreak() {
        val h = harness(at(6, 8, 2))
        val e = farm.employee("Зубахин", breakMinutes = 45)
        val r = h.markArrival(e)
        assertEquals("Я", r.status)
        assertEquals(at(6, 8, 2), r.arrivalTime)
        assertNull(r.departureTime)
        assertNull(r.workedMinutes)
        assertEquals(45, r.breakMinutes)
        assertEquals(farm.startOf(day(6)), r.day)
        assertEquals(e.id, r.employeeId)
        assertEquals(TodayAction.MarkDeparture(at(6, 8, 2)), h.todayAction(e))
    }

    @Test fun secondArrivalThrowsAlreadyArrived() {
        val h = harness(at(6, 8, 0))
        val e = farm.employee("Зубахин")
        h.markArrival(e)
        h.clock = at(6, 8, 1)
        assertEquals(AttendanceError.AlreadyArrived, assertThrows(AttendanceError::class.java) { h.markArrival(e) })
        assertEquals(1, h.records.size)
        assertEquals(at(6, 8, 0), h.record(e, day(6))?.arrivalTime)
    }

    @Test fun forcedSecondArrivalRestartsTheDay() {
        val h = harness(at(6, 8, 0))
        val e = farm.employee("Зубахин")
        h.markArrival(e)
        h.clock = at(6, 9, 30)
        assertEquals(at(6, 9, 30), h.markArrival(e, force = true).arrivalTime)
        assertEquals(1, h.records.size)
    }

    @Test fun departureComputesMinutesWithBreak() {
        val h = harness(at(6, 8, 0))
        val e = farm.employee("Зубахин", breakMinutes = 60)
        h.markArrival(e)
        h.clock = at(6, 17, 0)
        val r = h.markDeparture(e)
        assertEquals(at(6, 17, 0), r.departureTime)
        assertEquals(480, r.workedMinutes)
        assertEquals(TodayAction.Closed(480), h.todayAction(e))
    }

    @Test fun secondDepartureThrowsAlreadyLeftUnlessForced() {
        val h = harness(at(6, 8, 0))
        val e = farm.employee("Зубахин", breakMinutes = 60)
        h.markArrival(e)
        h.clock = at(6, 17, 0)
        h.markDeparture(e)
        h.clock = at(6, 18, 0)
        assertEquals(AttendanceError.AlreadyLeft, assertThrows(AttendanceError::class.java) { h.markDeparture(e) })
        assertEquals(540, h.markDeparture(e, force = true).workedMinutes)
        assertEquals(1, h.records.size)
    }

    @Test fun departureWithoutArrivalThrowsNotArrived() {
        val h = harness(at(6, 17, 0))
        val e = farm.employee("Зубахин")
        assertEquals(AttendanceError.NotArrived, assertThrows(AttendanceError::class.java) { h.markDeparture(e) })
        assertEquals(0, h.records.size)
    }

    @Test fun nightShiftBelongsToArrivalDay() {
        val h = harness(at(5, 22, 0))
        val e = farm.employee("Зубахин", breakMinutes = 30)
        h.markArrival(e)
        h.clock = at(6, 6, 0)
        assertEquals(TodayAction.MarkDeparture(at(5, 22, 0)), h.todayAction(e))
        val r = h.markDeparture(e)
        assertEquals(450, r.workedMinutes)
        assertEquals(farm.startOf(day(5)), r.day)
        assertEquals(1, h.records.size)
        assertEquals(TodayAction.MarkArrival, h.todayAction(e))
    }

    @Test fun eightHourNightShiftIsStillOpen() {
        val h = harness(at(5, 22, 0))
        val e = farm.employee("Зубахин", breakMinutes = 30)
        h.markArrival(e)
        h.clock = at(6, 6, 0)
        assertEquals(TodayAction.MarkDeparture(at(5, 22, 0)), h.todayAction(e))
        assertNull(h.staleOpenRecord(e))
    }

    @Test fun forgottenDepartureDoesNotBlockTodaysArrival() {
        val h = harness(at(5, 8, 0))
        val e = farm.employee("Зубахин")
        val yesterday = h.markArrival(e)
        h.clock = at(6, 9, 0)
        assertNull(h.openRecord(e))
        assertEquals(TodayAction.MarkArrival, h.todayAction(e))
        assertEquals(yesterday.id, h.staleOpenRecord(e)?.id)
        val today = h.markArrival(e)
        assertNotEquals(today.id, yesterday.id)
        assertEquals(farm.startOf(day(6)), today.day)
        assertEquals(at(6, 9, 0), today.arrivalTime)
        val kept = h.records.first { it.id == yesterday.id }
        assertEquals(farm.startOf(day(5)), kept.day)
        assertNull(kept.departureTime)
        assertNull(kept.workedMinutes)
        assertEquals(2, h.records.size)
        assertEquals(TodayAction.MarkDeparture(at(6, 9, 0)), h.todayAction(e))
    }

    @Test fun marksAreTruncatedToWholeMinutes() {
        val h = harness(at(6, 8, 2).plusSeconds(50))
        val e = farm.employee("Зубахин", breakMinutes = 60)
        h.markArrival(e)
        h.clock = at(6, 17, 2).plusSeconds(10)
        val r = h.markDeparture(e)
        assertEquals(at(6, 8, 2), r.arrivalTime)
        assertEquals(at(6, 17, 2), r.departureTime)
        assertEquals(480, r.workedMinutes)
        val saved = h.save(e, day(6), DayStatus.PRESENT, r.arrivalTime, r.departureTime, r.breakMinutes,
            r.manualWorkedMinutes, r.comment)
        assertEquals(480, saved.workedMinutes)
    }

    @Test fun tomorrowsUnfinishedRecordIsNotOpen() {
        val h = harness(at(6, 10, 0))
        val e = farm.employee("Зубахин")
        // save refuses times for a future day, so the record goes in directly (old data or a restored backup)
        h.put(farm.record(e, day(7), arrival = at(7, 8, 0)))
        assertNull(h.openRecord(e))
        assertEquals(TodayAction.MarkArrival, h.todayAction(e))
    }

    @Test fun todaysArrivalLaterThanNowIsNotOpen() {
        val h = harness(at(6, 7, 0))
        val e = farm.employee("Зубахин")
        h.save(e, day(6), DayStatus.PRESENT, arrival = at(6, 20, 0), breakMinutes = 60)
        assertNull(h.openRecord(e))
        assertNull(h.staleOpenRecord(e))
    }

    @Test fun todaysUnfinishedRecordOlderThanSixteenHoursIsStale() {
        val h = harness(at(6, 0, 10))
        val e = farm.employee("Зубахин")
        val r = h.markArrival(e)
        h.clock = at(6, 23, 0)
        assertNull(h.openRecord(e))
        assertEquals(r.id, h.staleOpenRecord(e)?.id)
    }

    @Test fun openRecordOlderThanYesterdayIsNotOpen() {
        val h = harness(at(3, 8, 0))
        val e = farm.employee("Зубахин")
        h.markArrival(e)
        h.clock = at(6, 8, 0)
        assertNull(h.openRecord(e))
        assertEquals(TodayAction.MarkArrival, h.todayAction(e))
    }

    @Test fun saveUpsertsPastDay() {
        val h = harness(at(6, 12, 0))
        val e = farm.employee("Зубахин")
        val first = h.save(e, day(2), DayStatus.PRESENT, at(2, 8), at(2, 17), 60)
        assertEquals(480, first.workedMinutes)
        assertEquals(farm.startOf(day(2)), first.day)
        val second = h.save(e, day(2), DayStatus.PRESENT, at(2, 9), at(2, 17), 60, comment = "Опоздал")
        assertEquals(first.id, second.id)
        assertEquals(420, second.workedMinutes)
        assertEquals("Опоздал", second.comment)
        assertEquals(1, h.records.size)
    }

    @Test fun saveWithManualMinutesWins() {
        val h = harness(at(6, 18, 0))
        val e = farm.employee("Зубахин")
        h.save(e, day(6), DayStatus.PRESENT, at(6, 8), at(6, 17), 60, manualMinutes = 300)
        assertEquals(TodayAction.Closed(300), h.todayAction(e))
    }

    @Test fun nonPresentStatusesAreSavedWithoutTimes() {
        val h = harness(at(6, 12, 0))
        val e = farm.employee("Зубахин")
        val sick = h.save(e, day(6), DayStatus.SICK, at(6, 8), at(6, 17), 60, manualMinutes = 100, comment = "Справка")
        assertEquals("Б", sick.status)
        assertNull(sick.arrivalTime)
        assertNull(sick.departureTime)
        assertNull(sick.workedMinutes)
        assertNull(sick.manualWorkedMinutes)
        assertEquals(TodayAction.Status(DayStatus.SICK), h.todayAction(e))
        val dayOff = h.save(e, day(1), DayStatus.DAY_OFF)
        assertEquals("В", dayOff.status)
        assertNull(dayOff.arrivalTime)
        assertEquals(2, h.records.size)
    }

    @Test fun arrivalOnDayWithOtherStatusNeedsForce() {
        val h = harness(at(6, 8, 0))
        val e = farm.employee("Зубахин")
        h.save(e, day(6), DayStatus.SICK)
        assertEquals(
            AttendanceError.DayHasOtherStatus(DayStatus.SICK),
            assertThrows(AttendanceError::class.java) { h.markArrival(e) },
        )
        val r = h.markArrival(e, force = true)
        assertEquals("Я", r.status)
        assertEquals(at(6, 8, 0), r.arrivalTime)
        assertEquals(1, h.records.size)
    }

    @Test fun saveOutsideEmploymentThrows() {
        val h = harness(at(20, 12, 0))
        val e = farm.employee("Зубахин", hire = day(10), dismissal = day(15))
        for (d in listOf(day(9), day(16))) {
            assertEquals(
                AttendanceError.OutsideEmployment,
                assertThrows(AttendanceError::class.java) { h.save(e, d, DayStatus.PRESENT) },
            )
        }
        h.save(e, day(10), DayStatus.PRESENT)
        h.save(e, day(15), DayStatus.DAY_OFF)
        assertEquals(
            AttendanceError.OutsideEmployment,
            assertThrows(AttendanceError::class.java) { h.markArrival(e) },
        )
        assertEquals(2, h.records.size)
    }

    @Test fun saveRefusesPresenceAndTimesForFutureDays() {
        val h = harness(at(6, 12, 0))
        val e = farm.employee("Зубахин")
        val tomorrow = day(7)
        fun refused(status: DayStatus, arrival: Instant? = null, departure: Instant? = null) = assertEquals(
            AttendanceError.FutureTimes,
            assertThrows(AttendanceError::class.java) { h.save(e, tomorrow, status, arrival, departure) },
        )
        refused(DayStatus.PRESENT)
        refused(DayStatus.SICK, arrival = at(7, 8))
        refused(DayStatus.VACATION, departure = at(7, 17))
        assertEquals(0, h.records.size)
        DayStatus.futureStatuses.forEachIndexed { offset, status ->
            h.save(e, day(7 + offset), status, comment = "План")
        }
        h.save(e, day(6), DayStatus.PRESENT, arrival = at(6, 8), breakMinutes = 60)
        assertEquals(DayStatus.futureStatuses.size + 1, h.records.size)
        assertEquals("Время можно отметить только за прошедшие дни и сегодня", AttendanceError.FutureTimes.message)
    }

    @Test fun noRecordMeansMarkArrivalNeverAbsent() {
        val h = harness(at(6, 18, 0))
        val e = farm.employee("Зубахин")
        assertEquals(TodayAction.MarkArrival, h.todayAction(e))
        assertNull(h.record(e, day(6)))
        assertEquals(0, h.records.size)
    }

    @Test fun errorsHaveRussianDescriptions() {
        assertEquals("Приход уже отмечен", AttendanceError.AlreadyArrived.message)
        assertEquals("Сначала отметьте приход", AttendanceError.NotArrived.message)
        assertEquals("Уход уже отмечен", AttendanceError.AlreadyLeft.message)
        assertEquals(
            "На этот день уже стоит статус «Больничный»",
            AttendanceError.DayHasOtherStatus(DayStatus.SICK).message,
        )
        assertEquals(
            "В этот день сотрудник не работал в хозяйстве (до приёма или после увольнения)",
            AttendanceError.OutsideEmployment.message,
        )
        assertEquals(
            "Явку можно отметить только за прошедшие дни и сегодня. На будущие дни — выходной, отпуск, больничный и другие статусы.",
            AttendanceError.FuturePresence.message,
        )
    }

    @Test fun recordWithManualTotalIsNotOpenOrStale() {
        val h = harness(at(6, 10, 0))
        val e = farm.employee("Зубахин")
        h.save(e, day(6), DayStatus.PRESENT, arrival = at(6, 8), breakMinutes = 60, manualMinutes = 240)
        assertNull(h.openRecord(e))
        assertEquals(TodayAction.Closed(240), h.todayAction(e))
        h.clock = at(7, 9, 0)
        assertNull(h.staleOpenRecord(e))
    }

    @Test fun recordLookupUsesTheFarmDayNotTheStoredInstant() {
        val h = harness(at(6, 12, 0))
        val e = farm.employee("Зубахин")
        h.put(farm.record(e, day(5), status = "Б").copy(day = at(5, 13)))
        assertNotNull(h.record(e, day(5)))
        assertTrue(h.record(e, day(6)) == null)
    }
}
