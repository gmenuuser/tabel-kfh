package ru.kfh.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.kfh.core.roles.Role
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import java.io.IOException
import java.time.ZoneId

/** 401 on a signed-in request: the device is signed out or its access was revoked. */
class Unauthorized : Exception("Нужно войти заново")

/** Any other non-2xx answer (or one that cannot be read); [text] is the server's `{error}`. */
class ApiError(val status: Int, val text: String) : Exception(text)

/**
 * JSON requests to the farm server (server/src/app.ts) with the device token (`Authorization: Bearer`).
 * A network failure is an [IOException]; [Unauthorized] and [ApiError] are answers. The token is never logged
 * or put into an exception.
 */
class Api(base: String, private val client: OkHttpClient, private val token: () -> String?) {
    private val base = base.trimEnd('/')

    // MARK: Accounts

    /** `POST /auth/login`; returns the device token. A wrong login or password is an [ApiError] 401. */
    suspend fun login(login: String, password: String, deviceName: String): String {
        val body = buildJsonObject {
            put("login", login)
            put("password", password)
            put("platform", "android")
            put("deviceName", deviceName)
        }
        return parse(send("POST", "/auth/login", body, signedIn = false)) { it.obj.str("token")!! }
    }

    /**
     * `POST /devices/register`: the phone connects itself, no account; returns the device token. The owner closing
     * new devices is an [ApiError] 403 with the server's text; too many attempts — 429.
     */
    suspend fun register(name: String): String {
        val body = buildJsonObject {
            put("platform", "android")
            put("name", name.take(MAX_DEVICE_NAME))
        }
        return parse(send("POST", "/devices/register", body, signedIn = false)) { it.obj.str("token")!! }
    }

    suspend fun logout() {
        send("POST", "/auth/logout", JsonObject(emptyMap()))
    }

    suspend fun me(): Me = parse(send("GET", "/me")) { root ->
        val o = root.obj
        val farm = o["farm"]!!.jsonObject
        Me(
            userId = o.str("userId"),
            // An app device: `role` says «manager» only for the old builds.
            role = if (o.str("kind") == "app") Role.app else Role.fromWire(o.str("role").orEmpty()) ?: Role.viewer,
            farmName = farm.str("name").orEmpty(),
            managerName = farm.str("managerName"),
            region = farm.str("region"),
            timeZone = zone(farm.str("timezone")),
        )
    }

    /** `PATCH /farm` (owner): only the given fields are changed. */
    suspend fun patchFarm(name: String? = null, region: String? = null, managerName: String? = null, timeZone: String? = null) {
        val body = buildJsonObject {
            name?.let { put("name", it) }
            region?.let { put("region", it) }
            managerName?.let { put("managerName", it) }
            timeZone?.let { put("timezone", it) }
        }
        send("PATCH", "/farm", body)
    }

    // MARK: Sync

    suspend fun push(ops: List<Op>): List<OpResult> {
        val body = buildJsonObject {
            put("ops", buildJsonArray {
                for (op in ops) add(buildJsonObject {
                    put("opId", op.opId)
                    put("entity", op.entity)
                    put("recordId", op.recordId)
                    put("kind", op.kind)
                    put("baseVersion", op.baseVersion)
                    if (op.kind != "delete") put("fields", op.fields)
                })
            })
        }
        return parse(send("POST", "/sync/push", body)) { root ->
            root.obj["results"]!!.jsonArray.map { e ->
                val r = e.jsonObject
                OpResult(
                    opId = r.str("opId")!!,
                    status = r.str("status")!!,
                    version = r.long("version"),
                    errors = (r["errors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    repeated = (r["repeated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
        }
    }

    suspend fun pull(since: Long): PullPage = parse(send("GET", "/sync/pull?since=$since")) { root ->
        val o = root.obj
        PullPage(
            changes = o["changes"]!!.jsonArray.map { e ->
                val c = e.jsonObject
                RemoteRecord(
                    entity = c.str("entity")!!,
                    id = c.str("id")!!,
                    version = c.long("version")!!,
                    data = (c["data"] as? JsonObject) ?: JsonObject(emptyMap()),
                    deleted = (c["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            },
            next = o.long("next")!!,
            more = (o["more"] as JsonPrimitive).booleanOrNull!!,
        )
    }

    // MARK: Helpers of the farm

    suspend fun members(): List<Member> = parse(send("GET", "/members")) { root -> root.jsonArray.map { member(it.jsonObject) } }

    suspend fun addMember(login: String, password: String, role: Role): Member {
        val body = buildJsonObject {
            put("login", login)
            put("password", password)
            put("role", role.name)
        }
        return parse(send("POST", "/members", body)) { member(it.obj) }
    }

    suspend fun setPassword(userId: String, password: String) {
        send("POST", "/members/${seg(userId)}/password", buildJsonObject { put("password", password) })
    }

    suspend fun setRole(userId: String, role: Role) {
        send("PATCH", "/members/${seg(userId)}", buildJsonObject { put("role", role.name) })
    }

    suspend fun removeMember(userId: String) {
        send("DELETE", "/members/${seg(userId)}")
    }

    // MARK: Devices

    suspend fun devices(): List<Device> = parse(send("GET", "/devices")) { root ->
        root.jsonArray.map { e ->
            val d = e.jsonObject
            Device(
                id = d.str("id")!!,
                name = d.str("name"),
                platform = d.str("platform"),
                email = d.str("email"),
                login = d.str("login"),
                createdAt = d.str("createdAt"),
                lastSeenAt = d.str("lastSeenAt"),
                revoked = (d["revoked"] as? JsonPrimitive)?.booleanOrNull ?: false,
                current = (d["current"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    suspend fun revoke(id: String) {
        send("POST", "/devices/${seg(id)}/revoke", JsonObject(emptyMap()))
    }

    // MARK: Conflicts

    suspend fun conflicts(): List<Conflict> = parse(send("GET", "/conflicts")) { root ->
        root.obj["conflicts"]!!.jsonArray.map { e ->
            val c = e.jsonObject
            Conflict(
                id = c.str("id")!!,
                entity = c.str("entity")!!,
                recordId = c.str("recordId").orEmpty(),
                field = c.str("field")!!,
                serverValue = c["serverValue"] ?: JsonNull,
                clientValue = c["clientValue"] ?: JsonNull,
                createdAt = c.str("createdAt"),
            )
        }
    }

    suspend fun resolve(id: String, choice: ConflictChoice) {
        send("POST", "/conflicts/${seg(id)}/resolve", buildJsonObject { put("choice", choice.name) })
    }

    // MARK: Server backups (owner)

    suspend fun backups(): List<Backup> = parse(send("GET", "/backups")) { root -> root.jsonArray.map { backup(it.jsonObject) } }

    suspend fun createBackup(): Backup = parse(send("POST", "/backups", JsonObject(emptyMap()))) { backup(it.obj) }

    /** The stored copy is whole (its SHA-256 matches). */
    suspend fun verifyBackup(id: String): Boolean =
        parse(send("GET", "/backups/${seg(id)}/verify")) { (it.obj["ok"] as JsonPrimitive).booleanOrNull!! }

    /** Restores the copy; returns how many records it had. */
    suspend fun restoreBackup(id: String): Int =
        parse(send("POST", "/backups/${seg(id)}/restore", buildJsonObject { put("confirm", true) })) { it.obj.long("records")!!.toInt() }

    // MARK: Wire

    override fun toString(): String = "Api($base)"

    private fun member(m: JsonObject) =
        Member(m.str("id")!!, m.str("login"), m.str("email"), m.str("name"), Role.fromWire(m.str("role").orEmpty()) ?: Role.viewer)

    /** `GET /backups` has the database's `created_at`, `POST /backups` — `createdAt`. */
    private fun backup(b: JsonObject) =
        Backup(b.str("id")!!, b.long("records")?.toInt() ?: 0, b.str("createdAt") ?: b.str("created_at").orEmpty())

    /**
     * One request, run synchronously on [Dispatchers.IO] (interrupted on cancellation); returns the 2xx body.
     * [signedIn] false (sign-in): no token, and a 401 is the server's message, not a revoked device.
     */
    private suspend fun send(method: String, path: String, body: JsonObject? = null, signedIn: Boolean = true): String {
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (signedIn) token()?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, body?.toString()?.toRequestBody(JSON))
        val request = builder.build()
        val (code, text) = runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
        }
        if (code == 401 && signedIn) throw Unauthorized()
        if (code !in 200..299) throw ApiError(code, errorText(text) ?: "Сервер ответил $code")
        return text
    }

    private inline fun <T> parse(text: String, read: (JsonElement) -> T): T = try {
        read(Json.parseToJsonElement(text))
    } catch (e: Exception) {
        // NullPointerException, IllegalArgumentException, SerializationException: not the answer we know.
        throw ApiError(200, "Непонятный ответ сервера")
    }

    private fun errorText(text: String): String? = try {
        (Json.parseToJsonElement(text) as? JsonObject)?.str("error")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** The server's limit on a device's name. */
        const val MAX_DEVICE_NAME = 100

        val JSON = "application/json; charset=utf-8".toMediaType()

        val JsonElement.obj: JsonObject get() = this.jsonObject

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        /** A number, or a number in a string (PostgreSQL bigint). */
        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

        fun zone(id: String?): ZoneId = try {
            if (id.isNullOrBlank()) DEFAULT_FARM_ZONE else ZoneId.of(id)
        } catch (e: Exception) {
            DEFAULT_FARM_ZONE
        }

        /** A path segment: ids are UUIDs, but nothing else may change the path. */
        fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
