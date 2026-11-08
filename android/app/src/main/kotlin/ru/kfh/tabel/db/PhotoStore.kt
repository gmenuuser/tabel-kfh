package ru.kfh.tabel.db

import android.content.Context
import java.io.File
import java.util.Locale

/** Employee photos as files `filesDir/photos/<employeeId>.jpg` (id lowercase). */
class PhotoStore(val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "photos"))

    fun file(employeeId: String): File = File(dir, employeeId.lowercase(Locale.ROOT) + ".jpg")

    fun load(employeeId: String): ByteArray? = file(employeeId).takeIf { it.isFile }?.readBytes()

    /** Written to a temporary file first, so a half-written photo never replaces a whole one. */
    fun save(employeeId: String, jpeg: ByteArray) {
        dir.mkdirs()
        val target = file(employeeId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot save photo" }
        }
    }

    fun delete(employeeId: String) {
        file(employeeId).delete()
    }

    /** Sign-out: every photo goes. False when something could not be deleted. */
    fun wipe(): Boolean = dir.deleteRecursively() && !dir.exists()
}
