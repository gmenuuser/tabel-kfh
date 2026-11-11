package ru.kfh.tabel.ui.timesheet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.model.Entities
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.AttendanceError
import ru.kfh.core.timesheet.AttendanceRules
import ru.kfh.core.timesheet.DayMarkChange
import ru.kfh.core.timesheet.DayMarkRules
import ru.kfh.core.timesheet.DayStatus
import ru.kfh.core.timesheet.EmployeeDraft
import ru.kfh.core.timesheet.EmployeeError
import ru.kfh.core.timesheet.EmployeeRules
import ru.kfh.core.timesheet.HoursChange
import ru.kfh.core.timesheet.HoursError
import ru.kfh.core.timesheet.HoursMarkRules
import ru.kfh.core.timesheet.HoursTotals
import ru.kfh.core.timesheet.TimesheetBuilder
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.TimesheetKind
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.observe
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.ui.common.ActionQueue
import ru.kfh.tabel.ui.common.FarmToday
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * «Табель работников»: the store's employees, hours marks and status records as flows the screens collect, and
 * the actions of the screens as the rules of the domain (`HoursMarkRules`, `AttendanceRules`, `EmployeeRules`)
 * written through [RecordWriter]. One per process ([ru.kfh.tabel.AppGraph.timesheet]).
 *
 * - «Сегодня», the day of a mark and «this month» are the farm's ([farmTime]), never the phone's.
 * - Every action runs in [scope] (the app's): leaving a screen in the middle never cancels a write.
 * - An action returns the text for «Не получилось» (the domain's Russian message), or null when it went through.
 * - Removing an hours mark writes `hours: null` (the iPhone's way); removing a status deletes the record.
 */
class TimesheetViewModel(
    private val dao: RecordDao,
    private val writer: RecordWriter,
    private val farmTime: StateFlow<FarmTime>,
    private val scope: CoroutineScope,
    val photos: PhotoStore,
    private val now: () -> Instant = { Instant.now() },
    /** The farm's name and manager (from `Me`) and the settings row: the header of the timesheet. */
    val farmInfo: Flow<FarmInfo> = kotlinx.coroutines.flow.flowOf(FarmInfo.DEFAULT),
) {
    /** One person of the day: their hours mark, their status mark, their photo (if any). */
    data class DayRow(val employee: Employee, val hours: Int?, val status: DayStatus?, val photo: File?)

    /** The day screen: who works that day with their marks, and which statuses the day takes. */
    data class DayState(val day: LocalDate, val today: LocalDate, val rows: List<DayRow>, val statuses: List<DayStatus>) {
        val isFuture: Boolean get() = day.isAfter(today)
        val canPresent: Boolean get() = DayStatus.PRESENT in statuses
        val marked: Int get() = rows.count { it.hours != null }
        val hours: Int get() = rows.sumOf { it.hours ?: 0 }
        val unmarked: Int get() = rows.size - marked
    }

    data class HistoryDay(val day: LocalDate, val hours: Int?)

    /** One employee's month: the days of employment, newest first, and the totals of the marked ones. */
    data class History(val employee: Employee, val days: List<HistoryDay>, val totals: HoursTotals, val photo: File?)

    /**
     * «Сегодня» as the screens see it. The iPhone recomputes it on every render and on scene activation; here the
     * day screen refreshes it when it resumes and at the farm's next midnight, and the sign-in resets it. When the
     * day advanced and the screen was on the old today, it follows to the new today (as the iPhone's `HomeView`,
     * whose `today` is live); a day chosen by hand stays.
     */
    private val farmToday: FarmToday = FarmToday(farmTime, scope, now) { old, fresh -> if (day.value == old) day.value = fresh }

    val farm: FarmTime get() = farmToday.farm

    /** The farm's day of this moment (the clock, not the cached [todayFlow]). */
    fun today(): LocalDate = farmToday.today()

    val todayFlow: StateFlow<LocalDate> get() = farmToday.flow

    /** The chosen day of the day screen; kept while the person walks to the month or the employees and back. */
    val day: MutableStateFlow<LocalDate> = MutableStateFlow(farmToday.flow.value)

    /** The month of the month screen, kept between visits (the iPhone's `timesheetMonth`). */
    val month: MutableStateFlow<YearMonth> = MutableStateFlow(YearMonth.from(farmToday.flow.value))

    /** Hours (the current timesheet) or the status codes. */
    val kind = MutableStateFlow(TimesheetKind.HOURS)

    /** Recomputes «Сегодня» from the clock and the farm's zone (see [farmToday]). */
    fun refreshToday() {
        farmToday.refresh()
    }

    /** A sign-in (a farm, maybe another zone): the day screen opens on today and the month screen on this month. */
    fun resetDays() {
        val fresh = farmToday.reset()
        // The month first: a screen (or a test) that waits for the day then sees the month already reset.
        month.value = YearMonth.from(fresh)
        day.value = fresh
    }

    /**
     * [resetDays] on every sign-in and never on the `SignedIn(me)` the session re-emits after a sync: a day or month
     * picked by hand must survive that (a zone change is followed through [farmToday] alone).
     */
    fun resetOnSignIn(states: Flow<SessionState>) = farmToday.onSignIn(scope, states) { resetDays() }

    /** Milliseconds until the farm's next midnight after [todayFlow]'s day (at least one second): when to refresh. */
    fun millisUntilNextFarmDay(): Long = farmToday.millisUntilNextFarmDay()

    /** Writes one at a time: a second tap reads what the first one wrote. */
    private val actions = ActionQueue(scope)

    val canEditMarks: Boolean get() = writer.canWrite(Entities.hoursMark) && writer.canWrite(Entities.attendanceRecord)
    val canEditEmployees: Boolean get() = writer.canWrite(Entities.employee)

    // MARK: What the screens show

    // Decoding runs off the main thread (the screens collect with collectAsState).
    val employees: Flow<List<Employee>> = dao.observe(Entities.employee).map { rows ->
        rows.map { RecordCodec.decode<Employee>(it.entity, it.id, it.json()) }
    }.flowOn(Dispatchers.Default)

    val marks: Flow<List<HoursMark>> = dao.observe(Entities.hoursMark).map { rows ->
        rows.map { RecordCodec.decode<HoursMark>(it.entity, it.id, it.json()) }
    }.flowOn(Dispatchers.Default)

    val attendance: Flow<List<AttendanceRecord>> = dao.observe(Entities.attendanceRecord).map { rows ->
        rows.map { RecordCodec.decode<AttendanceRecord>(it.entity, it.id, it.json()) }
    }.flowOn(Dispatchers.Default)

    /** The employee with [id]; always null for a blank id (a new employee's form). */
    fun employee(id: String): Flow<Employee?> =
        if (id.isBlank()) flowOf(null)
        else employees.map { list -> list.firstOrNull { it.id.equals(id, ignoreCase = true) } }.distinctUntilChanged()

    /** The people of [day] (not archived, employed that day), by name, with their marks. */
    fun dayState(day: LocalDate): Flow<DayState> =
        combine(employees, marks, attendance, farmTime, todayFlow) { people, marks, records, farm, today ->
            val hoursRules = HoursMarkRules(farm, now)
            val statusRules = AttendanceRules(farm, now)
            val rows = EmployeeRules.active(people, day, farm).map { e ->
                DayRow(e, hoursRules.hoursOf(e, marks, day), statusRules.status(e, records, day), photo(e.id))
            }
            DayState(day, today, rows, DayMarkRules.statuses(day, today))
        }.flowOn(Dispatchers.Default)

    /** The month's timesheet of [kind] for everyone employed in it, archived ones too, or for [onlyEmployeeId] alone. */
    fun monthDocument(month: YearMonth, kind: TimesheetKind, onlyEmployeeId: String? = null): Flow<TimesheetDocument> =
        combine(employees, marks, attendance, farmTime, farmInfo) { people, marks, records, farm, info ->
            TimesheetBuilder.build(
                month = month, employees = people, marks = marks, attendance = records, onlyEmployeeId = onlyEmployeeId,
                settings = info, createdAt = now(), farm = farm, kind = kind,
            )
        }.flowOn(Dispatchers.Default)

    /** One employee's days of [month] within employment, newest first; null when the employee is gone. */
    fun history(employeeId: String, month: YearMonth): Flow<History?> =
        combine(employee(employeeId), marks, farmTime) { e, marks, farm ->
            if (e == null) return@combine null
            val rules = HoursMarkRules(farm, now)
            val hire = farm.dayOf(e.hireDate)
            val dismissal = e.dismissalDate?.let { farm.dayOf(it) }
            val days = (month.lengthOfMonth() downTo 1).map { month.atDay(it) }
                .filter { d -> !d.isBefore(hire) && (dismissal == null || !d.isAfter(dismissal)) }
                .map { d -> HistoryDay(d, rules.hoursOf(e, marks, d)) }
            var totals = HoursTotals()
            for (d in days) d.hours?.let { totals = totals.count(it) }
            History(e, days, totals, photo(e.id))
        }

    fun photo(employeeId: String): File? = photos.file(employeeId).takeIf { it.isFile }

    // MARK: Hours

    /** The button row: the selected value again removes the mark, another value replaces it. */
    suspend fun tapHours(e: Employee, day: LocalDate, hours: Int): String? = action {
        write(HoursMarkRules(farm, now).tap(hours, e, marks.first(), day))
    }

    /** Sets the day's hours; null removes the mark. */
    suspend fun setHours(e: Employee, day: LocalDate, hours: Int?): String? = action {
        write(HoursMarkRules(farm, now).set(hours, e, marks.first(), day))
    }

    /** The month grid knows only the employee's id (a row's snapshot). */
    suspend fun setHours(employeeId: String, day: LocalDate, hours: Int?): String? = action {
        write(HoursMarkRules(farm, now).set(hours, find(employeeId), marks.first(), day))
    }

    private suspend fun find(employeeId: String): Employee =
        employees.first().firstOrNull { it.id.equals(employeeId, ignoreCase = true) } ?: throw EmployeeGone()

    /** The employee was deleted on another device between the screen and the tap. */
    private class EmployeeGone : IllegalStateException("Сотрудник удалён")

    /** «Всем неотмеченным — 8 ч»: everyone on the day screen without a mark. */
    suspend fun markUnmarked(day: LocalDate, hours: Int = 8): String? = action {
        val people = EmployeeRules.active(employees.first(), day, farm)
        val new = HoursMarkRules(farm, now).markUnmarked(people, marks.first(), hours, day)
        if (new.isNotEmpty()) writer.saveAll(new.map { Triple(Entities.hoursMark, it.id, RecordCodec.encode(it)) })
    }

    /** The put and every removal of one tap in one transaction: one op per touched mark. */
    private suspend fun write(change: HoursChange) {
        val changes = mutableListOf<Triple<String, String, JsonObject>>()
        change.put?.let { changes += Triple(Entities.hoursMark, it.id, RecordCodec.encode(it)) }
        for (m in change.delete) changes += Triple(Entities.hoursMark, m.id, RecordCodec.removedHoursMark(m))
        if (changes.isNotEmpty()) writer.saveAll(changes)
    }

    // MARK: Statuses

    /** The big button: не отмечен → Я, Я → не отмечен, another status → Я. «Я» on a future day is refused. */
    suspend fun togglePresence(e: Employee, day: LocalDate): String? = action {
        write(AttendanceRules(farm, now).togglePresence(e, attendance.first(), day))
    }

    /** Sets the day's status; null removes the mark. */
    suspend fun setStatus(e: Employee, day: LocalDate, status: DayStatus?): String? = action {
        write(AttendanceRules(farm, now).setStatus(e, attendance.first(), day, status))
    }

    suspend fun setStatus(employeeId: String, day: LocalDate, status: DayStatus?): String? = action {
        write(AttendanceRules(farm, now).setStatus(find(employeeId), attendance.first(), day, status))
    }

    private suspend fun write(change: DayMarkChange) {
        when (change) {
            is DayMarkChange.Put -> writer.save(Entities.attendanceRecord, change.record.id, RecordCodec.encode(change.record))
            is DayMarkChange.Delete -> change.record?.let { writer.delete(Entities.attendanceRecord, it.id) }
        }
    }

    // MARK: Employees

    /**
     * Saves the form: a new employee (`existing == null`, a new lowercase id) or the changed one. The photo stays on
     * this phone only: [photo] replaces it, [removePhoto] deletes it. The failure is the form's validation text.
     */
    suspend fun saveEmployee(
        existing: Employee?, draft: EmployeeDraft, photo: ByteArray?, removePhoto: Boolean,
    ): Result<Employee> = actions.run {
        run {
            try {
                val e = if (existing == null) {
                    EmployeeRules.create(draft, RecordWriter.newId(), farm)
                } else {
                    EmployeeRules.update(existing, draft, farm)
                }
                fun applyPhoto() {
                    if (photo != null) photos.save(e.id, photo) else if (removePhoto) photos.delete(e.id)
                }
                if (existing == null) {
                    // A new employee: the photo (local only) first, so a refused record write leaves nothing half-saved
                    // and a retry of the form never queues a second employee.
                    applyPhoto()
                    try {
                        writer.save(Entities.employee, e.id, RecordCodec.encode(e))
                    } catch (ex: Exception) {
                        photos.delete(e.id)
                        throw ex
                    }
                } else {
                    // An existing one: the record first, so a refused write keeps the old photo; the same id is
                    // written again on a retry (no duplicate, no second op when nothing changed).
                    writer.update(Entities.employee, e.id, RecordCodec.encode(e))
                    applyPhoto()
                }
                Result.success(e)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                Result.failure(ex)
            }
        }
    }

    /** Archiving only hides the employee from the day screen; the marks and timesheets stay. */
    suspend fun archive(e: Employee): String? = action {
        writer.update(Entities.employee, e.id, RecordCodec.encode(EmployeeRules.archive(e)))
    }

    suspend fun restore(e: Employee): String? = action {
        writer.update(Entities.employee, e.id, RecordCodec.encode(EmployeeRules.restore(e)))
    }

    // MARK: Running

    /** Runs the change in the app's scope; the domain's refusal (or the role's) comes back as the text to show. */
    private suspend fun action(block: suspend () -> Unit): String? = actions.run {
        try {
            block()
            null
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                is AttendanceError, is HoursError, is EmployeeError -> e.message
                is IllegalStateException -> e.message ?: "Не получилось"
                else -> "Не получилось: ${e.message ?: e::class.java.simpleName}"
            }
        }
    }
}
