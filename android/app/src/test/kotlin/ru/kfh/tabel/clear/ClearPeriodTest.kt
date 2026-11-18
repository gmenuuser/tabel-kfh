package ru.kfh.tabel.clear

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kfh.tabel.db.RecordRow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** «Очистка периода»: те же границы и те же строки, что на сайте (`web/test/clear.test.ts`). */
class ClearPeriodTest {
    private val zone: ZoneId = ZoneId.of("Asia/Chita") // UTC+9, как у хозяйства

    private fun row(entity: String, id: String, json: String, deleted: Boolean = false) =
        RecordRow(entity = entity, id = id, version = 1, data = json, deleted = deleted)

    private val mark = { id: String, day: String, hours: String -> row("hoursMark", id, """{"day":"$day","hours":$hours}""") }

    @Test
    fun `границы дня и месяца включают обе даты`() {
        val day = LocalDate.of(2026, 11, 17)
        assertEquals(day to day, bounds(PeriodMode.Day, day, YearMonth.of(2026, 11)))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30), bounds(PeriodMode.Month, day, YearMonth.of(2026, 11)))
        assertEquals(LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29), bounds(PeriodMode.Month, day, YearMonth.of(2028, 2)))
    }

    @Test
    fun `табель - обнулённые отметки не считаются, границы месяца включительные`() {
        val rows = listOf(
            mark("a", "2026-11-01T00:00:00.000Z", "8"),
            mark("b", "2026-11-30T00:00:00.000Z", "4"),
            mark("c", "2026-12-01T00:00:00.000Z", "8"),
            mark("d", "2026-11-10T00:00:00.000Z", "null"),
        )
        val target = CLEAR_TARGETS.getValue(ClearSection.Timesheet).first()
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        assertEquals(listOf("a", "b"), rowsInPeriod(rows, target, from, to, zone).map { it.id })
    }

    @Test
    fun `день считается по зоне хозяйства, а не по UTC`() {
        // 20:00 UTC 30 ноября — это 05:00 1 декабря в Чите.
        val rows = listOf(row("coalSale", "s1", """{"saleDate":"2026-11-30T20:00:00.000Z"}"""))
        val target = CLEAR_TARGETS.getValue(ClearSection.Coal).first()
        val november = LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30)
        val december = LocalDate.of(2026, 12, 1) to LocalDate.of(2026, 12, 31)
        assertEquals(emptyList<String>(), rowsInPeriod(rows, target, november.first, november.second, zone).map { it.id })
        assertEquals(listOf("s1"), rowsInPeriod(rows, target, december.first, december.second, zone).map { it.id })
    }

    @Test
    fun `удалённая запись и запись без даты не чистятся`() {
        val target = CLEAR_TARGETS.getValue(ClearSection.Agro).first()
        val rows = listOf(
            row("agroCalculation", "c1", """{"createdAt":"2026-11-05T10:00:00.000Z"}""", deleted = true),
            row("agroCalculation", "c2", """{"title":"без даты"}"""),
        )
        assertEquals(emptyList<String>(), rowsInPeriod(rows, target, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), zone).map { it.id })
    }

    @Test
    fun `скот - причины забоя уходят вместе со своей операцией`() {
        val rows = listOf(
            row("livestockOperation", "o1", """{"kind":"slaughter","date":"2026-11-10T00:00:00.000Z","heads":1}"""),
            row("livestockOperation", "o2", """{"kind":"purchase","date":"2026-12-10T00:00:00.000Z","heads":2}"""),
            row("slaughterRecord", "sl1", """{"operationId":"o1","createdAt":"2026-11-10T05:00:00.000Z"}"""),
            row("slaughterRecord", "sl2", """{"operationId":"o2","createdAt":"2026-12-10T05:00:00.000Z"}"""),
        )
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        assertEquals(listOf("sl1"), slaughterOfClearedOps(rows, from, to, zone).map { it.id })
        assertEquals(2, countInPeriod(rows, ClearSection.Livestock, from, to, zone))
    }

    @Test
    fun `уголь за день - продажи, рабочий день и выплаты`() {
        val rows = listOf(
            row("coalSale", "s1", """{"saleDate":"2026-11-17T03:00:00.000Z"}"""),
            row("coalSale", "s2", """{"saleDate":"2026-11-18T03:00:00.000Z"}"""),
            row("coalWorkDay", "d1", """{"day":"2026-11-17T00:00:00.000Z"}"""),
            row("driverPayment", "p1", """{"paymentDate":"2026-11-17T06:00:00.000Z"}"""),
        )
        val day = LocalDate.of(2026, 11, 17)
        assertEquals(3, countInPeriod(rows, ClearSection.Coal, day, day, zone))
    }
}
