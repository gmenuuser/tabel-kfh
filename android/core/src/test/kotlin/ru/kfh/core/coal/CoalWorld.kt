package ru.kfh.core.coal

import java.time.Instant
import java.time.LocalDate
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.day
import ru.kfh.core.timesheet.farmIn

/** A stand-in for the store: applies the records the rules return, the way the later persistence layer will. */
class CoalWorld {
    val farm: FarmTime = farmIn()
    val info = FarmInfo.DEFAULT
    var clock: Instant = farm.startOf(day(6)).plusSeconds(9 * 3600)
    var settings = CoalSettings()
    var locations: List<DeliveryLocation> = CoalRules.seedLocations(emptyList(), clock)
    var drivers: List<Driver> = emptyList()
    var sales: List<CoalSale> = emptyList()
    var payments: List<DriverPayment> = emptyList()
    var workDays: List<CoalWorkDay> = emptyList()
    private var counter = 0

    val books get() = CoalBooks(sales, payments, drivers, workDays, farm)
    val today: LocalDate get() = farm.dayOf(clock)

    fun nextId() = "id-${++counter}"

    fun addDriver(lastName: String): Driver {
        val d = CoalRules.createDriver(DriverDraft(lastName = lastName), nextId(), clock)
        drivers = drivers + d
        return d
    }

    fun location(name: String) = locations.first { it.name == name && it.isActive }

    fun draft(tons: String, place: String, driver: Driver?): CoalSaleDraft {
        val d = CoalRules.newDraft(nextId(), clock, settings.pricePerTonKopecks)
            .chooseLocation(location(place)).copy(tonsText = tons)
        return if (driver != null) d.chooseDriver(driver) else d.copy(vehicle = VehicleType.OWN)
    }

    /** Saves a sale now, then moves the clock on a minute. */
    fun sell(tons: String, place: String, driver: Driver?): CoalSale {
        val s = CoalRules.saveSale(draft(tons, place, driver), books, clock)
        sales = sales + s
        clock = clock.plusSeconds(60)
        return s
    }

    fun update(s: CoalSale, d: CoalSaleDraft): CoalSale {
        val n = CoalRules.updateSale(s, d, books, clock)
        sales = sales.map { if (it.id == s.id) n else it }
        return n
    }

    fun pay(d: Driver, amount: Long, id: String = nextId(), comment: String = ""): DriverPayment {
        val p = CoalRules.pay(d, amount, books, clock, comment = comment, id = id)
        payments = payments + p
        return p
    }

    fun close(day: LocalDate = today): CoalWorkDay {
        val w = CoalRules.closeDay(day, books, info, clock)
        workDays = workDays.filter { it.id != w.id } + w
        return w
    }

    fun reopen(day: LocalDate = today) {
        val w = CoalRules.reopenDay(day, books)
        workDays = workDays.map { if (it.id == w.id) w else it }
    }

    fun dayReport(day: LocalDate = today) = CoalReport.day(day, books, info, clock)
}
