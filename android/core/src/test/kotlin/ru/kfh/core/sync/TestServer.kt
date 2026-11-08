package ru.kfh.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections
import java.util.concurrent.TimeUnit

/** One request the fake server saw. */
data class Seen(val method: String, val path: String, val query: String?, val authorization: String?, val body: String) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    /** `ops` of a push body. */
    val ops: List<JsonObject> get() = json["ops"]!!.jsonArray.map { it.jsonObject }
}

/**
 * The farm server for the engine tests (MockWebServer): push answers from [push] (null — the connection drops after
 * the request is read), pull pages in order (then an empty last page at `since`), [conflicts] open ones.
 * [override] answers a request first when it returns non-null.
 */
class TestServer {
    val server = MockWebServer()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    @Volatile var push: (List<JsonObject>) -> List<JsonObject>? = applyAll(1)
    private val pages = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var conflicts = 0
    @Volatile var override: (Seen) -> MockResponse? = { null }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val s = Seen(request.method!!, url.encodedPath, url.encodedQuery, request.getHeader("Authorization"),
                             request.body.readUtf8())
                seen += s
                override(s)?.let { return it }
                return when (s.path) {
                    "/sync/push" -> {
                        val results = push(s.ops) ?: return MockResponse().setSocketPolicy(
                            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        json(buildJsonObject { put("results", JsonArray(results)) }.toString())
                    }
                    "/sync/pull" -> {
                        val page = synchronized(pages) { if (pages.isEmpty()) null else pages.removeAt(0) }
                        page?.let { json(it) } ?: run {
                            val since = url.queryParameter("since")?.toLongOrNull() ?: 0
                            json("""{"changes":[],"next":$since,"more":false}""")
                        }
                    }
                    "/conflicts" -> json(buildJsonObject {
                        put("conflicts", buildJsonArray {
                            repeat(conflicts) {
                                add(buildJsonObject {
                                    put("id", "c$it"); put("entity", "driver"); put("recordId", "r"); put("field", "phone")
                                    put("serverValue", "1"); put("clientValue", kotlinx.serialization.json.JsonNull)
                                })
                            }
                        })
                    }.toString())
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
                }
            }
        }
        server.start()
    }

    fun addPages(vararg p: String) {
        pages += p
    }

    val base: String get() = server.url("/").toString().trimEnd('/')

    fun api(token: () -> String? = { "tok" }, base: String = this.base): Api =
        Api(base, OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), token)

    fun paths(): List<String> = seen.map { it.path }

    fun pushes(): List<Seen> = seen.filter { it.path == "/sync/push" }

    fun pulls(): List<Seen> = seen.filter { it.path == "/sync/pull" }

    fun close() = server.shutdown()

    companion object {
        fun json(body: String, code: Int = 200): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

        fun result(op: JsonObject, status: String, version: Long? = null, errors: List<String>? = null) = buildJsonObject {
            put("opId", op["opId"]!!.jsonPrimitive.content)
            put("status", status)
            if (version != null) put("version", version)
            if (errors != null) put("errors", buildJsonArray { errors.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }

        fun applyAll(version: Long): (List<JsonObject>) -> List<JsonObject> = { ops -> ops.map { result(it, "applied", version) } }

        fun change(entity: String, id: String, version: Long, data: String = "{}", deleted: Boolean = false) =
            """{"entity":"$entity","id":"$id","version":$version,"deleted":$deleted,"data":$data,"updatedAt":"2026-11-07T00:00:00.000Z"}"""

        fun page(changes: List<String>, next: Long, more: Boolean = false) =
            """{"changes":[${changes.joinToString(",")}],"next":$next,"more":$more}"""
    }
}
