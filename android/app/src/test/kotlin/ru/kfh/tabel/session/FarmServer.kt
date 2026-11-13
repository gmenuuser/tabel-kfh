package ru.kfh.tabel.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One request the fake farm server saw. */
data class Seen(
    val method: String,
    val path: String,
    val authorization: String?,
    val body: String,
    val query: Map<String, String> = emptyMap(),
) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject

    fun query(name: String): String? = query[name]
}

/**
 * The farm server for the session tests: `/devices/register` ([registerClosed], [registerStatus]), `/auth/login`
 * (password [password]), `/me` ([kind], [role], [timezone]),
 * `/sync/pull` (one page with [records], then empty), `/sync/push` (everything applied, or everything `rejected`
 * with [rejectPush]), `/conflicts` ([conflicts], resolved away by `POST /conflicts/{id}/resolve`), `/auth/logout`,
 * the owner's lists (`/members`, `/devices`, `/backups`) with canned rows, `PATCH /farm`. [unauthorized] answers
 * every signed-in request with 401; while [pullGate] is set, every pull waits for it to be counted down.
 */
class FarmServer {
    val server = MockWebServer()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    @Volatile var password = "secret"
    @Volatile var role = "owner"
    @Volatile var timezone = "Asia/Chita"
    @Volatile var farmName = "КФХ Зубахин"
    @Volatile var managerName = "Зубахин А. В."
    @Volatile var unauthorized = false
    /**
     * `GET /me`'s `kind`: `"app"` (a phone that connected itself: no user, role «manager» for the old builds) or
     * `"owner"`; null answers as the server before the apps connected themselves (only `userId` and [role]).
     */
    @Volatile var kind: String? = "app"
    /** `POST /devices/register` answers 403 «Владелец сейчас не подключает новые устройства». */
    @Volatile var registerClosed = false
    /** `POST /devices/register` answers this status with [registerError] (429, 503). */
    @Volatile var registerStatus: Int? = null
    @Volatile var registerError = "Слишком много попыток, попробуйте позже"
    /** Every request: the connection drops without an answer and nothing is recorded (no network). */
    @Volatile var offline = false
    /** Every pushed op is answered `rejected` with «Нет прав на изменение». */
    @Volatile var rejectPush = false
    /** `GET /sync/pull` answers 500 (a push before it went through). */
    @Volatile var pullFails = false
    @Volatile var records: List<JsonObject> = emptyList()
    /** `GET /conflicts`; a resolve removes the conflict. */
    @Volatile var conflicts: List<JsonObject> = emptyList()
    @Volatile var pullGate: CountDownLatch? = null
    /** Counted down when a pull arrives (before it waits for [pullGate]). */
    @Volatile var pullStarted = CountDownLatch(1)

    val url: String get() = server.url("/").toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                val query = u.queryParameterNames.associateWith { u.queryParameter(it).orEmpty() }
                val s = Seen(request.method!!, u.encodedPath, request.getHeader("Authorization"), request.body.readUtf8(), query)
                // No network: the request never reaches the server (not recorded), the phone gets no answer.
                // DISCONNECT_AFTER_REQUEST: a dispatcher's DISCONNECT_AT_START is not honoured (only `peek()` is
                // read at the connection's start) and goes out as an empty 200.
                if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                seen += s
                if (s.path == "/devices/register") {
                    if (registerClosed) {
                        return MockResponse().setResponseCode(403).setBody("""{"error":"Владелец сейчас не подключает новые устройства"}""")
                    }
                    registerStatus?.let { return MockResponse().setResponseCode(it).setBody("""{"error":"$registerError"}""") }
                    return json("""{"token":"tok-1","deviceId":"d9","farmId":"f1","role":"manager","kind":"app"}""").setResponseCode(201)
                }
                if (s.path == "/auth/login") {
                    val ok = s.json["password"]!!.jsonPrimitive.content == password
                    return if (ok) json("""{"token":"tok-1","role":"$role"}""")
                    else MockResponse().setResponseCode(401).setBody("""{"error":"Неверный логин или пароль"}""")
                }
                if (unauthorized || s.authorization != "Bearer tok-1") {
                    return MockResponse().setResponseCode(401).setBody("""{"error":"Нужно войти"}""")
                }
                return when (s.path) {
                    "/me" -> json(buildJsonObject {
                        val k = kind
                        if (k == "app") {
                            put("userId", JsonNull)
                            put("role", "manager")
                        } else {
                            put("userId", "u1")
                            put("role", role)
                        }
                        if (k != null) put("kind", k)
                        put("farm", buildJsonObject {
                            put("id", "f1"); put("name", farmName); put("region", "Приаргунский район")
                            put("managerName", managerName); put("timezone", timezone)
                        })
                    }.toString())
                    "/sync/pull" -> {
                        pullStarted.countDown()
                        pullGate?.await(10, TimeUnit.SECONDS)
                        if (pullFails) return MockResponse().setResponseCode(500).setBody("""{"error":"Сервер недоступен"}""")
                        json(buildJsonObject {
                            put("changes", JsonArray(records))
                            put("next", 10)
                            put("more", false)
                        }.toString())
                    }
                    "/sync/push" -> json(buildJsonObject {
                        put("results", buildJsonArray {
                            for (op in s.json["ops"]!!.jsonArray) add(buildJsonObject {
                                put("opId", op.jsonObject["opId"]!!.jsonPrimitive.content)
                                if (rejectPush) {
                                    put("status", "rejected")
                                    put("errors", buildJsonArray { add(JsonPrimitive("Нет прав на изменение")) })
                                } else {
                                    put("status", "applied")
                                    put("version", 1)
                                }
                            })
                        })
                    }.toString())
                    "/conflicts" -> json(buildJsonObject { put("conflicts", JsonArray(conflicts)) }.toString())
                    "/auth/logout" -> ok()
                    "/farm" -> if (s.method == "PATCH") {
                        s.json["name"]?.jsonPrimitive?.content?.let { farmName = it }
                        s.json["managerName"]?.jsonPrimitive?.content?.let { managerName = it }
                        ok()
                    } else notFound()
                    "/members" -> when (s.method) {
                        "GET" -> json(
                            """[{"id":"o1","login":"zubakhin","email":null,"name":null,"role":"owner"},""" +
                                """{"id":"m1","login":"anna","email":null,"name":null,"role":"timesheet"}]""",
                        )
                        "POST" -> json("""{"id":"m2","login":"petya","email":null,"name":null,"role":"coal"}""")
                        else -> notFound()
                    }
                    "/devices" -> json(
                        """[{"id":"d1","name":"Google Pixel 9","platform":"android","login":"zubakhin",""" +
                            """"createdAt":"2026-11-01T00:00:00.000Z","lastSeenAt":"2026-11-06T03:00:00.000Z","revoked":false,"current":true},""" +
                            """{"id":"d2","name":"iPhone","platform":"ios","login":"anna",""" +
                            """"createdAt":"2026-11-01T00:00:00.000Z","lastSeenAt":"2026-11-05T03:00:00.000Z","revoked":false,"current":false}]""",
                    )
                    "/backups" -> when (s.method) {
                        "GET" -> json("""[{"id":"b1","records":120,"created_at":"2026-11-06T01:00:00.000Z"}]""")
                        "POST" -> json("""{"id":"b2","records":121,"createdAt":"2026-11-06T03:00:00.000Z"}""")
                        else -> notFound()
                    }
                    else -> {
                        val resolve = Regex("^/conflicts/([^/]+)/resolve$").find(s.path)
                        when {
                            resolve != null && s.method == "POST" -> {
                                val id = resolve.groupValues[1]
                                conflicts = conflicts.filter { it["id"]!!.jsonPrimitive.content != id }
                                ok()
                            }
                            s.path.startsWith("/members/") && s.method in setOf("PATCH", "DELETE", "POST") -> ok()
                            s.path.startsWith("/devices/") && s.path.endsWith("/revoke") -> ok()
                            s.path.startsWith("/backups/") && s.path.endsWith("/verify") -> ok()
                            s.path.startsWith("/backups/") && s.path.endsWith("/restore") -> json("""{"ok":true,"records":120}""")
                            else -> notFound()
                        }
                    }
                }
            }
        }
        server.start()
    }

    fun requests(path: String) = synchronized(seen) { seen.filter { it.path == path } }

    fun shutdown() {
        pullGate?.countDown()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun ok() = json("""{"ok":true}""")

    private fun notFound() = MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")

    companion object {
        fun driver(id: String, version: Long = 3) = buildJsonObject {
            put("entity", "driver"); put("id", id); put("version", version)
            put("data", buildJsonObject { put("lastName", "Мунгалов"); put("firstName", "Сергей") })
            put("deleted", false)
        }
    }
}
