package ru.kfh.tabel.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Room on the plain JVM: the bundled SQLite (natives for Windows/Linux) over a file in the test's folder. */
fun openTestDb(file: File): TabelDb =
    TabelDb.configure(Room.databaseBuilder<TabelDb>(file.path).setDriver(BundledSQLiteDriver())).build()

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

private fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    else -> error("unsupported $v")
}
