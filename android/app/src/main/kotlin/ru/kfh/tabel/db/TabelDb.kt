package ru.kfh.tabel.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** The phone's store: synced records, the send queue, refused ops and small values (cursor, role, Me). */
@Database(
    entities = [RecordRow::class, OutboxRow::class, RefusedRow::class, MetaRow::class],
    version = 1,
    exportSchema = true,
)
abstract class TabelDb : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun meta(): MetaDao

    companion object {
        const val FILE = "tabel.db"

        /** The app's database file (`databases/tabel.db`); [name] differs only in instrumented tests. */
        fun open(context: Context, name: String = FILE): TabelDb = configure(
            Room.databaseBuilder<TabelDb>(context.applicationContext, context.getDatabasePath(name).path)
                .setDriver(AndroidSQLiteDriver()),
        ).build()

        /** What every build of the database shares (the app and the JVM tests). */
        fun configure(builder: Builder<TabelDb>): Builder<TabelDb> = builder.setQueryCoroutineContext(Dispatchers.IO)
    }
}
