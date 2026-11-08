package ru.kfh.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.testing.assertEquals
import ru.kfh.core.testing.assertFalse
import ru.kfh.core.testing.assertNotNull
import ru.kfh.core.testing.assertNull
import ru.kfh.core.testing.assertTrue
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `TabelKFHTests/SyncEngineTests.swift` (and the engine parts of `SessionStoreTests.swift`) on MockWebServer. */
class SyncEngineTest {
    private val server = TestServer()
    private val store = FakeStore()
    private var signedOut = 0
    private var engine = newEngine()

    private fun newEngine(api: Api = server.api()) = SyncEngine(api, store) { signedOut++ }

    @After
    fun tearDown() = server.close()

    private fun id() = UUID.randomUUID().toString()

    private fun fields(vararg p: Pair<String, String>) = buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

    private fun localDriver(lastName: String = "Мунгалов", id: String = id()): Op =
        store.change("driver", id, fields("lastName" to lastName, "firstName" to "Сергей"))

    /** A driver saved here and already on the server (version 1). */
    private fun sentDriver(): String = runBlocking {
        val op = localDriver()
        engine.syncNow()
        assertEquals(0, store.queue.size)
        server.seen.clear()
        op.recordId
    }

    // MARK: Ported

    @Test
    fun pushThenPull() = runBlocking {
        val op = localDriver()
        val employeeId = id()
        server.addPages(TestServer.page(listOf(
            TestServer.change("driver", op.recordId, 1, """{"lastName":"Мунгалов","firstName":"Сергей"}"""),
            TestServer.change("employee", employeeId, 3,
                """{"lastName":"Зубахин","firstName":"Иван","hireDate":"2025-03-01T00:00:00.000Z"}"""),
        ), next = 7))
        engine.syncNow()

        assertEquals(0, store.queue.size)
        assertEquals(7L, store.cursorValue)
        assertEquals(1L, store.record("driver", op.recordId)?.version)
        val e = assertNotNull(store.record("employee", employeeId))
        assertEquals("Зубахин", e.data["lastName"]?.jsonPrimitive?.content)
        assertEquals(3L, e.version)

        assertEquals(listOf("/sync/push", "/sync/pull", "/conflicts"), server.paths())
        assertEquals("POST", server.seen[0].method)
        assertEquals("since=0", server.seen[1].query)
        assertTrue(server.seen.all { it.authorization == "Bearer tok" })
        val sent = server.seen[0].ops.single()
        assertEquals(op.opId, sent["opId"]?.jsonPrimitive?.content)
        assertEquals("driver", sent["entity"]?.jsonPrimitive?.content)
        assertEquals(op.recordId, sent["recordId"]?.jsonPrimitive?.content)
        assertEquals("upsert", sent["kind"]?.jsonPrimitive?.content)
        assertEquals(0, sent["baseVersion"]?.jsonPrimitive?.int)
        assertEquals("Мунгалов", sent["fields"]?.jsonObject?.get("lastName")?.jsonPrimitive?.content)

        assertEquals(SyncStatus.Synced, engine.status.value)
    }

    @Test
    fun networkDropKeepsQueueAndSameOpId() = runBlocking {
        val op = localDriver()
        // The server reads the push, then the socket closes: no answer.
        server.push = { null }
        engine.syncNow()

        assertEquals(listOf(op), store.queue)
        assertEquals(SyncStatus.Offline(1), engine.status.value)
        assertEquals(0L, store.cursorValue)
        assertEquals(listOf("/sync/push"), server.paths())

        server.push = TestServer.applyAll(1)
        engine.syncNow()
        val pushes = server.pushes()
        assertEquals(2, pushes.size)
        assertEquals(op.opId, pushes[1].ops.single()["opId"]?.jsonPrimitive?.content)
        assertEquals(0, store.queue.size)
        assertEquals(SyncStatus.Synced, engine.status.value)
    }

    @Test
    fun rejectedGoesToFailed() = runBlocking {
        localDriver()
        server.push = { ops ->
            ops.map { TestServer.result(it, "rejected", errors = listOf("Нет прав на изменение этих данных", "Фамилия обязательна")) }
        }
        engine.syncNow()

        assertEquals(0, store.queue.size)
        val r = store.refused.single()
        assertEquals(listOf("Нет прав на изменение этих данных", "Фамилия обязательна"), r.errors)
        assertTrue(engine.status.value is SyncStatus.Failed)
        assertEquals("Ошибка синхронизации", engine.status.value.line(ru.kfh.core.roles.Role.owner))

        // A refused change is not sent again by itself.
        engine.syncNow()
        assertEquals(1, server.pushes().size)
        assertEquals(1, store.refused.size)
    }

    @Test
    fun conflictRemovesOpAndCountsConflict() = runBlocking {
        val id = sentDriver()
        store.change("driver", id, fields("phone" to "+7 914 000-00-00"))
        server.push = { ops -> ops.map { TestServer.result(it, "conflict", 3) } }
        server.conflicts = 1
        engine.syncNow()

        assertEquals(1, server.pushes().single().ops.single()["baseVersion"]?.jsonPrimitive?.int)
        assertEquals(0, store.queue.size)
        assertEquals(3L, store.record("driver", id)?.version)
        assertEquals(SyncStatus.Conflicts(1), engine.status.value)
    }

    @Test
    fun partialRemovesOp() = runBlocking {
        val id = sentDriver()
        store.change("driver", id, fields("phone" to "1", "comment" to "x"))
        server.push = { ops -> ops.map { TestServer.result(it, "partial", 2) } }
        server.conflicts = 1
        engine.syncNow()

        assertEquals(0, store.queue.size)
        assertEquals(0, store.refused.size)
        assertEquals(2L, store.record("driver", id)?.version)
        assertEquals(SyncStatus.Conflicts(1), engine.status.value)
    }

    @Test
    fun laterOpOfSameRecordMovesToReturnedVersion() = runBlocking {
        val first = localDriver()
        server.push = { null }
        engine.syncNow()
        // The first change went out once: this one is a new op made on top of it (base 0).
        store.change("driver", first.recordId, fields("phone" to "123"))
        assertEquals(listOf(0L, 0L), store.queue.map { it.baseVersion })

        val version = AtomicInteger()
        server.push = { ops -> ops.map { TestServer.result(it, "applied", version.incrementAndGet().toLong()) } }
        server.seen.clear()
        engine.syncNow()

        val p = server.pushes()
        assertEquals(listOf(1, 1), p.map { it.ops.size })
        assertEquals(first.opId, p[0].ops.single()["opId"]?.jsonPrimitive?.content)
        assertEquals(1, p[1].ops.single()["baseVersion"]?.jsonPrimitive?.int)
        assertEquals("123", p[1].ops.single()["fields"]?.jsonObject?.get("phone")?.jsonPrimitive?.content)
        assertEquals(0, store.queue.size)
        assertEquals(2L, store.record("driver", first.recordId)?.version)
    }

    /** A1 sent once without an answer, A2 its later edit, B1 another record: the first push is [A1] alone. */
    @Test
    fun pushStopsAtTheSecondOpOfARecord() = runBlocking {
        val a = localDriver("Алексеев")
        server.push = { null }
        engine.syncNow()
        store.change("driver", a.recordId, fields("phone" to "20"))
        val b = localDriver("Борисов")
        assertEquals(listOf(a.recordId, a.recordId, b.recordId), store.queue.map { it.recordId })

        val version = AtomicInteger()
        server.push = { ops -> ops.map { TestServer.result(it, "applied", version.incrementAndGet().toLong()) } }
        server.seen.clear()
        engine.syncNow()

        val ids = server.pushes().map { s -> s.ops.map { it["recordId"]!!.jsonPrimitive.content } }
        assertEquals(listOf(listOf(a.recordId), listOf(a.recordId, b.recordId)), ids)
        assertEquals(1, server.pushes()[1].ops.first()["baseVersion"]?.jsonPrimitive?.int, "A2 goes on top of A1's version")
        assertEquals(0, store.queue.size)
    }

    @Test
    fun pullMoreLoops() = runBlocking {
        val a = id()
        val b = id()
        server.addPages(
            TestServer.page(listOf(TestServer.change("driver", a, 1, """{"lastName":"Аксёнов"}""")), next = 5, more = true),
            TestServer.page(listOf(TestServer.change("driver", b, 1, """{"lastName":"Белов"}""")), next = 9, more = true),
            TestServer.page(emptyList(), next = 12, more = false),
        )
        engine.syncNow()

        assertEquals(listOf("since=0", "since=5", "since=9"), server.pulls().map { it.query })
        assertEquals(12L, store.cursorValue)
        assertEquals("Аксёнов", store.record("driver", a)?.data?.get("lastName")?.jsonPrimitive?.content)
        assertEquals("Белов", store.record("driver", b)?.data?.get("lastName")?.jsonPrimitive?.content)
        assertEquals(0, store.queue.size)
    }

    /** An op the server did not answer stays queued, and the pull still runs with it kept on top. */
    @Test
    fun pullKeepsPendingLocalFields() = runBlocking {
        val id = sentDriver()
        store.change("driver", id, fields("phone" to "local"))
        server.push = { emptyList() }
        server.addPages(TestServer.page(listOf(TestServer.change("driver", id, 2,
            """{"lastName":"Мунгалов","firstName":"Сергей","phone":"server","comment":"с сайта"}""")), next = 4))
        engine.syncNow()

        val rec = assertNotNull(store.record("driver", id))
        assertEquals("local", rec.data["phone"]?.jsonPrimitive?.content)
        assertEquals("с сайта", rec.data["comment"]?.jsonPrimitive?.content)
        assertEquals(2L, rec.version)
        assertEquals(1, store.queue.size)
        assertEquals(fields("phone" to "local"), store.queue.single().fields)
        assertEquals(1, server.pushes().size)
        assertEquals(4L, store.cursorValue)
        assertEquals(SyncStatus.Pending(1), engine.status.value)
        assertEquals("Ждут отправки: 1", engine.status.value.line(ru.kfh.core.roles.Role.owner))
    }

    @Test
    fun pullDeleteRemovesRecord() = runBlocking {
        val id = sentDriver()
        server.addPages(TestServer.page(listOf(TestServer.change("driver", id, 2, deleted = true)), next = 3))
        engine.syncNow()

        val rec = assertNotNull(store.record("driver", id))
        assertTrue(rec.deleted)
        assertEquals(2L, rec.version)
        assertEquals(0, store.queue.size)
    }

    @Test
    fun pullNullHoursRemovesMark() = runBlocking {
        val markId = id()
        server.addPages(TestServer.page(listOf(TestServer.change("hoursMark", markId, 2,
            """{"employeeId":"${id()}","day":"2026-11-05T00:00:00.000Z","hours":null}""")), next = 2))
        engine.syncNow()

        val change = store.pulled.flatten().single()
        assertEquals(JsonNull, change.data["hours"])
        assertTrue(RecordCodec.isRemovedHoursMark(change.data))
        assertFalse(change.deleted)
        assertEquals(2L, change.version)
        assertEquals(0, server.pushes().size)
    }

    @Test
    fun unauthorizedSignsOut() = runBlocking {
        localDriver()
        server.override = { TestServer.json("""{"error":"Нужно войти заново: устройство не подключено или доступ отозван"}""", 401) }
        engine.syncNow()

        assertEquals(1, signedOut)
        assertEquals(1, store.wipes)
        assertEquals(0, store.queue.size)
        assertEquals(1, server.seen.size)
        // Called again by a later trigger: nothing is asked, nothing breaks.
        engine.syncNow()
        assertEquals(1, server.seen.size)
        assertEquals(1, signedOut)
    }

    @Test
    fun serverErrorKeepsQueueAndShowsMessage() = runBlocking {
        val op = localDriver()
        server.override = { TestServer.json("""{"error":"Сбой сервера"}""", 500) }
        engine.syncNow()

        assertEquals(listOf(op), store.queue)
        assertEquals(SyncStatus.Failed("Сбой сервера"), engine.status.value)

        server.override = { null }
        engine.syncNow()
        assertEquals(op.opId, server.pushes().last().ops.single()["opId"]?.jsonPrimitive?.content)
        assertEquals(0, store.queue.size)
    }

    @Test
    fun emptyQueueSendsNoPush() = runBlocking {
        server.addPages(TestServer.page(listOf(TestServer.change("driver", id(), 4, """{"lastName":"Мунгалов"}""")), next = 8))
        engine.syncNow()

        assertEquals(listOf("/sync/pull", "/conflicts"), server.paths())
        assertEquals(0, store.queue.size)
    }

    /** A refused change does not stay on the phone: the next pull starts over and brings the server's value back. */
    @Test
    fun rejectedChangeShowsServerValueAgain() = runBlocking {
        val id = sentDriver()
        store.cursorValue = 5
        store.change("driver", id, fields("phone" to "refused"))
        server.push = { ops -> ops.map { TestServer.result(it, "rejected", errors = listOf("Нет прав")) } }
        server.addPages(TestServer.page(listOf(TestServer.change("driver", id, 1,
            """{"lastName":"Мунгалов","firstName":"Сергей","phone":"1"}""")), next = 6))
        engine.syncNow()

        assertEquals("since=0", server.pulls().first().query)
        assertEquals("1", store.record("driver", id)?.data?.get("phone")?.jsonPrimitive?.content)
        assertEquals(1, store.refused.size)
        assertEquals(6L, store.cursorValue)
    }

    /** A broken connection (TLS) is not «offline»: it is shown as an error. */
    @Test
    fun connectionErrorIsNotOffline() = runBlocking {
        val op = localDriver()
        // https to a server that answers in plain text at once: the TLS handshake fails.
        val plain = java.net.ServerSocket(0)
        val t = Thread {
            runCatching {
                plain.accept().use { s ->
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                    Thread.sleep(200)
                }
            }
        }.apply { start() }
        engine = newEngine(server.api(base = "https://127.0.0.1:${plain.localPort}"))
        engine.syncNow()
        t.join(5000)
        plain.close()

        assertEquals(SyncStatus.Failed("Ошибка соединения с сервером"), engine.status.value)
        assertEquals(listOf(op), store.queue)
    }

    @Test
    fun pullStopsWhenPageDoesNotMoveOn() = runBlocking {
        server.addPages(TestServer.page(emptyList(), next = 0, more = true), TestServer.page(emptyList(), next = 0, more = true))
        engine.syncNow()

        assertEquals(1, server.pulls().size)
    }

    // MARK: Android

    /** The app is killed after a push went out unanswered: the saved queue goes again with its opId, once. */
    @Test
    fun survivesRestart() = runBlocking {
        val sale = store.change("coalSale", id(), buildJsonObject { put("kilograms", 2000); put("total", 500000) })
        server.push = { null }
        engine.syncNow()
        assertEquals(listOf(sale), store.queue)

        // A new process: a new engine (and Api) over the same saved store.
        val restarted = newEngine(server.api())
        server.push = TestServer.applyAll(1)
        restarted.syncNow()

        val sent = server.pushes().map { s -> s.ops.map { it["opId"]!!.jsonPrimitive.content } }
        assertEquals(listOf(listOf(sale.opId), listOf(sale.opId)), sent)
        assertEquals(0, store.queue.size)
        assertEquals(1L, store.record("coalSale", sale.recordId)?.version)
        assertEquals(SyncStatus.Synced, restarted.status.value)
    }

    @Test
    fun pushesInBatchesOf100() = runBlocking {
        repeat(150) { localDriver("Д$it") }
        engine.syncNow()

        assertEquals(listOf(100, 50), server.pushes().map { it.ops.size })
        assertEquals(0, store.queue.size)
    }

    @Test
    fun deleteGoesWithoutFields() = runBlocking {
        val id = sentDriver()
        val op = store.delete("driver", id)
        engine.syncNow()

        val sent = server.pushes().single().ops.single()
        assertEquals(op.opId, sent["opId"]?.jsonPrimitive?.content)
        assertEquals("delete", sent["kind"]?.jsonPrimitive?.content)
        assertEquals(1L, sent["baseVersion"]?.jsonPrimitive?.long)
        assertNull(sent["fields"])
    }

    @Test
    fun oneSyncAtATime() = runBlocking {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        server.override = {
            most.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(50)
            inFlight.decrementAndGet()
            null
        }
        localDriver()
        val a = async(kotlinx.coroutines.Dispatchers.Default) { engine.syncNow() }
        val b = async(kotlinx.coroutines.Dispatchers.Default) { engine.syncNow() }
        a.await()
        b.await()

        assertEquals(1, most.get())
        assertEquals(listOf("/sync/push", "/sync/pull", "/conflicts", "/sync/pull", "/conflicts"), server.paths())
        assertEquals(1, server.pushes().size)
    }

    /** Sign-out during a run (`SessionStoreTests.testSignOutStopsTheRunInFlight`): nothing fetched later is written. */
    @Test
    fun stopDuringPullWritesNothing() = runBlocking {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.addPages(TestServer.page(listOf(TestServer.change("driver", id(), 1, """{"lastName":"Поздний"}""")), next = 9))
        server.override = { s ->
            if (s.path == "/sync/pull") {
                asked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            null
        }
        val run = async(kotlinx.coroutines.Dispatchers.Default) { engine.syncNow() }
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        engine.stop()
        release.countDown()
        run.await()

        assertEquals(0, store.pulled.size)
        assertEquals(0L, store.cursorValue)
        assertEquals(listOf("/sync/pull"), server.paths())
        engine.syncNow()
        assertEquals(1, server.seen.size)
    }

    /**
     * The window `stop()` alone cannot close: the engine is past its check and about to write when sign-out comes.
     * `stopAndWait()` returns only after that write, so the wipe after it leaves nothing of the farm.
     */
    @Test
    fun stopAndWaitWaitsForTheWriteInFlight() = runBlocking {
        for (window in listOf("pull", "push")) {
            store.wipe()
            store.pulled.clear()
            server.seen.clear()
            val e = newEngine()
            if (window == "push") localDriver()
            server.addPages(TestServer.page(listOf(TestServer.change("driver", id(), 1, """{"lastName":"Поздний"}""")), next = 9))
            val atWrite = CountDownLatch(1)
            val release = CountDownLatch(1)
            store.beforeWrite = {
                atWrite.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val run = async(kotlinx.coroutines.Dispatchers.Default) { e.syncNow() }
            assertTrue(atWrite.await(5, TimeUnit.SECONDS), window)
            val signOut = async(kotlinx.coroutines.Dispatchers.Default) { e.stopAndWait() }
            Thread.sleep(150)
            assertFalse(signOut.isCompleted, "$window: stopAndWait must wait for the write in flight")
            release.countDown()
            signOut.await()
            store.beforeWrite = {}
            store.wipe()
            run.await()

            assertEquals(0, store.records.size, window)
            assertEquals(0, store.queue.size, window)
            assertEquals(0L, store.cursorValue, window)
            assertTrue(e.stopped)
            val asked = server.seen.size
            e.syncNow()
            assertEquals(asked, server.seen.size, window)
        }
    }

    @Test
    fun stopAndWaitWithNoRunReturnsAtOnce() = runBlocking {
        engine.stopAndWait()
        assertTrue(engine.stopped)
        engine.syncNow()
        assertEquals(0, server.seen.size)
    }

    @Test
    fun statusLines() {
        val owner = ru.kfh.core.roles.Role.owner
        val coal = ru.kfh.core.roles.Role.coal
        assertEquals("Синхронизация…", SyncStatus.Syncing.line(owner))
        assertEquals("Нет связи. Ждут отправки: 3", SyncStatus.Offline(3).line(owner))
        assertEquals("Ошибка синхронизации", SyncStatus.Failed("Сбой сервера").line(owner))
        assertEquals("Конфликты: 2", SyncStatus.Conflicts(2).line(owner))
        assertEquals("Конфликты: 2 — обратитесь к владельцу", SyncStatus.Conflicts(2).line(coal))
        assertEquals("Ждут отправки: 4", SyncStatus.Pending(4).line(owner))
        assertEquals("Все данные синхронизированы", SyncStatus.Synced.line(owner))
        assertEquals("Синхронизировать сейчас", SyncStatus.ACTION)
        assertTrue(SyncStatus.Offline(0).isProblem)
        assertTrue(SyncStatus.Failed("x").isProblem)
        assertTrue(SyncStatus.Conflicts(1).isProblem)
        assertFalse(SyncStatus.Synced.isProblem)
        assertFalse(SyncStatus.Pending(1).isProblem)
        assertFalse(SyncStatus.Syncing.isProblem)
    }

    @Test
    fun refreshStatusCountsTheStore() = runBlocking {
        localDriver()
        engine.refreshStatus()
        assertEquals(SyncStatus.Pending(1), engine.status.value)
        store.refused += FakeStore.Refused(store.queue.removeAt(0), listOf("Нет прав"))
        engine.refreshStatus()
        assertTrue(engine.status.value is SyncStatus.Failed)
    }
}
