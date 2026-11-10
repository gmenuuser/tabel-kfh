package ru.kfh.core.livestock

import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import ru.kfh.core.testing.assertEquals
import ru.kfh.core.testing.assertFailsWith
import ru.kfh.core.testing.assertFalse
import ru.kfh.core.testing.assertNotEquals
import ru.kfh.core.testing.assertNotNull
import ru.kfh.core.testing.assertNull
import ru.kfh.core.testing.assertTrue
import ru.kfh.core.ids.StableId
import ru.kfh.core.livestock.LivestockOperationKind.*
import ru.kfh.core.livestock.Species.*
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportPair
import ru.kfh.core.timesheet.at
import ru.kfh.core.timesheet.day

/**
 * Port of the domain tests of `TabelKFHTests/LivestockTests.swift`. Skipped (SwiftData / backup / PDF / XLSX
 * mechanics, nothing of it exists in the Android core): testBackupRoundTripKeepsTheHerd (BackupService),
 * testVersion2StoreOpensWithLivestock (SwiftData schema migration); the PDF and XLSX assertions inside
 * testMovementReport and testHerdAndSlaughterDocuments (renderers), where the same facts are asserted on the
 * document blocks instead.
 */
class LivestockTest {
    private val w = LivestockWorld()

    @Test fun defaultCategoriesAreMadeOnce() {
        assertEquals(listOf("Коровы", "Быки", "Телята", "Молодняк", "Другие"), LivestockRules.categories(w.books, CATTLE).map { it.name })
        assertEquals(listOf("Козы", "Козлы", "Козлята", "Молодняк", "Другие"), LivestockRules.categories(w.books, GOATS).map { it.name })
        assertEquals(listOf("Свиноматки", "Хряки", "Поросята", "Откормочные", "Другие"), LivestockRules.categories(w.books, PIGS).map { it.name })
        w.ensureDefaults()
        assertEquals(15, LivestockRules.categories(w.books).size)
        val own = LivestockRules.addCategory(" Нетели ", CATTLE, w.books, w.nextId(), w.clock)
        assertEquals("Нетели", own.name)
        w.categories = w.categories + own
        assertFailsWith<LivestockError.DuplicateCategory> { LivestockRules.addCategory("нетели", CATTLE, w.books, w.nextId(), w.clock) }
        assertEquals("Нетели", LivestockRules.categories(w.books, CATTLE).last().name)
        assertEquals(own, LivestockRules.checkCategoryDeletable(own, w.books))
        assertFailsWith<LivestockError.CategoryInUse>("default categories stay") {
            LivestockRules.checkCategoryDeletable(w.cat(CATTLE, "Коровы"), w.books)
        }
    }

    @Test fun ownCategoryGetsANewIdWhenAddedAgain() {
        val first = LivestockRules.addCategory("Нетели", CATTLE, w.books, "random-1", w.clock)
        assertNotEquals(StableId.livestockCategory("cattle", "Нетели"), first.id)
        // Deleted and added again: a new record with its own id.
        val again = LivestockRules.addCategory("Нетели", CATTLE, w.books, "random-2", w.clock)
        assertNotEquals(first.id, again.id)
        w.categories = w.categories + again
        assertEquals(1, w.categories.count { it.name == "Нетели" })
        assertFailsWith<LivestockError.DuplicateCategory>("the name still finds the duplicate") {
            LivestockRules.addCategory(" нетели", CATTLE, w.books, "random-3", w.clock)
        }
        assertEquals(StableId.livestockCategory("cattle", "Коровы"), w.cat(CATTLE, "Коровы").id)
    }

    @Test fun categoryNamesCollapseUnicodeWhitespace() {
        val c = LivestockRules.addCategory("Нетели   первой   тёлки", CATTLE, w.books, "x", w.clock)
        assertEquals("Нетели первой тёлки", c.name)
        assertFailsWith<LivestockError.Invalid> { LivestockRules.addCategory("   ", CATTLE, w.books, "y", w.clock) }
    }

    @Test fun categoryInUseCannotBeDeleted() {
        val own = LivestockRules.addCategory("Нетели", CATTLE, w.books, "own-1", w.clock)
        w.categories = w.categories + own
        w.record(PURCHASE, own, 2)
        assertFailsWith<LivestockError.CategoryInUse> { LivestockRules.checkCategoryDeletable(own, w.books) }
        val target = LivestockRules.addCategory("Тёлки", CATTLE, w.books, "own-2", w.clock)
        w.categories = w.categories + target
        w.record(TRANSFER, own, 1, target)
        assertFailsWith<LivestockError.CategoryInUse>("used as a transfer target") { LivestockRules.checkCategoryDeletable(target, w.books) }
    }

    @Test fun scenario1() {
        val cows = w.cat(CATTLE, "Коровы")
        w.record(INITIAL, cows, 20)
        w.record(SLAUGHTER, cows, 2)
        assertEquals(18, w.heads(cows))
        assertEquals(18, w.total(CATTLE))
    }

    @Test fun scenario2() {
        val goats = w.cat(GOATS, "Козы")
        val kids = w.cat(GOATS, "Козлята")
        w.record(INITIAL, goats, 15)
        w.record(BIRTH, kids, 3)
        w.record(SLAUGHTER, goats, 2)
        assertEquals(13, w.heads(goats))
        assertEquals(3, w.heads(kids))
        assertEquals(16, w.total(GOATS))
    }

    @Test fun scenario3() {
        val pigs = w.cat(PIGS, "Откормочные")
        w.record(INITIAL, pigs, 30)
        w.record(SALE, pigs, 5)
        w.record(SLAUGHTER, pigs, 3)
        assertEquals(22, w.total(PIGS))
        // A sale is not a slaughter.
        val p = LivestockRules.period(LivestockPeriod.DAY, w.clock, w.farm)
        assertEquals(listOf(3L), LivestockRules.slaughterSummary(w.books, p.start, p.end).map { it.heads })
    }

    @Test fun scenario4SlaughterAboveTheHerdIsRefused() {
        val cows = w.cat(CATTLE, "Коровы")
        w.record(INITIAL, cows, 10)
        val e = assertFailsWith<LivestockError.NotEnough> { w.record(SLAUGHTER, cows, 11) }
        assertEquals("Коровы", e.category)
        assertEquals(10, e.available)
        assertEquals(10, w.heads(cows))
        assertEquals(1, w.operations.size, "nothing was saved")
    }

    @Test fun notEnoughMessageIsVerbatim() {
        val cows = w.cat(CATTLE, "Коровы")
        w.record(INITIAL, cows, 10)
        val e = assertFailsWith<LivestockError.NotEnough> { w.record(SLAUGHTER, cows, 11) }
        assertEquals("Не хватает голов: в категории «Коровы» на 06.11.2026 было 10 голов.", e.message)
    }

    @Test fun scenario5CorrectingTheHeadsRecounts() {
        val bulls = w.cat(CATTLE, "Быки")
        w.record(INITIAL, bulls, 10)
        val o = w.record(SLAUGHTER, bulls, 2)
        assertEquals(8, w.heads(bulls))
        val d = LivestockRules.draftFor(o, w.books).copy(headsText = "1")
        w.update(o, d)
        assertEquals(9, w.heads(bulls), "only one head is written off")
        w.update(w.operations.first { it.id == o.id }, d)
        assertEquals(9, w.heads(bulls), "saving the correction again does not write off twice")
        assertEquals(2, w.operations.size)
    }

    @Test fun scenario6DeletingASlaughterRestoresTheHerd() {
        val sows = w.cat(PIGS, "Свиноматки")
        w.record(INITIAL, sows, 7)
        val o = w.record(SLAUGHTER, sows, 2) { it.copy(meatWeightText = "150") }
        assertEquals(5, w.heads(sows))
        assertEquals(1, w.slaughters.size)
        w.delete(o)
        assertEquals(7, w.heads(sows))
        assertEquals(0, w.slaughters.size, "the details go with it")
    }

    @Test fun secondSaveOfTheSameFormIsRefused() {
        val cows = w.cat(CATTLE, "Коровы")
        w.record(INITIAL, cows, 5)
        val d = w.draft(SLAUGHTER, cows, 1)
        w.apply(LivestockRules.save(d, w.books, w.clock, w.nextId()))
        assertFailsWith<LivestockError.AlreadySaved> { LivestockRules.save(d, w.books, w.clock, w.nextId()) }
        assertEquals(4, w.heads(cows))
    }

    @Test fun transferKeepsTheTotal() {
        val calves = w.cat(CATTLE, "Телята")
        val young = w.cat(CATTLE, "Молодняк")
        w.record(BIRTH, calves, 6)
        w.record(TRANSFER, calves, 4, young)
        assertEquals(2, w.heads(calves))
        assertEquals(4, w.heads(young))
        assertEquals(6, w.total(CATTLE))
        assertFailsWith<LivestockError.NotEnough> { w.record(TRANSFER, calves, 3, young) }
        assertFailsWith<LivestockError.Invalid>("same category") { w.record(TRANSFER, calves, 1, calves) }
    }

    /** A correction of the past may not leave a later decrease without animals. */
    @Test fun historyNeverGoesBelowZero() {
        val cows = w.cat(CATTLE, "Коровы")
        val purchase = w.record(PURCHASE, cows, 5)
        w.record(SLAUGHTER, cows, 4)
        assertFailsWith<LivestockError.NotEnough>("the slaughter relies on it") { LivestockRules.delete(purchase, w.books) }
        val d = LivestockRules.draftFor(purchase, w.books).copy(headsText = "3")
        assertFailsWith<LivestockError.NotEnough> { LivestockRules.update(purchase, d, w.books, w.clock) }
        assertEquals(1, w.heads(cows))
        // A slaughter dated before the animals came is refused too.
        val early = w.draft(SLAUGHTER, cows, 1) { it.copy(date = w.clock.minusSeconds(86_400)) }
        assertFailsWith<LivestockError.NotEnough> { LivestockRules.save(early, w.books, w.clock, w.nextId()) }
    }

    @Test fun slaughterWeightsAreOptionalAndSummed() {
        val cows = w.cat(CATTLE, "Коровы")
        val bulls = w.cat(CATTLE, "Быки")
        w.record(INITIAL, cows, 10)
        w.record(INITIAL, bulls, 4)
        w.record(SLAUGHTER, cows, 2) {
            it.copy(liveWeightText = "900,5", carcassWeightText = "520", meatWeightText = "410,25", reason = SlaughterReason.FORCED)
        }
        w.record(SLAUGHTER, cows, 1)
        w.record(SLAUGHTER, bulls, 1) { it.copy(meatWeightText = "300") }
        val p = LivestockRules.period(LivestockPeriod.MONTH, w.clock, w.farm)
        val rows = LivestockRules.slaughterSummary(w.books, p.start, p.end)
        assertEquals(listOf("Коровы", "Быки"), rows.map { it.name })
        assertEquals(3, rows[0].heads)
        assertEquals(2, rows[0].operations)
        assertEquals(900_500, rows[0].liveGrams)
        assertEquals(520_000, rows[0].carcassGrams)
        assertEquals(410_250, rows[0].meatGrams)
        assertEquals(300_000, rows[1].meatGrams)
        assertEquals("forced", w.slaughters.first { it.liveWeightGrams == 900_500L }.reason)
        var bad = LivestockDraft(id = "b", kind = SLAUGHTER, date = w.clock, categoryId = cows.id, headsText = "1", liveWeightText = "много")
        assertFalse(bad.isValid)
        bad = bad.copy(headsText = "1,5")
        assertTrue("Количество голов — целое число" in bad.validationErrors)
    }

    @Test fun draftValidationTexts() {
        val base = LivestockDraft(id = "d", kind = TRANSFER, date = w.clock)
        assertEquals(
            listOf("Выберите категорию", "Укажите количество голов", "Выберите, в какую категорию перевести"),
            base.validationErrors,
        )
        assertEquals(listOf("Количество голов — больше нуля"), base.copy(kind = SALE, categoryId = "c", headsText = "0").validationErrors)
        assertEquals(
            listOf("Слишком большое количество голов"),
            base.copy(kind = SALE, categoryId = "c", headsText = "100001").validationErrors,
        )
        assertEquals(
            listOf("Категории перевода совпадают"),
            base.copy(categoryId = "c", targetCategoryId = "c", headsText = "1").validationErrors,
        )
        assertEquals(
            listOf("Неверный вес мяса: число килограммов, например 250,5"),
            base.copy(kind = SLAUGHTER, categoryId = "c", headsText = "1", meatWeightText = "x").validationErrors,
        )
    }

    @Test fun movementReport() {
        val goats = w.cat(GOATS, "Козы")
        val kids = w.cat(GOATS, "Козлята")
        w.record(INITIAL, goats, 15)
        w.clock = w.farm.at(3, 9, month = 12)
        w.record(BIRTH, kids, 3)
        w.record(PURCHASE, goats, 2)
        w.record(SLAUGHTER, goats, 2)
        w.record(DEATH, kids, 1)
        w.record(TRANSFER, kids, 1, goats)
        w.record(OTHER, goats, 1)
        w.record(SALE, goats, 1)
        val p = LivestockRules.period(LivestockPeriod.MONTH, w.clock, w.farm)
        val rows = LivestockRules.movement(w.books, p.start, p.end)
        val g = rows.first { it.name == "Козы" }
        assertEquals(15, g.start)
        assertEquals(2, g.purchase)
        assertEquals(1, g.transferIn)
        assertEquals(2, g.slaughter)
        assertEquals(1, g.sale)
        assertEquals(1, g.other)
        assertEquals(14, g.end)
        val k = rows.first { it.name == "Козлята" }
        assertEquals(3, k.birth)
        assertEquals(1, k.death)
        assertEquals(1, k.transferOut)
        assertEquals(1, k.end)
        assertEquals(15, w.total(GOATS))

        val doc = LivestockReport.movement(rows, LivestockPeriod.MONTH, p.start, w.info, w.clock, w.farm)
        assertEquals("Движение_поголовья_2026-12", doc.fileName)
        assertTrue(ReportBlock.Title("ДВИЖЕНИЕ ПОГОЛОВЬЯ") in doc.blocks)
        assertTrue(ReportBlock.Text("Период: Декабрь 2026") in doc.blocks)
        val pairs = doc.blocks.filterIsInstance<ReportBlock.Pairs>().single().pairs
        assertTrue(pairs.any { it.label == "Остаток на конец периода" })
        assertEquals(ReportCell.count(15), pairs.last().value)
    }

    @Test fun movementRowsSkipCategoriesWithoutAnimalsAndMoves() {
        val goats = w.cat(GOATS, "Козы")
        assertTrue(LivestockRules.movement(w.books, w.clock.minusSeconds(3600), w.clock.plusSeconds(3600)).isEmpty())
        w.record(INITIAL, goats, 4)
        val rows = LivestockRules.movement(w.books, w.clock.plusSeconds(86_400), w.clock.plusSeconds(2 * 86_400))
        assertEquals(listOf("Козы"), rows.map { it.name })
        assertEquals(4, rows[0].start)
    }

    @Test fun herdAndSlaughterDocuments() {
        val cows = w.cat(CATTLE, "Коровы")
        w.record(INITIAL, cows, 20)
        w.record(SLAUGHTER, cows, 2) { it.copy(meatWeightText = "300") }
        val balances = LivestockRules.balances(w.books)
        val lines = LivestockRules.categories(w.books).map { LivestockReport.HerdLine(Species.fromWire(it.species), it.name, balances[it.id] ?: 0) }
        val herd = LivestockReport.herd(lines, w.farm.dayOf(w.clock), w.info, w.clock, w.farm)
        assertEquals("Поголовье_2026-11-06", herd.fileName)
        assertTrue(
            ReportBlock.Pairs(
                listOf(
                    ReportPair("КРС", ReportCell.count(18), false),
                    ReportPair("Козы", ReportCell.count(0), false),
                    ReportPair("Свиньи", ReportCell.count(0), false),
                    ReportPair("Всего животных", ReportCell.count(18), true),
                ),
            ) in herd.blocks,
        )
        val p = LivestockRules.period(LivestockPeriod.YEAR, w.clock, w.farm)
        val doc = LivestockReport.slaughter(
            LivestockRules.slaughterSummary(w.books, p.start, p.end), emptyList(), LivestockPeriod.YEAR, p.start, w.info, w.clock, w.farm,
        )
        assertEquals("Забой_2026", doc.fileName)
        val table = doc.blocks.filterIsInstance<ReportBlock.Table>().first().table
        assertTrue(ReportCell.tons(300_000) in table.rows.single(), "meat weight as a number")
    }

    @Test fun periodTitles() {
        val start = w.farm.startOf(day(1, 12))
        assertEquals("Декабрь 2026", LivestockReport.periodTitle(LivestockPeriod.MONTH, start, w.farm))
        assertEquals("2026 год", LivestockReport.periodTitle(LivestockPeriod.YEAR, start, w.farm))
        assertEquals("01.12.2026", LivestockReport.periodTitle(LivestockPeriod.DAY, start, w.farm))
        val m = LivestockRules.period(LivestockPeriod.MONTH, start, w.farm)
        assertEquals(w.farm.startOf(LocalDate.of(2027, 1, 1)), m.end)
        assertEquals(start, m.start)
    }

    @Test fun defaultCategoryIdsAreStable() {
        val other = LivestockWorld()
        val mine = w.categories.map { it.id }
        assertEquals(mine.toSet(), other.categories.map { it.id }.toSet())
        assertEquals(15, mine.toSet().size)
        assertEquals(StableId.livestockCategory("cattle", "Коровы"), w.cat(CATTLE, "Коровы").id)
        assertEquals(StableId.livestockSettings, w.settings?.id)
        // Made once: with the settings in the store nothing more is made.
        val again = LivestockRules.ensureDefaults(w.books, w.clock)
        assertNull(again.settings)
        assertTrue(again.categories.isEmpty())
    }

    // ---- The ledger and the server's herd rule ----

    private fun mv(id: String, kind: LivestockOperationKind, at: Instant, cat: String, n: Long, to: String? = null, created: Instant = at) =
        HerdMove(id, kind, at, created, cat, to, n)

    @Test fun firstShortageFindsTheFirstDayBelowZero() {
        val t0 = Instant.parse("2026-11-01T00:00:00Z")
        val ok = listOf(mv("1", INITIAL, t0, "a", 5), mv("2", SLAUGHTER, t0.plusSeconds(60), "a", 5))
        assertNull(LivestockLedger.firstShortage(ok))
        // An addition moved to a later date than the decrease leaves the decrease without animals.
        val moved = listOf(mv("1", INITIAL, t0.plusSeconds(120), "a", 5), mv("2", SLAUGHTER, t0.plusSeconds(60), "a", 5))
        val s = assertNotNull(LivestockLedger.firstShortage(moved))
        assertEquals("a", s.categoryId)
        assertEquals(t0.plusSeconds(60), s.date)
        assertEquals(0, s.heads)
    }

    @Test fun sameMomentIsOrderedByCreation() {
        val t = Instant.parse("2026-11-01T00:00:00Z")
        val inOrder = listOf(
            mv("2", SLAUGHTER, t, "a", 3, created = t.plusSeconds(1)),
            mv("1", INITIAL, t, "a", 3, created = t),
        )
        assertNull(LivestockLedger.firstShortage(inOrder))
    }

    @Test fun transferShortageIsFoundInTheSource() {
        val t = Instant.parse("2026-11-01T00:00:00Z")
        val moves = listOf(mv("1", BIRTH, t, "a", 2), mv("2", TRANSFER, t.plusSeconds(1), "a", 3, to = "b"))
        val s = assertNotNull(LivestockLedger.firstShortage(moves))
        assertEquals("a", s.categoryId)
        assertEquals(2, s.heads)
        assertEquals(mapOf("a" to -1L, "b" to 3L), LivestockLedger.balances(moves))
    }

    @Test fun balancesBeforeAMoment() {
        val t = Instant.parse("2026-11-01T00:00:00Z")
        val moves = listOf(mv("1", INITIAL, t, "a", 5), mv("2", SALE, t.plusSeconds(60), "a", 2))
        assertEquals(mapOf("a" to 5L), LivestockLedger.balances(moves, before = t.plusSeconds(60)))
        assertEquals(mapOf("a" to 3L), LivestockLedger.balances(moves))
    }

    @Test fun serverErrorsMirrorTheServer() {
        val ok = w.record(INITIAL, w.cat(CATTLE, "Коровы"), 1)
        assertTrue(LivestockRules.serverErrors(ok).isEmpty())
        assertEquals(listOf("Количество голов — целое число больше нуля"), LivestockRules.serverErrors(ok.copy(heads = 0)))
        assertEquals(listOf("Перевод — в другую категорию"), LivestockRules.serverErrors(ok.copy(kind = "transfer")))
        assertEquals(listOf("Неизвестный тип операции"), LivestockRules.serverErrors(ok.copy(kind = "zzz")))
    }

    @Test fun textHelpers() {
        assertEquals("1 голова", LivestockText.heads(1))
        assertEquals("2 головы", LivestockText.heads(2))
        assertEquals("5 голов", LivestockText.heads(5))
        assertEquals("—", LivestockText.kilograms(null))
        assertEquals("120,5 кг", LivestockText.kilograms(120_500))
        assertEquals("КРС", CATTLE.title)
        assertEquals("Прочее списание", OTHER.title)
        assertEquals("На мясо", SlaughterReason.MEAT.title)
        assertEquals("Вынужденный забой", SlaughterReason.FORCED.title)
        assertEquals("Другое", SlaughterReason.OTHER.title)
    }
}
