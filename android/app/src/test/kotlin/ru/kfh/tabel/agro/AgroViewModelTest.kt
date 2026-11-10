package ru.kfh.tabel.agro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.agro.AgroKind
import ru.kfh.core.agro.AgroNumber
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.model.Entities
import ru.kfh.core.roles.Role
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.agro.AgroDraft
import ru.kfh.tabel.ui.agro.AgroDraftStore
import ru.kfh.tabel.ui.agro.AgroViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** The agro module over a real Room store: the drafts, and what «Сохранить» writes (one op, the site's fields). */
class AgroViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 2026-11-06 16:30 UTC: already 01:30 of 7 November at the farm (Asia/Chita, UTC+9). */
    private val moment: Instant = Instant.parse("2026-11-06T16:30:00Z")
    private val chita: ZoneId = ZoneId.of("Asia/Chita")
    private val farm = FarmTime(chita, Clock.fixed(moment, chita))

    /** What `SharedPreferences` holds between recreations: the strings under their keys. */
    private class MemoryDrafts : AgroDraftStore {
        val strings = HashMap<String, String>()
        override fun get(key: String): String? = strings[key]
        override fun put(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key) }
    }

    private lateinit var db: TabelDb
    private lateinit var scope: CoroutineScope
    private lateinit var drafts: MemoryDrafts
    private lateinit var vm: AgroViewModel
    private var role = Role.owner
    private lateinit var defaultZone: TimeZone
    private var clockNow: Instant = moment

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        db = openTestDb(tmp.newFile("a.db").also { it.delete() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        drafts = MemoryDrafts()
        vm = newVm()
    }

    private fun newVm() = AgroViewModel(
        dao = db.records(),
        writer = RecordWriter(db) { role },
        farmTime = MutableStateFlow(farm),
        scope = scope,
        drafts = drafts,
        now = { clockNow },
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        TimeZone.setDefault(defaultZone)
    }

    private suspend fun ops() = db.outbox().all()

    private val seeding = mapOf("area" to "10", "rate" to "200", "reserve" to "", "bag" to "50")

    @Test
    fun draftSurvivesRecreate() {
        assertNull(vm.draft(AgroKind.SEEDING))
        vm.storeDraft(AgroKind.SEEDING, mapOf("area" to "10", "rate" to "12,5", "reserve" to ""), "Поле у реки")
        vm.storeDraft(AgroKind.FERTILIZER, mapOf("rateUnit" to "c"), "")
        // The iPhone's key, one JSON object of strings with the title under «__title».
        assertEquals(setOf("agroDraft.seeding", "agroDraft.fertilizer"), drafts.strings.keys)
        val stored = kotlinx.serialization.json.Json.parseToJsonElement(drafts.strings.getValue("agroDraft.seeding")).jsonObject
        assertEquals(JsonPrimitive("Поле у реки"), stored["__title"])
        assertEquals(JsonPrimitive("12,5"), stored["rate"])
        // A new view model (the process was killed) reads the same draft back.
        val again = newVm()
        assertEquals(AgroDraft(mapOf("area" to "10", "rate" to "12,5", "reserve" to ""), "Поле у реки"), again.draft(AgroKind.SEEDING))
        assertEquals(AgroDraft(mapOf("rateUnit" to "c"), ""), again.draft(AgroKind.FERTILIZER))
        assertNull(again.draft(AgroKind.AREA))
        // «Очистить» drops it; a broken string reads as no draft.
        again.clearDraft(AgroKind.SEEDING)
        assertNull(newVm().draft(AgroKind.SEEDING))
        drafts.strings["agroDraft.area"] = "{not json"
        assertNull(newVm().draft(AgroKind.AREA))
    }

    @Test
    fun saveWritesAgroCalculationWithInputsAndLines() = runTest {
        vm.storeDraft(AgroKind.SEEDING, seeding, " Поле у реки ")
        val id = vm.newId()
        val saved = vm.save(AgroKind.SEEDING, seeding, " Поле у реки ", id).getOrThrow()
        assertEquals(id, saved.id)
        assertEquals(id.lowercase(), id)
        // One op, the fields as the site writes them (`web/src/pages/agro.tsx`): empty inputs dropped, the title trimmed.
        val op = ops().single()
        assertEquals(Entities.agroCalculation, op.entity)
        assertEquals("upsert", op.kind)
        assertEquals(id, op.recordId)
        val f = op.fieldsJson()
        assertEquals(JsonPrimitive("seeding"), f["kind"])
        assertEquals(JsonPrimitive("Поле у реки"), f["title"])
        assertEquals(JsonObject(mapOf("area" to JsonPrimitive("10"), "rate" to JsonPrimitive("200"), "bag" to JsonPrimitive("50"))), f["inputs"])
        val lines = f["lines"] as JsonArray
        assertEquals(4, lines.size)
        assertEquals(JsonPrimitive("Нужно семян"), lines[0].jsonObject["title"])
        assertEquals(JsonPrimitive(AgroNumber.with(2000.0, "кг")), lines[0].jsonObject["text"])
        assertEquals(JsonPrimitive(true), lines[0].jsonObject["main"])
        assertEquals(JsonPrimitive("Мешков по 50 кг"), lines[3].jsonObject["title"])
        assertEquals(JsonPrimitive("40"), lines[3].jsonObject["text"])
        assertEquals(JsonPrimitive(false), lines[3].jsonObject["main"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), f["createdAt"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), f["updatedAt"])
        // The saved one is in the history and the draft of a new calculation is gone.
        val c = vm.calculations.first().single()
        assertEquals(id, c.id)
        assertEquals("Поле у реки", c.title)
        assertEquals("Нужно семян", c.lines.first { it.main }.title)
        assertNull(vm.draft(AgroKind.SEEDING))
        // «Сохранить изменения»: the same record, only what changed, createdAt untouched.
        clockNow = moment.plusSeconds(60)
        val corrected = vm.save(AgroKind.SEEDING, seeding + ("rate" to "250"), "Поле у реки", vm.newId(), over = c).getOrThrow()
        assertEquals(id, corrected.id)
        assertEquals(moment, corrected.createdAt)
        val fix = ops().last().fieldsJson()
        assertEquals(id, ops().last().recordId)
        assertEquals(setOf("inputs", "lines", "updatedAt"), fix.keys)
        assertEquals(JsonPrimitive("2026-11-06T16:31:00.000Z"), fix["updatedAt"])
        assertEquals(AgroNumber.with(2500.0, "кг"), vm.calculations.first().single().lines[0].text)
        // «Сохранить как новый расчёт»: another record with its own id and time.
        val copy = vm.save(AgroKind.SEEDING, seeding, "Копия", vm.newId()).getOrThrow()
        assertNotEquals(id, copy.id)
        assertEquals(moment.plusSeconds(60), copy.createdAt)
        assertEquals(3, ops().size)
        assertEquals(listOf(copy.id, id), vm.calculations.first().map { it.id })
    }

    @Test
    fun incompleteOrWrongInputIsRefused() = runTest {
        val missing = vm.save(AgroKind.SEEDING, mapOf("area" to "10"), "", vm.newId()).exceptionOrNull()
        assertEquals("Заполните: Норма высева", missing?.message)
        val wrong = vm.save(AgroKind.SEEDING, mapOf("area" to "10", "rate" to "abc"), "", vm.newId()).exceptionOrNull()
        assertEquals("«Норма высева»: введите число, например 12,5", wrong?.message)
        assertTrue(ops().isEmpty())
        assertTrue(vm.calculations.first().isEmpty())
    }

    @Test
    fun doubleTapSaveCreatesOneCalculation() = runTest {
        val id = vm.newId()
        val results = coroutineScope {
            val a = async { vm.save(AgroKind.SEEDING, seeding, "", id) }
            val b = async { vm.save(AgroKind.SEEDING, seeding, "", id) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("Эта запись уже сохранена", results.first { it.isFailure }.exceptionOrNull()?.message)
        assertEquals(1, ops().size)
        assertEquals(1, vm.calculations.first().size)
    }

    @Test
    fun viewerReadsButCannotSaveOrDelete() = runTest {
        val c = vm.save(AgroKind.FUEL, mapOf("area" to "100", "rate" to "8"), "", vm.newId()).getOrThrow()
        role = Role.viewer
        assertFalse(vm.canSave)
        assertTrue(vm.save(AgroKind.FUEL, mapOf("area" to "100", "rate" to "8"), "", vm.newId()).isFailure)
        assertEquals("Нет прав на изменение этих данных", vm.delete(c))
        assertEquals(1, ops().size)
        assertEquals(listOf(c.id), vm.calculations.first().map { it.id })
        // A draft is only on the phone: a viewer may keep one.
        vm.storeDraft(AgroKind.FUEL, mapOf("area" to "1"), "")
        assertEquals(mapOf("area" to "1"), vm.draft(AgroKind.FUEL)?.inputs)
    }

    @Test
    fun historyFiltersAndDeletes() = runTest {
        val fuel = vm.save(AgroKind.FUEL, mapOf("area" to "100", "rate" to "8"), "Дальнее поле", vm.newId()).getOrThrow()
        clockNow = moment.plusSeconds(86_400)
        val seed = vm.save(AgroKind.SEEDING, seeding, "У реки", vm.newId()).getOrThrow()
        val all = vm.calculations.first()
        assertEquals(listOf(seed.id, fuel.id), all.map { it.id })
        assertEquals(listOf(fuel.id), vm.history(all, kind = AgroKind.FUEL).map { it.id })
        assertEquals(listOf(seed.id), vm.history(all, search = "реки").map { it.id })
        assertEquals(listOf(fuel.id), vm.history(all, search = "топлив").map { it.id })
        // «День» at the farm: 7 November (Chita) holds only the first one; 8 November the second.
        assertEquals(listOf(fuel.id), vm.history(all, period = LivestockPeriod.DAY, day = farm.dayOf(moment)).map { it.id })
        assertEquals(listOf(seed.id), vm.history(all, period = LivestockPeriod.DAY, day = farm.dayOf(moment).plusDays(1)).map { it.id })
        assertEquals(2, vm.history(all, period = LivestockPeriod.MONTH, day = farm.dayOf(moment)).size)
        assertNull(vm.delete(fuel))
        assertEquals("delete", ops().last().kind)
        assertEquals(listOf(seed.id), vm.calculations.first().map { it.id })
    }
}
