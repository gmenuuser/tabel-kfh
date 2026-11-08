package ru.kfh.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import ru.kfh.core.ids.StableId
import ru.kfh.core.time.Iso
import java.time.Instant
import org.junit.Test

/**
 * Port of TabelKFHTests/SyncCodecTests.swift (the wire format). Skipped as SwiftData mechanics: the in-memory
 * `insert`/`find` of rows (`roundTrip` goes record -> JSON -> record instead), `testAttendanceRecordLinksEmployee`
 * (the `employee` relationship), and the one-row-per-store insert of `testSingletonIds` (only its ids are ported).
 */
class RecordCodecTest {
    private val t1 = Instant.parse("2026-11-06T15:00:00.123Z")
    private val t2 = Instant.parse("2026-11-07T01:30:45.678Z")
    private val day = Instant.parse("2026-11-06T15:00:00.000Z")
    private val uuidA = "6f1c2a3e-0000-4000-8000-00000000000a"
    private val uuidB = "6f1c2a3e-0000-4000-8000-00000000000b"

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** Record -> JSON -> record -> JSON: same record, same fields. */
    private inline fun <reified T : Any> roundTrip(entity: String, id: String, record: T): T {
        val fields = RecordCodec.encode(record)
        assertFalse(fields.isEmpty())
        val back = RecordCodec.decode<T>(entity, id, fields)
        assertEquals(record, back)
        assertEquals(fields, RecordCodec.encode(back))
        return back
    }

    private fun assertKeys(record: Any, vararg keys: String) {
        val missing = keys.toSet() - RecordCodec.encode(record).keys
        assertTrue("lacks ${missing.sorted()}", missing.isEmpty())
    }

    // MARK: samples

    private fun employee() = Employee(
        id = uuidA, lastName = "Зубахин", firstName = "Иван", patronymic = "Петрович", position = "Тракторист",
        hireDate = day, dismissalDate = t2, isArchived = true, workSchedule = "twoTwo", standardWorkHours = 7.5,
        defaultBreakMinutes = 45,
    )

    private fun sale() = CoalSale(
        id = "s1", saleDate = t1, kilograms = 1500, locationNameSnapshot = "Приаргунск",
        coalPricePerTonSnapshot = 650_000, deliveryPriceSnapshot = 120_000, coalAmount = 975_000,
        deliveryPrice = 120_000, totalAmount = 1_095_000, vehicleType = "hired", driverId = uuidB,
        driverNameSnapshot = "Мунгалов Сергей", driverPaymentAmount = 100_000, comment = "до обеда",
        createdAt = t1, updatedAt = t2,
    )

    private fun agro() = AgroCalculation(
        id = "a1", kind = "seeding", title = "Поле у реки", inputs = mapOf("area" to "5", "rate" to "240"),
        lines = listOf(AgroLine("Нужно семян", "1 200 кг", true), AgroLine("В центнерах", "12 ц")),
        createdAt = t1, updatedAt = t2,
    )

    // MARK: round trips, one per entity

    @Test
    fun entityList() {
        assertEquals(
            setOf(
                "employee", "hoursMark", "attendanceRecord", "timesheetSnapshot", "farmSettings", "coalSettings",
                "deliveryLocation", "driver", "coalSale", "driverPayment", "coalWorkDay", "livestockCategory",
                "livestockOperation", "slaughterRecord", "livestockSettings", "agroCalculation",
            ),
            Entities.ALL,
        )
        assertEquals(
            setOf("coalSale", "driverPayment", "coalWorkDay", "livestockOperation", "slaughterRecord"),
            Entities.CAREFUL,
        )
    }

    @Test
    fun roundTripEmployee() {
        val e = roundTrip(Entities.employee, uuidA, employee())
        assertEquals("twoTwo", e.workSchedule)
        assertEquals(7.5, e.standardWorkHours, 0.0)
    }

    @Test
    fun roundTripHoursMark() {
        val id = StableId.hoursMark(uuidA, java.time.LocalDate.parse("2026-11-06"))
        val m = roundTrip(Entities.hoursMark, id, HoursMark(id, uuidA, day, 12, t1, t2))
        assertEquals(12, m.hours)
        assertEquals(uuidA, m.employeeId)
    }

    @Test
    fun roundTripAttendanceRecord() {
        val r = roundTrip(
            Entities.attendanceRecord, "r1",
            AttendanceRecord("r1", uuidA, day, t1, t2, 30, 600, 540, "К", "Чита", t1, t2),
        )
        assertEquals("К", r.status)
        assertEquals(540, r.manualWorkedMinutes)
    }

    @Test
    fun roundTripTimesheetSnapshot() {
        val s = roundTrip(
            Entities.timesheetSnapshot, "t1",
            TimesheetSnapshot("t1", 11, 2026, 3, uuidA, t1, "eyJhIjoxfQ==", "AP8H"),
        )
        assertEquals("AP8H", s.employeeData)
        assertEquals("eyJhIjoxfQ==", s.documentData)
    }

    @Test
    fun roundTripFarmSettings() {
        val f = roundTrip(
            Entities.farmSettings, StableId.farmSettings,
            FarmSettings(StableId.farmSettings, "Петрова", "с. Новоцурухайтуй", "+7 914 000-00-00", "ИНН 1"),
        )
        assertEquals("с. Новоцурухайтуй", f.address)
    }

    @Test
    fun roundTripCoalSettings() {
        val s = roundTrip(Entities.coalSettings, StableId.coalSettings, CoalSettings(StableId.coalSettings, 700_000, t1, t2))
        assertEquals(700_000L, s.pricePerTonKopecks)
    }

    @Test
    fun roundTripDeliveryLocation() {
        roundTrip(
            Entities.deliveryLocation, StableId.deliveryLocation("Луговое"),
            DeliveryLocation(StableId.deliveryLocation("Луговое"), "Луговое", 90_000, 80_000, false, t1),
        )
        val l = roundTrip(Entities.deliveryLocation, "l1", DeliveryLocation("l1", "Приаргунск", 120_000, null, true, t1))
        assertNull(l.driverPaymentKopecks)
    }

    @Test
    fun roundTripDriver() {
        roundTrip(Entities.driver, "d1", Driver("d1", "Мунгалов", "Сергей", "Иванович", "8 914", "КамАЗ", true, t1))
    }

    @Test
    fun roundTripCoalSale() {
        val s = roundTrip(Entities.coalSale, "s1", sale())
        assertEquals("hired", s.vehicleType)
        assertEquals(1_095_000L, s.totalAmount)
        assertEquals(uuidB, s.driverId)
        roundTrip(
            Entities.coalSale, "s2",
            CoalSale("s2", t1, 500, "Луговое", 650_000, 0, 325_000, 0, 325_000, "own", null, "", 0, "", t1, t1),
        )
    }

    @Test
    fun roundTripDriverPayment() {
        roundTrip(Entities.driverPayment, "p1", DriverPayment("p1", uuidB, "Мунгалов Сергей", t2, 50_000, "наличными", t1))
    }

    @Test
    fun roundTripCoalWorkDay() {
        val id = StableId.coalWorkDay(java.time.LocalDate.parse("2026-11-06"))
        roundTrip(Entities.coalWorkDay, id, CoalWorkDay(id, day, true, t2, 1_095_000, 1500, 100_000, 0, t1))
        roundTrip(Entities.coalWorkDay, "w2", CoalWorkDay("w2", day, createdAt = t1))
    }

    @Test
    fun roundTripLivestockCategory() {
        val id = StableId.livestockCategory("goats", "Козлята")
        val c = roundTrip(Entities.livestockCategory, id, LivestockCategory(id, "goats", "Козлята", 2, true, t1))
        assertEquals("goats", c.species)
    }

    @Test
    fun roundTripLivestockOperation() {
        val o = roundTrip(
            Entities.livestockOperation, "o1",
            LivestockOperation("o1", "transfer", t1, uuidA, uuidB, 3, "подросли", t1, t2),
        )
        assertEquals("transfer", o.kind)
        assertEquals(uuidB, o.targetCategoryId)
    }

    @Test
    fun roundTripSlaughterRecord() {
        val s = roundTrip(
            Entities.slaughterRecord, "sl1",
            SlaughterRecord("sl1", uuidA, "forced", 450_500, 250_000, null, t1),
        )
        assertEquals("forced", s.reason)
        assertNull(s.meatWeightGrams)
    }

    @Test
    fun roundTripLivestockSettings() {
        roundTrip(
            Entities.livestockSettings, StableId.livestockSettings,
            LivestockSettings(StableId.livestockSettings, true, t1),
        )
    }

    @Test
    fun roundTripAgroCalculation() {
        val c = roundTrip(Entities.agroCalculation, "a1", agro())
        assertEquals(mapOf("area" to "5", "rate" to "240"), c.inputs)
        assertEquals(AgroLine("Нужно семян", "1 200 кг", true), c.lines.first())
        assertEquals("seeding", c.kind)
    }

    // MARK: the site's field names and shapes

    @Test
    fun fieldNamesMatchSite() {
        assertKeys(
            sale(), "saleDate", "kilograms", "locationNameSnapshot", "coalPricePerTonSnapshot", "deliveryPriceSnapshot",
            "coalAmount", "deliveryPrice", "totalAmount", "vehicleType", "driverId", "driverNameSnapshot",
            "driverPaymentAmount", "comment", "createdAt",
        )
        assertKeys(
            employee(), "lastName", "firstName", "patronymic", "position", "hireDate", "dismissalDate", "isArchived",
            "workSchedule", "standardWorkHours", "defaultBreakMinutes",
        )
        assertKeys(HoursMark(employeeId = uuidA, day = day, hours = 8), "employeeId", "day", "hours")
        assertKeys(
            LivestockOperation(kind = "birth", date = t1, categoryId = uuidA, heads = 1),
            "kind", "date", "categoryId", "targetCategoryId", "heads", "comment", "createdAt",
        )
        assertKeys(
            SlaughterRecord(operationId = uuidA, reason = "meat"),
            "operationId", "reason", "liveWeightGrams", "carcassWeightGrams", "meatWeightGrams", "createdAt",
        )
        assertKeys(agro(), "kind", "title", "inputs", "lines", "createdAt", "updatedAt")
        assertKeys(CoalSettings(), "pricePerTonKopecks")
        assertKeys(DeliveryLocation(name = "Луговое"), "name", "deliveryPriceKopecks", "driverPaymentKopecks", "isActive", "createdAt")
        assertKeys(Driver(lastName = "М"), "lastName", "firstName", "middleName", "phone", "comment", "isArchived", "createdAt")
        assertKeys(DriverPayment(), "driverId", "driverNameSnapshot", "amount", "paymentDate", "comment", "createdAt")
        assertKeys(
            CoalWorkDay(), "date", "isClosed", "closedAt", "totalRevenue", "totalKilograms", "totalDriverPayments",
            "ownVehicleDeliveryRevenue", "createdAt",
        )
        assertKeys(LivestockCategory(), "species", "name", "sortOrder", "isBuiltIn", "createdAt")
    }

    /**
     * For every entity, the keys of `encode(default record)` are exactly what the site writes
     * (`engine.save('<entity>', id, {...})` in web/src/pages/<page>.tsx, union over all its calls) plus the fields only
     * the iPhone writes. The site never writes attendanceRecord, timesheetSnapshot, farmSettings or
     * livestockSettings (iPhone-only entities for now).
     */
    @Test
    fun matchesSiteFieldNames() {
        val site = mapOf(
            "employee" to setOf(
                "lastName", "firstName", "patronymic", "position", "hireDate", "dismissalDate", "isArchived",
                "workSchedule", "standardWorkHours", "defaultBreakMinutes",
            ),
            "hoursMark" to setOf("employeeId", "day", "hours"),
            "coalSettings" to setOf("pricePerTonKopecks"),
            "deliveryLocation" to setOf("name", "deliveryPriceKopecks", "driverPaymentKopecks", "isActive", "createdAt"),
            "driver" to setOf("lastName", "firstName", "middleName", "phone", "comment", "isArchived", "createdAt"),
            "coalSale" to setOf(
                "saleDate", "kilograms", "locationNameSnapshot", "coalPricePerTonSnapshot", "deliveryPriceSnapshot",
                "coalAmount", "deliveryPrice", "totalAmount", "vehicleType", "driverId", "driverNameSnapshot",
                "driverPaymentAmount", "comment", "createdAt",
            ),
            "driverPayment" to setOf("driverId", "driverNameSnapshot", "amount", "paymentDate", "comment", "createdAt"),
            "coalWorkDay" to setOf(
                "date", "isClosed", "closedAt", "totalRevenue", "totalKilograms", "totalDriverPayments",
                "ownVehicleDeliveryRevenue", "createdAt",
            ),
            "livestockCategory" to setOf("species", "name", "sortOrder", "isBuiltIn", "createdAt"),
            "livestockOperation" to setOf("kind", "date", "categoryId", "targetCategoryId", "heads", "comment", "createdAt"),
            "slaughterRecord" to setOf(
                "operationId", "reason", "liveWeightGrams", "carcassWeightGrams", "meatWeightGrams", "createdAt",
            ),
            "agroCalculation" to setOf("kind", "title", "inputs", "lines", "createdAt", "updatedAt"),
        )
        // Written by the iPhone (SyncCodec.swift) and not by the site; the site ignores them.
        val iphoneOnly = mapOf(
            "hoursMark" to setOf("createdAt", "updatedAt"),
            "coalSettings" to setOf("createdAt", "updatedAt"),
            "coalSale" to setOf("updatedAt"),
            "livestockOperation" to setOf("updatedAt"),
            "attendanceRecord" to setOf(
                "employeeId", "day", "arrivalTime", "departureTime", "breakMinutes", "workedMinutes",
                "manualWorkedMinutes", "status", "comment", "createdAt", "updatedAt",
            ),
            "timesheetSnapshot" to setOf(
                "month", "year", "version", "employeeId", "createdAt", "documentData", "employeeData",
            ),
            "farmSettings" to setOf("responsiblePerson", "address", "phone", "extraInfo"),
            "livestockSettings" to setOf("defaultsCreated", "createdAt"),
        )
        val defaults: Map<String, Any> = mapOf(
            "employee" to Employee(), "hoursMark" to HoursMark(), "attendanceRecord" to AttendanceRecord(),
            "timesheetSnapshot" to TimesheetSnapshot(), "farmSettings" to FarmSettings(),
            "coalSettings" to CoalSettings(), "deliveryLocation" to DeliveryLocation(), "driver" to Driver(),
            "coalSale" to CoalSale(), "driverPayment" to DriverPayment(), "coalWorkDay" to CoalWorkDay(),
            "livestockCategory" to LivestockCategory(), "livestockOperation" to LivestockOperation(),
            "slaughterRecord" to SlaughterRecord(), "livestockSettings" to LivestockSettings(),
            "agroCalculation" to AgroCalculation(),
        )
        assertEquals(Entities.ALL, defaults.keys)
        for ((entity, record) in defaults) {
            val expected = (site[entity] ?: emptySet()) + (iphoneOnly[entity] ?: emptySet())
            assertEquals(entity, expected, RecordCodec.encode(record).keys)
        }
    }

    @Test
    fun valueShapes() {
        val s = RecordCodec.encode(sale())
        assertEquals(JsonPrimitive("hired"), s["vehicleType"])
        assertNull(s["vehicleTypeRaw"])
        assertEquals(JsonPrimitive(uuidB), s["driverId"])
        assertEquals(JsonPrimitive("2026-11-06T15:00:00.123Z"), s["saleDate"])
        assertEquals(JsonPrimitive(1500L), s["kilograms"])
        assertEquals("1500", s["kilograms"]!!.toString())

        val own = RecordCodec.encode(CoalSale(kilograms = 1, vehicleType = "own"))
        assertEquals("an empty optional is sent as null, as the site does", JsonNull, own["driverId"])

        val e = RecordCodec.encode(employee())
        assertEquals(JsonPrimitive("twoTwo"), e["workSchedule"])
        assertEquals(JsonPrimitive("2026-11-06T15:00:00.000Z"), e["hireDate"])
        assertNull(e["photo"])
        assertNull(e["records"])
        assertNull(e["id"])
        assertEquals("7.5", e["standardWorkHours"]!!.toString())

        val a = RecordCodec.encode(agro())
        assertEquals(obj("""{"area":"5","rate":"240"}"""), a["inputs"])
        assertEquals(
            Json.parseToJsonElement(
                """[{"title":"Нужно семян","text":"1 200 кг","main":true},{"title":"В центнерах","text":"12 ц","main":false}]""",
            ),
            a["lines"],
        )
        assertNull(a["inputsData"])
        assertNull(a["resultData"])

        val t = RecordCodec.encode(TimesheetSnapshot(month = 1, year = 2026, version = 1, documentData = "AQI="))
        assertEquals(JsonPrimitive("AQI="), t["documentData"])
        assertEquals(JsonPrimitive(""), t["employeeData"])
        assertEquals(JsonNull, t["employeeId"])

        val f = RecordCodec.encode(FarmSettings())
        assertEquals(setOf("responsiblePerson", "address", "phone", "extraInfo"), f.keys)
    }

    @Test
    fun singletonIds() {
        assertEquals(StableId.coalSettings, CoalSettings().id)
        assertEquals(StableId.farmSettings, FarmSettings().id)
        assertEquals(StableId.livestockSettings, LivestockSettings().id)
        assertEquals(setOf("farmSettings", "coalSettings", "livestockSettings"), Entities.SINGLETONS)
    }

    // MARK: reading what the site writes

    @Test
    fun decodesSiteDate() {
        val expected = Instant.ofEpochSecond(1_793_977_200) // 2026-11-06 15:00:00 UTC
        assertEquals(expected, InstantSerializer.parse("2026-11-06T15:00:00.000Z"))
        assertEquals(expected, InstantSerializer.parse("2026-11-06T15:00:00Z"))
        assertNull(InstantSerializer.parse("06.11.2026"))
        assertEquals("2026-11-06T15:00:00.000Z", Iso.format(expected))
        // a date read back and written again gives the same string, however long its fraction was
        assertEquals(Instant.parse("2026-11-06T15:00:00.124Z"), InstantSerializer.parse("2026-11-06T15:00:00.1236Z"))
    }

    @Test
    fun decodesSiteRecordJson() {
        val data = obj(
            """
            {"lastName":"Иванов","firstName":"Пётр","patronymic":"","position":"","hireDate":"2026-11-01T00:00:00Z",
             "dismissalDate":null,"isArchived":false,"workSchedule":"fiveTwo","standardWorkHours":8,"defaultBreakMinutes":60}
            """,
        )
        assertEquals(JsonNull, data["dismissalDate"])
        val e = RecordCodec.decode<Employee>(Entities.employee, "e1", data)
        assertEquals("Иванов", e.lastName)
        assertEquals(8.0, e.standardWorkHours, 0.0)
        assertEquals(Instant.parse("2026-11-01T00:00:00.000Z"), e.hireDate)
        assertNull(e.dismissalDate)
        assertEquals("e1", e.id)

        // The site's agro lines may omit `main`.
        val agroJson = obj(
            """
            {"kind":"fertilizer","title":"","inputs":{"area":"2"},"lines":[{"title":"Нужно удобрения","text":"100 кг","main":true},
             {"title":"В тоннах","text":"0,1 т"}],"createdAt":"2026-11-06T15:00:00.000Z","updatedAt":"2026-11-06T15:00:00.000Z"}
            """,
        )
        val c = RecordCodec.decode<AgroCalculation>(Entities.agroCalculation, "a2", agroJson)
        assertEquals("fertilizer", c.kind)
        assertEquals(mapOf("area" to "2"), c.inputs)
        assertEquals(
            listOf(AgroLine("Нужно удобрения", "100 кг", true), AgroLine("В тоннах", "0,1 т", false)),
            c.lines,
        )

        // And back to JSON: the same values.
        assertEquals(data, Json.parseToJsonElement(Json.encodeToString(JsonObject.serializer(), data)))
    }

    @Test
    fun agroInputsTakeNumbersAndSkipTheRest() {
        val c = RecordCodec.decode<AgroCalculation>(
            Entities.agroCalculation, "a3",
            obj("""{"inputs":{"a":"x","b":5,"c":2.5,"d":true,"e":null,"f":{"x":1}},"lines":[1,"x",{"title":"t","text":"u"}]}"""),
        )
        assertEquals(mapOf("a" to "x", "b" to "5", "c" to "2.5"), c.inputs)
        assertEquals(listOf(AgroLine("t", "u", false)), c.lines)
    }

    @Test
    fun missingFieldsTakeModelDefaults() {
        val e = RecordCodec.decode<Employee>(
            Entities.employee, "e1", obj("""{"lastName":"Иванов","firstName":"Пётр"}"""),
        )
        assertEquals("e1", e.id)
        assertEquals("fiveTwo", e.workSchedule)
        assertEquals(8.0, e.standardWorkHours, 0.0)
        assertEquals(60, e.defaultBreakMinutes)
        assertFalse(e.isArchived)
        assertNull(e.dismissalDate)
        assertNotNull(e.hireDate)

        val empty = RecordCodec.decode<CoalSale>(Entities.coalSale, "s", JsonObject(emptyMap()))
        assertEquals("own", empty.vehicleType)
        assertEquals(0L, empty.totalAmount)
        val kinds = RecordCodec.decode<LivestockOperation>(Entities.livestockOperation, "o", JsonObject(emptyMap()))
        assertEquals("other", kinds.kind)
        assertEquals("seeding", RecordCodec.decode<AgroCalculation>(Entities.agroCalculation, "a", JsonObject(emptyMap())).kind)
        assertEquals(650_000L, RecordCodec.decode<CoalSettings>(Entities.coalSettings, "c", JsonObject(emptyMap())).pricePerTonKopecks)
    }

    @Test
    fun wrongTypesAndNullsKeepDefaults() {
        val e = RecordCodec.decode<Employee>(
            Entities.employee, "e1",
            obj(
                """{"lastName":5,"firstName":null,"hireDate":"06.11.2026","isArchived":"yes","standardWorkHours":"8",
                    "defaultBreakMinutes":45.0,"workSchedule":["x"]}""",
            ),
        )
        assertEquals("", e.lastName)
        assertEquals("", e.firstName)
        assertFalse(e.isArchived)
        assertEquals(8.0, e.standardWorkHours, 0.0)
        assertEquals(45, e.defaultBreakMinutes)
        assertEquals("fiveTwo", e.workSchedule)
        assertTrue("unreadable date falls back to now", e.hireDate.isAfter(Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    fun wholeDoublesReadAsIntegersAndNullClearsOptionals() {
        val s = RecordCodec.decode<CoalSale>(
            Entities.coalSale, "s",
            obj("""{"kilograms":1500.0,"coalAmount":975000.0,"driverId":null,"totalAmount":1095000,"deliveryPrice":120000}"""),
        )
        assertEquals(1500L, s.kilograms)
        assertEquals(975_000L, s.coalAmount)
        assertNull(s.driverId)
        assertEquals(1_095_000L, s.totalAmount)
    }

    @Test
    fun coalSaleWithoutTotalComputesIt() {
        val s = RecordCodec.decode<CoalSale>(Entities.coalSale, "s", obj("""{"coalAmount":100,"deliveryPrice":20}"""))
        assertEquals(120L, s.totalAmount)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val d = RecordCodec.decode<Driver>(
            Entities.driver, "d",
            obj("""{"lastName":"М","photo":"zzz","records":[1],"extra":{"a":1},"isArchived":true}"""),
        )
        assertEquals("М", d.lastName)
        assertTrue(d.isArchived)
    }

    @Test
    fun invalidBase64KeepsDefault() {
        val s = RecordCodec.decode<TimesheetSnapshot>(
            Entities.timesheetSnapshot, "t", obj("""{"month":11,"documentData":"AQI=","employeeData":"@@@"}"""),
        )
        assertEquals("AQI=", s.documentData)
        assertEquals("", s.employeeData)
        assertEquals(11, s.month)
    }

    @Test
    fun removedHoursMarkIsTolerated() {
        val removed = obj("""{"employeeId":"$uuidA","day":"2026-11-06T15:00:00.000Z","hours":null}""")
        assertTrue(RecordCodec.isRemovedHoursMark(removed))
        assertFalse(RecordCodec.isRemovedHoursMark(obj("""{"hours":8}""")))
        assertFalse(RecordCodec.isRemovedHoursMark(obj("""{"hours":0}""")))
        assertFalse(RecordCodec.isRemovedHoursMark(obj("""{}""")))

        val out = RecordCodec.removedHoursMark(HoursMark("m", uuidA, day, 8, t1, t2))
        assertEquals(JsonNull, out["hours"])
        assertTrue(RecordCodec.isRemovedHoursMark(out))
        assertEquals(JsonPrimitive(uuidA), out["employeeId"])
    }

    // MARK: diff

    @Test
    fun diffReturnsOnlyChangedFields() {
        val old = RecordCodec.encode(employee())
        val new = RecordCodec.encode(employee().copy(position = "Комбайнёр", isArchived = false))
        assertEquals(buildJsonObject {
            put("position", "Комбайнёр")
            put("isArchived", false)
        }, RecordCodec.diff(old, new))
        assertTrue(RecordCodec.diff(old, old).isEmpty())
    }

    @Test
    fun diffSendsNullForClearedOptionals() {
        val old = RecordCodec.encode(employee())
        val new = RecordCodec.encode(employee().copy(dismissalDate = null))
        assertEquals(buildJsonObject { put("dismissalDate", JsonNull) }, RecordCodec.diff(old, new))
    }

    @Test
    fun diffOfNothingIsEverything() {
        val new = RecordCodec.encode(employee())
        assertEquals(new, RecordCodec.diff(null, new))
    }

    @Test
    fun diffTreatsWholeNumberAndDoubleAsEqual() {
        val old = obj("""{"standardWorkHours":8,"kilograms":1500}""")
        val new = obj("""{"standardWorkHours":8.0,"kilograms":1500.0}""")
        assertTrue(RecordCodec.diff(old, new).isEmpty())
        assertEquals(obj("""{"standardWorkHours":7.5}"""), RecordCodec.diff(old, obj("""{"standardWorkHours":7.5,"kilograms":1500}""")))
    }

    @Test
    fun diffMissingOldFieldIsNullAndDeepValuesCompare() {
        // as the site's engine.save: a missing field counts as null
        assertTrue(RecordCodec.diff(obj("""{}"""), obj("""{"dismissalDate":null}""")).isEmpty())
        assertEquals(obj("""{"driverId":"x"}"""), RecordCodec.diff(obj("""{}"""), obj("""{"driverId":"x"}""")))
        val oldA = RecordCodec.encode(agro())
        assertTrue(RecordCodec.diff(oldA, RecordCodec.encode(agro())).isEmpty())
        val changed = RecordCodec.encode(agro().copy(lines = listOf(AgroLine("Нужно семян", "1 300 кг", true))))
        assertEquals(setOf("lines"), RecordCodec.diff(oldA, changed).keys)
        assertTrue(RecordCodec.diff(oldA, changed)["lines"] is JsonArray)
    }

    @Test
    fun encodedRecordsCarryNoId() {
        for (r in listOf<Any>(employee(), sale(), agro(), HoursMark(id = "m"))) {
            assertFalse(RecordCodec.encode(r).containsKey("id"))
        }
        assertTrue(RecordCodec.encode(agro())["inputs"]!!.jsonObject.values.all { it.jsonPrimitive.isString })
        assertTrue(RecordCodec.encode(agro())["lines"]!!.jsonArray[0].jsonObject["main"]!!.jsonPrimitive.boolean)
    }
}
