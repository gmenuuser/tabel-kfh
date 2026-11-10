package ru.kfh.tabel.export

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.coal.CoalBooks
import ru.kfh.core.coal.CoalPeriodReport
import ru.kfh.core.coal.CoalReport
import ru.kfh.core.coal.CoalRules
import ru.kfh.core.coal.DriverDraft
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo

/**
 * The spec's day of CoalReportExportTests.swift (as `CoalSpecDay` of the core tests): 06.11.2026 from 08:00, Мунгалов 3
 * deliveries, Кайдолов 2, one own-vehicle sale with a hand-corrected price, a payment of 2000 ₽ to Мунгалов; [extra]
 * more own-vehicle sales for a long table.
 */
class CoalSpecDay(extra: Int = 0) {
    val farm = FarmTime(DEFAULT_FARM_ZONE)
    val info = FarmInfo.DEFAULT.copy(managerName = "Зубахин Иван Петрович")
    val day: LocalDate = LocalDate.of(2026, 11, 6)
    var clock: Instant = farm.startOf(day).plusSeconds(8 * 3600)
    private val locations = CoalRules.seedLocations(emptyList(), clock)
    private var counter = 0
    var drivers: List<Driver> = emptyList()
    var sales: List<CoalSale> = emptyList()
    var payments: List<DriverPayment> = emptyList()
    var workDays: List<ru.kfh.core.model.CoalWorkDay> = emptyList()
    val books get() = CoalBooks(sales, payments, drivers, workDays, farm)

    /** «Завершить день» at the clock's time. */
    fun close() {
        workDays = listOf(CoalRules.closeDay(day, books, info, clock))
    }

    init {
        val mungalov = driver(DriverDraft(lastName = "Мунгалов", firstName = "Сергей", middleName = "Иванович"))
        val kaydolov = driver(DriverDraft(lastName = "Кайдолов", firstName = "Пётр"))
        val plan = listOf(
            Triple("2", "Новоцурухайтуй", mungalov), Triple("1,5", "Приаргунск", mungalov), Triple("0,5", "Новоцурухайтуй", mungalov),
            Triple("1", "Приаргунск", kaydolov), Triple("2,5", "Новоцурухайтуй", kaydolov), Triple("1", "Приаргунск", null),
        ) + List(extra) { Triple("1", "Новоцурухайтуй", null) }
        for ((index, p) in plan.withIndex()) {
            val (tons, place, driver) = p
            var d = CoalRules.newDraft("id-${++counter}", clock, CoalSettings().pricePerTonKopecks)
                .chooseLocation(locations.first { it.name == place && it.isActive }).copy(tonsText = tons)
            d = if (driver != null) d.chooseDriver(driver) else d.copy(vehicle = VehicleType.OWN)
            if (index == 5) d = d.startManual().copy(manualCoalText = "6000", comment = "постоянный покупатель")
            sales = sales + CoalRules.saveSale(d, books, clock)
            clock = clock.plusSeconds(5 * 60)
        }
        payments = payments + CoalRules.pay(mungalov, 200_000, books, clock, comment = "наличными", id = "id-${++counter}")
    }

    private fun driver(draft: DriverDraft): Driver {
        val d = CoalRules.createDriver(draft, "id-${++counter}", clock)
        drivers = drivers + d
        return d
    }

    fun dayReport(): CoalPeriodReport = CoalReport.day(day, books, info, clock)

    fun monthReport(): CoalPeriodReport = CoalReport.month(YearMonth.of(2026, 11), books, info, clock)
}
