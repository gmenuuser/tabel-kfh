package ru.kfh.core.coal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.ids.StableId

private fun CoalWorld.balance(d: ru.kfh.core.model.Driver) = CoalReport.balance(d.id, sales, payments)

private fun assertError(expected: CoalError, block: () -> Unit) {
    val e = assertThrows(CoalError::class.java) { block() }
    assertEquals(expected, e)
}

/**
 * Port of the domain part of CoalServiceTests.swift: the services become pure functions over the record lists.
 * Skipped (SwiftData / persistence mechanics, covered by the later storage task): the in-memory ModelContext
 * setup, `context.fetchCount` assertions and CoalSettingsService.current() fetch-or-create.
 */
class CoalRulesTest {
    private val w = CoalWorld()
    private val mungalov = w.addDriver("Мунгалов")
    private val kaydolov = w.addDriver("Кайдолов")

    /** Мунгалов 1000 + 1200 + 1000, Кайдолов 1200 + 1000, and one own-vehicle sale. */
    private fun sellScenarioDay() {
        w.sell("2", "Новоцурухайтуй", mungalov)
        w.sell("1,5", "Приаргунск", mungalov)
        w.sell("0,5", "Новоцурухайтуй", mungalov)
        w.sell("1", "Приаргунск", kaydolov)
        w.sell("2,5", "Новоцурухайтуй", kaydolov)
        w.sell("1", "Приаргунск", null)
    }

    @Test fun defaultsAreSeededOnce() {
        assertEquals(650_000L, w.settings.pricePerTonKopecks)
        assertEquals(listOf("Новоцурухайтуй 100000", "Приаргунск 120000"),
            CoalRules.activeLocations(w.locations).map { "${it.name} ${it.deliveryPriceKopecks}" })
        // Anything already stored means no seeding: a removed village is not seeded again.
        val removed = w.locations.map { if (it.name == "Приаргунск") CoalRules.removeLocation(it) else it }
        assertTrue(CoalRules.seedLocations(removed, w.clock).isEmpty())
        assertEquals(1, CoalRules.activeLocations(removed).size)
    }

    @Test fun scenario1HiredSaleToNovotsurukhaituy() {
        val s = w.sell("2", "Новоцурухайтуй", mungalov)
        assertEquals(1_300_000L, s.coalAmount)
        assertEquals(100_000L, s.deliveryPrice)
        assertEquals(1_400_000L, s.totalAmount)
        assertEquals(100_000L, s.driverPaymentAmount)
        assertEquals("Мунгалов", s.driverNameSnapshot)
        assertEquals(VehicleType.HIRED, s.vehicleType)
        assertEquals(100_000L, w.balance(mungalov).debt)
    }

    @Test fun scenario2OwnVehicleSaleToPriargunsk() {
        val s = w.sell("1", "Приаргунск", null)
        assertEquals(650_000L, s.coalAmount)
        assertEquals(120_000L, s.deliveryPrice)
        assertEquals(770_000L, s.totalAmount)
        assertEquals(0L, s.driverPaymentAmount)
        assertNull(s.driverId)
        val report = w.dayReport()
        assertEquals(120_000L, report.totals.ownVehicleDeliveryRevenue)
        assertEquals(0L, report.totals.driverPayments)
        assertTrue("own vehicle owes nobody", report.drivers.isEmpty())
    }

    @Test fun scenarios3to5DriverAccruals() {
        sellScenarioDay()
        val report = w.dayReport()
        val m = report.drivers.first { it.driverId == mungalov.id }
        assertEquals(3, m.deliveries)
        assertEquals(320_000L, m.accrued)
        assertEquals(320_000L, m.debtAfter)
        val k = report.drivers.first { it.driverId == kaydolov.id }
        assertEquals(2, k.deliveries)
        assertEquals(220_000L, k.accrued)
        assertEquals(540_000L, report.totals.driverPayments)
        assertEquals(540_000L, report.totalDebtAfter)
        assertEquals(listOf("Кайдолов", "Мунгалов"), report.drivers.map { it.name })

        val t = report.totals
        assertEquals(6, t.salesCount)
        assertEquals(8500L, t.kilograms)
        assertEquals(5_525_000L, t.coalAmount)
        assertEquals(660_000L, t.deliveryAmount)
        assertEquals(6_185_000L, t.revenue)
        assertEquals(120_000L, t.ownVehicleDeliveryRevenue)
        assertEquals(6_185_000L - 540_000L, t.remainderAfterDrivers)
        assertEquals(5, t.hiredDeliveries)
        assertEquals(1, t.ownDeliveries)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), report.rows.map { it.number })
        assertEquals(listOf("Новоцурухайтуй", "Приаргунск"), report.locations.map { it.name })
        assertEquals(listOf(3, 3), report.locations.map { it.salesCount })
    }

    @Test fun scenario6PartialPaymentLeavesDebtForNextDay() {
        sellScenarioDay()
        w.pay(mungalov, 200_000, comment = "наличными")
        assertEquals(120_000L, w.balance(mungalov).debt)
        assertEquals(200_000L, w.balance(mungalov).paid)
        val m = w.dayReport().drivers.first { it.driverId == mungalov.id }
        assertEquals(200_000L, m.paid)
        assertEquals(120_000L, m.debtAfter)

        w.clock = w.clock.plusSeconds(24 * 3600)
        w.sell("1", "Новоцурухайтуй", mungalov)
        val next = w.dayReport()
        val m2 = next.drivers.first { it.driverId == mungalov.id }
        assertEquals(120_000L, m2.debtBefore)
        assertEquals(100_000L, m2.accrued)
        assertEquals(0L, m2.paid)
        assertEquals(220_000L, m2.debtAfter)
        val k2 = next.drivers.first { it.driverId == kaydolov.id }
        assertEquals(0, k2.deliveries)
        assertEquals(220_000L, k2.debtAfter)
    }

    @Test fun scenario7ClosingTheDayPaysNothingAndCountsOnce() {
        sellScenarioDay()
        val day = w.close()
        assertTrue(day.isClosed)
        assertEquals(w.clock, day.closedAt)
        assertEquals(6_185_000L, day.totalRevenue)
        assertEquals(540_000L, day.totalDriverPayments)
        assertEquals(120_000L, day.ownVehicleDeliveryRevenue)
        assertEquals(8500L, day.totalKilograms)
        assertEquals("closing pays nothing", 0, w.payments.size)
        assertEquals(6, w.sales.size)
        repeat(3) {
            val report = w.dayReport()
            assertEquals("reading the history again accrues nothing", 540_000L, report.totalDebtAfter)
            assertEquals(w.clock, report.closedAt)
        }
        assertEquals(320_000L, w.balance(mungalov).debt)
        assertEquals(1, CoalRules.closedDays(w.workDays).size)
        assertError(CoalError.DayAlreadyClosed) { w.close() }
    }

    @Test fun closedDayLocksSalesUntilReopened() {
        val s = w.sell("2", "Новоцурухайтуй", mungalov)
        w.close()
        val edit = CoalSaleDraft.from(s).copy(tonsText = "3")
        assertError(CoalError.DayClosed(w.today)) { w.update(s, edit) }
        assertThrows(CoalError::class.java) { CoalRules.checkDeletable(s, w.books) }
        assertThrows(CoalError::class.java) { w.sell("1", "Приаргунск", null) }
        assertEquals(2000L, w.sales.single().kilograms)

        w.reopen()
        val updated = w.update(s, edit)
        assertEquals(1_950_000L, updated.coalAmount)
        assertEquals(2_050_000L, w.dayReport().totals.revenue)
        val closed = w.close()
        assertEquals("closing again stores the new totals", 2_050_000L, closed.totalRevenue)
        assertEquals(1, w.workDays.size)
    }

    @Test fun movingASaleIntoAClosedDayIsRefused() {
        val yesterday = w.today.minusDays(1)
        w.close(yesterday)
        val s = w.sell("1", "Приаргунск", null)
        val edit = CoalSaleDraft.from(s).copy(saleDate = w.farm.startOf(yesterday).plusSeconds(10 * 3600))
        assertError(CoalError.DayClosed(yesterday)) { w.update(s, edit) }
    }

    @Test fun editingAPastSaleRecomputesTheDebt() {
        var s = w.sell("2", "Новоцурухайтуй", mungalov)
        w.pay(mungalov, 50_000)
        var edit = CoalSaleDraft.from(s).chooseLocation(w.location("Приаргунск"))
        s = w.update(s, edit)
        assertEquals(70_000L, w.balance(mungalov).debt)
        edit = edit.chooseDriver(kaydolov)
        s = w.update(s, edit)
        assertEquals("the payment stays; the delivery moved", -50_000L, w.balance(mungalov).debt)
        assertEquals(120_000L, w.balance(kaydolov).debt)
        edit = edit.copy(vehicle = VehicleType.OWN)
        s = w.update(s, edit)
        assertEquals(0L, w.balance(kaydolov).debt)
        assertNull(s.driverId)
        assertEquals(0L, s.driverPaymentAmount)
        val other = w.sell("1", "Приаргунск", kaydolov)
        w.sales = w.sales.filter { it.id != CoalRules.checkDeletable(other, w.books).id }
        assertEquals(0L, w.balance(kaydolov).debt)
    }

    @Test fun priceChangeKeepsSavedSales() {
        val old = w.sell("2", "Новоцурухайтуй", mungalov)
        w.settings = CoalRules.setPricePerTon(w.settings, 700_000, w.clock)
        w.locations = w.locations.map {
            if (it.name == "Новоцурухайтуй") CoalRules.updateLocation(it, w.locations, "Новоцурухайтуй", 150_000, null) else it
        }
        assertEquals(1_400_000L, old.totalAmount)
        assertEquals(650_000L, old.coalPricePerTonSnapshot)
        assertFalse(old.isPriceAdjusted)
        val new = w.sell("2", "Новоцурухайтуй", mungalov)
        assertEquals(1_550_000L, new.totalAmount)
        // Editing the old sale keeps its own prices.
        val edited = w.update(old, CoalSaleDraft.from(old).copy(comment = "позвонить"))
        assertEquals(1_400_000L, edited.totalAmount)
        assertEquals("позвонить", edited.comment)
    }

    @Test fun manualCorrectionKeepsListAndActualAmounts() {
        var d = w.draft("2", "Новоцурухайтуй", mungalov).startManual()
        assertEquals("13000", d.manualCoalText)
        d = d.copy(manualCoalText = "12500")
        assertEquals(1_350_000L, d.total)
        val s = CoalRules.saveSale(d, w.books, w.clock)
        assertEquals(1_250_000L, s.coalAmount)
        assertEquals(1_350_000L, s.totalAmount)
        assertEquals(1_400_000L, s.listTotal)
        assertTrue(s.isPriceAdjusted)
        assertEquals("the driver's pay does not follow the discount", 100_000L, s.driverPaymentAmount)
        w.sales = listOf(s)
        assertEquals(1_400_000L, w.dayReport().rows.first().listTotal)
        val reopened = CoalSaleDraft.from(s)
        assertTrue(reopened.isManual)
        assertEquals(1_350_000L, reopened.total)
    }

    @Test fun separateDriverPayPerVillage() {
        w.locations = w.locations.map {
            if (it.name == "Приаргунск") CoalRules.updateLocation(it, w.locations, "Приаргунск", 120_000, 100_000) else it
        }
        val s = w.sell("1", "Приаргунск", kaydolov)
        assertEquals(120_000L, s.deliveryPrice)
        assertEquals(100_000L, s.driverPaymentAmount)
        val report = w.dayReport()
        assertEquals(100_000L, report.totals.driverPayments)
        assertEquals(670_000L, report.totals.remainderAfterDrivers)
    }

    @Test fun validation() {
        var d = CoalRules.newDraft("x", w.clock, w.settings.pricePerTonKopecks)
        assertEquals(listOf("Укажите количество угля", "Выберите населённый пункт", "Выберите водителя наёмной машины",
            "Укажите оплату водителю, например 1000"), d.validationErrors)
        d = d.copy(tonsText = "0").chooseLocation(w.location("Приаргунск"))
        assertTrue(d.validationErrors.contains("Количество угля должно быть больше нуля"))
        d = d.copy(tonsText = "1,5")
        assertEquals(listOf("Выберите водителя наёмной машины"), d.validationErrors)
        assertThrows(CoalError.Invalid::class.java) { CoalRules.saveSale(d, w.books, w.clock) }
        d = d.copy(vehicle = VehicleType.OWN)
        assertTrue("own vehicle needs no driver", d.isValid)
        d = d.copy(tonsText = "101")
        assertFalse(d.isValid)
        assertTrue(d.validationErrors.contains("Не больше 100 тонн в одной продаже"))
        assertEquals(listOf("Количество угля — число, например 1,5"),
            d.copy(tonsText = "abc", vehicle = VehicleType.OWN).validationErrors)
    }

    @Test fun secondSaveOfTheSameFormIsRefused() {
        val d = w.draft("1", "Приаргунск", null)
        w.sales = listOf(CoalRules.saveSale(d, w.books, w.clock))
        assertError(CoalError.AlreadySaved) { CoalRules.saveSale(d, w.books, w.clock) }
        assertEquals(1, w.sales.size)
    }

    @Test fun paymentRules() {
        assertError(CoalError.NothingToPay) { w.pay(mungalov, 100) }
        w.sell("2", "Новоцурухайтуй", mungalov)
        assertThrows(CoalError.Invalid::class.java) { w.pay(mungalov, 0) }
        assertError(CoalError.PaymentTooLarge(100_000)) { w.pay(mungalov, 100_001) }
        w.pay(mungalov, 100_000, id = "pay-1")
        assertError(CoalError.AlreadySaved) { w.pay(mungalov, 1, id = "pay-1") }
        assertEquals(0L, w.balance(mungalov).debt)
        w.payments = emptyList()
        assertEquals("deleting a payment makes the debt grow back", 100_000L, w.balance(mungalov).debt)
    }

    @Test fun archivedDriverKeepsHistoryAndLeavesThePicker() {
        w.sell("2", "Новоцурухайтуй", mungalov)
        assertError(CoalError.DriverHasHistory) { CoalRules.checkDeletable(mungalov, w.books) }
        w.drivers = w.drivers.map { if (it.id == mungalov.id) CoalRules.archive(it) else it }
        assertEquals(listOf("Кайдолов"), CoalRules.activeDrivers(w.drivers).map { it.lastName })
        assertEquals(1, CoalReport.salesOf(mungalov.id, w.sales).size)
        assertEquals(100_000L, w.balance(mungalov).debt)
        val archived = w.drivers.first { it.id == mungalov.id }
        assertError(CoalError.DriverArchived) { w.sell("1", "Приаргунск", archived) }
        w.drivers = w.drivers.map { if (it.id == mungalov.id) CoalRules.restore(it) else it }
        assertEquals(2, CoalRules.activeDrivers(w.drivers).size)
        // A driver without history may be deleted.
        val fresh = w.addDriver("Петров")
        CoalRules.checkDeletable(fresh, w.books)
        w.drivers = w.drivers.filter { it.id != fresh.id }
        assertEquals(2, CoalRules.allDrivers(w.drivers).size)
    }

    @Test fun archivedDriverIsAcceptedOnASaleThatAlreadyHadThem() {
        val s = w.sell("2", "Новоцурухайтуй", mungalov)
        w.drivers = w.drivers.map { if (it.id == mungalov.id) CoalRules.archive(it) else it }
        val updated = w.update(s, CoalSaleDraft.from(s).copy(comment = "ещё"))
        assertEquals("ещё", updated.comment)
    }

    @Test fun renamingADriverRenamesTheirSales() {
        val s = w.sell("2", "Новоцурухайтуй", mungalov)
        w.pay(mungalov, 10_000)
        val change = CoalRules.updateDriver(mungalov, DriverDraft.from(mungalov).copy(firstName = " Сергей "), w.books)
        assertEquals("Мунгалов Сергей", change.sales.single { it.id == s.id }.driverNameSnapshot)
        assertEquals("Мунгалов Сергей", change.payments.single().driverNameSnapshot)
        assertEquals("М", change.driver.letter)
        assertThrows(CoalError.Invalid::class.java) { CoalRules.createDriver(DriverDraft(lastName = "  "), "z", w.clock) }
        assertEquals(listOf("Укажите фамилию водителя"), DriverDraft(lastName = " ").validationErrors)
    }

    @Test fun driverSearch() {
        w.addDriver("Мусин")
        assertEquals(listOf("Мунгалов", "Мусин"), CoalRules.activeDrivers(w.drivers, "му").map { it.lastName })
        assertEquals(listOf("Кайдолов"), CoalRules.activeDrivers(w.drivers, "КАЙ").map { it.lastName })
        assertEquals("matches the start of a name", 0, CoalRules.activeDrivers(w.drivers, "лов").size)
    }

    @Test fun locations() {
        var all = w.locations
        all = all + CoalRules.addLocation(all, " Забайкальск ", 150_000, 140_000, w.clock)
        assertEquals(listOf("Забайкальск", "Новоцурухайтуй", "Приаргунск"), CoalRules.activeLocations(all).map { it.name })
        assertError(CoalError.DuplicateLocation("приаргунск")) { CoalRules.addLocation(all, "приаргунск", 1, null, w.clock) }
        val sale = run { w.locations = all; w.sell("1", "Приаргунск", null) }
        all = all.map { if (it.name == "Приаргунск") CoalRules.removeLocation(it) else it }
        assertEquals(2, CoalRules.activeLocations(all).size)
        assertEquals("the sale keeps the name", "Приаргунск", sale.locationNameSnapshot)
        val back = CoalRules.addLocation(all, "Приаргунск", 130_000, null, w.clock)
        assertTrue(back.isActive)
        assertEquals(130_000L, back.deliveryPriceKopecks)
        all = all.map { if (it.id == back.id) back else it }
        assertEquals("a hidden village is shown again, not duplicated", 3, all.size)
        assertEquals(3, CoalRules.activeLocations(all).size)
        assertThrows(CoalError.Invalid::class.java) { CoalRules.setPricePerTon(w.settings, 0, w.clock) }
    }

    @Test fun workDayIdIsStable() {
        sellScenarioDay()
        val day = w.close()
        assertEquals(StableId.coalWorkDay(w.today), day.id)
        assertEquals(w.farm.startOf(w.today), day.date)
    }

    @Test fun locationIdsAreStable() {
        assertEquals(StableId.deliveryLocation("Приаргунск"), w.location("Приаргунск").id)
        val added = CoalRules.addLocation(w.locations, "Луговое", 90_000, null, w.clock)
        assertEquals(StableId.deliveryLocation("Луговое"), added.id)
    }

    @Test fun sameDeliveryIsNotAccruedTwice() {
        val d = w.draft("2", "Новоцурухайтуй", mungalov)
        w.sales = listOf(CoalRules.saveSale(d, w.books, w.clock))
        assertError(CoalError.AlreadySaved) { CoalRules.saveSale(d, w.books, w.clock) }
        assertEquals(100_000L, w.balance(mungalov).debt)
    }

    /** The checks of `server/src/entities.ts` that must also hold before a sale or payment is written. */
    @Test fun serverChecksHoldClientSide() {
        val hired = w.sell("2", "Новоцурухайтуй", mungalov)
        val own = w.sell("1", "Приаргунск", null)
        assertEquals(emptyList<String>(), CoalRules.serverErrors(hired))
        assertEquals(emptyList<String>(), CoalRules.serverErrors(own))
        assertEquals(hired.coalAmount + hired.deliveryPrice, hired.totalAmount)
        assertEquals(listOf("Итого не равно углю и доставке"), CoalRules.serverErrors(hired.copy(totalAmount = 1)))
        assertEquals(listOf("У наёмной машины должен быть водитель"), CoalRules.serverErrors(hired.copy(driverId = null)))
        assertEquals(listOf("Собственная машина не создаёт долга водителю"),
            CoalRules.serverErrors(own.copy(driverPaymentAmount = 5)))
        assertEquals(listOf("Количество угля — целое число килограммов больше нуля"),
            CoalRules.serverErrors(hired.copy(kilograms = 0)))
        assertEquals(listOf("Неизвестный тип машины"), CoalRules.serverErrors(hired.copy(vehicleType = "x")))
        val p = w.pay(mungalov, 1000)
        assertEquals(emptyList<String>(), CoalRules.serverErrors(p))
        assertEquals(listOf("Сумма выплаты — целое число копеек больше нуля"), CoalRules.serverErrors(p.copy(amount = 0)))
        assertEquals(listOf("Не указан водитель"), CoalRules.serverErrors(p.copy(driverId = "")))
    }

    /** `docs/coal-spec.md` §17, the seven examples of the owner. */
    @Test fun ownerSpecExamples() {
        // 1. 2 т Новоцурухайтуй, Мунгалов: 13 000 / 1 000 / 14 000, Мунгалову 1 000.
        val s1 = w.sell("2", "Новоцурухайтуй", mungalov)
        assertEquals(1_300_000L, s1.coalAmount)
        assertEquals(100_000L, s1.deliveryPrice)
        assertEquals(1_400_000L, s1.totalAmount)
        assertEquals(100_000L, s1.driverPaymentAmount)
        // 2. 1 т Приаргунск, своя: 6 500 / 1 200 / 7 700, водителю 0, своя доставка 1 200.
        val s2 = w.sell("1", "Приаргунск", null)
        assertEquals(650_000L, s2.coalAmount)
        assertEquals(120_000L, s2.deliveryPrice)
        assertEquals(770_000L, s2.totalAmount)
        assertEquals(0L, s2.driverPaymentAmount)
        assertEquals(120_000L, w.dayReport().totals.ownVehicleDeliveryRevenue)
        // 3. Мунгалов 3 200.  4. Кайдолов 2 200.  5. Всего 5 400.
        w.sell("1,5", "Приаргунск", mungalov)
        w.sell("0,5", "Новоцурухайтуй", mungalov)
        w.sell("1", "Приаргунск", kaydolov)
        w.sell("2,5", "Новоцурухайтуй", kaydolov)
        val report = w.dayReport()
        assertEquals(320_000L, report.drivers.first { it.driverId == mungalov.id }.accrued)
        assertEquals(220_000L, report.drivers.first { it.driverId == kaydolov.id }.accrued)
        assertEquals(540_000L, report.totals.driverPayments)
        // 6. Выплатили Мунгалову 2 000 из 3 200 → долг 1 200.
        w.pay(mungalov, 200_000)
        assertEquals(120_000L, w.balance(mungalov).debt)
        // 7. День закрыт без выплат: продажи и отчёт есть, долг сохранён, повторного начисления нет.
        val before = w.balance(mungalov).debt + w.balance(kaydolov).debt
        w.close()
        assertEquals(1, w.payments.size)
        assertEquals(6, w.dayReport().rows.size)
        assertEquals(before, w.balance(mungalov).debt + w.balance(kaydolov).debt)
        assertEquals(340_000L, before)
        assertEquals(before, w.dayReport().totalDebtAfter)
    }
}
