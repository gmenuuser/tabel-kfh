package ru.kfh.core.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AgroLine
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
import java.time.Instant
import java.time.ZoneId

/**
 * The key order of the canonical text is what Swift's `JSONEncoder(.sortedKeys)` gives: the UTF-8 bytes on iOS 18+
 * (swift-foundation), `NSString.compare(numeric, caseInsensitive, forcedOrdering)` before. The payload's fixed key
 * sets must not depend on which; only the agro `inputs` keys («plot.10.area») can.
 */
class BackupKeyOrderTest {
    private val created: Instant = Instant.parse("2026-11-06T03:04:05.123Z")
    private val meta = BackupMeta(created, "1.0", ZoneId.of("Asia/Chita"))

    private fun agro(inputs: Map<String, String>) = AgroCalculation(
        id = "a2000000-0000-4000-8000-000000000001", kind = "area", title = "Поле", inputs = inputs,
        lines = listOf(AgroLine("Площадь", "12 га", true)), createdAt = created, updatedAt = created,
    )

    private fun records(agro: List<AgroCalculation>) = BackupRecords(
        settings = FarmInfo.DEFAULT, employees = emptyList(), attendance = emptyList(), snapshots = emptyList(),
        hours = emptyList(), coalSettings = null, locations = emptyList(), drivers = emptyList(), sales = emptyList(),
        payments = emptyList(), days = emptyList(), livestockSettings = null, categories = emptyList(),
        operations = emptyList(), slaughters = emptyList(), agro = agro,
    )

    @Test
    fun theFileFollowsIos18ByteOrder() {
        assertSame(BackupCodec.UTF8_KEY_ORDER, BackupCodec.KEY_ORDER)
    }

    /** Ten plots of «Площадь поля»: `plot.10` sorts between `plot.1` and `plot.2` by bytes, after `plot.9` numerically. */
    @Test
    fun plotKeysOrder() {
        val keys = listOf("plot.1.area", "plot.2.area", "plot.10.area", "plot.9.area")
        assertEquals(listOf("plot.1.area", "plot.10.area", "plot.2.area", "plot.9.area"), keys.sortedWith(BackupCodec.UTF8_KEY_ORDER))
        assertEquals(listOf("plot.1.area", "plot.2.area", "plot.9.area", "plot.10.area"), keys.sortedWith(BackupCodec.LEGACY_KEY_ORDER))

        val inputs = mapOf("plot.10.area" to "3", "plot.2.area" to "2", "plot.1.area" to "1", "count" to "10")
        val canonical = BackupCodec.canonical(BackupCodec.payload(records(listOf(agro(inputs))), meta))
        assertTrue(canonical.contains("\"inputs\":{\"count\":\"10\",\"plot.1.area\":\"1\",\"plot.10.area\":\"3\",\"plot.2.area\":\"2\"}"))
    }

    @Test
    fun mixedCaseKeys() {
        val keys = listOf("zebra", "Zebra", "apple", "Apple2", "apple10", "apple9")
        // Bytes: every capital before every small letter.
        assertEquals(listOf("Apple2", "Zebra", "apple", "apple10", "apple9", "zebra"), keys.sortedWith(BackupCodec.UTF8_KEY_ORDER))
        // Case-insensitive, numeric runs by value, equal-but-for-case ordered by bytes (forcedOrdering).
        assertEquals(listOf("apple", "Apple2", "apple9", "apple10", "Zebra", "zebra"), keys.sortedWith(BackupCodec.LEGACY_KEY_ORDER))
        assertEquals(0, BackupCodec.LEGACY_KEY_ORDER.compare("a", "a"))
    }

    /** Every object of a full payload (the header too) orders its keys the same under both comparators. */
    @Test
    fun fixedKeySetsAgreeUnderBothOrders() {
        val e = Employee(id = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01", lastName = "Зубахин", hireDate = created, dismissalDate = created)
        val full = BackupRecords(
            settings = FarmInfo.DEFAULT,
            employees = listOf(e),
            photos = mapOf(e.id to byteArrayOf(1)),
            attendance = listOf(AttendanceRecord(id = "a0000000-0000-4000-8000-000000000001", employeeId = e.id, day = created, arrivalTime = created,
                                                 departureTime = created, workedMinutes = 1, manualWorkedMinutes = 1)),
            snapshots = listOf(TimesheetSnapshot(id = "b0000000-0000-4000-8000-000000000001", employeeId = e.id, documentData = "e30=", employeeData = "W10=")),
            hours = listOf(HoursMark(id = "c0000000-0000-4000-8000-000000000001", employeeId = e.id, day = created, hours = 8)),
            coalSettings = CoalSettings(),
            locations = listOf(DeliveryLocation(id = "d0000000-0000-4000-8000-000000000001", name = "Н", driverPaymentKopecks = 1)),
            drivers = listOf(Driver(id = "e0000000-0000-4000-8000-000000000001", lastName = "М")),
            sales = listOf(CoalSale(id = "f0000000-0000-4000-8000-000000000001", driverId = "e0000000-0000-4000-8000-000000000001")),
            payments = listOf(DriverPayment(id = "f1000000-0000-4000-8000-000000000001", driverId = "e0000000-0000-4000-8000-000000000001")),
            days = listOf(CoalWorkDay(id = "f2000000-0000-4000-8000-000000000001", closedAt = created)),
            livestockSettings = LivestockSettings(),
            categories = listOf(LivestockCategory(id = "a1000000-0000-4000-8000-000000000001", name = "К")),
            operations = listOf(LivestockOperation(id = "a3000000-0000-4000-8000-000000000001", categoryId = "a1000000-0000-4000-8000-000000000001",
                                                   targetCategoryId = "a1000000-0000-4000-8000-000000000002")),
            slaughters = listOf(SlaughterRecord(id = "a4000000-0000-4000-8000-000000000001", operationId = "a3000000-0000-4000-8000-000000000001",
                                                liveWeightGrams = 1, carcassWeightGrams = 1, meatWeightGrams = 1)),
            agro = listOf(agro(mapOf("area" to "1", "count" to "2"))),
        )
        val file = kotlinx.serialization.json.Json.parseToJsonElement(String(BackupCodec.encode(full, meta), Charsets.UTF_8))
        var objects = 0
        fun walk(x: JsonElement) {
            when (x) {
                is JsonObject -> {
                    objects++
                    val keys = x.keys.toList()
                    assertEquals(keys, keys.sortedWith(BackupCodec.UTF8_KEY_ORDER))
                    assertEquals(keys, keys.sortedWith(BackupCodec.LEGACY_KEY_ORDER))
                    x.values.forEach(::walk)
                }
                is JsonArray -> x.forEach(::walk)
                else -> Unit
            }
        }
        walk(file)
        // The header, the payload, settings, coal, livestock, every row type and the agro inputs/lines.
        assertTrue("objects=$objects", objects >= 20)
    }
}
