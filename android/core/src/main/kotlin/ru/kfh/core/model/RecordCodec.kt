package ru.kfh.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.reflect.KClass

/**
 * The phone's records <-> the server's `data` of `{entity, id, version, data, deleted}`. `data` has exactly the field
 * names and value shapes the site writes (`web/src/pages/<page>.tsx`), the same rules as `TabelKFH/Sync/SyncCodec.swift`:
 * dates are ISO 8601 in UTC with milliseconds and `Z`; an empty optional is `null` (sent, so clearing reaches the
 * server); money, mass and heads are integers; the record id is not a field.
 *
 * Reading never fails on a bad field: a missing field, one of the wrong type, or `null` for a non-optional property
 * gives the property's default (the iPhone model's default). Unknown fields are ignored. A `hoursMark` with
 * `hours: null` is a removed mark: see [isRemovedHoursMark].
 */
object RecordCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private class Spec<T : Any>(
        val entity: String,
        val kClass: KClass<T>,
        val serializer: KSerializer<T>,
        val withId: (T, String) -> T,
    )

    private inline fun <reified T : Any> spec(entity: String, serializer: KSerializer<T>, noinline withId: (T, String) -> T) =
        Spec(entity, T::class, serializer, withId)

    private val specs: Map<String, Spec<*>> = listOf(
        spec(Entities.employee, Employee.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.hoursMark, HoursMark.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.attendanceRecord, AttendanceRecord.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.timesheetSnapshot, TimesheetSnapshot.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.farmSettings, FarmSettings.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.coalSettings, CoalSettings.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.deliveryLocation, DeliveryLocation.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.driver, Driver.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.coalSale, CoalSale.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.driverPayment, DriverPayment.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.coalWorkDay, CoalWorkDay.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.livestockCategory, LivestockCategory.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.livestockOperation, LivestockOperation.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.slaughterRecord, SlaughterRecord.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.livestockSettings, LivestockSettings.serializer()) { r, id -> r.copy(id = id) },
        spec(Entities.agroCalculation, AgroCalculation.serializer()) { r, id -> r.copy(id = id) },
    ).associateBy { it.entity }

    /** The record of an entity from the `data` the server holds; [id] becomes the record's `id`. */
    @Suppress("UNCHECKED_CAST")
    fun <T> decode(entity: String, id: String, data: JsonObject): T {
        val spec = specs[entity] as Spec<Any>? ?: throw IllegalArgumentException("unknown entity $entity")
        val clean = sanitize(spec.serializer.descriptor, data, entity)
        val record = json.decodeFromJsonElement(spec.serializer, clean)
        return spec.withId(record, id) as T
    }

    /** The record's `data`: every field, `null` for an empty optional. */
    @Suppress("UNCHECKED_CAST")
    fun encode(record: Any): JsonObject {
        val spec = specs.values.firstOrNull { it.kClass.isInstance(record) } as Spec<Any>?
            ?: throw IllegalArgumentException("not a record: ${record::class}")
        return json.encodeToJsonElement(spec.serializer, record).jsonObject
    }

    /**
     * What changed from [old] to [new]: only the changed fields, `null` for a cleared optional. A missing field
     * counts as `null` (as the site's `engine.save`), and a whole number equals the same double (8 and 8.0).
     * Without [old] everything is new.
     */
    fun diff(old: JsonObject?, new: JsonObject): JsonObject {
        if (old == null) return new
        val changed = LinkedHashMap<String, JsonElement>()
        for ((k, v) in new) {
            if (!same(old[k] ?: JsonNull, v)) changed[k] = v
        }
        return JsonObject(changed)
    }

    /** A `hoursMark` record of a removed mark: the server keeps it with `hours: null`. */
    fun isRemovedHoursMark(data: JsonObject): Boolean = data["hours"] is JsonNull

    /** The `data` that removes a mark: its fields with `hours: null`. */
    fun removedHoursMark(mark: HoursMark): JsonObject = JsonObject(encode(mark) + ("hours" to JsonNull))

    // MARK: reading

    private fun sanitize(d: SerialDescriptor, data: JsonObject, entity: String): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            val v = data[name] ?: continue
            val ok = clean(d.getElementDescriptor(i), v) ?: continue
            out[name] = ok
        }
        if (entity == Entities.timesheetSnapshot) {
            for (k in listOf("documentData", "employeeData")) {
                val s = out[k] as? JsonPrimitive ?: continue
                if (s !is JsonNull && !isBase64(s.content)) out.remove(k)
            }
        }
        if (entity == Entities.coalSale && "totalAmount" !in out && ("coalAmount" in out || "deliveryPrice" in out)) {
            fun amount(k: String) = (out[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull() ?: 0L
            out["totalAmount"] = JsonPrimitive(amount("coalAmount") + amount("deliveryPrice"))
        }
        return JsonObject(out)
    }

    /** The value as the property reads it, or null when it is of the wrong type (the default stays). */
    private fun clean(d: SerialDescriptor, v: JsonElement): JsonElement? {
        if (v is JsonNull) return v
        return when (d.kind) {
            PrimitiveKind.STRING -> {
                val p = v as? JsonPrimitive ?: return null
                if (!p.isString) return null
                if (d.serialName.removeSuffix("?") == InstantSerializer.NAME && InstantSerializer.parse(p.content) == null) null else p
            }
            PrimitiveKind.INT, PrimitiveKind.LONG -> {
                val p = v as? JsonPrimitive ?: return null
                if (p.isString) return null
                val n = p.content.toLongOrNull()
                    ?: p.content.toDoubleOrNull()?.takeIf { it.isFinite() && Math.abs(it) < 9e15 }?.let { Math.round(it) }
                    ?: return null
                if (d.kind == PrimitiveKind.INT && n !in Int.MIN_VALUE..Int.MAX_VALUE) null else JsonPrimitive(n)
            }
            PrimitiveKind.DOUBLE -> {
                val p = v as? JsonPrimitive ?: return null
                if (p.isString || p.booleanOrNull != null || p.content.toDoubleOrNull() == null) null else p
            }
            PrimitiveKind.BOOLEAN -> {
                val p = v as? JsonPrimitive ?: return null
                if (p.isString || p.booleanOrNull == null) null else p
            }
            StructureKind.MAP -> {
                // inputs: string values, numbers as their text, the rest dropped
                val o = v as? JsonObject ?: return null
                val m = LinkedHashMap<String, JsonElement>()
                for ((k, x) in o) {
                    val p = x as? JsonPrimitive ?: continue
                    if (p is JsonNull || (!p.isString && p.booleanOrNull != null)) continue
                    m[k] = JsonPrimitive(p.content)
                }
                JsonObject(m)
            }
            StructureKind.LIST -> {
                val a = v as? JsonArray ?: return null
                val element = d.getElementDescriptor(0)
                JsonArray(a.mapNotNull { (it as? JsonObject)?.let { o -> sanitize(element, o, "") } })
            }
            else -> v
        }
    }

    private fun isBase64(s: String): Boolean = try {
        Base64.getDecoder().decode(s)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    // MARK: comparing

    private fun isNumber(e: JsonElement) = e is JsonPrimitive && e !is JsonNull && !e.isString && e.booleanOrNull == null

    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        isNumber(a) && isNumber(b) -> {
            val x = (a as JsonPrimitive).content.toBigDecimalOrNull()
            val y = (b as JsonPrimitive).content.toBigDecimalOrNull()
            if (x != null && y != null) x.compareTo(y) == 0 else a == b
        }
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (k, v) -> same(v, b.getValue(k)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }
}
