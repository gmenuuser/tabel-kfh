package ru.kfh.tabel.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.OpResult
import ru.kfh.core.sync.RemoteRecord
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** [RoomLocalStore] behaves as ru.kfh.core.sync.FakeStore (the LocalStore contract). */
class RoomLocalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var db: TabelDb
    private lateinit var photos: PhotoStore
    private lateinit var store: RoomLocalStore
    private lateinit var writer: RecordWriter

    private val saleId = "aa0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c"

    @Before
    fun setUp() {
        file = File(tmp.root, "t.db")
        photos = PhotoStore(File(tmp.root, "photos"))
        open()
    }

    private fun open() {
        db = openTestDb(file)
        store = RoomLocalStore(db, photos)
        writer = RecordWriter(db) { Role.owner }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun row(entity: String, id: String) = db.records().get(entity, id)

    private fun ok(opId: String, version: Long, status: String = "applied") = OpResult(opId, status, version, emptyList(), false)

    @Test
    fun pulledDoesNotOverwritePendingFields() = runTest {
        store.applyPulled(listOf(RemoteRecord("coalSale", saleId, 3, obj("kilograms" to 1000, "comment" to "старый"), false)))
        writer.save("coalSale", saleId, obj("kilograms" to 2000))
        store.applyPulled(listOf(RemoteRecord("coalSale", saleId, 4, obj("kilograms" to 1500, "comment" to "с сайта"), false)))
        val r = row("coalSale", saleId)!!
        assertEquals(JsonPrimitive(2000), r.json()["kilograms"])
        assertEquals(JsonPrimitive("с сайта"), r.json()["comment"])
        assertEquals(4L, r.version)
        assertEquals("pull queues nothing", 1, store.pendingCount())
    }

    @Test
    fun pulledDeleteAndQueuedDeleteKeepTheRecordDeleted() = runTest {
        store.applyPulled(listOf(RemoteRecord("coalSale", saleId, 3, obj("kilograms" to 1000), false)))
        writer.delete("coalSale", saleId)
        store.applyPulled(listOf(RemoteRecord("coalSale", saleId, 4, obj("kilograms" to 1500), false)))
        assertTrue("a queued delete keeps it deleted", row("coalSale", saleId)!!.deleted)

        val other = "bb0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c"
        writer.save("coalSale", other, obj("kilograms" to 5))
        store.applyPulled(listOf(RemoteRecord("coalSale", other, 7, JsonObject(emptyMap()), true)))
        val r = row("coalSale", other)!!
        assertTrue(r.deleted)
        assertEquals(7L, r.version)
        assertEquals(emptyList<RecordRow>(), db.records().observe("coalSale").first())
    }

    @Test
    fun pulledRemovedHoursMarkIsNotShownAndNeverSentAsZero() = runTest {
        val markId = "5e0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c"
        val day = Instant.parse("2026-11-04T16:00:00.000Z")
        val mark = RecordCodec.encode(HoursMark(id = markId, employeeId = "7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b", day = day,
            hours = 8, createdAt = day, updatedAt = day))
        store.applyPulled(listOf(RemoteRecord("hoursMark", markId, 2, mark, false)))
        assertEquals(1, db.records().observe("hoursMark").first().size)

        store.applyPulled(listOf(RemoteRecord("hoursMark", markId, 3, RecordCodec.removedHoursMark(
            RecordCodec.decode("hoursMark", markId, mark)), false)))
        assertEquals("a removed mark is not a 0-hour mark", emptyList<RecordRow>(), db.records().observe("hoursMark").first())
        assertTrue(RecordCodec.isRemovedHoursMark(row("hoursMark", markId)!!.json()))

        // Removing it again sends nothing; marking it again sends the new hours, not 0.
        writer.delete("hoursMark", markId)
        assertEquals(0, store.pendingCount())
        writer.save("hoursMark", markId, JsonObject(mark + ("hours" to JsonPrimitive(6))))
        val op = store.pendingOps(10).single()
        assertEquals(obj("hours" to 6), op.fields)
        assertEquals(3L, op.baseVersion)
    }

    @Test
    fun pendingOpsInQueueOrderWithLimit() = runTest {
        for (i in 1..5) writer.save("coalSale", saleId, obj("kilograms" to i))
        val ops = store.pendingOps(3)
        assertEquals(listOf(1, 2, 3).map { obj("kilograms" to it) }, ops.map { it.fields })
        assertEquals(5, store.pendingCount())
        assertEquals(ops, store.pendingOps(3))
    }

    @Test
    fun acknowledgeMovesRejectedToRefused() = runTest {
        writer.save("coalSale", saleId, obj("kilograms" to 1))
        val op = store.pendingOps(1).single()
        store.acknowledge(listOf(OpResult(op.opId, "rejected", null, listOf("Нет прав"), false)))
        assertEquals(0, store.pendingCount())
        assertEquals(1, store.refusedCount())
        val refused = db.outbox().refused().single()
        assertEquals(op.opId, refused.opId)
        assertEquals("coalSale", refused.entity)
        assertEquals(saleId, refused.recordId)
        assertEquals(obj("kilograms" to 1), refused.fieldsJson())
        assertEquals(listOf("Нет прав"), refused.errorList())
        assertEquals(emptyList<Any>(), store.pendingOps(10))
    }

    @Test
    fun acknowledgeKeepsTheOtherOpsOfTheRecord() = runTest {
        writer.save("coalSale", saleId, obj("kilograms" to 1))
        writer.save("coalSale", saleId, obj("kilograms" to 2))
        writer.save("coalSale", saleId, obj("kilograms" to 3))
        val (a, b, c) = store.pendingOps(10)
        store.acknowledge(listOf(ok(a.opId, 7)))
        val left = store.pendingOps(10)
        assertEquals(listOf(b.opId, c.opId), left.map { it.opId })
        assertEquals("later ops are on top of the applied one", listOf(7L, 7L), left.map { it.baseVersion })
        assertEquals(listOf(obj("kilograms" to 2), obj("kilograms" to 3)), left.map { it.fields })
        assertEquals(7L, row("coalSale", saleId)!!.version)

        // A conflict leaves the queue but moves no baseVersion; the version only grows.
        store.acknowledge(listOf(ok(b.opId, 9, "conflict"), ok("unknown-op", 100)))
        assertEquals(listOf(7L), store.pendingOps(10).map { it.baseVersion })
        assertEquals(9L, row("coalSale", saleId)!!.version)
        store.acknowledge(listOf(ok(c.opId, 8, "partial")))
        assertEquals(9L, row("coalSale", saleId)!!.version)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun cursorAndMe() = runTest {
        assertEquals(0L, store.cursor())
        store.setCursor(42)
        assertEquals(42L, store.cursor())
        assertNull(store.me())
        val me = Me("u-1", Role.coal, "КФХ Иванов", "Иванов И.", null, ZoneId.of("Asia/Chita"))
        store.saveMe(me)
        assertEquals(me, store.me())
        assertEquals(Role.coal, store.role())
    }

    @Test
    fun wipeClearsEverything() = runTest {
        writer.save("coalSale", saleId, obj("kilograms" to 1))
        writer.save("coalSale", saleId, obj("kilograms" to 2))
        store.acknowledge(listOf(OpResult(store.pendingOps(1).single().opId, "rejected", null, listOf("x"), false)))
        store.setCursor(10)
        store.saveMe(Me("u-1", Role.owner, "КФХ", null, null, ZoneId.of("Asia/Chita")))
        photos.save("7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b", byteArrayOf(1, 2, 3))
        photos.save("8e2f5d3b-2c4a-4f6e-9b0c-1d2e3f4a5b6c", byteArrayOf(4))
        assertTrue(photos.file("7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b").exists())
        store.wipe()
        assertEquals(emptyList<RecordRow>(), db.records().all())
        assertEquals(0, store.pendingCount())
        assertEquals(0, store.refusedCount())
        assertEquals(0L, store.cursor())
        assertNull(store.me())
        assertNull(store.role())
        assertNull(photos.load("7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b"))
        assertNull(photos.load("8e2f5d3b-2c4a-4f6e-9b0c-1d2e3f4a5b6c"))
        assertFalse("the photos folder is gone", photos.dir.exists())
    }

    @Test
    fun outboxSurvivesReopen() = runTest {
        writer.save("coalSale", saleId, obj("kilograms" to 1))
        val before = store.pendingOps(10)
        store.setCursor(5)
        db.close()
        open()
        assertEquals(before, store.pendingOps(10))
        assertEquals(5L, store.cursor())
        assertEquals(obj("kilograms" to 1), row("coalSale", saleId)!!.json())
    }

    @Test
    fun photosAreFilesByEmployee() {
        val id = "7D1E4C2A-1B3F-4E5D-8A9B-0C1D2E3F4A5B"
        photos.save(id, byteArrayOf(9, 8))
        assertEquals(File(photos.dir, "7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b.jpg"), photos.file(id))
        assertArrayEquals(byteArrayOf(9, 8), photos.load(id.lowercase()))
        photos.delete(id)
        assertFalse(photos.file(id).exists())
    }
}
