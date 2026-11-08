package ru.kfh.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ru.kfh.core.time.Iso
import java.time.Instant

/** Entity names exactly as the server (`server/src/entities.ts`), the site and the iPhone spell them. */
object Entities {
    const val employee = "employee"
    const val hoursMark = "hoursMark"
    const val attendanceRecord = "attendanceRecord"
    const val timesheetSnapshot = "timesheetSnapshot"
    const val farmSettings = "farmSettings"
    const val coalSettings = "coalSettings"
    const val deliveryLocation = "deliveryLocation"
    const val driver = "driver"
    const val coalSale = "coalSale"
    const val driverPayment = "driverPayment"
    const val coalWorkDay = "coalWorkDay"
    const val livestockCategory = "livestockCategory"
    const val livestockOperation = "livestockOperation"
    const val slaughterRecord = "slaughterRecord"
    const val livestockSettings = "livestockSettings"
    const val agroCalculation = "agroCalculation"

    val ALL: Set<String> = setOf(
        employee, hoursMark, attendanceRecord, timesheetSnapshot, farmSettings, coalSettings, deliveryLocation,
        driver, coalSale, driverPayment, coalWorkDay, livestockCategory, livestockOperation, slaughterRecord,
        livestockSettings, agroCalculation,
    )

    /** Money, sales, payments, slaughter and herd movements: the server never resolves them by last-writer-wins. */
    val CAREFUL: Set<String> = setOf(coalSale, driverPayment, coalWorkDay, livestockOperation, slaughterRecord)

    /** One row per store (their ids are the constants of `StableId`). */
    val SINGLETONS: Set<String> = setOf(farmSettings, coalSettings, livestockSettings)
}

/**
 * A moment on the wire: ISO 8601 in UTC with milliseconds and `Z` (as JavaScript's `toISOString()`) when written;
 * with or without the fraction when read, rounded to the millisecond so a date read back and written again gives
 * the same string.
 */
object InstantSerializer : KSerializer<Instant> {
    const val NAME = "Instant"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(Iso.format(value))

    override fun deserialize(decoder: Decoder): Instant =
        parse(decoder.decodeString()) ?: throw IllegalArgumentException("not a date")

    fun parse(s: String): Instant? {
        val i = Iso.parse(s) ?: return null
        return Instant.ofEpochMilli(Math.round(i.epochSecond * 1000.0 + i.nano / 1_000_000.0))
    }
}

/** The model's own default for a moment it has no value for (the iPhone uses "now"). */
internal fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
