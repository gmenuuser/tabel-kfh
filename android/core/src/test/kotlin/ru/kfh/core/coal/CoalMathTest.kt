package ru.kfh.core.coal

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver

private const val NB = " "

/** Port of CoalMathTests.swift (plus the texts of CoalErrors.swift). */
class CoalMathTest {
    @Test fun coalAmountIsTonsTimesPrice() {
        assertEquals(1_300_000L, CoalMath.coalAmount(2000, 650_000))
        assertEquals(650_000L, CoalMath.coalAmount(1000, 650_000))
        assertEquals(325_000L, CoalMath.coalAmount(500, 650_000))
        assertEquals(975_000L, CoalMath.coalAmount(1500, 650_000))
        assertEquals(1_625_000L, CoalMath.coalAmount(2500, 650_000))
        assertEquals(0L, CoalMath.coalAmount(0, 650_000))
    }

    @Test fun coalAmountRoundsHalfUpToKopeck() {
        assertEquals(650L, CoalMath.coalAmount(1, 650_005))
        assertEquals(0L, CoalMath.coalAmount(3, 150))
        assertEquals(1L, CoalMath.coalAmount(5, 100))
    }

    @Test fun parseTons() {
        assertEquals(2000L, CoalMath.parseTons("2"))
        assertEquals(2500L, CoalMath.parseTons("2,5"))
        assertEquals(500L, CoalMath.parseTons("0.5"))
        assertEquals(1250L, CoalMath.parseTons(" 1,25 "))
        assertEquals(125L, CoalMath.parseTons("0,125"))
        assertEquals(500L, CoalMath.parseTons(",5"))
        assertEquals(3000L, CoalMath.parseTons("3,"))
        assertNull(CoalMath.parseTons(""))
        assertNull(CoalMath.parseTons("-1"))
        assertNull(CoalMath.parseTons("1,2345"))
        assertNull(CoalMath.parseTons("два"))
        assertNull(CoalMath.parseTons("1,2,3"))
        assertNull(CoalMath.parseTons(","))
    }

    @Test fun parseRubles() {
        assertEquals(650_000L, CoalMath.parseRubles("6500"))
        assertEquals(650_000L, CoalMath.parseRubles("6 500"))
        assertEquals(650_050L, CoalMath.parseRubles("6${NB}500,5"))
        assertEquals(120_000L, CoalMath.parseRubles("1200.00"))
        assertEquals(0L, CoalMath.parseRubles("0"))
        assertNull(CoalMath.parseRubles("12,345"))
        assertNull(CoalMath.parseRubles("-5"))
        assertNull(CoalMath.parseRubles(""))
    }

    @Test fun texts() {
        assertEquals("14${NB}000${NB}₽", CoalMath.rubles(1_400_000))
        assertEquals("16${NB}250,50${NB}₽", CoalMath.rubles(1_625_050))
        assertEquals("1${NB}000${NB}000${NB}₽", CoalMath.rubles(100_000_000))
        assertEquals("−1${NB}200${NB}₽", CoalMath.rubles(-120_000))
        assertEquals("0${NB}₽", CoalMath.rubles(0))
        assertEquals("6500", CoalMath.rublesField(650_000))
        assertEquals("1200,50", CoalMath.rublesField(120_050))
        assertEquals("2", CoalMath.tons(2000))
        assertEquals("2,5", CoalMath.tons(2500))
        assertEquals("0,125", CoalMath.tons(125))
        assertEquals("1${NB}250", CoalMath.tons(1_250_000))
        assertEquals("0,5${NB}т", CoalMath.tonsText(500))
        assertEquals("16250.50", CoalMath.spreadsheetRubles(1_625_050))
        assertEquals("14000", CoalMath.spreadsheetRubles(1_400_000))
        assertEquals("2.5", CoalMath.spreadsheetTons(2500))
        assertEquals("1250", CoalMath.spreadsheetTons(1_250_000))
    }

    @Test fun saleListTotalAndAdjustment() {
        val sale = CoalSale(
            id = "s", saleDate = Instant.EPOCH, kilograms = 2000, locationNameSnapshot = "Новоцурухайтуй",
            coalPricePerTonSnapshot = 650_000, deliveryPriceSnapshot = 100_000, coalAmount = 1_300_000,
            deliveryPrice = 100_000, totalAmount = 1_400_000, vehicleType = "own",
        )
        assertEquals(1_400_000L, sale.totalAmount)
        assertEquals(1_400_000L, sale.listTotal)
        assertFalse(sale.isPriceAdjusted)
        val adjusted = sale.copy(coalAmount = 1_250_000, totalAmount = 1_250_000 + sale.deliveryPrice)
        assertTrue(adjusted.isPriceAdjusted)
        assertEquals(1_400_000L, adjusted.listTotal)
    }

    @Test fun driverNameParts() {
        val d = Driver(id = "d", lastName = " мунгалов", firstName = "Сергей", middleName = "")
        assertEquals("М", d.letter)
        assertEquals("мунгалов Сергей", d.fullName)
        val location = DeliveryLocation(id = "l", name = "Приаргунск", deliveryPriceKopecks = 120_000)
        assertEquals(120_000L, location.effectiveDriverPaymentKopecks)
        assertEquals(100_000L, location.copy(driverPaymentKopecks = 100_000).effectiveDriverPaymentKopecks)
    }

    @Test fun russianPlurals() {
        assertEquals("1 доставка", CoalText.deliveries(1))
        assertEquals("2 доставки", CoalText.deliveries(2))
        assertEquals("5 доставок", CoalText.deliveries(5))
        assertEquals("11 доставок", CoalText.deliveries(11))
        assertEquals("21 доставка", CoalText.deliveries(21))
        assertEquals("5 продаж", CoalText.sales(5))
    }

    @Test fun errorTextsAreVerbatim() {
        assertEquals("Рабочий день 06.11.2026 завершён. Чтобы изменить его продажи, откройте день для редактирования.",
            CoalError.DayClosed(LocalDate.of(2026, 11, 6)).message)
        assertEquals("Этот рабочий день уже завершён", CoalError.DayAlreadyClosed.message)
        assertEquals("Этот рабочий день не завершён", CoalError.DayNotClosed.message)
        assertEquals("Эта продажа уже сохранена", CoalError.AlreadySaved.message)
        assertEquals("Водитель не найден", CoalError.DriverNotFound.message)
        assertEquals("Водитель в архиве. Верните его из архива, чтобы записывать на него доставки.", CoalError.DriverArchived.message)
        assertEquals("У водителя есть доставки или выплаты, поэтому его можно только убрать в архив. История сохранится.",
            CoalError.DriverHasHistory.message)
        assertEquals("Населённый пункт «Луг» уже есть", CoalError.DuplicateLocation("Луг").message)
        assertEquals("Долга перед водителем нет", CoalError.NothingToPay.message)
        assertEquals("Сумма больше долга перед водителем (1${NB}000${NB}₽)", CoalError.PaymentTooLarge(100_000).message)
        assertEquals("а\nб", CoalError.Invalid(listOf("а", "б")).message)
    }
}
