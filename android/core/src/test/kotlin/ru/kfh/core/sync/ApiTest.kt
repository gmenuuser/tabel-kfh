package ru.kfh.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Test
import ru.kfh.core.roles.Role
import ru.kfh.core.testing.assertEquals
import ru.kfh.core.testing.assertFailsWith
import ru.kfh.core.testing.assertFalse
import ru.kfh.core.testing.assertNull
import ru.kfh.core.testing.assertTrue
import java.io.IOException
import java.time.ZoneId

class ApiTest {
    private val server = TestServer()
    private var token: String? = "tok-1"
    private val api = server.api(token = { token })

    @After
    fun tearDown() = server.close()

    private fun answer(body: String, code: Int = 200) {
        server.override = { TestServer.json(body, code) }
    }

    private fun me(timezone: String?) = """{"userId":"u1","deviceId":"d1","role":"coal","farm":{"id":"f1","name":"КФХ Зубахин А.В.",""" +
        """"region":"Приаргунский район","managerName":"Зубахин А. В."""" +
        (if (timezone == null) "" else ""","timezone":$timezone""") + "}}"

    @Test
    fun loginSendsAndroidPlatformAndDeviceName() = runBlocking {
        token = null
        answer("""{"token":"tok-1","deviceId":"d1","farmId":"f1","userId":"u1","role":"owner","farms":[{"farmId":"f1","role":"owner"}]}""")
        val t = api.login("ivan", "secret-pass", "Xiaomi 23053RN02Y")

        assertEquals("tok-1", t)
        val r = server.seen.single()
        assertEquals("POST", r.method)
        assertEquals("/auth/login", r.path)
        assertNull(r.authorization)
        assertEquals("ivan", r.json["login"]?.jsonPrimitive?.content)
        assertEquals("secret-pass", r.json["password"]?.jsonPrimitive?.content)
        assertEquals("android", r.json["platform"]?.jsonPrimitive?.content)
        assertEquals("Xiaomi 23053RN02Y", r.json["deviceName"]?.jsonPrimitive?.content)
    }

    /** The login's 401 is a wrong login or password, not a revoked device (`testWrongPasswordShowsServerMessage`). */
    @Test
    fun wrongPasswordShowsServerMessage() = runBlocking {
        answer("""{"error":"Неверный логин или пароль"}""", 401)
        val e = assertFailsWith<ApiError> { runBlocking { api.login("ivan", "wrong", "Pixel") } }
        assertEquals(401, e.status)
        assertEquals("Неверный логин или пароль", e.text)
        assertEquals("Неверный логин или пароль", e.message)
    }

    @Test
    fun meParsesFarmAndZone() = runBlocking {
        answer(me("\"Asia/Yakutsk\""))
        val m = api.me()

        assertEquals(Me("u1", Role.coal, "КФХ Зубахин А.В.", "Зубахин А. В.", "Приаргунский район", ZoneId.of("Asia/Yakutsk")), m)
        assertEquals("Bearer tok-1", server.seen.single().authorization)
        assertEquals("GET", server.seen.single().method)
        assertEquals("/me", server.seen.single().path)
    }

    @Test
    fun meZoneDefaultsToChita() = runBlocking {
        for (tz in listOf(null, "null", "\"\"", "\"Mars/Olympus\"")) {
            answer(me(tz))
            assertEquals(ZoneId.of("Asia/Chita"), api.me().timeZone, "timezone $tz")
        }
    }

    @Test
    fun meWithNullFarmFields() = runBlocking {
        answer("""{"userId":"u1","deviceId":"d1","role":"owner","farm":{"id":"f1","name":"КФХ","region":null,"managerName":null,"timezone":"Asia/Chita"}}""")
        val m = api.me()
        assertNull(m.managerName)
        assertNull(m.region)
        assertEquals(Role.owner, m.role)
    }

    @Test
    fun unauthorizedOn401() = runBlocking {
        answer("""{"error":"Нужно войти заново: устройство не подключено или доступ отозван"}""", 401)
        assertFailsWith<Unauthorized> { runBlocking { api.me() } }
        Unit
    }

    @Test
    fun serverErrorCarriesText() = runBlocking {
        answer("""{"error":"Сбой сервера"}""", 500)
        val e = assertFailsWith<ApiError> { runBlocking { api.pull(0) } }
        assertEquals(500, e.status)
        assertEquals("Сбой сервера", e.text)

        server.override = { MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>") }
        val g = assertFailsWith<ApiError> { runBlocking { api.pull(0) } }
        assertEquals(502, g.status)
        assertEquals("Сервер ответил 502", g.text)
    }

    @Test
    fun garbledAnswerIsApiError() = runBlocking {
        answer("""{"changes":"nope"}""")
        val e = assertFailsWith<ApiError> { runBlocking { api.pull(0) } }
        assertEquals("Непонятный ответ сервера", e.text)
    }

    @Test
    fun networkDropIsIOException() = runBlocking {
        server.override = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertFailsWith<IOException> { runBlocking { api.push(listOf(Op("o", "driver", "r", "upsert", 0, buildJsonObject { }))) } }
        assertEquals(1, server.seen.size)
    }

    @Test
    fun pushSendsOpsAndParsesResults() = runBlocking {
        answer("""{"results":[{"opId":"o1","status":"applied","version":1,"repeated":true},
            {"opId":"o2","status":"partial","version":4,"conflicts":["phone"]},
            {"opId":"o3","status":"conflict","version":2,"conflicts":["__deleted"]},
            {"opId":"o4","status":"rejected","errors":["Выплата больше нуля","Нет прав"]}]}""")
        val results = api.push(listOf(
            Op("o1", "coalSale", "r1", "upsert", 0, buildJsonObject { put("kilograms", 2000); put("comment", JsonNull) }),
            Op("o2", "driver", "r2", "delete", 3, buildJsonObject { }),
        ))

        assertEquals(listOf(
            OpResult("o1", "applied", 1, emptyList(), true),
            OpResult("o2", "partial", 4, emptyList(), false),
            OpResult("o3", "conflict", 2, emptyList(), false),
            OpResult("o4", "rejected", null, listOf("Выплата больше нуля", "Нет прав"), false),
        ), results)
        val r = server.seen.single()
        assertEquals("POST", r.method)
        assertEquals("/sync/push", r.path)
        assertEquals("Bearer tok-1", r.authorization)
        val ops = r.ops
        assertEquals("o1", ops[0]["opId"]?.jsonPrimitive?.content)
        assertEquals("coalSale", ops[0]["entity"]?.jsonPrimitive?.content)
        assertEquals("r1", ops[0]["recordId"]?.jsonPrimitive?.content)
        assertEquals("upsert", ops[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(0, ops[0]["baseVersion"]?.jsonPrimitive?.int)
        assertEquals(buildJsonObject { put("kilograms", 2000); put("comment", JsonNull) }, ops[0]["fields"])
        assertEquals("delete", ops[1]["kind"]?.jsonPrimitive?.content)
        assertEquals(3, ops[1]["baseVersion"]?.jsonPrimitive?.int)
        assertNull(ops[1]["fields"])
    }

    @Test
    fun pullSendsSinceAndKeepsDataRaw() = runBlocking {
        answer("""{"changes":[
            {"entity":"hoursMark","id":"h1","version":2,"deleted":false,"data":{"employeeId":"e1","hours":null},"updatedAt":"2026-11-07T00:00:00.000Z"},
            {"entity":"driver","id":"d1","version":5,"deleted":true,"data":{},"updatedAt":"2026-11-07T00:00:00.000Z"}],
            "next":1234567890123,"more":true}""")
        val page = api.pull(42)

        assertEquals("since=42", server.seen.single().query)
        assertEquals(1234567890123L, page.next)
        assertTrue(page.more)
        assertEquals(RemoteRecord("hoursMark", "h1", 2, buildJsonObject { put("employeeId", "e1"); put("hours", JsonNull) }, false),
                     page.changes[0])
        assertEquals(RemoteRecord("driver", "d1", 5, buildJsonObject { }, true), page.changes[1])
    }

    @Test
    fun logoutAndFarm() = runBlocking {
        answer("""{"ok":true}""")
        api.logout()
        api.patchFarm(name = "КФХ Новое", managerName = "Петров П. П.")
        api.patchFarm(timeZone = "Asia/Yakutsk")

        assertEquals(listOf("POST /auth/logout", "PATCH /farm", "PATCH /farm"), server.seen.map { "${it.method} ${it.path}" })
        assertTrue(server.seen.all { it.authorization == "Bearer tok-1" })
        assertEquals(buildJsonObject { put("name", "КФХ Новое"); put("managerName", "Петров П. П.") }, server.seen[1].json)
        assertEquals(buildJsonObject { put("timezone", "Asia/Yakutsk") }, server.seen[2].json)
    }

    /** `testOwnerRequestsGoWithTheToken`. */
    @Test
    fun members() = runBlocking {
        answer("""[{"id":"u1","login":"ivan","email":null,"name":"ivan","role":"owner"},{"id":"u2","login":"anna","email":null,"name":"anna","role":"timesheet"}]""")
        val list = api.members()
        assertEquals(listOf("ivan", "anna"), list.map { it.login })
        assertEquals(Role.timesheet, list.last().role)
        assertEquals("Bearer tok-1", server.seen.single().authorization)

        answer("""{"id":"u3","login":"petr","role":"coal"}""", 201)
        assertEquals(Member("u3", "petr", null, null, Role.coal), api.addMember("petr", "secret-pass", Role.coal))
        assertEquals(buildJsonObject { put("login", "petr"); put("password", "secret-pass"); put("role", "coal") }, server.seen.last().json)

        answer("""{"ok":true}""")
        api.setPassword("u3", "new-secret")
        api.setRole("u3", Role.viewer)
        api.removeMember("u3")
        val tail = server.seen.takeLast(3)
        assertEquals(listOf("POST /members/u3/password", "PATCH /members/u3", "DELETE /members/u3"), tail.map { "${it.method} ${it.path}" })
        assertEquals(buildJsonObject { put("password", "new-secret") }, tail[0].json)
        assertEquals(buildJsonObject { put("role", "viewer") }, tail[1].json)
    }

    @Test
    fun devices() = runBlocking {
        answer("""[{"id":"d1","name":"Pixel владельца","platform":"android","email":"z@example.ru","login":null,"createdAt":"2026-11-01T00:00:00.000Z","lastSeenAt":"2026-11-06T02:00:00.000Z","revoked":false,"current":true},
            {"id":"d3","name":"","platform":"web","email":null,"login":"sergey","createdAt":"2026-11-02T00:00:00.000Z","lastSeenAt":null,"revoked":true,"current":false}]""")
        val list = api.devices()
        assertEquals(Device("d1", "Pixel владельца", "android", "z@example.ru", null, "2026-11-01T00:00:00.000Z",
                            "2026-11-06T02:00:00.000Z", revoked = false, current = true), list[0])
        assertTrue(list[1].revoked)
        assertNull(list[1].lastSeenAt)

        answer("""{"ok":true}""")
        api.revoke("d3")
        assertEquals("POST /devices/d3/revoke", server.seen.last().let { "${it.method} ${it.path}" })
    }

    @Test
    fun conflicts() = runBlocking {
        answer("""{"conflicts":[{"id":"c1","entity":"driver","recordId":"r1","field":"phone","serverValue":"1","clientValue":null,"baseVersion":1,"deviceId":"d1","opId":"o1","createdAt":"2026-11-07T00:00:00.000Z"}]}""")
        val c = api.conflicts().single()
        assertEquals(Conflict("c1", "driver", "r1", "phone", JsonPrimitive("1"), JsonNull, "2026-11-07T00:00:00.000Z"), c)

        answer("""{"ok":true,"resolved":1}""")
        api.resolve("c1", ConflictChoice.client)
        assertEquals("POST /conflicts/c1/resolve", server.seen.last().let { "${it.method} ${it.path}" })
        assertEquals(buildJsonObject { put("choice", "client") }, server.seen.last().json)

        answer("""{"error":"Конфликт не найден или уже решён"}""", 409)
        val e = assertFailsWith<ApiError> { runBlocking { api.resolve("c1", ConflictChoice.server) } }
        assertEquals("Конфликт не найден или уже решён", e.text)
    }

    @Test
    fun backups() = runBlocking {
        // GET /backups gives the database's names; bigint `size` comes as a string.
        answer("""[{"id":"b1","sha256":"ab","records":12,"size":"3456","created_at":"2026-11-07T10:00:00.000Z"}]""")
        assertEquals(listOf(Backup("b1", 12, "2026-11-07T10:00:00.000Z")), api.backups())

        answer("""{"id":"b2","sha256":"cd","records":13,"createdAt":"2026-11-08T10:00:00.000Z"}""", 201)
        assertEquals(Backup("b2", 13, "2026-11-08T10:00:00.000Z"), api.createBackup())
        assertEquals("POST /backups", server.seen.last().let { "${it.method} ${it.path}" })

        answer("""{"ok":false}""")
        assertFalse(api.verifyBackup("b2"))
        assertEquals("GET /backups/b2/verify", server.seen.last().let { "${it.method} ${it.path}" })

        answer("""{"ok":true,"records":13}""")
        assertEquals(13, api.restoreBackup("b2"))
        assertEquals("POST /backups/b2/restore", server.seen.last().let { "${it.method} ${it.path}" })
        assertTrue(server.seen.last().json["confirm"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun tokenIsNeverInErrors() = runBlocking {
        token = "secret-token-value"
        answer("""{"error":"Сбой"}""", 500)
        val e = assertFailsWith<ApiError> { runBlocking { api.me() } }
        assertFalse(e.toString().contains("secret-token-value"))
        assertFalse(api.toString().contains("secret-token-value"))
    }
}
