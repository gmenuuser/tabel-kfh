package ru.kfh.tabel.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** The send queue and the refused ops. Rows are only inserted and deleted; [setBaseVersion] is the one update. */
@Dao
interface OutboxDao {
    /** Fails on a repeated opId (an op is never replaced). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: OutboxRow): Long

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun all(): List<OutboxRow>

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    suspend fun oldest(limit: Int): List<OutboxRow>

    @Query("SELECT * FROM outbox WHERE opId = :opId")
    suspend fun byOpId(opId: String): OutboxRow?

    @Query("SELECT * FROM outbox WHERE entity = :entity AND recordId = :recordId ORDER BY seq")
    suspend fun ofRecord(entity: String, recordId: String): List<OutboxRow>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE opId = :opId")
    suspend fun deleteOp(opId: String)

    @Query("UPDATE outbox SET baseVersion = :version WHERE entity = :entity AND recordId = :recordId")
    suspend fun setBaseVersion(entity: String, recordId: String, version: Long)

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefused(row: RefusedRow)

    @Query("SELECT * FROM refused ORDER BY at, opId")
    suspend fun refused(): List<RefusedRow>

    @Query("SELECT * FROM refused ORDER BY at, opId")
    fun observeRefused(): Flow<List<RefusedRow>>

    @Query("SELECT COUNT(*) FROM refused")
    suspend fun refusedCount(): Int

    @Query("DELETE FROM refused")
    suspend fun clearRefused()
}
