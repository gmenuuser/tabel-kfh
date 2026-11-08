package ru.kfh.core.ids

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * The same ids as the site (`web/src/lib/ids.ts`) and the iPhone: first 16 bytes of SHA-256(UTF-8 name) with
 * version nibble 8 and variant 10, as a lowercase UUID. UUIDs inside names are lowercase.
 */
object StableId {
    fun make(name: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x80).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
            .joinToString("-")
    }

    fun hoursMark(employeeId: String, day: LocalDate): String =
        make("hoursMark:${employeeId.lowercase(Locale.ROOT)}:$day")

    fun coalWorkDay(day: LocalDate): String = make("coalWorkDay:$day")

    fun deliveryLocation(name: String): String = make("deliveryLocation:$name")

    /** `species` is the site's identifier: cattle, goats, pigs. */
    fun livestockCategory(species: String, name: String): String = make("livestockCategory:$species:$name")

    val coalSettings: String = make("coalSettings")
    val farmSettings: String = make("farmSettings")
    val livestockSettings: String = make("livestockSettings")
}
