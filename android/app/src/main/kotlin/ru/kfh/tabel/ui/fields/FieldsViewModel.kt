package ru.kfh.tabel.ui.fields

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import ru.kfh.core.fields.FieldText
import ru.kfh.core.model.Entities
import ru.kfh.core.model.Field
import ru.kfh.core.model.RecordCodec
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.observe

/**
 * «Поля»: the farm's fields as a flow and «Сохранить» / «Удалить» through [RecordWriter]. A field is an ordinary
 * synced record (`field`), so nothing here knows about the network: the outbox carries it. One per process
 * ([ru.kfh.tabel.AppGraph.fields]).
 */
class FieldsViewModel(
    private val dao: RecordDao,
    private val writer: RecordWriter,
) {
    /** Every field, sorted by name in Russian (`FieldText.sort`). */
    val fields: Flow<List<Field>> = dao.observe(Entities.field)
        .map { rows -> FieldText.sort(rows.map { RecordCodec.decode<Field>(Entities.field, it.id, it.json()) }) }
        .flowOn(Dispatchers.Default)

    /** May the signed-in role change fields (`Role.canWrite("field")`; a viewer only reads). */
    val canSave: Boolean get() = writer.canWrite(Entities.field)

    /**
     * A new field when [id] is null, an edit otherwise. The area is whole square metres already (`FieldText`).
     * An edit of a field deleted on another device is refused by the writer, not silently brought back.
     */
    suspend fun save(id: String?, name: String, areaSqM: Long, culture: String, note: String) {
        val data = RecordCodec.encode(
            Field(name = name.trim(), areaSqM = areaSqM, culture = culture.trim().ifEmpty { null }, note = note.trim().ifEmpty { null }),
        )
        if (id == null) writer.save(Entities.field, RecordWriter.newId(), data) else writer.update(Entities.field, id, data)
    }

    suspend fun remove(id: String) = writer.delete(Entities.field, id)
}
