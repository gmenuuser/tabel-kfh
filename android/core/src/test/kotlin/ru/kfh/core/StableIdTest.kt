package ru.kfh.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.ids.StableId
import ru.kfh.core.time.FarmTime
import ru.kfh.core.time.Iso

class StableIdTest {
    private val employee = "6F1C2A3E-0000-4000-8000-000000000001"

    @Test
    fun matchesSite() {
        val file = File(System.getProperty("kfh.repoRoot"), "shared/fixtures/stable-ids.json")
        val pairs = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject["pairs"]!!.jsonArray
        assertTrue(pairs.size >= 7)
        for (p in pairs) {
            val name = p.jsonObject["name"]!!.jsonPrimitive.content
            assertEquals(name, p.jsonObject["id"]!!.jsonPrimitive.content, StableId.make(name))
        }
    }

    @Test
    fun namedHelpersMatchSite() {
        val day = LocalDate.of(2026, 11, 7)
        // the employee id is lowercased in the name
        assertEquals("5337ef8b-89b1-8bd3-96e9-a27e67205ff7", StableId.hoursMark(employee, day))
        assertEquals("267e507b-3bc5-81c8-b4ad-a44028534672", StableId.coalWorkDay(day))
        assertEquals("3f9d5530-0b6d-86ce-9ec5-b392829d9df5", StableId.deliveryLocation("Луговое"))
        assertEquals("37093837-97e0-8515-af51-f22c98ee7499", StableId.livestockCategory("cattle", "Коровы"))
        assertEquals("95dedd13-e1ca-8a22-b8f7-dd380554b776", StableId.coalSettings)
        assertEquals("6f0e626d-d54f-8197-8901-1a4905a531de", StableId.farmSettings)
        assertEquals("0a989983-1107-8b34-84d3-14c31f0c93ae", StableId.livestockSettings)
    }

    @Test
    fun hoursMarkUsesFarmDay() {
        val ft = FarmTime(ZoneId.of("Asia/Chita"))
        val moment = Instant.parse("2026-11-06T16:30:00Z") // 01:30 on the 7th in Chita
        assertEquals("2026-11-07", Iso.day(ft.dayOf(moment)))
        val id = UUID.randomUUID().toString()
        assertEquals(StableId.make("hoursMark:${id.lowercase()}:2026-11-07"), StableId.hoursMark(id, ft.dayOf(moment)))
    }
}
