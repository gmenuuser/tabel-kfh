package ru.kfh.tabel.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.Driver
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.RemoteRecord
import java.time.Instant
import java.util.UUID

/** Port of TabelKFHTests/SyncedSaveTests.swift: a change and its op are written together. */
class RecordWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: TabelDb
    private var role = Role.owner
    private lateinit var writer: RecordWriter

    private val created = Instant.ofEpochSecond(1_800_000_000)
    private val driverId = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01"
    private val driver = Driver(id = driverId, lastName = "Мунгалов", firstName = "Сергей", createdAt = created)

    @Before
    fun setUp() {
        db = openTestDb(tmp.newFile("t.db").also { it.delete() })
        writer = RecordWriter(db) { role }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ops() = db.outbox().all()
    private suspend fun row(entity: String, id: String) = db.records().get(entity, id)

    @Test
    fun saveWritesRecordAndOpAtomically() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        val all = ops()
        assertEquals(1, all.size)
        val op = all.single()
        assertEquals("driver", op.entity)
        assertEquals(driverId, op.recordId)
        assertEquals("upsert", op.kind)
        assertEquals(0L, op.baseVersion)
        assertEquals(RecordCodec.encode(driver), op.fieldsJson())
        val r = row("driver", driverId)!!
        assertEquals(0L, r.version)
        assertFalse(r.deleted)
        assertEquals(RecordCodec.encode(driver), r.json())
        assertEquals(1, db.records().all().size)
    }

    @Test
    fun editQueuesOnlyChangedFields() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        val first = ops().single()
        writer.save("driver", driverId, RecordCodec.encode(driver.copy(phone = "+7 914 000-00-00")))
        val all = ops()
        assertEquals(2, all.size)
        val op = all.last()
        assertNotEquals(first.opId, op.opId)
        assertEquals(obj("phone" to "+7 914 000-00-00"), op.fieldsJson())
        assertEquals("upsert", op.kind)

        // The same value again changes nothing.
        writer.save("driver", driverId, RecordCodec.encode(driver.copy(phone = "+7 914 000-00-00")))
        assertEquals(2, ops().size)
    }

    @Test
    fun noChangeNoOp() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        writer.save("driver", driverId, RecordCodec.encode(driver))
        writer.save("driver", driverId, obj())
        assertEquals(1, ops().size)
        assertEquals(RecordCodec.encode(driver), row("driver", driverId)!!.json())
    }

    /** SyncedSaveTests merged an edit into an unsent op; here every save is its own op (the queue is never rewritten). */
    @Test
    fun everySaveIsANewOpNeverMerged() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        writer.save("driver", driverId, RecordCodec.encode(driver.copy(phone = "123")))
        writer.save("driver", driverId, RecordCodec.encode(driver.copy(phone = "123", comment = "по средам")))
        val all = ops()
        assertEquals(3, all.size)
        assertEquals(3, all.map { it.opId }.toSet().size)
        assertEquals(JsonPrimitive(""), all[0].fieldsJson()["phone"])
        assertEquals(obj("phone" to "123"), all[1].fieldsJson())
        assertEquals(obj("comment" to "по средам"), all[2].fieldsJson())
        assertTrue(all[0].seq < all[1].seq && all[1].seq < all[2].seq)
    }

    @Test
    fun viewerCannotWrite() = runTest {
        role = Role.viewer
        assertFalse(writer.canWrite("driver"))
        assertThrowsOf<IllegalStateException> { writer.save("driver", driverId, RecordCodec.encode(driver)) }
        assertThrowsOf<IllegalStateException> { writer.delete("driver", driverId) }
        assertThrowsOf<IllegalStateException> { writer.saveAll(listOf(Triple("driver", driverId, RecordCodec.encode(driver)))) }
        assertEquals(0, ops().size)
        assertNull(row("driver", driverId))
    }

    @Test
    fun roleWritesOnlyItsEntities() = runTest {
        role = Role.timesheet
        assertTrue(writer.canWrite("hoursMark"))
        assertFalse(writer.canWrite("driver"))
        assertThrowsOf<IllegalStateException> { writer.save("driver", driverId, RecordCodec.encode(driver)) }
        role = Role.owner
        assertFalse("unknown entity", writer.canWrite("nonsense"))
        assertThrows { writer.save("nonsense", driverId, obj("a" to 1)) }
        assertEquals(0, ops().size)
    }

    @Test
    fun deleteQueuesDeleteOp() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        writer.delete("driver", driverId)
        val all = ops()
        // A delete is never merged: the server gets the insert, then the delete.
        assertEquals(listOf("upsert", "delete"), all.map { it.kind })
        assertEquals(listOf(driverId, driverId), all.map { it.recordId })
        assertEquals(0L, all.last().baseVersion)
        assertEquals(JsonObject(emptyMap()), all.last().fieldsJson())
        assertTrue(all[0].seq < all[1].seq)
        assertTrue(row("driver", driverId)!!.deleted)
        assertEquals(emptyList<RecordRow>(), db.records().observe("driver").first())

        // Deleting what is not there (or already deleted) queues nothing.
        writer.delete("driver", driverId)
        writer.delete("driver", "11111111-2222-4333-8444-555555555555")
        assertEquals(2, ops().size)
    }

    @Test
    fun saveAfterDeleteSendsAllFields() = runTest {
        writer.save("driver", driverId, RecordCodec.encode(driver))
        writer.delete("driver", driverId)
        writer.save("driver", driverId, RecordCodec.encode(driver))
        val op = ops().last()
        assertEquals("upsert", op.kind)
        assertEquals(RecordCodec.encode(driver), op.fieldsJson())
        assertFalse(row("driver", driverId)!!.deleted)
    }

    private val employeeId = "7d1e4c2a-1b3f-4e5d-8a9b-0c1d2e3f4a5b"
    private val markId = "5e0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c"
    private fun mark(hours: Int) = HoursMark(id = markId, employeeId = employeeId, day = created, hours = hours,
        createdAt = created, updatedAt = created)

    @Test
    fun removedHoursMarkQueuesNull() = runTest {
        writer.save("hoursMark", markId, RecordCodec.encode(mark(8)))
        writer.delete("hoursMark", markId)
        val op = ops().last()
        assertEquals("hoursMark", op.entity)
        assertEquals(markId, op.recordId)
        assertEquals("upsert", op.kind)
        assertEquals(obj("hours" to null), op.fieldsJson())
        assertEquals(0, db.records().observe("hoursMark").first().size)
    }

    @Test
    fun remarkAfterRemovalSendsTheNewHours() = runTest {
        writer.save("hoursMark", markId, RecordCodec.encode(mark(8)))
        writer.delete("hoursMark", markId)
        writer.save("hoursMark", markId, RecordCodec.encode(mark(4)))
        val markOps = ops().filter { it.recordId == markId }
        assertEquals(3, markOps.size)
        assertEquals(JsonPrimitive(4), markOps.last().fieldsJson()["hours"])
        assertEquals("upsert", markOps.last().kind)
        val shown = db.records().observe("hoursMark").first()
        assertEquals(listOf(4), shown.map { RecordCodec.decode<HoursMark>("hoursMark", it.id, it.json()).hours })
    }

    @Test
    fun saveAllWritesEveryRecordInOneGo() = runTest {
        val saleId = "aa0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c"
        val sale = RecordCodec.encode(CoalSale(id = saleId, saleDate = created, kilograms = 2000, createdAt = created))
        writer.saveAll(listOf(Triple("driver", driverId, RecordCodec.encode(driver)), Triple("coalSale", saleId, sale)))
        assertEquals(listOf("driver" to driverId, "coalSale" to saleId), ops().map { it.entity to it.recordId })
        assertEquals(sale, ops().last().fieldsJson())

        // One record the role may not write: nothing of the action is written.
        role = Role.coal
        assertThrowsOf<IllegalStateException> {
            writer.saveAll(listOf(Triple("driver", driverId, RecordCodec.encode(driver.copy(phone = "1"))),
                Triple("employee", employeeId, obj("lastName" to "Зубахин"))))
        }
        assertEquals(2, ops().size)
        assertEquals(JsonPrimitive(""), row("driver", driverId)!!.json()["phone"])
        assertNull(row("employee", employeeId))
    }

    @Test
    fun saveAllRollsBackOnFailure() = runTest {
        val bad = RecordWriter(db, { role }, sequenceOf("op-1", "op-1").iterator()::next, System::currentTimeMillis)
        assertThrows {
            bad.saveAll(listOf(Triple("driver", driverId, RecordCodec.encode(driver)),
                Triple("coalSale", "aa0b8f4d-2c6a-4a1e-9f3b-7d8c9e0a1b2c", obj("kilograms" to 1))))
        }
        assertEquals("the second op broke the unique opId: the first record and op are gone too", 0, ops().size)
        assertNull(row("driver", driverId))
    }

    @Test
    fun idsAreWrittenLowercase() = runTest {
        writer.save("driver", driverId.uppercase(), RecordCodec.encode(driver))
        assertEquals(driverId, ops().single().recordId)
        assertEquals(driverId, db.records().all().single().id)
        val opId = ops().single().opId
        assertEquals(opId.lowercase(), opId)
        assertTrue(UUID.fromString(opId).toString() == opId)
        val fresh = RecordWriter.newId()
        assertEquals(fresh.lowercase(), fresh)
    }

    @Test
    fun baseVersionIsTheStoredVersion() = runTest {
        RoomLocalStore(db).applyPulled(listOf(RemoteRecord("driver", driverId, 5, RecordCodec.encode(driver), false)))
        writer.save("driver", driverId, RecordCodec.encode(driver.copy(phone = "1")))
        assertEquals(5L, ops().single().baseVersion)
        assertEquals(5L, row("driver", driverId)!!.version)
    }

    private suspend fun assertThrows(block: suspend () -> Unit) = assertThrowsOf<Exception>(block)

    private suspend inline fun <reified E : Exception> assertThrowsOf(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is E) return
            throw AssertionError("expected ${E::class.simpleName}, got $e")
        }
        throw AssertionError("expected an exception")
    }
}
