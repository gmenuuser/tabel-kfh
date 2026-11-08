package ru.kfh.tabel.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A synced record as the server keeps it: [data] is its JSON (`{}` when [deleted]); screens decode it with RecordCodec. */
@Entity(tableName = "records", primaryKeys = ["entity", "id"])
data class RecordRow(
    val entity: String,
    val id: String,
    val version: Long,
    val data: String,
    val deleted: Boolean,
) {
    fun json(): JsonObject = parseObject(data)
}

/** One queued op: [seq] is the queue order, [opId] is made once and never changes. */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["opId"], unique = true), Index(value = ["entity", "recordId"])],
)
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val entity: String,
    val recordId: String,
    val kind: String,
    val baseVersion: Long,
    val fields: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
) {
    fun fieldsJson(): JsonObject = parseObject(fields)
}

/** An op the server refused («Не принятые изменения»): [errors] is a JSON array of the server's texts. */
@Entity(tableName = "refused")
data class RefusedRow(
    @PrimaryKey val opId: String,
    val entity: String,
    val recordId: String,
    val kind: String,
    val fields: String,
    val errors: String,
    /** Epoch milliseconds. */
    val at: Long,
) {
    fun fieldsJson(): JsonObject = parseObject(fields)
    fun errorList(): List<String> = Json.parseToJsonElement(errors).jsonArray.map { it.jsonPrimitive.content }
}

/** Small values: the pull cursor, the role, `Me` as JSON. */
@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

internal fun parseObject(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

internal fun errorsJson(errors: List<String>): String = JsonArray(errors.map { JsonPrimitive(it) }).toString()
