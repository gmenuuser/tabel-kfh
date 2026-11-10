package ru.kfh.tabel.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.roles.Role
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.obj
import ru.kfh.tabel.db.openTestDb

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var syncs = 0

    private fun TestScope.scheduler() = SyncScheduler(backgroundScope) { syncs++ }

    @Test
    fun changesAreSentOneSecondAfterTheLastOne() = runTest {
        val s = scheduler()
        s.changed()
        advanceTimeBy(500)
        s.changed()
        advanceTimeBy(500)
        s.changed()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, syncs)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, syncs)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun foregroundSyncsAtOnceAndEveryMinute() = runTest {
        val s = scheduler()
        s.foreground()
        runCurrent()
        assertEquals(1, syncs)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(2, syncs)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, syncs)
        s.background()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(3, syncs)
    }

    @Test
    fun networkComingBackSyncs() = runTest {
        val s = scheduler()
        // The first callback after registering is the network that is already there: not a regain.
        s.networkAvailable()
        runCurrent()
        assertEquals(0, syncs)
        s.networkLost()
        s.networkAvailable()
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun manualButtonSyncs() = runTest {
        val s = scheduler()
        s.syncNow()
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun recordWriterWriteSchedulesTheDebouncedSync() = runTest {
        val db = openTestDb(tmp.newFile("t.db").also { it.delete() })
        try {
            val s = scheduler()
            val writer = RecordWriter(db, { Role.owner }, s::changed)
            // Two saves within the second: one sync. (runBlocking: virtual time must not run on while Room works.)
            runBlocking {
                writer.save("driver", "d1", obj("lastName" to "Мунгалов"))
                writer.save("driver", "d2", obj("lastName" to "Бянкин"))
            }
            runCurrent()
            assertEquals(0, syncs)
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, syncs)
            // Nothing changed: nothing queued, no sync asked for.
            runBlocking { writer.save("driver", "d2", obj("lastName" to "Бянкин")) }
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, syncs)
        } finally {
            db.close()
        }
    }
}
