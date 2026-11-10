package ru.kfh.core.coal

import ru.kfh.core.model.Driver
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.day

/**
 * The spec's day of CoalReportExportTests.swift: 06.11.2026 from 08:00, Мунгалов 3 deliveries, Кайдолов 2, one
 * own-vehicle sale with a hand-corrected price, a payment of 2000 ₽ to Мунгалов; [extra] more own-vehicle sales for a
 * long table. Sales five minutes apart.
 */
class CoalSpecDay(extra: Int = 0) {
    val w = CoalWorld()
    val info = FarmInfo.DEFAULT.copy(managerName = "Зубахин Иван Петрович")
    val mungalov: Driver
    val kaydolov: Driver

    init {
        w.clock = w.farm.startOf(day(6)).plusSeconds(8 * 3600)
        mungalov = driver(DriverDraft(lastName = "Мунгалов", firstName = "Сергей", middleName = "Иванович"))
        kaydolov = driver(DriverDraft(lastName = "Кайдолов", firstName = "Пётр"))
        val plan = listOf(
            Triple("2", "Новоцурухайтуй", mungalov), Triple("1,5", "Приаргунск", mungalov), Triple("0,5", "Новоцурухайтуй", mungalov),
            Triple("1", "Приаргунск", kaydolov), Triple("2,5", "Новоцурухайтуй", kaydolov), Triple("1", "Приаргунск", null),
        ) + List(extra) { Triple("1", "Новоцурухайтуй", null) }
        for ((index, p) in plan.withIndex()) {
            val (tons, place, driver) = p
            var d = w.draft(tons, place, driver)
            if (index == 5) d = d.startManual().copy(manualCoalText = "6000", comment = "постоянный покупатель")
            w.sales = w.sales + CoalRules.saveSale(d, w.books, w.clock)
            w.clock = w.clock.plusSeconds(5 * 60)
        }
        w.pay(mungalov, 200_000, comment = "наличными")
    }

    private fun driver(draft: DriverDraft): Driver {
        val d = CoalRules.createDriver(draft, w.nextId(), w.clock)
        w.drivers = w.drivers + d
        return d
    }

    fun close() = w.close(day(6))

    fun dayReport(): CoalPeriodReport = CoalReport.day(day(6), w.books, info, w.clock)

    fun monthReport(): CoalPeriodReport = CoalReport.month(java.time.YearMonth.of(2026, 11), w.books, info, w.clock)
}
