package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.time.FarmTime

/** What the big button on an employee's Home card does today. */
sealed class TodayAction {
    data object MarkArrival : TodayAction()
    data class MarkDeparture(val openSince: Instant) : TodayAction()
    data class Closed(val minutes: Int) : TodayAction()
    data class Status(val status: DayStatus) : TodayAction()
}

/** Refusals of the attendance rules; `message` is the text shown to the owner. */
sealed class AttendanceError(message: String) : Exception(message) {
    data object AlreadyArrived : AttendanceError("Приход уже отмечен")
    data object AlreadyLeft : AttendanceError("Уход уже отмечен")
    data class DayHasOtherStatus(val status: DayStatus) :
        AttendanceError("На этот день уже стоит статус «${status.title}»")
    data object NotArrived : AttendanceError("Сначала отметьте приход")
    data object OutsideEmployment :
        AttendanceError("В этот день сотрудник не работал в хозяйстве (до приёма или после увольнения)")
    data object FutureTimes : AttendanceError("Время можно отметить только за прошедшие дни и сегодня")
    data object FuturePresence : AttendanceError(
        "Явку можно отметить только за прошедшие дни и сегодня. На будущие дни — выходной, отпуск, больничный и другие статусы.",
    )
}

/** What a status change does to the store: write this record, or delete this one (null = nothing to delete). */
sealed class DayMarkChange {
    abstract val status: DayStatus?

    data class Put(val record: AttendanceRecord) : DayMarkChange() {
        override val status: DayStatus get() = record.dayStatus()
    }

    data class Delete(val record: AttendanceRecord?) : DayMarkChange() {
        override val status: DayStatus? get() = null
    }
}

/**
 * Attendance marks as pure functions of the stored records: each call takes the records (all of them, any
 * employee) and returns the record to write, never touching a store. Keeps one record per (employee, day); the day
 * of a record is the arrival day, in the farm's zone. Port of `AttendanceService`.
 *
 * Hours marks are not here: a removed hours mark is never an attendance record.
 */
class AttendanceRules(
    private val farm: FarmTime,
    private val now: () -> Instant,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        /** An arrival without a departure counts as «на работе» for at most this long. */
        const val MAX_OPEN_SHIFT_SECONDS = 16 * 3600L
    }

    private val today: LocalDate get() = farm.dayOf(now())

    /** Now, truncated to the whole minute: marks are stored as hh:mm:00. */
    private fun currentMinute(): Instant = now().truncatedTo(ChronoUnit.MINUTES)

    private fun dayOf(r: AttendanceRecord) = farm.dayOf(r.day)

    fun record(e: Employee, records: List<AttendanceRecord>, day: LocalDate): AttendanceRecord? =
        records.firstOrNull { it.employeeId == e.id && dayOf(it) == day }

    /**
     * The latest Я record from today or yesterday with an arrival no later than now and no more than 16 hours ago,
     * and no departure. Yesterday counts so a night shift (or the app left open past midnight) still shows «Ушёл»;
     * a departure forgotten long ago does not block today's arrival.
     */
    fun openRecord(e: Employee, records: List<AttendanceRecord>): AttendanceRecord? {
        val moment = now()
        return unfinishedRecords(e, records).firstOrNull { r ->
            val arrival = r.arrivalTime ?: return@firstOrNull false
            !arrival.isAfter(moment) && secondsBetween(arrival, moment) <= MAX_OPEN_SHIFT_SECONDS
        }
    }

    /**
     * Today's or yesterday's Я record whose departure was never marked and whose arrival is more than 16 hours ago.
     * It stays unfinished (0 hours) until the owner fixes it.
     */
    fun staleOpenRecord(e: Employee, records: List<AttendanceRecord>): AttendanceRecord? {
        val moment = now()
        return unfinishedRecords(e, records).firstOrNull { r ->
            val arrival = r.arrivalTime ?: return@firstOrNull false
            secondsBetween(arrival, moment) > MAX_OPEN_SHIFT_SECONDS
        }
    }

    private fun secondsBetween(from: Instant, to: Instant): Double =
        (to.toEpochMilli() - from.toEpochMilli()) / 1000.0

    /**
     * Я records of yesterday and today (never later days) with an arrival, no departure and no manual total (a manual
     * total closes the day, as in the timesheet), latest day first.
     */
    private fun unfinishedRecords(e: Employee, records: List<AttendanceRecord>): List<AttendanceRecord> {
        val to = today
        val from = to.minusDays(1)
        return records
            .filter { r ->
                val d = dayOf(r)
                r.employeeId == e.id && !d.isBefore(from) && !d.isAfter(to) &&
                    r.dayStatus() == DayStatus.PRESENT && r.arrivalTime != null && r.departureTime == null &&
                    r.manualWorkedMinutes == null
            }
            .sortedByDescending { dayOf(it) }
    }

    fun todayAction(e: Employee, records: List<AttendanceRecord>): TodayAction {
        val open = openRecord(e, records)
        val since = open?.arrivalTime
        if (since != null) return TodayAction.MarkDeparture(since)
        val r = record(e, records, today) ?: return TodayAction.MarkArrival
        return if (r.dayStatus() == DayStatus.PRESENT) {
            TodayAction.Closed(WorkTimeCalculator.effectiveMinutes(r))
        } else {
            TodayAction.Status(r.dayStatus())
        }
    }

    fun isEmployed(e: Employee, day: LocalDate): Boolean {
        if (day.isBefore(farm.dayOf(e.hireDate))) return false
        val dismissal = e.dismissalDate
        if (dismissal != null && day.isAfter(farm.dayOf(dismissal))) return false
        return true
    }

    /**
     * Status Я, arrival = now, break = the employee's default. Without `force` it refuses when the employee
     * already arrived or the day has another status; with `force` today's record is started again.
     */
    fun markArrival(e: Employee, records: List<AttendanceRecord>, force: Boolean = false): AttendanceRecord {
        val moment = currentMinute()
        val day = farm.dayOf(moment)
        if (!isEmployed(e, day)) throw AttendanceError.OutsideEmployment
        val existing = record(e, records, day)
        if (!force) {
            if (openRecord(e, records) != null) throw AttendanceError.AlreadyArrived
            if (existing != null) {
                throw if (existing.dayStatus() == DayStatus.PRESENT) {
                    AttendanceError.AlreadyArrived
                } else {
                    AttendanceError.DayHasOtherStatus(existing.dayStatus())
                }
            }
        }
        return (existing ?: newRecord(e, day)).copy(
            status = DayStatus.PRESENT.code, arrivalTime = moment, departureTime = null, workedMinutes = null,
            manualWorkedMinutes = null, breakMinutes = e.defaultBreakMinutes, updatedAt = moment,
        )
    }

    /**
     * Closes the open record (today's or yesterday's) and computes the worked minutes. Without an open record:
     * `force` moves the departure of today's closed day to now.
     */
    fun markDeparture(e: Employee, records: List<AttendanceRecord>, force: Boolean = false): AttendanceRecord {
        val moment = currentMinute()
        val open = openRecord(e, records)
        val r = if (open != null) {
            open
        } else {
            val closed = record(e, records, farm.dayOf(moment))
            if (closed != null && closed.dayStatus() == DayStatus.PRESENT && closed.arrivalTime != null &&
                closed.departureTime != null
            ) {
                if (!force) throw AttendanceError.AlreadyLeft
                closed
            } else {
                throw AttendanceError.NotArrived
            }
        }
        val arrival = r.arrivalTime ?: throw AttendanceError.NotArrived
        return r.copy(
            departureTime = moment,
            workedMinutes = WorkTimeCalculator.workedMinutes(arrival, moment, r.breakMinutes),
            manualWorkedMinutes = null, updatedAt = moment,
        )
    }

    /**
     * Creates or updates the record of (employee, day), any day within employment. Times and the manual total are
     * kept only for Я; other statuses are stored without them. A day after today takes no Я and no times; the other
     * statuses can be set ahead.
     */
    fun save(
        e: Employee, records: List<AttendanceRecord>, day: LocalDate, status: DayStatus, arrival: Instant?,
        departure: Instant?, breakMinutes: Int, manualMinutes: Int?, comment: String,
    ): AttendanceRecord {
        if (!isEmployed(e, day)) throw AttendanceError.OutsideEmployment
        if (day.isAfter(today) && (status == DayStatus.PRESENT || arrival != null || departure != null)) {
            throw AttendanceError.FutureTimes
        }
        val base = (record(e, records, day) ?: newRecord(e, day)).copy(
            status = status.code, breakMinutes = maxOf(0, breakMinutes), comment = comment, updatedAt = now(),
        )
        if (status != DayStatus.PRESENT) {
            return base.copy(arrivalTime = null, departureTime = null, workedMinutes = null, manualWorkedMinutes = null)
        }
        val leaves = if (arrival == null) null else departure
        val worked = if (arrival != null && leaves != null) {
            WorkTimeCalculator.workedMinutes(arrival, leaves, base.breakMinutes)
        } else {
            null
        }
        return base.copy(
            arrivalTime = arrival, departureTime = leaves, workedMinutes = worked,
            manualWorkedMinutes = manualMinutes?.let { maxOf(0, it) },
        )
    }

    // Marks without times (the timesheet of statuses).

    /** The status of (employee, day); null = не отмечен. */
    fun status(e: Employee, records: List<AttendanceRecord>, day: LocalDate): DayStatus? =
        record(e, records, day)?.dayStatus()

    /**
     * Sets the day's status; null removes the mark. Only this one day changes. An old Я record keeps its arrival
     * and departure (history) while it stays Я; another status is stored without times.
     */
    fun setStatus(e: Employee, records: List<AttendanceRecord>, day: LocalDate, status: DayStatus?): DayMarkChange {
        if (!isEmployed(e, day)) throw AttendanceError.OutsideEmployment
        if (status == DayStatus.PRESENT && day.isAfter(today)) throw AttendanceError.FuturePresence
        val existing = record(e, records, day)
        if (status == null) return DayMarkChange.Delete(existing)
        var r = existing ?: newRecord(e, day)
        if (existing == null || r.dayStatus() != status) {
            r = r.copy(status = status.code, updatedAt = now())
            if (status != DayStatus.PRESENT) {
                r = r.copy(arrivalTime = null, departureTime = null, workedMinutes = null, manualWorkedMinutes = null)
            }
        }
        return DayMarkChange.Put(r)
    }

    /** The big button: не отмечен -> Я, Я -> не отмечен, any other status -> Я. */
    fun togglePresence(e: Employee, records: List<AttendanceRecord>, day: LocalDate): DayMarkChange =
        setStatus(e, records, day, if (status(e, records, day) == DayStatus.PRESENT) null else DayStatus.PRESENT)

    /** «Отметить всех»: Я for everyone in `employees` employed that day who has no mark; the new records. */
    fun markAllPresent(employees: List<Employee>, records: List<AttendanceRecord>, day: LocalDate): List<AttendanceRecord> {
        if (day.isAfter(today)) throw AttendanceError.FuturePresence
        return employees
            .filter { isEmployed(it, day) && record(it, records, day) == null }
            .map { newRecord(it, day).copy(status = DayStatus.PRESENT.code) }
    }

    private fun newRecord(e: Employee, day: LocalDate): AttendanceRecord {
        val moment = now()
        return AttendanceRecord(
            id = newId(), employeeId = e.id, day = farm.startOf(day), breakMinutes = e.defaultBreakMinutes,
            createdAt = moment, updatedAt = moment,
        )
    }
}
