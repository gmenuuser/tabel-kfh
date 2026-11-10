package ru.kfh.tabel.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.kfh.core.model.RecordCodec

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE entity = :entity AND id = :id")
    suspend fun get(entity: String, id: String): RecordRow?

    @Query("SELECT * FROM records ORDER BY entity, id")
    suspend fun all(): List<RecordRow>

    @Query("SELECT * FROM records WHERE entity = :entity ORDER BY id")
    suspend fun ofEntity(entity: String): List<RecordRow>

    /** The row goes (a record the server never had, dropped with its refused change). */
    @Query("DELETE FROM records WHERE entity = :entity AND id = :id")
    suspend fun delete(entity: String, id: String)

    /** Every record of the entity that is not deleted (removed hours marks still in; use [observe]). */
    @Query("SELECT * FROM records WHERE entity = :entity AND deleted = 0 ORDER BY id")
    fun observeStored(entity: String): Flow<List<RecordRow>>

    // Not @Upsert: its Android adapter rethrows the driver's constraint error as a framework exception.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RecordRow)

    @Query("UPDATE records SET version = :version WHERE entity = :entity AND id = :id AND version < :version")
    suspend fun raiseVersion(entity: String, id: String, version: Long)

    @Query("DELETE FROM records")
    suspend fun clear()
}

/**
 * The records a screen shows: not deleted, and no removed hours marks (`hours: null` would decode as a 0-hour
 * mark). Screens decode them with `RecordCodec.decode`.
 */
fun RecordDao.observe(entity: String): Flow<List<RecordRow>> =
    observeStored(entity).map { rows -> rows.filterNot { it.isRemovedHoursMark() } }

/** A `hoursMark` the server keeps with `hours: null`: no mark. */
fun RecordRow.isRemovedHoursMark(): Boolean = entity == "hoursMark" && RecordCodec.isRemovedHoursMark(json())
