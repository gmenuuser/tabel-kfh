package ru.kfh.tabel.timesheet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.Entities
import ru.kfh.core.roles.Role
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.AttendanceError
import ru.kfh.core.timesheet.DayStatus
import ru.kfh.core.timesheet.EmployeeDraft
import ru.kfh.core.timesheet.TimesheetKind
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.timesheet.TimesheetViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

/** The timesheet module over a real Room store: what a tap writes, and what the screens then see. */
class TimesheetViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 2026-11-06 16:30 UTC: 19:30 in Moscow, already 01:30 of 7 November at the farm (Asia/Chita, UTC+9). */
    private val moment: Instant = Instant.parse("2026-11-06T16:30:00Z")
    private val chita: ZoneId = ZoneId.of("Asia/Chita")
    private val farm = FarmTime(chita, Clock.fixed(moment, chita))
    private val farmDay: LocalDate = LocalDate.of(2026, 11, 7)

    private lateinit var db: TabelDb
    private lateinit var scope: CoroutineScope
    private lateinit var vm: TimesheetViewModel
    private var role = Role.owner
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        db = openTestDb(tmp.newFile("t.db").also { it.delete() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = TimesheetViewModel(
            dao = db.records(),
            writer = RecordWriter(db) { role },
            farmTime = MutableStateFlow(farm),
            scope = scope,
            photos = PhotoStore(tmp.newFolder("photos")),
            now = { moment },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        TimeZone.setDefault(defaultZone)
    }

    private suspend fun addEmployee(lastName: String = "Зубахин", firstName: String = "Иван"): String {
        val draft = EmployeeDraft(lastName = lastName, firstName = firstName, hireDate = farmDay.minusDays(30))
        val saved = vm.saveEmployee(existing = null, draft = draft, photo = null, removePhoto = false)
        return saved.getOrThrow().id
    }

    private suspend fun employee(id: String) = vm.employee(id).first()!!

    private suspend fun ops() = db.outbox().all()

    @Test
    fun todayIsFarmDay() {
        // The phone is in Moscow, where it is still 6 November: «Сегодня» is the farm's 7 November.
        assertEquals(farmDay, vm.today())
        assertEquals(farmDay, vm.day.value)
        assertEquals(YearMonth.of(2026, 11), vm.month.value)
    }

    @Test
    fun markWritesStableHoursMarkId() = runTest {
        val id = addEmployee()
        val e = employee(id)
        assertNull(vm.tapHours(e, farmDay, 8))

        val op = ops().last()
        assertEquals(Entities.hoursMark, op.entity)
        assertEquals(StableId.hoursMark(id, farmDay), op.recordId)
        assertEquals(op.recordId.lowercase(), op.recordId)
        assertEquals(JsonPrimitive(8), op.fieldsJson()["hours"])
        // The mark's day is the farm midnight of 7 November (2026-11-06T15:00:00Z), not the phone's.
        assertEquals(JsonPrimitive("2026-11-06T15:00:00.000Z"), op.fieldsJson()["day"])

        val row = vm.dayState(farmDay).first().rows.single()
        assertEquals(8, row.hours)
    }

    @Test
    fun clearHoursWritesNull() = runTest {
        val id = addEmployee()
        val e = employee(id)
        vm.tapHours(e, farmDay, 8)
        val before = ops().size
        // The selected value again removes the mark: `hours: null`, the record stays on the server.
        assertNull(vm.tapHours(e, farmDay, 8))
        val all = ops()
        assertEquals(before + 1, all.size)
        val op = all.last()
        assertEquals(Entities.hoursMark, op.entity)
        assertEquals(StableId.hoursMark(id, farmDay), op.recordId)
        assertEquals("upsert", op.kind)
        assertEquals(JsonNull, op.fieldsJson()["hours"])
        assertNull(vm.dayState(farmDay).first().rows.single().hours)
        // Marking again after removal writes the hours back on the same id.
        assertNull(vm.tapHours(e, farmDay, 4))
        assertEquals(4, vm.dayState(farmDay).first().rows.single().hours)
        assertEquals(StableId.hoursMark(id, farmDay), ops().last().recordId)
    }

    @Test
    fun presentOnFutureDayRefused() = runTest {
        val id = addEmployee()
        val e = employee(id)
        val tomorrow = farmDay.plusDays(1)
        val before = ops().size

        assertEquals(AttendanceError.FuturePresence.message, vm.setStatus(e, tomorrow, DayStatus.PRESENT))
        assertEquals(AttendanceError.FuturePresence.message, vm.togglePresence(e, tomorrow))
        assertEquals("nothing written", before, ops().size)
        assertNull(vm.dayState(tomorrow).first().rows.single().status)
        assertEquals(DayStatus.futureStatuses, vm.dayState(tomorrow).first().statuses)

        // Every other status can be set ahead.
        assertNull(vm.setStatus(e, tomorrow, DayStatus.DAY_OFF))
        assertEquals(DayStatus.DAY_OFF, vm.dayState(tomorrow).first().rows.single().status)
        assertEquals(Entities.attendanceRecord, ops().last().entity)

        // Today «Я» is one tap, and the same tap removes it (a delete op).
        assertNull(vm.togglePresence(e, farmDay))
        assertEquals(DayStatus.PRESENT, vm.dayState(farmDay).first().rows.single().status)
        assertEquals(DayStatus.entries.toList(), vm.dayState(farmDay).first().statuses)
        assertNull(vm.togglePresence(e, farmDay))
        assertNull(vm.dayState(farmDay).first().rows.single().status)
        assertEquals("delete", ops().last().kind)
    }

    @Test
    fun archivedEmployeeHiddenButHistoryKept() = runTest {
        val id = addEmployee()
        val other = addEmployee("Мунгалов", "Сергей")
        val e = employee(id)
        vm.tapHours(e, farmDay, 12)
        assertEquals(listOf(other, id).sorted(), vm.dayState(farmDay).first().rows.map { it.employee.id }.sorted())

        assertNull(vm.archive(e))
        assertTrue(employee(id).isArchived)
        // Hidden from the day...
        assertEquals(listOf(other), vm.dayState(farmDay).first().rows.map { it.employee.id })
        // ...but the month's timesheet and the history keep the marks.
        val document = vm.monthDocument(YearMonth.of(2026, 11), TimesheetKind.HOURS).first()
        val row = document.rows.first { it.employee.id == id }
        assertEquals(12, row.cells[farmDay.dayOfMonth - 1].hours)
        assertEquals(12, row.hoursTotals?.hours)
        val history = vm.history(id, YearMonth.of(2026, 11)).first()
        assertNotNull(history)
        assertEquals(12, history!!.days.first { it.day == farmDay }.hours)
        assertEquals(12, history.totals.hours)
        assertEquals(1, history.totals.workedDays)
        // Days are newest first, employment only.
        assertEquals(farmDay.withDayOfMonth(30), history.days.first().day)

        assertNull(vm.restore(employee(id)))
        assertEquals(2, vm.dayState(farmDay).first().rows.size)
    }

    @Test
    fun monthOfCodesShowsTheStatusMark() = runTest {
        val id = addEmployee()
        vm.togglePresence(employee(id), farmDay)
        val codes = vm.monthDocument(YearMonth.of(2026, 11), TimesheetKind.CODES).first()
        assertEquals("Я", codes.rows.single().cells[farmDay.dayOfMonth - 1].text)
        val hours = vm.monthDocument(YearMonth.of(2026, 11), TimesheetKind.HOURS).first()
        assertEquals("", hours.rows.single().cells[farmDay.dayOfMonth - 1].text)
    }

    @Test
    fun viewerCannotChangeAnything() = runTest {
        val id = addEmployee()
        val e = employee(id)
        role = Role.viewer
        assertTrue(!vm.canEditMarks && !vm.canEditEmployees)
        val before = ops().size
        assertNotNull(vm.tapHours(e, farmDay, 8))
        assertNotNull(vm.togglePresence(e, farmDay))
        assertNotNull(vm.archive(e))
        assertEquals(before, ops().size)
    }

    @Test
    fun employeeFormIsValidated() = runTest {
        val bad = vm.saveEmployee(null, EmployeeDraft(hireDate = farmDay), photo = null, removePhoto = false)
        assertEquals("Укажи фамилию\nУкажи имя", bad.exceptionOrNull()?.message)
        assertEquals(0, ops().size)
    }
}
