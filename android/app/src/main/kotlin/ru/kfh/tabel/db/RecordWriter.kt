package ru.kfh.tabel.db

import androidx.room3.withWriteTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.roles.Role
import ru.kfh.core.roles.canWrite
import java.util.Locale
import java.util.UUID

/**
 * Every local change goes through here: the record and its op are written in one transaction, so a saved change is
 * never lost before it is sent (as `TabelKFH/Sync/SyncedSave.swift` and the site's `engine.save`).
 *
 * - The op carries only what changed (`RecordCodec.diff` against the stored record); nothing changed — nothing written.
 * - Every save is a new op with a new opId: a queued op is never merged into or rewritten.
 * - `baseVersion` is the stored record's version (0 for a new one).
 * - Ids are written lowercase.
 * - A role that may not write the entity (`Role.canWrite`) gets [IllegalStateException] and nothing is written.
 */
class RecordWriter internal constructor(
    private val db: TabelDb,
    private val role: () -> Role,
    private val opIds: () -> String,
    private val clock: () -> Long,
) {
    constructor(db: TabelDb, role: () -> Role) : this(db, role, ::newId, System::currentTimeMillis)

    /** May the signed-in role change records of [entity]. Screens hide editing and seeding of defaults with it. */
    fun canWrite(entity: String): Boolean = role().canWrite(entity)

    /** [newData] is the record's JSON (`RecordCodec.encode`) or only some of its fields; it goes on top of the stored one. */
    suspend fun save(entity: String, id: String, newData: JsonObject) = saveAll(listOf(Triple(entity, id, newData)))

    /** One user action that changes several records (closing a coal day): all records and ops in one transaction. */
    suspend fun saveAll(changes: List<Triple<String, String, JsonObject>>) {
        for ((entity, _, _) in changes) requireWrite(entity)
        db.withWriteTransaction {
            for ((entity, id, data) in changes) write(entity, lower(id), data)
        }
    }

    /**
     * Deletes the record (a `delete` op). An hours mark is not deleted but removed: `hours: null` (the server keeps it).
     * A record that is not there or already deleted queues nothing.
     */
    suspend fun delete(entity: String, id: String) {
        requireWrite(entity)
        val key = lower(id)
        db.withWriteTransaction {
            if (entity == HOURS_MARK) {
                val old = db.records().get(entity, key)
                if (old != null && !old.deleted) write(entity, key, JsonObject(mapOf("hours" to JsonNull)))
                return@withWriteTransaction
            }
            val old = db.records().get(entity, key) ?: return@withWriteTransaction
            if (old.deleted) return@withWriteTransaction
            db.records().upsert(old.copy(data = EMPTY, deleted = true))
            queue(entity, key, "delete", old.version, JsonObject(emptyMap()))
        }
    }

    private suspend fun write(entity: String, id: String, newData: JsonObject) {
        val old = db.records().get(entity, id)
        val known = old?.takeUnless { it.deleted }?.json()
        val fields = RecordCodec.diff(known, newData)
        if (fields.isEmpty()) return
        val merged = JsonObject((known ?: JsonObject(emptyMap())) + newData)
        db.records().upsert(RecordRow(entity, id, old?.version ?: 0, merged.toString(), deleted = false))
        queue(entity, id, "upsert", old?.version ?: 0, fields)
    }

    private suspend fun queue(entity: String, id: String, kind: String, baseVersion: Long, fields: JsonObject) {
        db.outbox().insert(
            OutboxRow(opId = lower(opIds()), entity = entity, recordId = id, kind = kind, baseVersion = baseVersion,
                fields = fields.toString(), createdAt = clock()),
        )
    }

    private fun requireWrite(entity: String) {
        check(canWrite(entity)) { "Нет прав на изменение этих данных" }
    }

    companion object {
        private const val HOURS_MARK = "hoursMark"
        private const val EMPTY = "{}"

        /** A new record id or opId: a random UUID, lowercase. */
        fun newId(): String = UUID.randomUUID().toString().lowercase(Locale.ROOT)

        private fun lower(id: String) = id.lowercase(Locale.ROOT)
    }
}
