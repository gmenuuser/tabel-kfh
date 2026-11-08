package ru.kfh.core.coal

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.NameOrder
import ru.kfh.core.timesheet.RuMonths

/** Everything the coal rules read: the saved records and the farm's calendar. */
class CoalBooks(
    val sales: List<CoalSale>,
    val payments: List<DriverPayment>,
    /** Every driver, archived ones included. */
    val drivers: List<Driver>,
    val workDays: List<CoalWorkDay>,
    val farm: FarmTime,
) {
    fun isClosed(day: LocalDate): Boolean = workDays.any { it.isClosed && farm.dayOf(it.date) == day }

    fun workDay(day: LocalDate): CoalWorkDay? = workDays.firstOrNull { farm.dayOf(it.date) == day }
}

/** One line of the sales table. */
data class CoalSaleRow(
    val number: Int,
    val id: String,
    val date: Instant,
    val kilograms: Long,
    val location: String,
    val vehicle: String,
    /** The hired driver's name; "" for the own vehicle. */
    val driver: String,
    val coalAmount: Long,
    val deliveryPrice: Long,
    val totalAmount: Long,
    val driverPayment: Long,
    /** The amount at the list prices when it differs from `totalAmount`, else null. */
    val listTotal: Long?,
    val comment: String,
)

/** The figures of a day or a month, computed from the sales. */
data class CoalTotals(
    val salesCount: Int = 0,
    val kilograms: Long = 0,
    /** А. Everything the buyers pay: coal and delivery, own and hired trucks. */
    val revenue: Long = 0,
    /** Б. */
    val coalAmount: Long = 0,
    /** В. */
    val deliveryAmount: Long = 0,
    /** Г. Owed to hired drivers for the deliveries of the period (not what was paid). */
    val driverPayments: Long = 0,
    /** Д. Deliveries made by the farm's own truck. */
    val ownVehicleDeliveryRevenue: Long = 0,
    val hiredDeliveries: Int = 0,
    val ownDeliveries: Int = 0,
) {
    /** Е. А − Г. Not a profit: the coal's cost and other expenses are not counted. */
    val remainderAfterDrivers: Long get() = revenue - driverPayments

    fun plus(s: CoalSale): CoalTotals {
        val t = copy(
            salesCount = salesCount + 1, kilograms = kilograms + s.kilograms, revenue = revenue + s.totalAmount,
            coalAmount = coalAmount + s.coalAmount, deliveryAmount = deliveryAmount + s.deliveryPrice,
        )
        return if (s.vehicleType == VehicleType.HIRED) {
            t.copy(hiredDeliveries = hiredDeliveries + 1, driverPayments = driverPayments + s.driverPaymentAmount)
        } else {
            t.copy(ownDeliveries = ownDeliveries + 1, ownVehicleDeliveryRevenue = ownVehicleDeliveryRevenue + s.deliveryPrice)
        }
    }

    companion object {
        fun of(sales: List<CoalSale>): CoalTotals = sales.fold(CoalTotals()) { t, s -> t.plus(s) }
    }
}

/** What the farm owes one hired driver over a period. */
data class DriverSettlement(
    val driverId: String,
    val name: String,
    /** Debt carried in from before the period. */
    val debtBefore: Long = 0,
    val deliveries: Int = 0,
    /** Accrued for the period's deliveries. */
    val accrued: Long = 0,
    /** Paid during the period. */
    val paid: Long = 0,
) {
    /** To pay at the end of the period: debt before + accrued − paid. */
    val debtAfter: Long get() = debtBefore + accrued - paid
}

/** One village's share of a period. */
data class LocationTotal(
    val name: String,
    val salesCount: Int = 0,
    val kilograms: Long = 0,
    val revenue: Long = 0,
    val deliveryAmount: Long = 0,
)

/** A payment as listed in a report. */
data class CoalPaymentRow(val date: Instant, val driver: String, val amount: Long, val comment: String)

/** A day or a month report: the sales, the totals, the drivers' accounts and the villages. */
data class CoalPeriodReport(
    val isMonth: Boolean,
    /** First day of the period and the day after the last one. */
    val start: LocalDate,
    val endExclusive: LocalDate,
    val farmName: String,
    val managerName: String,
    val rows: List<CoalSaleRow>,
    val totals: CoalTotals,
    val drivers: List<DriverSettlement>,
    val locations: List<LocationTotal>,
    val payments: List<CoalPaymentRow>,
    /** When the day was closed; null for an open day and for a month. */
    val closedAt: Instant?,
    val createdAt: Instant,
) {
    /** Σ of the drivers' debts at the end of the period. */
    val totalDebtAfter: Long get() = drivers.sumOf { it.debtAfter }
    val totalPaid: Long get() = payments.sumOf { it.amount }

    /** «06.11.2026» or «Ноябрь 2026». */
    val periodTitle: String
        get() = if (isMonth) RuMonths.title(YearMonth.from(start)) else CoalText.date(start)
}

/** A driver's account over all time. */
data class DriverBalance(val deliveries: Int = 0, val earned: Long = 0, val paid: Long = 0) {
    /** What the farm still owes; never set by hand, always earned − paid. */
    val debt: Long get() = earned - paid
}

/**
 * Reports built from the saved sales and payments every time; nothing is taken from stored totals.
 * Port of `CoalReport.swift` and the reading parts of `CoalDayService` / `DriverService`.
 */
object CoalReport {
    fun day(day: LocalDate, books: CoalBooks, info: FarmInfo, now: Instant): CoalPeriodReport {
        val wd = books.workDay(day)
        return build(false, day, day.plusDays(1), books, info, if (wd?.isClosed == true) wd.closedAt else null, now)
    }

    fun month(month: YearMonth, books: CoalBooks, info: FarmInfo, now: Instant): CoalPeriodReport =
        build(true, month.atDay(1), month.plusMonths(1).atDay(1), books, info, null, now)

    /** The driver's hired deliveries. */
    fun salesOf(driverId: String, sales: List<CoalSale>): List<CoalSale> =
        sales.filter { it.driverId == driverId }.sortedByDescending { it.saleDate }

    fun paymentsOf(driverId: String, payments: List<DriverPayment>): List<DriverPayment> =
        payments.filter { it.driverId == driverId }.sortedByDescending { it.paymentDate }

    fun balance(driverId: String, sales: List<CoalSale>, payments: List<DriverPayment>): DriverBalance {
        val hired = sales.filter { it.vehicleType == VehicleType.HIRED && it.driverId == driverId }
        return DriverBalance(
            deliveries = hired.size,
            earned = hired.sumOf { it.driverPaymentAmount },
            paid = payments.filter { it.driverId == driverId }.sumOf { it.amount },
        )
    }

    /**
     * @param books every sale and payment of the store (earlier ones give the debt carried in) and every driver.
     */
    fun build(
        isMonth: Boolean, startDay: LocalDate, endDay: LocalDate, books: CoalBooks, info: FarmInfo,
        closedAt: Instant?, createdAt: Instant,
    ): CoalPeriodReport {
        val start = books.farm.startOf(startDay)
        val end = books.farm.startOf(endDay)
        val inPeriod = books.sales.filter { it.saleDate >= start && it.saleDate < end }
            .sortedWith(compareBy<CoalSale>({ it.saleDate }, { it.createdAt }))
        val rows = inPeriod.mapIndexed { index, s ->
            val hired = s.vehicleType == VehicleType.HIRED
            CoalSaleRow(
                number = index + 1, id = s.id, date = s.saleDate, kilograms = s.kilograms,
                location = s.locationNameSnapshot, vehicle = s.vehicleType,
                driver = if (hired) s.driverNameSnapshot else "",
                coalAmount = s.coalAmount, deliveryPrice = s.deliveryPrice, totalAmount = s.totalAmount,
                driverPayment = if (hired) s.driverPaymentAmount else 0,
                listTotal = if (s.isPriceAdjusted) s.listTotal else null, comment = s.comment,
            )
        }

        val names = books.drivers.associate { it.id to it.fullName }
        val accounts = LinkedHashMap<String, DriverSettlement>()
        fun account(id: String, fallbackName: String) = accounts[id] ?: DriverSettlement(id, names[id] ?: fallbackName)
        for (s in books.sales) {
            if (s.vehicleType != VehicleType.HIRED || s.saleDate >= end) continue
            val id = s.driverId ?: continue
            val a = account(id, s.driverNameSnapshot)
            accounts[id] = if (s.saleDate < start) {
                a.copy(debtBefore = a.debtBefore + s.driverPaymentAmount)
            } else {
                a.copy(deliveries = a.deliveries + 1, accrued = a.accrued + s.driverPaymentAmount)
            }
        }
        val periodPayments = books.payments.filter { it.paymentDate >= start && it.paymentDate < end }
            .sortedWith(compareBy<DriverPayment>({ it.paymentDate }, { it.createdAt }))
        for (p in books.payments) {
            if (p.paymentDate >= end) continue
            val a = account(p.driverId, p.driverNameSnapshot)
            accounts[p.driverId] = if (p.paymentDate < start) a.copy(debtBefore = a.debtBefore - p.amount) else a.copy(paid = a.paid + p.amount)
        }
        val settlements = accounts.values.filter { it.deliveries > 0 || it.paid != 0L || it.debtBefore != 0L }
            .sortedWith { a, b -> NameOrder.compare(a.name, b.name) }

        val locations = LinkedHashMap<String, LocationTotal>()
        for (s in inPeriod) {
            val l = locations[s.locationNameSnapshot] ?: LocationTotal(s.locationNameSnapshot)
            locations[s.locationNameSnapshot] = l.copy(
                salesCount = l.salesCount + 1, kilograms = l.kilograms + s.kilograms,
                revenue = l.revenue + s.totalAmount, deliveryAmount = l.deliveryAmount + s.deliveryPrice,
            )
        }

        return CoalPeriodReport(
            isMonth = isMonth, start = startDay, endExclusive = endDay, farmName = info.farmName,
            managerName = info.managerName, rows = rows, totals = CoalTotals.of(inPeriod), drivers = settlements,
            locations = locations.values.sortedWith { a, b -> NameOrder.compare(a.name, b.name) },
            payments = periodPayments.map { CoalPaymentRow(it.paymentDate, names[it.driverId] ?: it.driverNameSnapshot, it.amount, it.comment) },
            closedAt = closedAt, createdAt = createdAt,
        )
    }
}
