package ru.kfh.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ru.kfh.core.roles.Role
import java.time.ZoneId

/**
 * One change waiting for the server (`POST /sync/push`, server/README.md «Протокол синхронизации»).
 * [opId] is made once per user action and kept until the server answers: a repeat is answered with the old result.
 * [kind] is `"upsert"` (only the changed [fields]) or `"delete"` ([fields] empty and not sent).
 */
data class Op(
    val opId: String,
    val entity: String,
    val recordId: String,
    val kind: String,
    val baseVersion: Long,
    val fields: JsonObject,
)

/** The server's answer to one op (server/src/sync.ts `OpResult`). [status]: applied | partial | conflict | rejected. */
data class OpResult(
    val opId: String,
    val status: String,
    val version: Long?,
    val errors: List<String>,
    val repeated: Boolean,
) {
    /** A definite answer: the op leaves the queue (rejected ones go to «Не принятые изменения»). */
    val isSettled: Boolean get() = status in SETTLED

    companion object {
        val SETTLED = setOf("applied", "partial", "conflict", "rejected")
    }
}

/**
 * A record from `GET /sync/pull`. [data] is the server's JSON as is (`{}` when [deleted]); a `hoursMark` with
 * `hours: null` is a removed mark (`RecordCodec.isRemovedHoursMark`).
 */
data class RemoteRecord(val entity: String, val id: String, val version: Long, val data: JsonObject, val deleted: Boolean)

/** One page of `GET /sync/pull?since=`: keep [next] as the cursor; [more] — ask again. */
data class PullPage(val changes: List<RemoteRecord>, val next: Long, val more: Boolean)

/**
 * `GET /me` (server/src/app.ts): the user, their role and the farm. [timeZone] is the farm's zone (`farm.timezone`),
 * `Asia/Chita` when absent or unknown: "today" is counted in it. [userId] is null for a phone that connected itself
 * ([Role.app]).
 */
data class Me(
    val userId: String?,
    val role: Role,
    val farmName: String,
    val managerName: String?,
    val region: String?,
    val timeZone: ZoneId,
)

/** `GET /members`. */
data class Member(val id: String, val login: String?, val email: String?, val name: String?, val role: Role)

/** `GET /devices`. Dates are the server's ISO strings. */
data class Device(
    val id: String,
    val name: String?,
    val platform: String?,
    val email: String?,
    val login: String?,
    val createdAt: String?,
    val lastSeenAt: String?,
    val revoked: Boolean,
    val current: Boolean,
)

/** `GET /conflicts`: [field] `"__deleted"` is a conflict about deleting. */
data class Conflict(
    val id: String,
    val entity: String,
    val recordId: String,
    val field: String,
    val serverValue: JsonElement,
    val clientValue: JsonElement,
    val createdAt: String?,
)

/** `POST /conflicts/{id}/resolve {choice}`. */
enum class ConflictChoice { server, client }

/** `GET /backups` / `POST /backups`. */
data class Backup(val id: String, val records: Int, val createdAt: String)

/**
 * The sync in one line on the main screen (texts — `TabelKFH/Domain/Roles.swift` `SyncStatusText`, shown by
 * `SyncStatusLine.swift`).
 */
sealed interface SyncStatus {
    /** Everything sent and received. */
    data object Synced : SyncStatus

    /** A sync is running. */
    data object Syncing : SyncStatus

    /** No connection; [pending] changes wait. */
    data class Offline(val pending: Int) : SyncStatus

    /** The server answered with an error, the connection is broken, or changes were refused. */
    data class Failed(val message: String) : SyncStatus

    /** Open conflicts on the server. */
    data class Conflicts(val count: Int) : SyncStatus

    /** Online, but [count] changes are still waiting (the server did not answer them yet). */
    data class Pending(val count: Int) : SyncStatus

    /** The status line. Only the owner chooses a version of a conflict; the others see the number. */
    fun line(role: Role): String = when (this) {
        Syncing -> "Синхронизация…"
        is Offline -> "Нет связи. Ждут отправки: $pending"
        is Failed -> "Ошибка синхронизации"
        is Conflicts -> if (role == Role.owner) "Конфликты: $count" else "Конфликты: $count — обратитесь к владельцу"
        is Pending -> "Ждут отправки: $count"
        Synced -> "Все данные синхронизированы"
    }

    /** Something is wrong (shown in the accent colour). */
    val isProblem: Boolean get() = this is Offline || this is Failed || this is Conflicts

    companion object {
        /** The second line of the status button. */
        const val ACTION = "Синхронизировать сейчас"
    }
}
