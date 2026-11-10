package ru.kfh.core.coal

import java.time.Instant
import java.time.LocalDate
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.NameOrder

/** The owner's starting villages. */
object CoalDefaults {
    val locations: List<Pair<String, Long>> = listOf("Новоцурухайтуй" to 100_000L, "Приаргунск" to 120_000L)
}

private fun collapse(s: String) = collapseSpaces(s)

/** The «Новая продажа» form. Amounts are recomputed from it on every change. */
data class CoalSaleDraft(
    /** The id the sale gets: a second save of the same form is refused instead of adding the sale twice. */
    val id: String,
    val saleDate: Instant,
    val pricePerTon: Long,
    val tonsText: String = "",
    /** The chosen village and its price at the moment it was chosen. */
    val locationName: String = "",
    val listDeliveryPrice: Long = 0,
    val vehicle: String = VehicleType.HIRED,
    val driverId: String? = null,
    val driverName: String = "",
    /** The hired driver's pay in rubles, as typed («1000»). */
    val driverPaymentText: String = "",
    /** Hand-corrected amounts in rubles; the list amounts stay in the sale too. */
    val isManual: Boolean = false,
    val manualCoalText: String = "",
    val manualDeliveryText: String = "",
    val comment: String = "",
) {
    val kilograms: Long? get() = CoalMath.parseTons(tonsText)

    /** Tons × the price per ton. */
    val listCoalAmount: Long get() = CoalMath.coalAmount(kilograms ?: 0, pricePerTon)
    val listTotal: Long get() = listCoalAmount + listDeliveryPrice

    val coalAmount: Long get() = if (isManual) CoalMath.parseRubles(manualCoalText) ?: listCoalAmount else listCoalAmount
    val deliveryPrice: Long
        get() = if (isManual) CoalMath.parseRubles(manualDeliveryText) ?: listDeliveryPrice else listDeliveryPrice

    /** Итого к оплате. */
    val total: Long get() = coalAmount + deliveryPrice
    val isAdjusted: Boolean get() = total != listTotal

    /** 0 for the own vehicle. */
    val driverPayment: Long get() = if (vehicle == VehicleType.HIRED) CoalMath.parseRubles(driverPaymentText) ?: 0 else 0

    /** Takes the village and its current prices; the driver's pay follows the village. */
    fun chooseLocation(l: DeliveryLocation) = copy(
        locationName = l.name, listDeliveryPrice = l.deliveryPriceKopecks,
        driverPaymentText = CoalMath.rublesField(l.effectiveDriverPaymentKopecks),
    )

    fun chooseDriver(d: Driver) = copy(vehicle = VehicleType.HIRED, driverId = d.id, driverName = d.fullName)

    /** Starts a hand correction from the list amounts. */
    fun startManual() = copy(
        manualCoalText = CoalMath.rublesField(listCoalAmount),
        manualDeliveryText = CoalMath.rublesField(listDeliveryPrice), isManual = true,
    )

    val validationErrors: List<String>
        get() {
            val errors = mutableListOf<String>()
            val kg = kilograms
            if (kg != null) {
                if (kg <= 0) errors += "Количество угля должно быть больше нуля"
                if (kg > CoalMath.MAX_KILOGRAMS) errors += "Не больше 100 тонн в одной продаже"
            } else {
                errors += if (tonsText.trim().isEmpty()) "Укажите количество угля" else "Количество угля — число, например 1,5"
            }
            if (locationName.isEmpty()) errors += "Выберите населённый пункт"
            if (vehicle == VehicleType.HIRED) {
                if (driverId == null) errors += "Выберите водителя наёмной машины"
                val pay = CoalMath.parseRubles(driverPaymentText)
                if (pay != null) {
                    if (pay > CoalMath.MAX_KOPECKS) errors += "Неверная оплата водителю"
                } else {
                    errors += "Укажите оплату водителю, например 1000"
                }
            }
            if (isManual) {
                if (CoalMath.parseRubles(manualCoalText) == null) errors += "Неверная стоимость угля"
                if (CoalMath.parseRubles(manualDeliveryText) == null) errors += "Неверная стоимость доставки"
            }
            if (coalAmount > CoalMath.MAX_KOPECKS || deliveryPrice > CoalMath.MAX_KOPECKS) errors += "Слишком большая сумма"
            return errors
        }

    val isValid: Boolean get() = validationErrors.isEmpty()

    companion object {
        /** The form of a saved sale: its own prices, not today's. */
        fun from(s: CoalSale): CoalSaleDraft {
            val listCoal = CoalMath.coalAmount(s.kilograms, s.coalPricePerTonSnapshot)
            val manual = s.coalAmount != listCoal || s.deliveryPrice != s.deliveryPriceSnapshot
            return CoalSaleDraft(
                id = s.id, saleDate = s.saleDate, pricePerTon = s.coalPricePerTonSnapshot,
                tonsText = CoalMath.tons(s.kilograms).replace(" ", ""),
                locationName = s.locationNameSnapshot, listDeliveryPrice = s.deliveryPriceSnapshot,
                vehicle = s.vehicleType, driverId = s.driverId, driverName = s.driverNameSnapshot,
                driverPaymentText = CoalMath.rublesField(s.driverPaymentAmount),
                isManual = manual,
                manualCoalText = if (manual) CoalMath.rublesField(s.coalAmount) else "",
                manualDeliveryText = if (manual) CoalMath.rublesField(s.deliveryPrice) else "",
                comment = s.comment,
            )
        }
    }
}

/** The fields of the driver form; trimmed when saved. */
data class DriverDraft(
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val phone: String = "",
    val comment: String = "",
) {
    val validationErrors: List<String>
        get() = if (collapse(lastName).isEmpty()) listOf("Укажите фамилию водителя") else emptyList()

    val isValid: Boolean get() = validationErrors.isEmpty()

    companion object {
        fun from(d: Driver) = DriverDraft(d.lastName, d.firstName, d.middleName, d.phone, d.comment)
    }
}

/** A renamed driver and the sales and payments that carry the new name (only those that changed). */
data class DriverChange(val driver: Driver, val sales: List<CoalSale>, val payments: List<DriverPayment>)

/**
 * The coal module as pure functions: each takes the saved records and returns the record(s) to write, or throws a
 * [CoalError]. Port of the domain parts of `CoalSaleService`, `DriverPaymentService`, `CoalDayService`,
 * `DriverService` and `CoalSettingsService`.
 */
object CoalRules {
    // ---- Sales ----

    /** An empty form at the current coal price, dated `now`. */
    fun newDraft(id: String, now: Instant, pricePerTon: Long) = CoalSaleDraft(id = id, saleDate = now, pricePerTon = pricePerTon)

    /** «СОХРАНИТЬ ПРОДАЖУ». */
    fun saveSale(draft: CoalSaleDraft, books: CoalBooks, now: Instant): CoalSale {
        if (!draft.isValid) throw CoalError.Invalid(draft.validationErrors)
        if (books.sales.any { it.id == draft.id }) throw CoalError.AlreadySaved
        ensureOpen(draft.saleDate, books)
        val name = checkedDriverName(draft, null, books)
        val sale = apply(draft, name, CoalSale(id = draft.id, createdAt = now, updatedAt = now))
        return checked(sale)
    }

    /** The edited form over the sale. Neither the old nor the new day may be closed. */
    fun updateSale(sale: CoalSale, draft: CoalSaleDraft, books: CoalBooks, now: Instant): CoalSale {
        if (!draft.isValid) throw CoalError.Invalid(draft.validationErrors)
        ensureOpen(sale.saleDate, books)
        ensureOpen(draft.saleDate, books)
        val name = checkedDriverName(draft, sale.driverId, books)
        return checked(apply(draft, name, sale).copy(updatedAt = now))
    }

    /** Returns the sale when it may be deleted (its day is open); throws [CoalError.DayClosed] otherwise. */
    fun checkDeletable(sale: CoalSale, books: CoalBooks): CoalSale {
        ensureOpen(sale.saleDate, books)
        return sale
    }

    private fun ensureOpen(date: Instant, books: CoalBooks) {
        val day = books.farm.dayOf(date)
        if (books.isClosed(day)) throw CoalError.DayClosed(day)
    }

    /** The hired driver's current name; an archived driver is accepted only when the sale already had them. */
    private fun checkedDriverName(draft: CoalSaleDraft, previousDriverId: String?, books: CoalBooks): String {
        val id = draft.driverId
        if (draft.vehicle != VehicleType.HIRED || id == null) return ""
        val driver = books.drivers.firstOrNull { it.id == id } ?: throw CoalError.DriverNotFound
        if (driver.isArchived && previousDriverId != id) throw CoalError.DriverArchived
        return driver.fullName
    }

    private fun apply(d: CoalSaleDraft, driverName: String, s: CoalSale): CoalSale {
        val hired = d.vehicle == VehicleType.HIRED
        return s.copy(
            saleDate = d.saleDate, kilograms = d.kilograms ?: 0, locationNameSnapshot = d.locationName,
            coalPricePerTonSnapshot = d.pricePerTon, deliveryPriceSnapshot = d.listDeliveryPrice,
            coalAmount = d.coalAmount, deliveryPrice = d.deliveryPrice, totalAmount = d.coalAmount + d.deliveryPrice,
            vehicleType = d.vehicle, driverId = if (hired) d.driverId else null,
            driverNameSnapshot = if (hired) driverName else "", driverPaymentAmount = d.driverPayment,
            comment = d.comment.trim(),
        )
    }

    /** The server refuses what these find (`server/src/entities.ts`); the app never writes it. */
    private fun checked(sale: CoalSale): CoalSale {
        val errors = serverErrors(sale)
        if (errors.isNotEmpty()) throw CoalError.Invalid(errors)
        return sale
    }

    /** Port of the `coalSale` validate of `server/src/entities.ts`: empty when the server would accept the sale. */
    fun serverErrors(s: CoalSale): List<String> {
        val errors = mutableListOf<String>()
        if (s.kilograms <= 0) errors += "Количество угля — целое число килограммов больше нуля"
        for ((k, v) in listOf("coalAmount" to s.coalAmount, "deliveryPrice" to s.deliveryPrice,
            "totalAmount" to s.totalAmount, "driverPaymentAmount" to s.driverPaymentAmount)) {
            if (v < 0) errors += "Сумма «$k» — целое число копеек"
        }
        if (s.totalAmount != s.coalAmount + s.deliveryPrice) errors += "Итого не равно углю и доставке"
        if (s.vehicleType == VehicleType.HIRED && s.driverId.isNullOrEmpty()) errors += "У наёмной машины должен быть водитель"
        if (s.vehicleType == VehicleType.OWN && s.driverPaymentAmount != 0L) errors += "Собственная машина не создаёт долга водителю"
        if (s.vehicleType != VehicleType.HIRED && s.vehicleType != VehicleType.OWN) errors += "Неизвестный тип машины"
        return errors
    }

    /** Port of the `driverPayment` validate of the server. */
    fun serverErrors(p: DriverPayment): List<String> {
        val errors = mutableListOf<String>()
        if (p.amount <= 0) errors += "Сумма выплаты — целое число копеек больше нуля"
        if (p.driverId.isEmpty()) errors += "Не указан водитель"
        return errors
    }

    // ---- Payments ----

    /**
     * Records money handed to the driver. Refused when nothing is owed or the amount is above the debt; `id` guards
     * against saving the same form twice. Only «Выплатить водителю» makes a payment; nothing else ever does.
     */
    fun pay(
        driver: Driver, amount: Long, books: CoalBooks, now: Instant, date: Instant? = null, comment: String = "",
        id: String,
    ): DriverPayment {
        if (books.payments.any { it.id == id }) throw CoalError.AlreadySaved
        if (amount <= 0) throw CoalError.Invalid(listOf("Сумма выплаты должна быть больше нуля"))
        val debt = CoalReport.balance(driver.id, books.sales, books.payments).debt
        if (debt <= 0) throw CoalError.NothingToPay
        if (amount > debt) throw CoalError.PaymentTooLarge(debt)
        val p = DriverPayment(
            id = id, driverId = driver.id, driverNameSnapshot = driver.fullName, paymentDate = date ?: now,
            amount = amount, comment = comment.trim(), createdAt = now,
        )
        val errors = serverErrors(p)
        if (errors.isNotEmpty()) throw CoalError.Invalid(errors)
        return p
    }

    // ---- Working days ----

    /**
     * «Завершить рабочий день»: the record that locks the day's sales and keeps its totals with the moment of
     * closing. Nothing is paid: the drivers' debts stay as they are.
     */
    fun closeDay(day: LocalDate, books: CoalBooks, info: FarmInfo, now: Instant): CoalWorkDay {
        val existing = books.workDay(day)
        if (existing?.isClosed == true) throw CoalError.DayAlreadyClosed
        val totals = CoalReport.day(day, books, info, now).totals
        val base = existing ?: CoalWorkDay(id = StableId.coalWorkDay(day), date = books.farm.startOf(day), createdAt = now)
        return base.copy(
            isClosed = true, closedAt = now, totalRevenue = totals.revenue, totalKilograms = totals.kilograms,
            totalDriverPayments = totals.driverPayments, ownVehicleDeliveryRevenue = totals.ownVehicleDeliveryRevenue,
        )
    }

    /** «Открыть день для редактирования». Closing it again stores the new totals. */
    fun reopenDay(day: LocalDate, books: CoalBooks): CoalWorkDay {
        val wd = books.workDay(day)
        if (wd == null || !wd.isClosed) throw CoalError.DayNotClosed
        return wd.copy(isClosed = false)
    }

    /** Closed days, newest first. */
    fun closedDays(workDays: List<CoalWorkDay>): List<CoalWorkDay> =
        workDays.filter { it.isClosed }.sortedByDescending { it.date }

    // ---- Drivers ----

    fun createDriver(draft: DriverDraft, id: String, now: Instant): Driver {
        if (!draft.isValid) throw CoalError.Invalid(draft.validationErrors)
        return applyDriver(draft, Driver(id = id, createdAt = now))
    }

    /** A new name is written into the driver's sales and payments too: it is the same person. */
    fun updateDriver(d: Driver, draft: DriverDraft, books: CoalBooks): DriverChange {
        if (!draft.isValid) throw CoalError.Invalid(draft.validationErrors)
        val driver = applyDriver(draft, d)
        val name = driver.fullName
        return DriverChange(
            driver,
            CoalReport.salesOf(d.id, books.sales).filter { it.driverNameSnapshot != name }.map { it.copy(driverNameSnapshot = name) },
            CoalReport.paymentsOf(d.id, books.payments).filter { it.driverNameSnapshot != name }.map { it.copy(driverNameSnapshot = name) },
        )
    }

    fun archive(d: Driver): Driver = d.copy(isArchived = true)

    fun restore(d: Driver): Driver = d.copy(isArchived = false)

    fun hasHistory(d: Driver, books: CoalBooks): Boolean =
        CoalReport.salesOf(d.id, books.sales).isNotEmpty() || CoalReport.paymentsOf(d.id, books.payments).isNotEmpty()

    /** Only a driver without deliveries and payments is deleted; the others are archived instead. */
    fun checkDeletable(d: Driver, books: CoalBooks): Driver {
        if (hasHistory(d, books)) throw CoalError.DriverHasHistory
        return d
    }

    /** Drivers for the picker: not archived, by surname; `search` matches the start of any name part («мун» -> Мунгалов). */
    fun activeDrivers(drivers: List<Driver>, search: String = ""): List<Driver> =
        allDrivers(drivers).filter { !it.isArchived && matches(it, search) }

    /** Everyone, archived ones last, each group by surname. */
    fun allDrivers(drivers: List<Driver>): List<Driver> =
        drivers.sortedWith { a, b ->
            if (a.isArchived != b.isArchived) if (a.isArchived) 1 else -1 else NameOrder.compare(a.fullName, b.fullName)
        }

    fun matches(d: Driver, search: String): Boolean {
        val q = search.trim()
        if (q.isEmpty()) return true
        return listOf(d.lastName, d.firstName, d.middleName).any { it.startsWith(q, ignoreCase = true) }
    }

    private fun applyDriver(draft: DriverDraft, d: Driver) = d.copy(
        lastName = collapse(draft.lastName), firstName = collapse(draft.firstName), middleName = collapse(draft.middleName),
        phone = draft.phone.trim(), comment = draft.comment.trim(),
    )

    // ---- Prices and villages ----

    /** The villages to create when none exist yet; empty otherwise (a removed village is not seeded again). */
    fun seedLocations(existing: List<DeliveryLocation>, now: Instant): List<DeliveryLocation> {
        if (existing.isNotEmpty()) return emptyList()
        return CoalDefaults.locations.mapIndexed { index, (name, price) ->
            DeliveryLocation(
                id = StableId.deliveryLocation(name), name = name, deliveryPriceKopecks = price,
                createdAt = now.plusSeconds(index.toLong()),
            )
        }
    }

    /** The settings with a new price; the same object when the price did not change. */
    fun setPricePerTon(settings: CoalSettings, kopecks: Long, now: Instant): CoalSettings {
        if (kopecks <= 0 || kopecks > CoalMath.MAX_KOPECKS) {
            throw CoalError.Invalid(listOf("Цена угля — больше нуля и не больше 10 000 000 ₽ за тонну"))
        }
        if (settings.pricePerTonKopecks == kopecks) return settings
        return settings.copy(pricePerTonKopecks = kopecks, updatedAt = now)
    }

    /** The villages offered in a new sale, by name. */
    fun activeLocations(all: List<DeliveryLocation>): List<DeliveryLocation> =
        all.filter { it.isActive }.sortedWith { a, b -> NameOrder.compare(a.name, b.name) }

    /** Adds a village. A hidden («удалённый») village with the same name is shown again with the new prices. */
    fun addLocation(
        all: List<DeliveryLocation>, name: String, deliveryKopecks: Long, driverPaymentKopecks: Long?, now: Instant,
    ): DeliveryLocation {
        val trimmed = validateLocation(name, deliveryKopecks, driverPaymentKopecks)
        val same = all.filter { sameName(it.name, trimmed) }
        if (same.any { it.isActive }) throw CoalError.DuplicateLocation(trimmed)
        val base = same.firstOrNull()?.copy(name = trimmed, isActive = true)
            ?: DeliveryLocation(id = StableId.deliveryLocation(trimmed), name = trimmed, createdAt = now)
        return base.copy(deliveryPriceKopecks = deliveryKopecks, driverPaymentKopecks = driverPaymentKopecks)
    }

    fun updateLocation(
        location: DeliveryLocation, all: List<DeliveryLocation>, name: String, deliveryKopecks: Long,
        driverPaymentKopecks: Long?,
    ): DeliveryLocation {
        val trimmed = validateLocation(name, deliveryKopecks, driverPaymentKopecks)
        if (all.any { it.id != location.id && it.isActive && sameName(it.name, trimmed) }) {
            throw CoalError.DuplicateLocation(trimmed)
        }
        return location.copy(name = trimmed, deliveryPriceKopecks = deliveryKopecks, driverPaymentKopecks = driverPaymentKopecks)
    }

    /** «Удалить»: the village leaves the list of a new sale; saved sales keep its name and prices. */
    fun removeLocation(location: DeliveryLocation): DeliveryLocation = location.copy(isActive = false)

    private fun validateLocation(name: String, delivery: Long, driverPayment: Long?): String {
        val trimmed = collapse(name)
        val errors = mutableListOf<String>()
        if (trimmed.isEmpty()) errors += "Укажите название населённого пункта"
        if (delivery < 0 || delivery > CoalMath.MAX_KOPECKS) errors += "Неверная стоимость доставки"
        if (driverPayment != null && (driverPayment < 0 || driverPayment > CoalMath.MAX_KOPECKS)) errors += "Неверная оплата водителю"
        if (errors.isNotEmpty()) throw CoalError.Invalid(errors)
        return trimmed
    }

    private fun sameName(a: String, b: String) = a.equals(b, ignoreCase = true)
}
