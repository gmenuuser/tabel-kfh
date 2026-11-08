package ru.kfh.core.agro

import java.time.Instant
import org.junit.Test
import ru.kfh.core.testing.assertEquals
import ru.kfh.core.testing.assertFailsWith
import ru.kfh.core.testing.assertFalse
import ru.kfh.core.testing.assertNull
import ru.kfh.core.testing.assertTrue
import ru.kfh.core.agro.AgroKind.*

/**
 * Port of `TabelKFHTests/AgroTests.swift`. Skipped: testHistoryAndBackup (SwiftData store, XLSX/PDF and
 * BackupService); its domain facts (inputs without empty fields, results recomputed on correction, creation time
 * kept, incomplete not saved) are asserted in [calculationRecordIsBuiltFromTheInputs] and [historyFilters].
 */
class AgroTest {
    private fun line(r: AgroResult, title: String): String? = r.lines.firstOrNull { it.title == title }?.text

    private fun nb(s: String) = s.replace(' ', ' ')

    private fun calc(k: AgroKind, vararg i: Pair<String, String>) = AgroEngine.calculate(k, mapOf(*i))

    @Test fun seeding() {
        var r = calc(SEEDING, "area" to "10", "rate" to "200")
        assertTrue(r.isComplete)
        assertEquals(nb("2 000 кг"), line(r, "Нужно семян"))
        assertEquals(nb("20 ц"), line(r, "В центнерах"))
        assertEquals(nb("2 т"), line(r, "В тоннах"))
        r = calc(SEEDING, "area" to "10", "rate" to "200", "reserve" to "5", "bag" to "50")
        assertEquals(nb("2 100 кг"), line(r, "Нужно семян"))
        assertEquals("42", line(r, "Мешков по 50 кг"))
        r = calc(SEEDING, "area" to "2,5", "rate" to "180", "bag" to "40")
        assertEquals(nb("450 кг"), line(r, "Нужно семян"))
        assertEquals("12", line(r, "Мешков по 40 кг"), "11,25 bags -> 12")
    }

    @Test fun fertilizer() {
        var r = calc(FERTILIZER, "area" to "20", "rate" to "100", "package" to "50", "price" to "35")
        assertEquals(nb("2 000 кг"), line(r, "Нужно удобрения"))
        assertEquals("40", line(r, "Упаковок по 50 кг"))
        assertEquals(nb("70 000 ₽"), line(r, "Стоимость"))
        r = calc(FERTILIZER, "area" to "20", "rate" to "1", "rateUnit" to "c", "price" to "30000", "priceUnit" to "t")
        assertEquals(nb("2 000 кг"), line(r, "Нужно удобрения"))
        assertEquals(nb("60 000 ₽"), line(r, "Стоимость"))
    }

    @Test fun spraying() {
        var r = calc(SPRAYING, "area" to "10", "rate" to "2", "water" to "200", "tank" to "1000")
        assertEquals(nb("20 л"), line(r, "Препарата"))
        assertEquals(nb("2 000 л"), line(r, "Воды"))
        assertEquals(nb("2 020 л"), line(r, "Рабочего раствора"))
        assertEquals("2", line(r, "Полных заправок"))
        assertEquals(nb("20 л"), line(r, "Остаток на последнюю заправку"))
        assertTrue(r.warnings.any { "этикетки" in it })
        // A powder is not added to the volume.
        r = calc(SPRAYING, "area" to "10", "rate" to "0,5", "productUnit" to "kg", "water" to "200", "tank" to "1000")
        assertEquals(nb("5 кг"), line(r, "Препарата"))
        assertEquals(nb("2 000 л"), line(r, "Рабочего раствора"))
        assertEquals("2", line(r, "Полных заправок"))
        assertEquals("нет", line(r, "Остаток на последнюю заправку"))
        assertTrue(r.warnings.any { "плотность" in it })
    }

    @Test fun yieldCalculator() {
        var r = calc(YIELD, "area" to "10", "weight" to "30")
        assertEquals(listOf(nb("30 ц/га"), nb("3 т/га")), r.lines.filter { it.title == "Урожайность" }.map { it.text })
        r = calc(YIELD, "area" to "10", "weight" to "300", "weightUnit" to "c")
        assertEquals(nb("30 ц/га"), r.lines.first().text)
        r = calc(YIELD, "area" to "4", "weight" to "9500", "weightUnit" to "kg")
        assertEquals(nb("23,75 ц/га"), r.lines.first().text)
    }

    @Test fun moisture() {
        val r = calc(MOISTURE, "weight" to "10000", "actual" to "20", "target" to "14")
        assertEquals(nb("9 302,33 кг"), line(r, "Вес после сушки"))
        assertEquals(nb("697,67 кг"), line(r, "Потеря массы"))
        assertEquals(nb("10 000 кг"), line(r, "Исходный вес"))
        assertTrue(r.warnings.first().contains("теоретической массы"))
        assertEquals(
            listOf("Целевая влажность должна быть меньше фактической"),
            calc(MOISTURE, "weight" to "100", "actual" to "14", "target" to "20").errors,
        )
        assertFalse(calc(MOISTURE, "weight" to "100", "actual" to "120", "target" to "14").isComplete)
    }

    @Test fun fuel() {
        var r = calc(FUEL, "area" to "10", "rate" to "15", "price" to "68,5")
        assertEquals(nb("150 л"), line(r, "Нужно топлива"))
        assertEquals(nb("10 275 ₽"), line(r, "Стоимость топлива"))
        r = calc(FUEL, "mode" to "stock", "fuel" to "400", "rate" to "15")
        assertEquals(nb("26,67 га"), line(r, "Можно обработать"))
    }

    @Test fun area() {
        var r = calc(AREA, "plot.1.length" to "500", "plot.1.width" to "200")
        assertEquals(nb("10 га"), line(r, "Площадь поля"))
        assertEquals(nb("100 000 м²"), line(r, "Всего"))
        r = calc(
            AREA, "plots" to "3", "plot.1.length" to "500", "plot.1.width" to "200",
            "plot.2.area" to "2500", "plot.3.length" to "100", "plot.3.width" to "50,5",
        )
        assertEquals(nb("10,755 га"), line(r, "Площадь поля"))
        assertTrue(r.warnings.first().contains("прямоугольника"))
        assertFalse(calc(AREA, "plot.1.length" to "500").isComplete)
        assertFalse(AgroEngine.calculate(AREA, emptyMap()).isComplete)
    }

    @Test fun transport() {
        var r = calc(TRANSPORT, "grain" to "30", "truck" to "10", "bag" to "50")
        assertEquals("3", line(r, "Полных рейсов"))
        assertEquals("нет", line(r, "Остаток на последний рейс"))
        assertEquals("600", line(r, "Полных мешков по 50 кг"))
        r = calc(TRANSPORT, "grain" to "32,5", "truck" to "10000", "truckUnit" to "kg", "bag" to "40")
        assertEquals("3", line(r, "Полных рейсов"))
        assertEquals(nb("2 500 кг"), line(r, "Остаток на последний рейс"))
        assertEquals("4", line(r, "Всего рейсов"))
        assertEquals("812", line(r, "Полных мешков по 40 кг"))
        assertEquals(nb("20 кг"), line(r, "Остаток зерна"))
    }

    @Test fun wrongValues() {
        assertEquals(listOf("Норма высева"), calc(SEEDING, "area" to "10").missing)
        assertEquals(listOf("«Площадь поля» должно быть больше нуля"), calc(SEEDING, "area" to "0", "rate" to "200").errors)
        assertEquals(1, calc(SEEDING, "area" to "-5", "rate" to "200").errors.size)
        assertEquals(
            listOf("«Площадь поля»: введите число, например 12,5"),
            calc(SEEDING, "area" to "десять", "rate" to "200").errors,
        )
        assertTrue(calc(SEEDING, "area" to "10", "rate" to "200", "reserve" to "0").isComplete, "0 % запаса")
        assertEquals(1200.5, AgroNumber.parse("1 200,5"))
        assertNull(AgroNumber.parse("1,2,3"))
        assertEquals(nb("9 302,33"), AgroNumber.format(9302.325581))
        assertEquals("2,01", AgroNumber.format(2.0051))
        assertEquals("3", AgroNumber.format(2.5, decimals = 0), "half away from zero")
        assertEquals("0", AgroNumber.format(0.004))
    }

    @Test fun numberFormatEdges() {
        assertEquals("−1,5", AgroNumber.format(-1.5))
        assertEquals("0", AgroNumber.format(-0.001))
        assertEquals(nb("1 234 567,89"), AgroNumber.format(1234567.891))
        assertEquals(nb("12,5 кг"), AgroNumber.with(12.5, "кг"))
        assertEquals("7", AgroNumber.with(7.0, ""))
        assertNull(AgroNumber.parse(null))
        assertNull(AgroNumber.parse(""))
        assertEquals(12.5, AgroNumber.parse(" 12,5 "))
        assertEquals(-3.0, AgroNumber.parse("-3"))
    }

    @Test fun kindsTitlesAndWireNames() {
        assertEquals(
            listOf(
                "Посев семян", "Удобрения", "Опрыскивание", "Урожайность", "Влажность зерна", "Топливо", "Площадь поля",
                "Перевозка и хранение зерна",
            ),
            AgroKind.entries.map { it.title },
        )
        assertEquals(
            listOf("seeding", "fertilizer", "spraying", "yield", "moisture", "fuel", "area", "transport"),
            AgroKind.entries.map { it.wire },
        )
        assertEquals(TRANSPORT, AgroKind.fromWire("transport"))
        assertEquals(SEEDING, AgroKind.fromWire("zzz"))
    }

    @Test fun inputsAndShareText() {
        val inputs = mapOf("area" to "10", "rate" to "200")
        assertEquals("Площадь поля: 10 га; Норма высева: 200 кг/га", AgroEngine.inputsText(SEEDING, inputs))
        val r = AgroEngine.calculate(SEEDING, inputs)
        assertEquals(
            "Посев семян — Поле у реки\nДанные: Площадь поля: 10 га; Норма высева: 200 кг/га\n" +
                "Нужно семян: ${nb("2 000 кг")}\nВ центнерах: ${nb("20 ц")}\nВ тоннах: ${nb("2 т")}",
            AgroEngine.shareText(SEEDING, "Поле у реки", inputs, r),
        )
        assertEquals(
            "Расчёт: По запасу топлива; Есть топлива: 400 л; Расход топлива: 15 л/га",
            AgroEngine.inputsText(FUEL, mapOf("mode" to "stock", "fuel" to "400", "rate" to "15")),
        )
        assertEquals(
            "Участок 1: 500 × 200 м; Участок 2: 2500 м²",
            AgroEngine.inputsText(AREA, mapOf("plots" to "2", "plot.1.length" to "500", "plot.1.width" to "200", "plot.2.area" to "2500")),
        )
    }

    @Test fun calculationRecordIsBuiltFromTheInputs() {
        val now = Instant.parse("2026-11-06T09:00:00Z")
        val c = AgroEngine.record(SEEDING, mapOf("area" to "10", "rate" to "200", "reserve" to " "), " Поле у реки ", "id-1", now)
        assertEquals(mapOf("area" to "10", "rate" to "200"), c.inputs, "empty fields are not stored")
        assertEquals("Поле у реки", c.title)
        assertEquals("seeding", c.kind)
        assertEquals(nb("2 000 кг"), c.lines.first().text)
        // Corrected: the result follows, the creation time stays.
        val later = now.plusSeconds(86_400)
        val fixed = AgroEngine.record(SEEDING, mapOf("area" to "20", "rate" to "200"), "Поле у реки", "id-1", later, over = c)
        assertEquals(nb("4 000 кг"), fixed.lines.first().text)
        assertEquals(now, fixed.createdAt)
        assertEquals(later, fixed.updatedAt)
        // An incomplete one is not saved.
        val e = assertFailsWith<AgroError.Invalid> { AgroEngine.record(FUEL, mapOf("area" to "10"), "", "id-2", now) }
        assertEquals("Заполните: Расход топлива", e.message)
    }

    @Test fun historyFilters() {
        val d1 = Instant.parse("2026-11-06T09:00:00Z")
        val d2 = d1.plusSeconds(86_400)
        val seeding = AgroEngine.record(SEEDING, mapOf("area" to "10", "rate" to "200"), "Поле у реки", "a", d1)
        val fuel = AgroEngine.record(FUEL, mapOf("area" to "10", "rate" to "15"), "", "b", d2)
        val all = listOf(seeding, fuel)
        assertEquals(listOf("b", "a"), AgroEngine.history(all).map { it.id }, "newest first")
        assertEquals(listOf("b"), AgroEngine.history(all, kind = FUEL).map { it.id })
        assertEquals(listOf("a"), AgroEngine.history(all, search = "РЕКИ").map { it.id })
        assertEquals(listOf("a"), AgroEngine.history(all, from = d1, to = d2).map { it.id })
    }
}
