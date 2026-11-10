package ru.kfh.tabel.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One request the fake farm server saw. */
data class Seen(val method: String, val path: String, val authorization: String?, val body: String) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
}

/**
 * The farm server for the session tests: `/auth/login` (password [password]), `/me` ([role], [timezone]),
 * `/sync/pull` (one page with [records], then empty), `/sync/push` (everything applied), `/conflicts` (none),
 * `/auth/logout`. [unauthorized] answers every signed-in request with 401; while [pullGate] is set, every pull
 * waits for it to be counted down.
 */
class FarmServer {
    val server = MockWebServer()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    @Volatile var password = "secret"
    @Volatile var role = "owner"
    @Volatile var timezone = "Asia/Chita"
    @Volatile var farmName = "КФХ Зубахин"
    @Volatile var unauthorized = false
    @Volatile var records: List<JsonObject> = emptyList()
    @Volatile var pullGate: CountDownLatch? = null
    /** Counted down when a pull arrives (before it waits for [pullGate]). */
    @Volatile var pullStarted = CountDownLatch(1)

    val url: String get() = server.url("/").toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                val s = Seen(request.method!!, u.encodedPath, request.getHeader("Authorization"), request.body.readUtf8())
                seen += s
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
                        put("userId", "u1")
                        put("role", role)
                        put("farm", buildJsonObject {
                            put("id", "f1"); put("name", farmName); put("region", "Приаргунский район")
                            put("managerName", "Зубахин А. В."); put("timezone", timezone)
                        })
                    }.toString())
                    "/sync/pull" -> {
                        pullStarted.countDown()
                        pullGate?.await(10, TimeUnit.SECONDS)
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
                                put("status", "applied")
                                put("version", 1)
                            })
                        })
                    }.toString())
                    "/conflicts" -> json("""{"conflicts":[]}""")
                    "/auth/logout" -> json("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
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

    companion object {
        fun driver(id: String, version: Long = 3) = buildJsonObject {
            put("entity", "driver"); put("id", id); put("version", version)
            put("data", buildJsonObject { put("lastName", "Мунгалов"); put("firstName", "Сергей") })
            put("deleted", false)
        }
    }
}
