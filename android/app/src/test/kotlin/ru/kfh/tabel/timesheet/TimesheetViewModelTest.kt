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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import ru.kfh.core.sync.Me
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.EmployeeDraft
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordRow
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.session.ConnectReason
import ru.kfh.tabel.session.SessionState
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
    /** The controllable clock of the view model (tests move it across the farm's midnight). */
    private var clockNow: Instant = moment
    private lateinit var photos: PhotoStore

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        db = openTestDb(tmp.newFile("t.db").also { it.delete() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        photos = PhotoStore(tmp.newFolder("photos"))
        vm = TimesheetViewModel(
            dao = db.records(),
            writer = RecordWriter(db) { role },
            farmTime = MutableStateFlow(farm),
            scope = scope,
            photos = photos,
            now = { clockNow },
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
    fun hoursCanBeSetAheadAndTomorrowIsFuture() = runTest {
        val id = addEmployee()
        val e = employee(id)
        val tomorrow = farmDay.plusDays(1)
        // Hours are set on any day of employment, tomorrow too (the iPhone's `HoursService.set`).
        assertTrue(vm.dayState(tomorrow).first().isFuture)
        assertNull(vm.tapHours(e, tomorrow, 8))
        assertEquals(8, vm.dayState(tomorrow).first().rows.single().hours)
        assertEquals(Entities.hoursMark, ops().last().entity)
        // Only hours marks are ever written: no status record from any tap.
        assertEquals(emptyList<String>(), ops().filter { it.entity == Entities.attendanceRecord }.map { it.recordId })
    }

    @Test
    fun oldStatusRecordsAreShownAsHistoryAndNeverTouched() = runTest {
        val id = addEmployee()
        // Status records of the old timesheet, as the iPhone or the site wrote them and a sync pulled them (an
        // uppercase employee id too); one of a deleted employee.
        suspend fun pulled(n: Int, employeeId: String, day: LocalDate, status: String) {
            val json = buildJsonObject {
                put("employeeId", employeeId); put("day", farm.startOf(day).toString()); put("status", status)
                put("createdAt", "2026-10-01T00:00:00.000Z"); put("updatedAt", "2026-10-01T00:00:00.000Z")
            }
            db.records().upsert(RecordRow(Entities.attendanceRecord, "r-$n", n.toLong(), json.toString(), deleted = false))
        }
        pulled(1, id.uppercase(), farmDay.minusDays(1), "Я")
        pulled(2, id.uppercase(), farmDay, "В")
        pulled(3, id, farmDay.minusMonths(1), "Б")
        pulled(4, "00000000-0000-4000-8000-000000000000", farmDay, "О")
        val before = ops().size

        // «Старые отметки (архив)»: this month's two records of the employee by day, the deleted employee's line, the total.
        val archive = vm.oldMarks(YearMonth.of(2026, 11)).first()
        assertEquals(3, archive.total)
        assertEquals(listOf("Зубахин Иван", "Сотрудник удалён"), archive.lines.map { it.name })
        assertEquals("6 — Я, 7 — В", archive.lines[0].days)
        assertEquals("7 — О", archive.lines[1].days)
        assertEquals(1, vm.oldMarks(YearMonth.of(2026, 10)).first().total)
        assertEquals(0, vm.oldMarks(YearMonth.of(2026, 9)).first().total)

        // They are not hours: the day screen, the month table and the history show no mark...
        assertNull(vm.dayState(farmDay).first().rows.single().hours)
        assertEquals("", vm.monthDocument(YearMonth.of(2026, 11)).first().rows.single().cells[farmDay.dayOfMonth - 1].text)
        assertNull(vm.history(id, YearMonth.of(2026, 11)).first()!!.days.first { it.day == farmDay }.hours)
        // ...and marking hours on the same day leaves the old record exactly as it was (nothing deleted, nothing rewritten).
        assertNull(vm.tapHours(employee(id), farmDay, 8))
        assertNull(vm.tapHours(employee(id), farmDay, 8))
        assertEquals(before + 2, ops().size)
        assertTrue(ops().drop(before).all { it.entity == Entities.hoursMark })
        assertEquals("6 — Я, 7 — В", vm.oldMarks(YearMonth.of(2026, 11)).first().lines[0].days)
        val kept = db.records().ofEntity(Entities.attendanceRecord)
        assertEquals(listOf("r-1", "r-2", "r-3", "r-4"), kept.map { it.id })
        assertTrue(kept.none { it.deleted })
        assertEquals(JsonPrimitive("В"), kept[1].json()["status"])
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
        val document = vm.monthDocument(YearMonth.of(2026, 11)).first()
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
    fun viewerCannotChangeAnything() = runTest {
        val id = addEmployee()
        val e = employee(id)
        role = Role.viewer
        assertTrue(!vm.canEditMarks && !vm.canEditEmployees)
        val before = ops().size
        assertNotNull(vm.tapHours(e, farmDay, 8))
        assertNotNull(vm.setHours(id, farmDay, 4))
        assertNotNull(vm.markUnmarked(farmDay))
        assertNotNull(vm.archive(e))
        assertEquals(before, ops().size)
        // The archive is read by every role.
        assertEquals(0, vm.oldMarks(YearMonth.of(2026, 11)).first().total)
    }

    @Test
    fun employeeFormIsValidated() = runTest {
        val bad = vm.saveEmployee(null, EmployeeDraft(hireDate = farmDay), photo = null, removePhoto = false)
        assertEquals("Укажи фамилию\nУкажи имя", bad.exceptionOrNull()?.message)
        assertEquals(0, ops().size)
    }

    @Test
    fun todayFollowsTheFarmMidnight() = runTest {
        val id = addEmployee()
        assertEquals(farmDay, vm.todayFlow.value)
        assertEquals(farmDay, vm.dayState(farmDay).first().today)
        // 00:10 of 8 November at the farm (15:10 UTC on the 7th; Moscow still has the 7th).
        clockNow = Instant.parse("2026-11-07T15:10:00Z")
        // Nothing changed in the store: the cached today is still the 7th until the screen refreshes it...
        assertEquals(farmDay, vm.dayState(farmDay).first().today)
        assertTrue(vm.millisUntilNextFarmDay() in 1_000L..24 * 3_600_000L)
        // ...and on refresh «Сегодня» and the day screen (which sat on the old today) move to the 8th.
        vm.refreshToday()
        val eighth = farmDay.plusDays(1)
        assertEquals(eighth, vm.todayFlow.value)
        assertEquals(eighth, vm.day.value)
        val state = vm.dayState(eighth).first()
        assertEquals(eighth, state.today)
        assertTrue("the new today is not a future day", !state.isFuture)
        // A mark on the new today carries the farm midnight of the 8th.
        assertNull(vm.tapHours(employee(id), eighth, 8))
        assertEquals("2026-11-07T15:00:00.000Z", ops().last().fieldsJson()["day"]!!.let { (it as JsonPrimitive).content })

        // A day chosen by hand stays where it is when midnight passes again.
        vm.day.value = farmDay
        clockNow = Instant.parse("2026-11-08T15:10:00Z")
        vm.refreshToday()
        assertEquals(farmDay.plusDays(2), vm.todayFlow.value)
        assertEquals(farmDay, vm.day.value)
        // The next midnight is computed from the fresh today.
        assertEquals(farm.startOf(farmDay.plusDays(3)).toEpochMilli() - clockNow.toEpochMilli(), vm.millisUntilNextFarmDay())

        // A sign-in opens on today and this month again.
        vm.resetDays()
        assertEquals(farmDay.plusDays(2), vm.day.value)
        assertEquals(YearMonth.of(2026, 11), vm.month.value)
    }

    @Test
    fun markUnmarkedGivesEightHoursToEveryoneWithoutAMark() = runTest {
        val a = addEmployee()
        val b = addEmployee("Мунгалов", "Сергей")
        val c = addEmployee("Иванов", "Пётр")
        vm.tapHours(employee(a), farmDay, 4)
        val before = ops().size
        assertNull(vm.markUnmarked(farmDay))
        val all = ops()
        // Two new marks in one go, the 4 h of the first one untouched.
        assertEquals(before + 2, all.size)
        assertEquals(setOf(StableId.hoursMark(b, farmDay), StableId.hoursMark(c, farmDay)), all.takeLast(2).map { it.recordId }.toSet())
        assertTrue(all.takeLast(2).all { it.fieldsJson()["hours"] == JsonPrimitive(8) })
        val rows = vm.dayState(farmDay).first().rows.associate { it.employee.id to it.hours }
        assertEquals(mapOf(a to 4, b to 8, c to 8), rows)
        // Nothing left to mark: nothing written.
        assertNull(vm.markUnmarked(farmDay))
        assertEquals(before + 2, ops().size)
    }

    @Test
    fun setHoursByIdFromTheMonthAndTheHistory() = runTest {
        val id = addEmployee()
        assertNull(vm.setHours(id, farmDay, 12))
        assertEquals(12, vm.history(id, YearMonth.of(2026, 11)).first()!!.days.first { it.day == farmDay }.hours)
        // «Снять отметку» from the history: `hours: null` on the same id.
        assertNull(vm.setHours(id, farmDay, null))
        val op = ops().last()
        assertEquals(StableId.hoursMark(id, farmDay), op.recordId)
        assertEquals(JsonNull, op.fieldsJson()["hours"])
        assertNull(vm.history(id, YearMonth.of(2026, 11)).first()!!.days.first { it.day == farmDay }.hours)
        // An id nobody has is refused with a message, not silently.
        val before = ops().size
        assertEquals("Сотрудник удалён", vm.setHours("00000000-0000-4000-8000-000000000000", farmDay, 8))
        assertEquals(before, ops().size)
    }

    @Test
    fun updateKeepsTheIdAndHandlesThePhoto() = runTest {
        val id = addEmployee()
        val e = employee(id)
        val jpeg = byteArrayOf(1, 2, 3)
        val draft = EmployeeDraft.from(e, farm).copy(position = "Тракторист", defaultBreakMinutes = 30)
        val updated = vm.saveEmployee(e, draft, photo = jpeg, removePhoto = false).getOrThrow()
        assertEquals(id, updated.id)
        assertEquals("Тракторист", employee(id).position)
        assertEquals(30, employee(id).defaultBreakMinutes)
        // Only the changed fields are queued; the photo is on the phone, never in the op.
        val op = ops().last()
        assertEquals(setOf("position", "defaultBreakMinutes"), op.fieldsJson().keys)
        assertTrue(jpeg.contentEquals(photos.load(id)))
        assertNotNull(vm.photo(id))
        // «Убрать фото» with nothing else changed: no op, the file goes.
        val before = ops().size
        vm.saveEmployee(employee(id), EmployeeDraft.from(employee(id), farm), photo = null, removePhoto = true).getOrThrow()
        assertEquals(before, ops().size)
        assertNull(photos.load(id))
        assertNull(vm.photo(id))
    }

    @Test
    fun failedRecordWriteLeavesNoHalfSavedEmployee() = runTest {
        // The viewer's save is refused by the writer after the photo was written: the photo must not stay behind,
        // and the form, retried by an owner, creates the employee once.
        role = Role.viewer
        val draft = EmployeeDraft(lastName = "Зубахин", firstName = "Иван", hireDate = farmDay)
        val refused = vm.saveEmployee(null, draft, photo = byteArrayOf(9), removePhoto = false)
        assertTrue(refused.isFailure)
        assertEquals(0, ops().size)
        assertEquals(emptyList<String>(), photos.dir.list()?.toList().orEmpty())
        role = Role.owner
        val saved = vm.saveEmployee(null, draft, photo = byteArrayOf(9), removePhoto = false).getOrThrow()
        assertEquals(1, ops().size)
        assertNotNull(photos.load(saved.id))
    }

    @Test
    fun pickedDaySurvivesAProfileRefreshButNotANewConnect() = runTest {
        val me = Me(null, Role.app, "КФХ Зубахин", null, null, chita)
        val states = MutableStateFlow<SessionState>(SessionState.NotConnected(ConnectReason.Offline))
        // Another day before the connect: the wait below sees the reset itself, not the day the screen started on
        // (the collector runs on its own dispatcher and may start after the first SignedIn).
        vm.day.value = farmDay.minusDays(10)
        vm.resetOnSignIn(states)
        states.value = SessionState.SignedIn(me)
        awaitUntil { vm.day.value == farmDay }
        // The owner picks another day and month.
        val picked = farmDay.minusDays(3)
        vm.day.value = picked
        vm.month.value = YearMonth.of(2026, 10)
        // After a sync the session re-emits SignedIn with a changed Me (role, name): nothing moves.
        states.value = SessionState.SignedIn(me.copy(farmName = "КФХ Зубахин А. В."))
        states.value = SessionState.SignedIn(me.copy(farmName = "КФХ Зубахин А. В.", region = "Забайкалье"))
        kotlinx.coroutines.delay(200)
        assertEquals(picked, vm.day.value)
        assertEquals(YearMonth.of(2026, 10), vm.month.value)
        // Switched off and connected again: today and this month again. Loading is held a moment, as a connect's
        // requests hold it (a StateFlow collector that only ever sees SignedIn sees no new connect).
        states.value = SessionState.NotConnected(ConnectReason.Revoked)
        kotlinx.coroutines.delay(200)
        states.value = SessionState.Loading
        kotlinx.coroutines.delay(200)
        states.value = SessionState.SignedIn(me)
        awaitUntil { vm.day.value == farmDay }
        assertEquals(YearMonth.of(2026, 11), vm.month.value)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in 5 s")
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun refusedUpdateKeepsTheOldPhoto() = runTest {
        val id = addEmployee()
        val old = byteArrayOf(1, 1, 1)
        vm.saveEmployee(employee(id), EmployeeDraft.from(employee(id), farm), photo = old, removePhoto = false).getOrThrow()
        val before = ops().size
        role = Role.viewer
        val e = employee(id)
        val draft = EmployeeDraft.from(e, farm).copy(position = "Сторож")
        // A replaced photo and a removed photo: both refused writes leave the old photo in place.
        assertTrue(vm.saveEmployee(e, draft, photo = byteArrayOf(2, 2), removePhoto = false).isFailure)
        assertTrue(old.contentEquals(photos.load(id)))
        assertTrue(vm.saveEmployee(e, draft, photo = null, removePhoto = true).isFailure)
        assertTrue(old.contentEquals(photos.load(id)))
        assertEquals(before, ops().size)
        // The owner's retry writes the record once and then the photo.
        role = Role.owner
        vm.saveEmployee(e, draft, photo = byteArrayOf(2, 2), removePhoto = false).getOrThrow()
        assertEquals(before + 1, ops().size)
        assertTrue(byteArrayOf(2, 2).contentEquals(photos.load(id)))
    }

    @Test
    fun employmentNotes() {
        val hiredLater = ru.kfh.core.model.Employee(id = "a", hireDate = farm.startOf(farmDay.plusDays(3)))
        val dismissed = ru.kfh.core.model.Employee(id = "b", hireDate = farm.startOf(farmDay.minusDays(30)),
                                                   dismissalDate = farm.startOf(farmDay.minusDays(2)))
        val working = ru.kfh.core.model.Employee(id = "c", hireDate = farm.startOf(farmDay), dismissalDate = farm.startOf(farmDay))
        assertEquals("Принят с 10.11.2026", ru.kfh.tabel.ui.timesheet.employmentNote(hiredLater, farmDay, farm))
        assertEquals("Уволен 05.11.2026", ru.kfh.tabel.ui.timesheet.employmentNote(dismissed, farmDay, farm))
        assertNull("hired today, dismissed today: still employed today", ru.kfh.tabel.ui.timesheet.employmentNote(working, farmDay, farm))
    }
}
