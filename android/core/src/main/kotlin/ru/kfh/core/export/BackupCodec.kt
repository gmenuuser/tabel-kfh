package ru.kfh.core.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.model.Employee
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.model.TimesheetSnapshot
import ru.kfh.core.timesheet.FarmInfo
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.Locale

/** Everything on the phone that goes into «Сохранить копию в файл». Ids are the records' (lowercase is fine). */
data class BackupRecords(
    val settings: FarmInfo,
    val employees: List<Employee>,
    /** Employee id (lowercase) → JPEG bytes. */
    val photos: Map<String, ByteArray> = emptyMap(),
    val attendance: List<AttendanceRecord>,
    val snapshots: List<TimesheetSnapshot>,
    val hours: List<HoursMark>,
    val coalSettings: CoalSettings?,
    val locations: List<DeliveryLocation>,
    val drivers: List<Driver>,
    val sales: List<CoalSale>,
    val payments: List<DriverPayment>,
    val days: List<CoalWorkDay>,
    val livestockSettings: LivestockSettings?,
    val categories: List<LivestockCategory>,
    val operations: List<LivestockOperation>,
    val slaughters: List<SlaughterRecord>,
    val agro: List<AgroCalculation>,
)

/** The header of the file and the farm's zone (day keys of the copy are compared in it). */
data class BackupMeta(val createdAt: Instant, val appVersion: String, val timeZone: ZoneId)

/**
 * The backup file of `TabelKFH/Domain/BackupCodec.swift` (schema 5), written so that the iPhone's `decodeFile`
 * accepts it: `format`, `schemaVersion`, `createdAt`, `appVersion`, `payloadSHA256` and the `payload` of
 * `BackupPayload` (settings, employees, records, snapshots, timeZone, coal, livestock, agro, hours).
 *
 * The iPhone checks the checksum by encoding the decoded payload again with Swift's `JSONEncoder` (`.sortedKeys`),
 * so [canonical] writes exactly that text: keys in byte order, no whitespace, `/` escaped as `\/`, dates as seconds
 * since 2001-01-01 (`Date`'s own encoding, shortest decimal), whole doubles without `.0`, `Data` as base64, `UUID`
 * upper case, and no key at all for an empty optional (`encodeIfPresent`). The file itself is pretty-printed as
 * Swift's `.prettyPrinted` (`"key" : value`, two spaces). There is no restore from a file (the owner restores a
 * server copy instead).
 */
object BackupCodec {
    const val FORMAT = "tabel-kfh-backup"

    /** 2 added the coal module, 3 the livestock module, 4 the agronomic calculations, 5 the timesheet of hours. */
    const val SCHEMA_VERSION = 5

    /** Seconds between 1970-01-01 (epoch) and 2001-01-01 (Swift's reference date). */
    private const val REFERENCE_EPOCH_MILLIS = 978_307_200_000L

    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    /** The whole file as UTF-8 (pretty-printed, keys sorted). */
    fun encode(records: BackupRecords, meta: BackupMeta): ByteArray {
        val payload = payload(records, meta)
        val file = obj(
            "format" to str(FORMAT),
            "schemaVersion" to num(SCHEMA_VERSION.toString()),
            "createdAt" to num(seconds(meta.createdAt)),
            "appVersion" to str(meta.appVersion),
            "payloadSHA256" to str(checksum(payload)),
            "payload" to payload,
        )
        return pretty(file).toByteArray(Charsets.UTF_8)
    }

    /** SHA-256 (lowercase hex) of the payload's canonical text — what the iPhone recomputes. */
    fun checksum(payload: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    /** «Табель-КФХ-копия-2026-11-06.json» (`BackupService.fileName`). */
    fun fileName(day: LocalDate): String =
        String.format(Locale.ROOT, "Табель-КФХ-копия-%04d-%02d-%02d.json", day.year, day.monthValue, day.dayOfMonth)

    // MARK: Payload

    /**
     * `BackupPayload` as a JSON tree (numbers already in Swift's spelling). As `BackupService.payload()`: lists by
     * id, records and hours only of listed employees, one attendance record per employee and day (the latest edit).
     */
    fun payload(r: BackupRecords, meta: BackupMeta): JsonObject {
        val employees = r.employees.sortedBy { it.id.lowercase(Locale.ROOT) }
        val ids = employees.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) }
        val latest = LinkedHashMap<String, AttendanceRecord>()
        for (a in r.attendance) {
            if (a.employeeId.lowercase(Locale.ROOT) !in ids) continue
            val key = a.employeeId.lowercase(Locale.ROOT) + ":" + a.day.atZone(meta.timeZone).toLocalDate()
            val kept = latest[key]
            if (kept != null && !kept.updatedAt.isBefore(a.updatedAt)) continue
            latest[key] = a
        }
        val records = latest.values.sortedBy { it.id.lowercase(Locale.ROOT) }
        val hours = r.hours.filter { it.employeeId.lowercase(Locale.ROOT) in ids }.sortedBy { it.id.lowercase(Locale.ROOT) }
        return obj(
            "settings" to obj(
                "farmName" to str(r.settings.farmName),
                "managerName" to str(r.settings.managerName),
                "responsiblePerson" to str(r.settings.responsiblePerson),
                "address" to str(r.settings.address),
                "phone" to str(r.settings.phone),
                "extraInfo" to str(r.settings.extraInfo),
            ),
            "employees" to JsonArray(employees.map { employee(it, r.photos[it.id.lowercase(Locale.ROOT)]) }),
            "records" to JsonArray(records.map(::record)),
            "snapshots" to JsonArray(r.snapshots.sortedBy { it.id.lowercase(Locale.ROOT) }.map(::snapshot)),
            "timeZone" to str(meta.timeZone.id),
            "coal" to coal(r),
            "livestock" to livestock(r),
            "agro" to JsonArray(r.agro.sortedBy { it.id.lowercase(Locale.ROOT) }.map(::agro)),
            "hours" to JsonArray(hours.map(::hoursMark)),
        )
    }

    private fun employee(e: Employee, photo: ByteArray?) = obj(
        "id" to uuid(e.id),
        "lastName" to str(e.lastName),
        "firstName" to str(e.firstName),
        "patronymic" to str(e.patronymic),
        "position" to str(e.position),
        "hireDate" to date(e.hireDate),
        "dismissalDate" to e.dismissalDate?.let(::date),
        "isArchived" to bool(e.isArchived),
        "workSchedule" to str(e.workSchedule),
        "standardWorkHours" to num(double(e.standardWorkHours)),
        "defaultBreakMinutes" to num(e.defaultBreakMinutes.toString()),
        "photo" to photo?.let { str(Base64.getEncoder().encodeToString(it)) },
    )

    private fun record(a: AttendanceRecord) = obj(
        "id" to uuid(a.id),
        "employeeId" to uuid(a.employeeId),
        "day" to date(a.day),
        "arrivalTime" to a.arrivalTime?.let(::date),
        "departureTime" to a.departureTime?.let(::date),
        "breakMinutes" to num(a.breakMinutes.toString()),
        "workedMinutes" to a.workedMinutes?.let { num(it.toString()) },
        "manualWorkedMinutes" to a.manualWorkedMinutes?.let { num(it.toString()) },
        "status" to str(a.status),
        "comment" to str(a.comment),
        "createdAt" to date(a.createdAt),
        "updatedAt" to date(a.updatedAt),
    )

    private fun snapshot(s: TimesheetSnapshot) = obj(
        "id" to uuid(s.id),
        "month" to num(s.month.toString()),
        "year" to num(s.year.toString()),
        "version" to num(s.version.toString()),
        "employeeId" to s.employeeId?.let(::uuid),
        "createdAt" to date(s.createdAt),
        "documentData" to str(base64(s.documentData)),
        "employeeData" to str(base64(s.employeeData)),
    )

    private fun hoursMark(h: HoursMark) = obj(
        "id" to uuid(h.id),
        "employeeId" to uuid(h.employeeId),
        "day" to date(h.day),
        "hours" to num(h.hours.toString()),
        "createdAt" to date(h.createdAt),
        "updatedAt" to date(h.updatedAt),
    )

    private fun coal(r: BackupRecords) = obj(
        "settings" to r.coalSettings?.let {
            obj(
                "pricePerTonKopecks" to num(it.pricePerTonKopecks.toString()),
                "createdAt" to date(it.createdAt),
                "updatedAt" to date(it.updatedAt),
            )
        },
        "locations" to JsonArray(r.locations.sortedBy { it.id.lowercase(Locale.ROOT) }.map { l ->
            obj(
                "id" to uuid(l.id),
                "name" to str(l.name),
                "deliveryPriceKopecks" to num(l.deliveryPriceKopecks.toString()),
                "driverPaymentKopecks" to l.driverPaymentKopecks?.let { num(it.toString()) },
                "isActive" to bool(l.isActive),
                "createdAt" to date(l.createdAt),
            )
        }),
        "drivers" to JsonArray(r.drivers.sortedBy { it.id.lowercase(Locale.ROOT) }.map { d ->
            obj(
                "id" to uuid(d.id),
                "lastName" to str(d.lastName),
                "firstName" to str(d.firstName),
                "middleName" to str(d.middleName),
                "phone" to str(d.phone),
                "comment" to str(d.comment),
                "isArchived" to bool(d.isArchived),
                "createdAt" to date(d.createdAt),
            )
        }),
        "sales" to JsonArray(r.sales.sortedBy { it.id.lowercase(Locale.ROOT) }.map { s ->
            obj(
                "id" to uuid(s.id),
                "saleDate" to date(s.saleDate),
                "kilograms" to num(s.kilograms.toString()),
                "locationNameSnapshot" to str(s.locationNameSnapshot),
                "coalPricePerTonSnapshot" to num(s.coalPricePerTonSnapshot.toString()),
                "deliveryPriceSnapshot" to num(s.deliveryPriceSnapshot.toString()),
                "coalAmount" to num(s.coalAmount.toString()),
                "deliveryPrice" to num(s.deliveryPrice.toString()),
                "totalAmount" to num(s.totalAmount.toString()),
                "vehicleType" to str(s.vehicleType),
                "driverId" to s.driverId?.let(::uuid),
                "driverNameSnapshot" to str(s.driverNameSnapshot),
                "driverPaymentAmount" to num(s.driverPaymentAmount.toString()),
                "comment" to str(s.comment),
                "createdAt" to date(s.createdAt),
                "updatedAt" to date(s.updatedAt),
            )
        }),
        "payments" to JsonArray(r.payments.sortedBy { it.id.lowercase(Locale.ROOT) }.map { p ->
            obj(
                "id" to uuid(p.id),
                "driverId" to uuid(p.driverId),
                "driverNameSnapshot" to str(p.driverNameSnapshot),
                "paymentDate" to date(p.paymentDate),
                "amount" to num(p.amount.toString()),
                "comment" to str(p.comment),
                "createdAt" to date(p.createdAt),
            )
        }),
        "days" to JsonArray(r.days.sortedBy { it.id.lowercase(Locale.ROOT) }.map { d ->
            obj(
                "id" to uuid(d.id),
                "date" to date(d.date),
                "isClosed" to bool(d.isClosed),
                "closedAt" to d.closedAt?.let(::date),
                "totalRevenue" to num(d.totalRevenue.toString()),
                "totalKilograms" to num(d.totalKilograms.toString()),
                "totalDriverPayments" to num(d.totalDriverPayments.toString()),
                "ownVehicleDeliveryRevenue" to num(d.ownVehicleDeliveryRevenue.toString()),
                "createdAt" to date(d.createdAt),
            )
        }),
    )

    private fun livestock(r: BackupRecords) = obj(
        "defaultsCreated" to bool(r.livestockSettings != null),
        "categories" to JsonArray(r.categories.sortedBy { it.id.lowercase(Locale.ROOT) }.map { c ->
            obj(
                "id" to uuid(c.id),
                "species" to str(c.species),
                "name" to str(c.name),
                "sortOrder" to num(c.sortOrder.toString()),
                "isBuiltIn" to bool(c.isBuiltIn),
                "createdAt" to date(c.createdAt),
            )
        }),
        "operations" to JsonArray(r.operations.sortedBy { it.id.lowercase(Locale.ROOT) }.map { o ->
            obj(
                "id" to uuid(o.id),
                "kind" to str(o.kind),
                "date" to date(o.date),
                "categoryId" to uuid(o.categoryId),
                "targetCategoryId" to o.targetCategoryId?.let(::uuid),
                "heads" to num(o.heads.toString()),
                "comment" to str(o.comment),
                "createdAt" to date(o.createdAt),
                "updatedAt" to date(o.updatedAt),
            )
        }),
        "slaughters" to JsonArray(r.slaughters.sortedBy { it.id.lowercase(Locale.ROOT) }.map { s ->
            obj(
                "id" to uuid(s.id),
                "operationId" to uuid(s.operationId),
                "reason" to str(s.reason),
                "liveWeightGrams" to s.liveWeightGrams?.let { num(it.toString()) },
                "carcassWeightGrams" to s.carcassWeightGrams?.let { num(it.toString()) },
                "meatWeightGrams" to s.meatWeightGrams?.let { num(it.toString()) },
                "createdAt" to date(s.createdAt),
            )
        }),
    )

    private fun agro(c: AgroCalculation) = obj(
        "id" to uuid(c.id),
        "kind" to str(c.kind),
        "title" to str(c.title),
        "inputs" to JsonObject(c.inputs.mapValues { str(it.value) }),
        "lines" to JsonArray(c.lines.map { obj("title" to str(it.title), "text" to str(it.text), "main" to bool(it.main)) }),
        "createdAt" to date(c.createdAt),
        "updatedAt" to date(c.updatedAt),
    )

    // MARK: Values as Swift writes them

    /** An object without the keys of empty optionals (`encodeIfPresent`). */
    private fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject =
        JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

    private fun str(s: String) = JsonPrimitive(s)

    private fun bool(b: Boolean) = JsonPrimitive(b)

    /** A number in its final spelling (never re-formatted by the writer). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun num(text: String): JsonPrimitive = JsonUnquotedLiteral(text)

    private fun date(i: Instant) = num(seconds(i))

    /** `UUID.uuidString`: upper case. Anything else cannot be read by the iPhone. */
    private fun uuid(id: String): JsonPrimitive {
        require(UUID_RE.matches(id)) { "не UUID: $id" }
        return str(id.uppercase(Locale.ROOT))
    }

    /** The stored base64 (the site's and the iPhone's) as `Data.base64EncodedString()` writes it again. */
    private fun base64(s: String): String = try {
        Base64.getEncoder().encodeToString(Base64.getDecoder().decode(s))
    } catch (_: IllegalArgumentException) {
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    }

    /**
     * `Date` as `timeIntervalSinceReferenceDate`: seconds since 2001-01-01 with the milliseconds as the shortest
     * decimal fraction («815627045.123», «815627045.1», «815627045», «-0.5»).
     */
    fun seconds(i: Instant): String {
        val millis = i.toEpochMilli() - REFERENCE_EPOCH_MILLIS
        val negative = millis < 0
        val abs = Math.abs(millis)
        val whole = abs / 1000
        val frac = (abs % 1000).toInt()
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(whole)
        if (frac != 0) {
            sb.append('.')
            sb.append(String.format(Locale.ROOT, "%03d", frac).trimEnd('0'))
        }
        return sb.toString()
    }

    /** A `Double` as Swift's encoder writes it: shortest round-trip digits, a whole value without `.0`. */
    fun double(d: Double): String {
        require(d.isFinite()) { "не число: $d" }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    }

    // MARK: Writing

    /** Swift's `.sortedKeys` without `.prettyPrinted`: the text the checksum is over. */
    fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it, indent = null, depth = 0) }.toString()

    /** Swift's `[.sortedKeys, .prettyPrinted]`. */
    private fun pretty(e: JsonElement): String = StringBuilder().also { write(e, it, indent = "  ", depth = 0) }.toString()

    private fun write(e: JsonElement, out: StringBuilder, indent: String?, depth: Int) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) escape(e.content, out) else out.append(e.content)
            is JsonArray -> {
                if (e.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                e.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    newline(out, indent, depth + 1)
                    write(item, out, indent, depth + 1)
                }
                newline(out, indent, depth)
                out.append(']')
            }
            is JsonObject -> {
                if (e.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                // Byte order of the keys: what Swift's `sortedKeys` gives for these ASCII names.
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    newline(out, indent, depth + 1)
                    escape(k, out)
                    out.append(if (indent == null) ":" else " : ")
                    write(v, out, indent, depth + 1)
                }
                newline(out, indent, depth)
                out.append('}')
            }
        }
    }

    private fun newline(out: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(depth) { out.append(indent) }
    }

    /** `JSONEncoder`'s escaping: `"`, `\`, `/`, the named controls, the rest as `\u00XX`; UTF-8 otherwise. */
    private fun escape(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '/' -> out.append("\\/")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append(String.format(Locale.ROOT, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }
}
