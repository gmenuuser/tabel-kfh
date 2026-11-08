package ru.kfh.tabel.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    // Not @Upsert: its Android adapter rethrows the driver's constraint error as a framework exception.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MetaRow)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM meta")
    suspend fun clear()

    companion object {
        const val CURSOR = "cursor"
        const val ROLE = "role"
        const val ME = "me"
    }
}
