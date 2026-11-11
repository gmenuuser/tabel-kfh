package ru.kfh.core.sync

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * The phone's store as the Room one (Task 8) behaves, in memory. The instance itself is the "disk": handing the same
 * [FakeStore] to a new [SyncEngine] is an app restart with everything saved kept.
 */
class FakeStore : LocalStore {
    data class Stored(val version: Long, val data: JsonObject, val deleted: Boolean)
    data class Refused(val op: Op, val errors: List<String>)

    val records = linkedMapOf<String, Stored>()
    val queue = mutableListOf<Op>()
    val refused = mutableListOf<Refused>()
    var cursorValue = 0L
    var wipes = 0
    /** Every page handed to [applyPulled], in order. */
    val pulled = mutableListOf<List<RemoteRecord>>()
    /** Runs at the start of [applyPulled] and [acknowledge]: a test can hold the engine right before a write. */
    @Volatile var beforeWrite: suspend () -> Unit = {}
    /** The next [wipe] throws (a broken database): the engine must still end the session. */
    @Volatile var failWipe = false

    private fun key(entity: String, id: String) = "$entity:$id"

    fun record(entity: String, id: String): Stored? = records[key(entity, id)]

    /** A change made here: the record and its op in one go (one action, one op). */
    fun change(entity: String, id: String, fields: JsonObject): Op {
        val old = records[key(entity, id)]
        val op = Op(UUID.randomUUID().toString(), entity, id, "upsert", old?.version ?: 0, fields)
        records[key(entity, id)] = Stored(old?.version ?: 0, JsonObject((old?.data ?: JsonObject(emptyMap())) + fields), false)
        queue += op
        return op
    }

    fun delete(entity: String, id: String): Op {
        val old = records[key(entity, id)]
        val op = Op(UUID.randomUUID().toString(), entity, id, "delete", old?.version ?: 0, JsonObject(emptyMap()))
        records[key(entity, id)] = Stored(old?.version ?: 0, JsonObject(emptyMap()), true)
        queue += op
        return op
    }

    override suspend fun pendingOps(limit: Int): List<Op> = queue.take(limit)

    override suspend fun pendingCount(): Int = queue.size

    override suspend fun refusedCount(): Int = refused.size

    override suspend fun acknowledge(results: List<OpResult>) {
        beforeWrite()
        for (r in results) {
            val op = queue.firstOrNull { it.opId == r.opId } ?: continue
            queue.remove(op)
            if (r.status == "rejected") {
                refused += Refused(op, r.errors)
                continue
            }
            val v = r.version ?: continue
            val k = key(op.entity, op.recordId)
            records[k]?.let { records[k] = it.copy(version = maxOf(it.version, v)) }
            if (r.status != "conflict") {
                // Later changes of this record were made on top of this one.
                for (i in queue.indices) {
                    val o = queue[i]
                    if (o.entity == op.entity && o.recordId == op.recordId) queue[i] = o.copy(baseVersion = v)
                }
            }
        }
    }

    override suspend fun applyPulled(changes: List<RemoteRecord>) {
        beforeWrite()
        pulled += changes
        for (c in changes) {
            val mine = queue.filter { it.entity == c.entity && it.recordId == c.id }
            if (c.deleted || mine.any { it.kind == "delete" }) {
                records[key(c.entity, c.id)] = Stored(c.version, JsonObject(emptyMap()), true)
                continue
            }
            var data: Map<String, kotlinx.serialization.json.JsonElement> = c.data
            for (o in mine) data = data + o.fields
            records[key(c.entity, c.id)] = Stored(c.version, JsonObject(data), false)
        }
    }

    override suspend fun cursor(): Long = cursorValue

    override suspend fun setCursor(v: Long) {
        cursorValue = v
    }

    override suspend fun wipe() {
        wipes++
        if (failWipe) {
            failWipe = false
            throw IllegalStateException("database is locked")
        }
        records.clear()
        queue.clear()
        refused.clear()
        cursorValue = 0
    }
}
