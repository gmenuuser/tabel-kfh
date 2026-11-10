package ru.kfh.core.coal

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kfh.core.timesheet.day

/** Port of testMonthReport and the report parts of CoalServiceTests.swift. */
class CoalReportTest {
    private val w = CoalWorld()
    private val mungalov = w.addDriver("Мунгалов")
    private val kaydolov = w.addDriver("Кайдолов")

    @Test fun monthReport() {
        w.sell("2", "Новоцурухайтуй", mungalov)
        w.clock = w.farm.startOf(day(20)).plusSeconds(10 * 3600)
        w.sell("1", "Приаргунск", null)
        w.pay(mungalov, 40_000)
        w.clock = w.farm.startOf(day(2, 12)).plusSeconds(10 * 3600)
        w.sell("1", "Приаргунск", kaydolov)
        val november = CoalReport.month(YearMonth.of(2026, 11), w.books, w.info, w.clock)
        assertEquals("Ноябрь 2026", november.periodTitle)
        assertEquals(2, november.rows.size)
        assertEquals(2_170_000L, november.totals.revenue)
        assertEquals(3000L, november.totals.kilograms)
        assertEquals(listOf(60_000L), november.drivers.map { it.debtAfter })
        assertEquals(40_000L, november.totalPaid)
        assertEquals(listOf(1_400_000L, 770_000L), november.locations.map { it.revenue })
        val december = CoalReport.month(YearMonth.of(2026, 12), w.books, w.info, w.clock)
        assertEquals(60_000L, december.drivers.first { it.driverId == mungalov.id }.debtBefore)
    }

    @Test fun dayPeriodTitleAndOpenDayHasNoClosedAt() {
        w.sell("1", "Приаргунск", null)
        val r = w.dayReport()
        assertEquals("06.11.2026", r.periodTitle)
        assertNull(r.closedAt)
    }

    @Test fun rowsAreInTimeOrderWithListTotalOnlyWhenAdjusted() {
        val a = w.sell("1", "Приаргунск", null)
        val b = CoalRules.saveSale(w.draft("2", "Новоцурухайтуй", mungalov).startManual().copy(manualCoalText = "12500"), w.books, w.clock)
        w.sales = w.sales + b
        val rows = w.dayReport().rows
        assertEquals(listOf(a.id, b.id), rows.map { it.id })
        assertNull(rows[0].listTotal)
        assertEquals(1_400_000L, rows[1].listTotal)
        assertEquals("", rows[0].driver)
        assertEquals("Мунгалов", rows[1].driver)
    }

    @Test fun balanceIsEarnedMinusPaid() {
        w.sell("2", "Новоцурухайтуй", mungalov)
        w.sell("1", "Приаргунск", mungalov)
        w.pay(mungalov, 50_000)
        val b = CoalReport.balance(mungalov.id, w.sales, w.payments)
        assertEquals(2, b.deliveries)
        assertEquals(220_000L, b.earned)
        assertEquals(50_000L, b.paid)
        assertEquals(170_000L, b.debt)
    }

    @Test fun booksLookUpSalesAndPaymentsOfAPeriod() {
        // Task 11 (d): the ports of CoalSaleService.sales(on:) / sales(from:to:) and DriverPaymentService.payments(from:to:).
        val first = w.sell("2", "Новоцурухайтуй", mungalov)
        val second = w.sell("1", "Приаргунск", null)
        w.pay(mungalov, 40_000)
        w.clock = w.farm.startOf(day(7)).plusSeconds(10 * 3600)
        val third = w.sell("1", "Приаргунск", kaydolov)
        w.pay(kaydolov, 50_000)
        val books = w.books
        assertEquals(listOf(first.id, second.id), books.sales(day(6)).map { it.id })
        assertEquals(listOf(third.id), books.sales(day(7)).map { it.id })
        assertEquals(emptyList<String>(), books.sales(day(8)).map { it.id })
        assertEquals(listOf(first.id, second.id, third.id), books.sales(day(6), day(8)).map { it.id })
        assertEquals(listOf(40_000L), books.payments(day(6), day(7)).map { it.amount })
        assertEquals(listOf(40_000L, 50_000L), books.payments(day(6), day(8)).map { it.amount })
        assertEquals(emptyList<Long>(), books.payments(day(8), day(9)).map { it.amount })
    }
}
