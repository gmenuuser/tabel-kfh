package ru.kfh.tabel.livestock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.ids.StableId
import ru.kfh.core.livestock.LivestockDraft
import ru.kfh.core.livestock.LivestockError
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.SlaughterReason
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.Entities
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.roles.Role
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.livestock.LivestockViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** The livestock module over a real Room store: what a tap writes (one transaction, one op per record) and what the herd then is. */
class LivestockViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 2026-11-06 16:30 UTC: 19:30 in Moscow, already 01:30 of 7 November at the farm (Asia/Chita, UTC+9). */
    private val moment: Instant = Instant.parse("2026-11-06T16:30:00Z")
    private val chita: ZoneId = ZoneId.of("Asia/Chita")
    private val farm = FarmTime(chita, Clock.fixed(moment, chita))

    private lateinit var db: TabelDb
    private lateinit var scope: CoroutineScope
    private lateinit var vm: LivestockViewModel
    private var role = Role.owner
    private lateinit var defaultZone: TimeZone
    private var clockNow: Instant = moment

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        db = openTestDb(tmp.newFile("l.db").also { it.delete() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = LivestockViewModel(
            dao = db.records(),
            writer = RecordWriter(db) { role },
            farmTime = MutableStateFlow(farm),
            scope = scope,
            now = { clockNow },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        TimeZone.setDefault(defaultZone)
    }

    private suspend fun ops() = db.outbox().all()

    private suspend fun opsOf(entity: String) = ops().filter { it.entity == entity }

    private suspend fun cat(species: Species, name: String): LivestockCategory =
        vm.categories.first().first { it.species == species.wire && it.name == name }

    private suspend fun heads(c: LivestockCategory): Long = vm.herd.first().heads(c)

    /** Saves an operation at [clockNow] (dated [at] when given), then moves the clock on a minute. */
    private suspend fun record(
        kind: LivestockOperationKind, c: LivestockCategory, n: Long, to: LivestockCategory? = null, at: Instant = clockNow,
        configure: (LivestockDraft) -> LivestockDraft = { it },
    ): LivestockOperation {
        val d = configure(vm.newDraft(kind, c.id).copy(date = at, targetCategoryId = to?.id, headsText = "$n"))
        val o = vm.save(d).getOrThrow()
        clockNow = clockNow.plusSeconds(60)
        return o
    }

    @Test
    fun defaultsAreSeededOnceWithStableIds() = runTest {
        assertEquals(0, ops().size)
        assertNull(vm.ensureDefaults())
        val settings = opsOf(Entities.livestockSettings).single()
        assertEquals(StableId.livestockSettings, settings.recordId)
        val categories = opsOf(Entities.livestockCategory)
        assertEquals(15, categories.size)
        assertTrue(StableId.livestockCategory("cattle", "Коровы") in categories.map { it.recordId })
        assertTrue(StableId.livestockCategory("pigs", "Откормочные") in categories.map { it.recordId })
        assertTrue(ops().all { it.recordId == it.recordId.lowercase() })
        // The fields as the site writes them.
        val cows = categories.first { it.recordId == StableId.livestockCategory("cattle", "Коровы") }.fieldsJson()
        assertEquals(JsonPrimitive("cattle"), cows["species"])
        assertEquals(JsonPrimitive("Коровы"), cows["name"])
        assertEquals(JsonPrimitive(0), cows["sortOrder"])
        assertEquals(JsonPrimitive(true), cows["isBuiltIn"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), cows["createdAt"])
        assertEquals(listOf("Коровы", "Быки", "Телята", "Молодняк", "Другие"), vm.categories.first().filter { it.species == "cattle" }.map { it.name })
        // A second opening writes nothing.
        assertNull(vm.ensureDefaults())
        assertEquals(16, ops().size)
    }

    @Test
    fun herdComputedFromOperations() = runTest {
        vm.ensureDefaults()
        val cows = cat(Species.CATTLE, "Коровы")
        val calves = cat(Species.CATTLE, "Телята")
        val young = cat(Species.CATTLE, "Молодняк")
        val goats = cat(Species.GOATS, "Козы")
        assertEquals(0L, vm.herd.first().total)
        val before = ops().size
        val purchase = record(LivestockOperationKind.PURCHASE, cows, 5) { it.copy(comment = " первая партия ") }
        record(LivestockOperationKind.BIRTH, calves, 3)
        record(LivestockOperationKind.TRANSFER, calves, 1, to = young)
        record(LivestockOperationKind.SALE, cows, 2)
        record(LivestockOperationKind.INITIAL, goats, 4)
        // Остаток = поступление + приплод + переводы в − продажа − переводы из, never stored, always counted.
        val herd = vm.herd.first()
        assertEquals(3L, herd.heads(cows))
        assertEquals(2L, herd.heads(calves))
        assertEquals(1L, herd.heads(young))
        assertEquals(6L, herd.heads(Species.CATTLE))
        assertEquals(4L, herd.heads(Species.GOATS))
        assertEquals(0L, herd.heads(Species.PIGS))
        assertEquals(10L, herd.total)
        // One op per operation, the fields as the site writes them (`web/src/pages/livestock.tsx`).
        val written = ops().drop(before)
        assertEquals(5, written.size)
        assertTrue(written.all { it.entity == Entities.livestockOperation && it.kind == "upsert" })
        val f = written.first().fieldsJson()
        assertEquals(purchase.id, written.first().recordId)
        assertEquals(purchase.id.lowercase(), purchase.id)
        assertEquals(JsonPrimitive("purchase"), f["kind"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), f["date"])
        assertEquals(JsonPrimitive(cows.id), f["categoryId"])
        assertEquals(JsonNull, f["targetCategoryId"])
        assertEquals(JsonPrimitive(5), f["heads"])
        assertEquals(JsonPrimitive("первая партия"), f["comment"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), f["createdAt"])
        val transfer = written[2].fieldsJson()
        assertEquals(JsonPrimitive("transfer"), transfer["kind"])
        assertEquals(JsonPrimitive(young.id), transfer["targetCategoryId"])
        // The history, newest first.
        assertEquals(listOf("initial", "sale", "transfer", "birth", "purchase"), vm.operations.first().map { it.kind })
        // A new «Уменьшить» cannot take more than there is.
        val tooMany = vm.newDraft(LivestockOperationKind.DEATH, cows.id).copy(headsText = "4")
        val refused = vm.save(tooMany).exceptionOrNull()
        assertTrue(refused is LivestockError.NotEnough)
        assertEquals("Не хватает голов: в категории «Коровы» на 07.11.2026 было 3 головы.", refused?.message)
        assertEquals(before + 5, ops().size)
    }

    @Test
    fun slaughterReducesHerd() = runTest {
        vm.ensureDefaults()
        val cows = cat(Species.CATTLE, "Коровы")
        record(LivestockOperationKind.INITIAL, cows, 10)
        val before = ops().size
        val slaughter = record(LivestockOperationKind.SLAUGHTER, cows, 2) {
            it.copy(reason = SlaughterReason.FORCED, liveWeightText = "900,5", carcassWeightText = "520", meatWeightText = "")
        }
        assertEquals(8L, heads(cows))
        assertEquals(8L, vm.herd.first().total)
        // One transaction: the operation and its details, one op each; the details have their own lowercase id.
        val written = ops().drop(before)
        assertEquals(listOf(Entities.livestockOperation, Entities.slaughterRecord), written.map { it.entity })
        assertEquals(JsonPrimitive("slaughter"), written[0].fieldsJson()["kind"])
        assertEquals(JsonPrimitive(2), written[0].fieldsJson()["heads"])
        val s = written[1]
        assertNotEquals(slaughter.id, s.recordId)
        assertEquals(s.recordId.lowercase(), s.recordId)
        val f = s.fieldsJson()
        assertEquals(JsonPrimitive(slaughter.id), f["operationId"])
        assertEquals(JsonPrimitive("forced"), f["reason"])
        assertEquals(JsonPrimitive(900_500), f["liveWeightGrams"])
        assertEquals(JsonPrimitive(520_000), f["carcassWeightGrams"])
        assertEquals(JsonNull, f["meatWeightGrams"])
        assertEquals(JsonPrimitive("2026-11-06T16:31:00.000Z"), f["createdAt"])
        val details = vm.slaughters.first().single()
        assertEquals(slaughter.id, details.operationId)
        // The form of the saved slaughter shows its weights.
        val d = vm.draftFor(slaughter.id)!!
        assertEquals("900,5", d.liveWeightText)
        assertEquals(SlaughterReason.FORCED, d.reason)
        // Corrected to 3 heads with the meat weight: the operation and the same details row change, nothing is added.
        assertNull(vm.update(slaughter, d.copy(headsText = "3", meatWeightText = "410,25")))
        assertEquals(7L, heads(cows))
        val corrected = ops().drop(before + 2)
        assertEquals(listOf(Entities.livestockOperation, Entities.slaughterRecord), corrected.map { it.entity })
        assertEquals(s.recordId, corrected[1].recordId)
        assertEquals(setOf("meatWeightGrams"), corrected[1].fieldsJson().keys)
        assertEquals(1, vm.slaughters.first().size)
        // Deleting the slaughter restores the herd: the operation and its details go in one transaction.
        val saved = vm.operations.first().first { it.id == slaughter.id }
        assertNull(vm.delete(saved))
        assertEquals(10L, heads(cows))
        val deleted = ops().drop(before + 4)
        assertEquals(listOf("delete", "delete"), deleted.map { it.kind })
        assertEquals(setOf(Entities.livestockOperation, Entities.slaughterRecord), deleted.map { it.entity }.toSet())
        assertTrue(vm.slaughters.first().isEmpty())
        assertEquals(1, vm.operations.first().size)
    }

    @Test
    fun cannotGoBelowZeroRetroactively() = runTest {
        vm.ensureDefaults()
        val cows = cat(Species.CATTLE, "Коровы")
        val purchase = record(LivestockOperationKind.PURCHASE, cows, 5)
        // The next day 4 of them are slaughtered (8 November at the farm).
        record(LivestockOperationKind.SLAUGHTER, cows, 4, at = moment.plusSeconds(86_400))
        assertEquals(1L, heads(cows))
        val before = ops().size
        val refusal = "Не хватает голов: в категории «Коровы» на 08.11.2026 было 3 головы."
        // Correcting the old purchase to 3 would leave the slaughter without animals: refused with the iPhone's text.
        val d = vm.draftFor(purchase.id)!!
        assertEquals(refusal, vm.update(purchase, d.copy(headsText = "3")))
        // Deleting it is refused the same way; a slaughter dated before the animals came is refused too.
        assertEquals("Не хватает голов: в категории «Коровы» на 08.11.2026 было 0 голов.", vm.delete(purchase))
        val early = vm.newDraft(LivestockOperationKind.SLAUGHTER, cows.id).copy(date = moment.minusSeconds(86_400), headsText = "1")
        assertEquals("Не хватает голов: в категории «Коровы» на 06.11.2026 было 0 голов.", vm.save(early).exceptionOrNull()?.message)
        // Nothing was written; the herd is as it was. A correction that keeps the history consistent goes through.
        assertEquals(before, ops().size)
        assertEquals(1L, heads(cows))
        assertEquals(5L, vm.operations.first().first { it.id == purchase.id }.heads)
        assertNull(vm.update(purchase, d.copy(headsText = "4")))
        assertEquals(0L, heads(cows))
        assertEquals(setOf("heads", "updatedAt"), ops().last().fieldsJson().keys)
    }

    @Test
    fun doubleTapSaveCreatesOneOperation() = runTest {
        vm.ensureDefaults()
        val cows = cat(Species.CATTLE, "Коровы")
        val d = vm.newDraft(LivestockOperationKind.PURCHASE, cows.id).copy(headsText = "5")
        val before = ops().size
        val results = coroutineScope {
            val a = async { vm.save(d) }
            val b = async { vm.save(d) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(LivestockError.AlreadySaved.message, results.first { it.isFailure }.exceptionOrNull()?.message)
        assertEquals(1, ops().drop(before).size)
        assertEquals(5L, heads(cows))
    }

    @Test
    fun ownCategoriesAreAddedAndDeleted() = runTest {
        vm.ensureDefaults()
        val before = ops().size
        assertNull(vm.addCategory("  Нетели ", Species.CATTLE))
        val added = ops().last()
        assertEquals(Entities.livestockCategory, added.entity)
        assertEquals(added.recordId.lowercase(), added.recordId)
        assertNotEquals(StableId.livestockCategory("cattle", "Нетели"), added.recordId)
        assertEquals(JsonPrimitive("Нетели"), added.fieldsJson()["name"])
        assertEquals(JsonPrimitive(false), added.fieldsJson()["isBuiltIn"])
        assertEquals(JsonPrimitive(5), added.fieldsJson()["sortOrder"])
        assertEquals("Категория «нетели» уже есть", vm.addCategory("нетели", Species.CATTLE))
        assertEquals("Укажите название категории", vm.addCategory("  ", Species.CATTLE))
        val own = cat(Species.CATTLE, "Нетели")
        record(LivestockOperationKind.PURCHASE, own, 2)
        assertEquals(LivestockError.CategoryInUse.message, vm.deleteCategory(own))
        assertEquals(LivestockError.CategoryInUse.message, vm.deleteCategory(cat(Species.CATTLE, "Коровы")))
        val empty = run { vm.addCategory("Тёлки", Species.CATTLE); cat(Species.CATTLE, "Тёлки") }
        assertNull(vm.deleteCategory(empty))
        assertEquals("delete", ops().last().kind)
        assertEquals(before + 4, ops().size)
        assertNull(vm.categories.first().firstOrNull { it.name == "Тёлки" })
    }

    @Test
    fun viewerCannotChangeAnything() = runTest {
        role = Role.viewer
        assertFalse(vm.canEditOperations || vm.canSlaughter || vm.canEditCategories)
        // The defaults are read, never written by a viewer (the server would refuse the rows).
        assertNull(vm.ensureDefaults())
        assertEquals(0, ops().size)
        assertTrue(vm.categories.first().isEmpty())
        val d = vm.newDraft(LivestockOperationKind.PURCHASE, "x").copy(headsText = "1")
        assertTrue(vm.save(d).isFailure)
        assertNotNull(vm.addCategory("Нетели", Species.CATTLE))
        assertEquals(0, ops().size)
    }
}
