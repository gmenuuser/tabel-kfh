package ru.kfh.tabel.db

import androidx.room3.withWriteTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.LocalStore
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.Op
import ru.kfh.core.sync.OpResult
import ru.kfh.core.sync.RemoteRecord
import java.time.ZoneId

/** [LocalStore] over Room, behaving as `ru.kfh.core.sync.FakeStore` (core tests). Also keeps the role and `Me`. */
class RoomLocalStore(
    private val db: TabelDb,
    private val photos: PhotoStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalStore {
    override suspend fun pendingOps(limit: Int): List<Op> = db.outbox().oldest(limit).map { it.toOp() }

    override suspend fun pendingCount(): Int = db.outbox().count()

    override suspend fun refusedCount(): Int = db.outbox().refusedCount()

    override suspend fun acknowledge(results: List<OpResult>) {
        db.withWriteTransaction {
            for (r in results) {
                val op = db.outbox().byOpId(r.opId) ?: continue
                db.outbox().deleteOp(op.opId)
                if (r.status == "rejected") {
                    db.outbox().insertRefused(
                        RefusedRow(op.opId, op.entity, op.recordId, op.kind, op.fields, errorsJson(r.errors), clock()),
                    )
                    continue
                }
                val v = r.version ?: continue
                db.records().raiseVersion(op.entity, op.recordId, v)
                // Later changes of this record were made on top of this one.
                if (r.status != "conflict") db.outbox().setBaseVersion(op.entity, op.recordId, v)
            }
        }
    }

    override suspend fun applyPulled(changes: List<RemoteRecord>) {
        db.withWriteTransaction {
            for (c in changes) {
                val mine = db.outbox().ofRecord(c.entity, c.id)
                if (c.deleted || mine.any { it.kind == "delete" }) {
                    db.records().upsert(RecordRow(c.entity, c.id, c.version, "{}", deleted = true))
                    continue
                }
                var data: Map<String, kotlinx.serialization.json.JsonElement> = c.data
                for (o in mine) data = data + o.fieldsJson()
                db.records().upsert(RecordRow(c.entity, c.id, c.version, JsonObject(data).toString(), deleted = false))
            }
        }
    }

    override suspend fun cursor(): Long = db.meta().get(MetaDao.CURSOR)?.toLongOrNull() ?: 0

    override suspend fun setCursor(v: Long) = db.meta().put(MetaRow(MetaDao.CURSOR, v.toString()))

    /** The signed-in role as last known (from `/me`), null when signed out. */
    suspend fun role(): Role? = db.meta().get(MetaDao.ROLE)?.let { Role.fromWire(it) }

    suspend fun me(): Me? {
        val o = db.meta().get(MetaDao.ME)?.let(::parseObject) ?: return null
        fun s(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        return Me(
            userId = s("userId") ?: return null,
            role = s("role")?.let { Role.fromWire(it) } ?: return null,
            farmName = s("farmName") ?: "",
            managerName = s("managerName"),
            region = s("region"),
            timeZone = s("timeZone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Asia/Chita"),
        )
    }

    /** `/me` answered: kept with its role, so the app opens offline as the same person. */
    suspend fun saveMe(me: Me) {
        val o = JsonObject(
            mapOf(
                "userId" to JsonPrimitive(me.userId),
                "role" to JsonPrimitive(me.role.name),
                "farmName" to JsonPrimitive(me.farmName),
                "managerName" to (me.managerName?.let(::JsonPrimitive) ?: JsonNull),
                "region" to (me.region?.let(::JsonPrimitive) ?: JsonNull),
                "timeZone" to JsonPrimitive(me.timeZone.id),
            ),
        )
        db.withWriteTransaction {
            db.meta().put(MetaRow(MetaDao.ME, o.toString()))
            db.meta().put(MetaRow(MetaDao.ROLE, me.role.name))
        }
    }

    /** Sign-out: records, queue, refused ops, cursor, role, Me and photos go. */
    override suspend fun wipe() {
        db.withWriteTransaction {
            db.records().clear()
            db.outbox().clear()
            db.outbox().clearRefused()
            db.meta().clear()
        }
        photos?.wipe()
    }

    private fun OutboxRow.toOp() = Op(opId, entity, recordId, kind, baseVersion, fieldsJson())
}
