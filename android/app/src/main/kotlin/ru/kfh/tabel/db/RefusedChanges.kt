package ru.kfh.tabel.db

import androidx.room3.withWriteTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import ru.kfh.core.model.Entities
import java.util.Locale

/**
 * «Не принятые изменения» (`TabelKFH/Sync/RefusedChanges.swift`): the ops the server refused, kept in the
 * `refused` table to be looked at and removed.
 */
object RefusedChanges {
    /** What was changed, in Russian (as the site's list). */
    fun title(entity: String): String = when (entity) {
        Entities.employee -> "Сотрудник"
        Entities.hoursMark -> "Часы"
        Entities.attendanceRecord -> "Отметка"
        Entities.timesheetSnapshot -> "Версия табеля"
        Entities.farmSettings -> "Настройки хозяйства"
        Entities.coalSettings -> "Цена угля"
        Entities.deliveryLocation -> "Населённый пункт"
        Entities.driver -> "Водитель"
        Entities.coalSale -> "Продажа угля"
        Entities.driverPayment -> "Выплата"
        Entities.coalWorkDay -> "Рабочий день"
        Entities.livestockCategory -> "Категория скота"
        Entities.livestockOperation -> "Операция со скотом"
        Entities.slaughterRecord -> "Забой"
        Entities.livestockSettings -> "Настройки скота"
        Entities.agroCalculation -> "Расчёт"
        else -> entity
    }

    fun title(row: RefusedRow): String {
        val what = title(row.entity)
        return if (row.kind == "delete") "$what: удаление" else what
    }

    /** «У этого водителя 3 продажи угля. Сначала выберите в них другого водителя, потом удалите.» */
    class DriverHasSales(n: Int) : IllegalStateException(
        "У этого водителя ${sales(n)} угля. Сначала выберите в них другого водителя, потом удалите.",
    )

    /** The records that cannot be without a record of the entity: the child entity and its link field. */
    private val dependents: Map<String, List<Pair<String, String>>> = mapOf(
        Entities.employee to listOf(
            Entities.hoursMark to "employeeId", Entities.attendanceRecord to "employeeId", Entities.timesheetSnapshot to "employeeId",
        ),
        Entities.driver to listOf(Entities.driverPayment to "driverId"),
        Entities.livestockCategory to listOf(Entities.livestockOperation to "categoryId", Entities.livestockOperation to "targetCategoryId"),
        Entities.livestockOperation to listOf(Entities.slaughterRecord to "operationId"),
    )

    /**
     * «Удалить»: the refused change goes. A record the server never had (version 0) goes from the phone with all its
     * queued changes, and so do the records that cannot be without it ([dependents]), which would otherwise reach
     * the server pointing at nothing; a new driver with coal sales is not removed ([DriverHasSales]). The pull
     * cursor goes back to 0, so the next sync takes every record from the server again: for a record the server
     * has, its values come back on the phone (the refused value is gone). One transaction; the caller syncs after.
     */
    suspend fun discard(db: TabelDb, opId: String) {
        db.withWriteTransaction {
            val op = db.outbox().refusedByOpId(opId) ?: return@withWriteTransaction
            val record = db.records().get(op.entity, op.recordId)
            val neverOnServer = record == null || record.version == 0L
            if (neverOnServer && op.entity == Entities.driver) {
                val sales = db.records().ofEntity(Entities.coalSale).count { !it.deleted && it.link("driverId") == op.recordId }
                if (sales > 0) throw DriverHasSales(sales)
            }
            if (neverOnServer) {
                for ((child, field) in dependents[op.entity].orEmpty()) {
                    for (id in linkedIds(db, child, field, op.recordId)) purge(db, child, id)
                }
                // Singletons are never deleted (as in SyncEngine.apply): the screens would recreate and re-queue them.
                if (op.entity !in Entities.SINGLETONS) db.records().delete(op.entity, op.recordId)
                db.outbox().deleteOfRecord(op.entity, op.recordId)
            }
            db.outbox().deleteRefused(op.opId)
            db.meta().put(MetaRow(MetaDao.CURSOR, "0"))
        }
    }

    /** The record, its dependents, all their queued and refused changes go. */
    private suspend fun purge(db: TabelDb, entity: String, id: String) {
        for ((child, field) in dependents[entity].orEmpty()) {
            for (childId in linkedIds(db, child, field, id)) purge(db, child, childId)
        }
        if (entity !in Entities.SINGLETONS) db.records().delete(entity, id)
        db.outbox().deleteOfRecord(entity, id)
        db.outbox().deleteRefusedOfRecord(entity, id)
    }

    /** The ids of the records of [entity] whose [field] is [parent]: the rows on the phone and the queued changes. */
    private suspend fun linkedIds(db: TabelDb, entity: String, field: String, parent: String): Set<String> {
        val key = parent.lowercase(Locale.ROOT)
        val ids = HashSet<String>()
        for (row in db.records().ofEntity(entity)) if (!row.deleted && row.link(field) == key) ids += row.id
        for (op in db.outbox().ofEntity(entity)) {
            val v = op.fieldsJson()[field] as? JsonPrimitive ?: continue
            if (v !is JsonNull && v.content.lowercase(Locale.ROOT) == key) ids += op.recordId
        }
        return ids
    }

    private fun RecordRow.link(field: String): String? {
        val v = json()[field] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content.lowercase(Locale.ROOT)
    }

    /** 1 продажа, 2 продажи, 5 продаж (`CoalText.sales`). */
    private fun sales(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> "продажа"
            m10 in 2..4 && m100 !in 12..14 -> "продажи"
            else -> "продаж"
        }
        return "$n $word"
    }
}
