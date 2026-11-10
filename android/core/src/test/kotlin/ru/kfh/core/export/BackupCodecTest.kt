package ru.kfh.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AgroLine
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.Employee
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.TimesheetSnapshot
import ru.kfh.core.timesheet.FarmInfo
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The file «Сохранить копию в файл» writes is the iPhone's (`TabelKFH/Domain/BackupCodec.swift`, schema 5): the
 * same header, the same payload keys, and a checksum the iPhone's `decodeFile` recomputes over the payload encoded
 * by Swift's `JSONEncoder` with sorted keys — so the canonical text here has to be what Swift would produce.
 */
class BackupCodecTest {
    private val zone: ZoneId = ZoneId.of("Asia/Chita")
    private val created: Instant = Instant.parse("2026-11-06T03:04:05.123Z")

    private fun day(d: Int, m: Int = 11, y: Int = 2026): Instant = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant()

    private val archivedId = "6f1d2b3c-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val activeId = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01"

    /** The Swift test's payload: an archived employee with a photo, an active one, records of both, one snapshot. */
    private fun records(): BackupRecords {
        val archived = Employee(
            id = archivedId, lastName = "Старов", firstName = "Пётр", patronymic = "Ильич", position = "Сторож",
            hireDate = day(1, 1, 2020), dismissalDate = day(31, 10), isArchived = true, workSchedule = "twoTwo",
            standardWorkHours = 12.0, defaultBreakMinutes = 30,
        )
        val active = Employee(
            id = activeId, lastName = "Зубахин", firstName = "Иван", patronymic = "Петрович", position = "Тракторист",
            hireDate = day(1, 3, 2025), dismissalDate = null, isArchived = false, workSchedule = "fiveTwo",
            standardWorkHours = 7.5, defaultBreakMinutes = 60,
        )
        val arrival = day(5).plusSeconds(8 * 3600)
        val attendance = listOf(
            AttendanceRecord(
                id = "a0000000-0000-4000-8000-000000000001", employeeId = active.id, day = day(5), arrivalTime = arrival,
                departureTime = arrival.plusSeconds(9 * 3600), breakMinutes = 60, workedMinutes = 480,
                manualWorkedMinutes = 470, status = "Я", comment = "Пахота «у реки»\nвторая строка",
                createdAt = created, updatedAt = created.plusMillis(60_500),
            ),
            AttendanceRecord(
                id = "a0000000-0000-4000-8000-000000000002", employeeId = active.id, day = day(6),
                arrivalTime = arrival.plusSeconds(86_400), departureTime = null, breakMinutes = 0, workedMinutes = null,
                manualWorkedMinutes = null, status = "Я", comment = "", createdAt = created, updatedAt = created,
            ),
            AttendanceRecord(
                id = "a0000000-0000-4000-8000-000000000003", employeeId = archived.id, day = day(3, 10),
                arrivalTime = null, departureTime = null, breakMinutes = 0, workedMinutes = null,
                manualWorkedMinutes = null, status = "ОТ", comment = "", createdAt = created, updatedAt = created,
            ),
        )
        val snapshot = TimesheetSnapshot(
            id = "b0000000-0000-4000-8000-000000000001", month = 10, year = 2026, version = 2, employeeId = null,
            createdAt = created, documentData = "eyJhIjoxfQ==", employeeData = "W10=",
        )
        val settings = FarmInfo(
            farmName = "КФХ Зубахин", managerName = "Зубахин И. П.", responsiblePerson = "Иванова А. А.",
            address = "х. Зубахин", phone = "+7 928 123-45-67", extraInfo = "ИНН 123",
        )
        return BackupRecords(
            settings = settings,
            employees = listOf(archived, active),
            photos = mapOf(archivedId to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2, 0xFF.toByte())),
            attendance = attendance,
            snapshots = listOf(snapshot),
            hours = listOf(
                HoursMark(id = "c0000000-0000-4000-8000-000000000001", employeeId = active.id, day = day(5), hours = 8,
                          createdAt = created, updatedAt = created),
            ),
            coalSettings = CoalSettings(pricePerTonKopecks = 650_000, createdAt = created, updatedAt = created),
            locations = listOf(
                DeliveryLocation(id = "d0000000-0000-4000-8000-000000000001", name = "Новоцурухайтуй", deliveryPriceKopecks = 100_000,
                                 driverPaymentKopecks = null, isActive = true, createdAt = created),
            ),
            drivers = listOf(
                Driver(id = "e0000000-0000-4000-8000-000000000001", lastName = "Мунгалов", firstName = "Сергей", middleName = "Иванович",
                       phone = "", comment = "", isArchived = false, createdAt = created),
            ),
            sales = listOf(
                CoalSale(
                    id = "f0000000-0000-4000-8000-000000000001", saleDate = created, kilograms = 2000,
                    locationNameSnapshot = "Новоцурухайтуй", coalPricePerTonSnapshot = 650_000, deliveryPriceSnapshot = 100_000,
                    coalAmount = 1_300_000, deliveryPrice = 100_000, totalAmount = 1_400_000, vehicleType = "hired",
                    driverId = "e0000000-0000-4000-8000-000000000001", driverNameSnapshot = "Мунгалов С. И.",
                    driverPaymentAmount = 100_000, comment = "а/б", createdAt = created, updatedAt = created,
                ),
            ),
            payments = emptyList(),
            days = emptyList(),
            livestockSettings = LivestockSettings(createdAt = created),
            categories = listOf(
                LivestockCategory(id = "a1000000-0000-4000-8000-000000000001", species = "cattle", name = "Коровы", sortOrder = 1,
                                  isBuiltIn = true, createdAt = created),
            ),
            operations = emptyList(),
            slaughters = emptyList(),
            agro = listOf(
                AgroCalculation(
                    id = "a2000000-0000-4000-8000-000000000001", kind = "seeding", title = "Поле у реки",
                    inputs = mapOf("rate" to "200", "area" to "12,5"), lines = listOf(AgroLine("Семян", "2 500 кг", true)),
                    createdAt = created, updatedAt = created,
                ),
            ),
        )
    }

    private fun meta() = BackupMeta(createdAt = created, appVersion = "1.0", timeZone = zone)

    private fun file(): JsonObject = Json.parseToJsonElement(String(BackupCodec.encode(records(), meta()), Charsets.UTF_8)).jsonObject

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // The keys and the schema version of `BackupCodecTests.testRoundTripKeepsEverything`.
    @Test
    fun sameShapeAsIphone() {
        val f = file()
        assertEquals(setOf("format", "schemaVersion", "createdAt", "appVersion", "payloadSHA256", "payload"), f.keys)
        assertEquals("tabel-kfh-backup", f["format"]!!.jsonPrimitive.content)
        assertEquals("5", f["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("1.0", f["appVersion"]!!.jsonPrimitive.content)
        assertEquals(64, f["payloadSHA256"]!!.jsonPrimitive.content.length)
        // Dates are Swift `Date`s: seconds since 2001-01-01 as a number.
        assertEquals("815627045.123", f["createdAt"]!!.jsonPrimitive.content)

        val p = f["payload"]!!.jsonObject
        assertEquals(
            setOf("settings", "employees", "records", "snapshots", "timeZone", "coal", "livestock", "agro", "hours"),
            p.keys,
        )
        assertEquals("Asia/Chita", p["timeZone"]!!.jsonPrimitive.content)
        assertEquals(setOf("farmName", "managerName", "responsiblePerson", "address", "phone", "extraInfo"), p["settings"]!!.jsonObject.keys)
        assertEquals(setOf("settings", "locations", "drivers", "sales", "payments", "days"), p["coal"]!!.jsonObject.keys)
        assertEquals(setOf("defaultsCreated", "categories", "operations", "slaughters"), p["livestock"]!!.jsonObject.keys)
        assertEquals(2, p["employees"]!!.jsonArray.size)
        assertEquals(3, p["records"]!!.jsonArray.size)
        assertEquals(1, p["snapshots"]!!.jsonArray.size)
        assertEquals(1, p["hours"]!!.jsonArray.size)
        assertEquals(1, p["agro"]!!.jsonArray.size)
    }

    @Test
    fun employeeFieldsAsTheIphoneStoresThem() {
        val p = file()["payload"]!!.jsonObject
        val employees = p["employees"]!!.jsonArray.map { it.jsonObject }
        val archived = employees.first { it["lastName"]!!.jsonPrimitive.content == "Старов" }
        val active = employees.first { it["lastName"]!!.jsonPrimitive.content == "Зубахин" }
        assertEquals(
            setOf("id", "lastName", "firstName", "patronymic", "position", "hireDate", "dismissalDate", "isArchived",
                  "workSchedule", "standardWorkHours", "defaultBreakMinutes", "photo"),
            archived.keys,
        )
        // Swift's `encodeIfPresent`: an empty optional is not in the file.
        assertNull(active["dismissalDate"])
        assertNull(active["photo"])
        assertEquals("iVBORwABAv8=", archived["photo"]!!.jsonPrimitive.content)
        assertEquals("twoTwo", archived["workSchedule"]!!.jsonPrimitive.content)
        assertEquals("12", archived["standardWorkHours"]!!.jsonPrimitive.content)
        assertEquals("7.5", active["standardWorkHours"]!!.jsonPrimitive.content)
        // `UUID.uuidString` is upper case.
        assertEquals("0B6F3A52-7C1E-4D7A-9A51-3F2C8E7D1A01", active["id"]!!.jsonPrimitive.content)
        // Midnight of 1 March 2025 at the farm (UTC+9) as reference seconds, no fraction.
        assertEquals("762447600", active["hireDate"]!!.jsonPrimitive.content)

        val records = p["records"]!!.jsonArray.map { it.jsonObject }
        val timeOff = records.first { it["status"]!!.jsonPrimitive.content == "ОТ" }
        assertNull(timeOff["arrivalTime"])
        assertNull(timeOff["workedMinutes"])
        val first = records.first { it["manualWorkedMinutes"] != null }
        assertEquals("470", first["manualWorkedMinutes"]!!.jsonPrimitive.content)
        assertEquals("Пахота «у реки»\nвторая строка", first["comment"]!!.jsonPrimitive.content)
        assertEquals("815627105.623", first["updatedAt"]!!.jsonPrimitive.content)

        val snapshot = p["snapshots"]!!.jsonArray.single().jsonObject
        assertNull(snapshot["employeeId"])
        assertEquals("eyJhIjoxfQ==", snapshot["documentData"]!!.jsonPrimitive.content)

        val sale = p["coal"]!!.jsonObject["sales"]!!.jsonArray.single().jsonObject
        assertEquals("E0000000-0000-4000-8000-000000000001", sale["driverId"]!!.jsonPrimitive.content)
        assertEquals("hired", sale["vehicleType"]!!.jsonPrimitive.content)
        val location = p["coal"]!!.jsonObject["locations"]!!.jsonArray.single().jsonObject
        assertNull(location["driverPaymentKopecks"])
        assertEquals("true", p["livestock"]!!.jsonObject["defaultsCreated"]!!.jsonPrimitive.content)
        val agro = p["agro"]!!.jsonArray.single().jsonObject
        assertEquals("12,5", agro["inputs"]!!.jsonObject["area"]!!.jsonPrimitive.content)
        assertEquals("true", agro["lines"]!!.jsonArray.single().jsonObject["main"]!!.jsonPrimitive.content)
    }

    /** The checksum is SHA-256 of the payload as Swift's `JSONEncoder` with `.sortedKeys` writes it. */
    @Test
    fun checksumIsOverSwiftsCanonicalJson() {
        val f = file()
        val canonical = BackupCodec.canonical(BackupCodec.payload(records(), meta()))
        assertEquals(f["payloadSHA256"]!!.jsonPrimitive.content, sha256(canonical))
        // Compact, keys sorted, slashes escaped, integral doubles without `.0`, no empty optionals.
        assertTrue(canonical.startsWith("{\"agro\":[{\"createdAt\":815627045.123,\"id\":\"A2000000-0000-4000-8000-000000000001\",\"inputs\":{\"area\":\"12,5\",\"rate\":\"200\"},\"kind\":\"seeding\",\"lines\":[{\"main\":true,\"text\":\"2 500 кг\",\"title\":\"Семян\"}],\"title\":\"Поле у реки\",\"updatedAt\":815627045.123}],\"coal\":{"))
        assertTrue(canonical.contains("\"comment\":\"а\\/б\""))
        assertTrue(canonical.contains("\"comment\":\"Пахота «у реки»\\nвторая строка\""))
        assertTrue(canonical.contains("\"standardWorkHours\":12,"))
        assertTrue(canonical.contains("\"standardWorkHours\":7.5,"))
        assertTrue(canonical.contains("\"status\":\"ОТ\""))
        assertTrue(canonical.contains("\"workSchedule\":\"twoTwo\""))
        assertFalse(canonical.contains("\"dismissalDate\":null"))
        assertFalse(canonical.contains(" : "))
        assertFalse(canonical.contains(System.lineSeparator()))
        assertFalse(canonical.contains("null"))
        // The pretty file is written as Swift's `.prettyPrinted` (`BackupCodecTests.testChangedPayloadIsDamaged`).
        val text = String(BackupCodec.encode(records(), meta()), Charsets.UTF_8)
        assertTrue(text.contains("\"defaultBreakMinutes\" : 60"))
        assertTrue(text.contains("\"status\" : \"ОТ\""))
        assertTrue(text.contains("\"workSchedule\" : \"twoTwo\""))
        assertTrue(text.contains("Тракторист"))
    }

    @Test
    fun recordsOfUnlistedEmployeesAndRemovedMarksAreLeftOut() {
        val r = records()
        val stranger = "ffffffff-0000-4000-8000-000000000009"
        val with = r.copy(
            attendance = r.attendance + AttendanceRecord(id = "a0000000-0000-4000-8000-000000000009", employeeId = stranger, day = day(7)),
            hours = r.hours + HoursMark(id = "c0000000-0000-4000-8000-000000000009", employeeId = stranger, day = day(7), hours = 4),
        )
        val p = BackupCodec.payload(with, meta())
        assertEquals(3, p["records"]!!.jsonArray.size)
        assertEquals(1, p["hours"]!!.jsonArray.size)
    }

    @Test
    fun emptyModulesAreNullsTheIphoneRestoresAround() {
        val r = records().copy(coalSettings = null, livestockSettings = null, categories = emptyList())
        val p = BackupCodec.payload(r, meta())
        assertNull(p["coal"]!!.jsonObject["settings"])
        assertEquals("false", p["livestock"]!!.jsonObject["defaultsCreated"]!!.jsonPrimitive.content)
        assertEquals("[]", BackupCodec.canonical(p["livestock"]!!.jsonObject["categories"]!!))
    }

    @Test
    fun fileNameIsTheIphones() {
        assertEquals("Табель-КФХ-копия-2026-11-06.json", BackupCodec.fileName(LocalDate.of(2026, 11, 6)))
    }

    @Test
    fun swiftNumbers() {
        assertEquals("815627045.123", BackupCodec.seconds(created))
        assertEquals("815627045", BackupCodec.seconds(Instant.parse("2026-11-06T03:04:05Z")))
        assertEquals("815627045.1", BackupCodec.seconds(Instant.parse("2026-11-06T03:04:05.100Z")))
        assertEquals("-31622400", BackupCodec.seconds(Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals("-0.5", BackupCodec.seconds(Instant.parse("2000-12-31T23:59:59.500Z")))
        assertEquals("8", BackupCodec.double(8.0))
        assertEquals("7.5", BackupCodec.double(7.5))
        assertEquals("0.25", BackupCodec.double(0.25))
        assertEquals("11.75", BackupCodec.double(11.75))
    }
}
