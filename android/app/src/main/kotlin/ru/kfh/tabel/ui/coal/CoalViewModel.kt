package ru.kfh.tabel.ui.coal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import ru.kfh.core.coal.CoalBooks
import ru.kfh.core.coal.CoalError
import ru.kfh.core.coal.CoalPeriodReport
import ru.kfh.core.coal.CoalReport
import ru.kfh.core.coal.CoalRules
import ru.kfh.core.coal.CoalSaleDraft
import ru.kfh.core.coal.CoalTotals
import ru.kfh.core.coal.DriverBalance
import ru.kfh.core.coal.DriverDraft
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.DEFAULT_PRICE_PER_TON_KOPECKS
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.model.Entities
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.observe
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.ui.common.ActionQueue
import ru.kfh.tabel.ui.common.FarmToday
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * «Продажа угля»: the store's coal records as flows the screens collect, and the screens' actions as the rules of
 * the domain ([CoalRules], [CoalReport]) written through [RecordWriter]. One per process ([ru.kfh.tabel.AppGraph.coal]).
 *
 * - «Сегодня», the day of a sale and the day that is closed are the farm's ([farmTime]), never the phone's.
 * - Every action runs in [scope] (the app's) one at a time: leaving a screen never cancels a write, and a second tap
 *   reads what the first one wrote (the same form id is refused the second time: one sale, one payment).
 * - One user action = one Room transaction with one op per touched record ([RecordWriter.saveAll]).
 * - An action returns the text for «Не получилось» (the domain's Russian message), or null when it went through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalViewModel(
    private val dao: RecordDao,
    private val writer: RecordWriter,
    farmTime: StateFlow<FarmTime>,
    scope: CoroutineScope,
    private val now: () -> Instant = { Instant.now() },
    /** The farm's name and manager (from `Me`) and the settings row: the header of the reports. */
    val farmInfo: Flow<FarmInfo> = kotlinx.coroutines.flow.flowOf(FarmInfo.DEFAULT),
) {
    /** The sale screen: the day's sales (newest first), its figures, whether it is closed, the current price. */
    data class DayState(
        val day: LocalDate,
        val today: LocalDate,
        val sales: List<CoalSale>,
        val totals: CoalTotals,
        val isClosed: Boolean,
        val closedAt: Instant?,
        val pricePerTon: Long,
    )

    /** One driver with his account and history, newest first (`DriverDetailView`). */
    data class DriverAccount(
        val driver: Driver,
        val balance: DriverBalance,
        val sales: List<CoalSale>,
        val payments: List<DriverPayment>,
    )

    private val farmToday = FarmToday(farmTime, scope, now)
    private val actions = ActionQueue(scope)

    val farm: FarmTime get() = farmToday.farm

    /** The farm's day of this moment (the clock, not the cached [todayFlow]). */
    fun today(): LocalDate = farmToday.today()

    /** «Сегодня» as the sale screen sees it: refreshed on resume, at the farm's midnight and on every sign-in. */
    val todayFlow: StateFlow<LocalDate> get() = farmToday.flow

    fun refreshToday() {
        farmToday.refresh()
    }

    fun millisUntilNextFarmDay(): Long = farmToday.millisUntilNextFarmDay()

    fun resetOnSignIn(scope: CoroutineScope, states: Flow<SessionState>) = farmToday.onSignIn(scope, states) { farmToday.reset() }

    fun now(): Instant = now.invoke()

    // MARK: What the role may do

    val canSell: Boolean get() = writer.canWrite(Entities.coalSale)
    val canCloseDay: Boolean get() = writer.canWrite(Entities.coalWorkDay)
    val canPay: Boolean get() = writer.canWrite(Entities.driverPayment)
    val canEditDrivers: Boolean get() = writer.canWrite(Entities.driver)
    val canEditPrices: Boolean get() = writer.canWrite(Entities.coalSettings) && writer.canWrite(Entities.deliveryLocation)

    // MARK: What the screens show

    // Decoding runs off the main thread (the screens collect with collectAsState).
    private inline fun <reified T : Any> records(entity: String): Flow<List<T>> =
        dao.observe(entity).map { rows -> rows.map { RecordCodec.decode<T>(entity, it.id, it.json()) } }.flowOn(Dispatchers.Default)

    /** The settings row, or null before the module was first opened by a role that may write it. */
    val settings: Flow<CoalSettings?> = records<CoalSettings>(Entities.coalSettings).map { it.minByOrNull { s -> s.createdAt } }

    /** The current price per ton; the owner's 6 500 ₽ until a row exists. */
    val pricePerTon: Flow<Long> = settings.map { it?.pricePerTonKopecks ?: DEFAULT_PRICE_PER_TON_KOPECKS }.distinctUntilChanged()

    /** Every village, hidden («удалённые») ones too. */
    val locations: Flow<List<DeliveryLocation>> = records(Entities.deliveryLocation)

    /** The villages offered in a new sale, by name. */
    val activeLocations: Flow<List<DeliveryLocation>> = locations.map(CoalRules::activeLocations)

    /** Every driver, archived ones last. */
    val drivers: Flow<List<Driver>> = records<Driver>(Entities.driver).map(CoalRules::allDrivers)

    /** The drivers of the picker: not archived, by surname. */
    val activeDrivers: Flow<List<Driver>> = drivers.map { CoalRules.activeDrivers(it) }

    val sales: Flow<List<CoalSale>> = records(Entities.coalSale)
    val payments: Flow<List<DriverPayment>> = records(Entities.driverPayment)
    val workDays: Flow<List<CoalWorkDay>> = records(Entities.coalWorkDay)

    /** Closed days, newest first («Дни» of the history). */
    val closedDays: Flow<List<CoalWorkDay>> = workDays.map(CoalRules::closedDays)

    /** Everything the rules read, rebuilt after any change. */
    val books: Flow<CoalBooks> = combine(sales, payments, drivers, workDays, farmTime) { s, p, d, w, farm ->
        CoalBooks(s, p, d, w, farm)
    }

    /** The day of [day] on the sale screen. */
    fun dayState(day: LocalDate): Flow<DayState> =
        combine(books, pricePerTon, todayFlow) { b, price, today ->
            val wd = b.workDay(day)
            val closed = wd?.isClosed == true
            val daySales = b.sales(day)
            DayState(
                day = day, today = today, sales = daySales.asReversed(), totals = CoalTotals.of(daySales),
                isClosed = closed, closedAt = if (closed) wd?.closedAt else null, pricePerTon = price,
            )
        }

    /** Today at the farm, live: the sale screen. */
    val todayState: Flow<DayState> = todayFlow.flatMapLatest { dayState(it) }

    fun dayReport(day: LocalDate): Flow<CoalPeriodReport> =
        combine(books, farmInfo) { b, info -> CoalReport.day(day, b, info, now()) }

    fun monthReport(month: YearMonth): Flow<CoalPeriodReport> =
        combine(books, farmInfo) { b, info -> CoalReport.month(month, b, info, now()) }

    fun driver(id: String): Flow<Driver?> =
        drivers.map { list -> list.firstOrNull { it.id.equals(id, ignoreCase = true) } }.distinctUntilChanged()

    fun balance(driverId: String): Flow<DriverBalance> =
        books.map { CoalReport.balance(driverId, it.sales, it.payments) }.distinctUntilChanged()

    /** Every driver's account, archived ones included («Общий долг перед водителями»). */
    val balances: Flow<Map<String, DriverBalance>> =
        books.map { b -> b.drivers.associate { it.id to CoalReport.balance(it.id, b.sales, b.payments) } }

    val totalDebt: Flow<Long> = balances.map { m -> m.values.sumOf { it.debt } }.distinctUntilChanged()

    /** The driver with his balance, deliveries and payments; null when he is gone. */
    fun driverAccount(id: String): Flow<DriverAccount?> =
        combine(driver(id), books) { d, b ->
            if (d == null) null
            else DriverAccount(d, CoalReport.balance(d.id, b.sales, b.payments), CoalReport.salesOf(d.id, b.sales), CoalReport.paymentsOf(d.id, b.payments))
        }

    // MARK: Opening the module

    /**
     * `CoalSettingsService.current()`: on the first opening by a role that may write the coal settings, the settings
     * row (6 500 ₽/т) is created and, when the farm has no villages at all yet, the owner's two are added with it —
     * all in one transaction. Any other opening, or a viewer's, writes nothing.
     */
    suspend fun ensureDefaults(): String? = action {
        if (settings.first() != null || !writer.canWrite(Entities.coalSettings)) return@action
        val moment = now()
        val changes = mutableListOf<Triple<String, String, JsonObject>>()
        val row = CoalSettings(createdAt = moment, updatedAt = moment)
        changes += Triple(Entities.coalSettings, row.id, RecordCodec.encode(row))
        if (writer.canWrite(Entities.deliveryLocation)) {
            for (l in CoalRules.seedLocations(locations.first(), moment)) {
                changes += Triple(Entities.deliveryLocation, l.id, RecordCodec.encode(l))
            }
        }
        writer.saveAll(changes)
    }

    // MARK: Sales

    /** An empty form at the current price with its own id (a second save of the same form is refused). */
    suspend fun newDraft(): CoalSaleDraft = CoalRules.newDraft(RecordWriter.newId(), now(), pricePerTon.first())

    /** «СОХРАНИТЬ ПРОДАЖУ»: the sale is dated the moment of saving. The failure carries the domain's text. */
    suspend fun saveSale(draft: CoalSaleDraft): Result<CoalSale> = result {
        val moment = now()
        val sale = CoalRules.saveSale(draft.copy(saleDate = moment), books.first(), moment)
        writer.save(Entities.coalSale, sale.id, RecordCodec.encode(sale))
        sale
    }

    /** The edited form over the sale (its own date). */
    suspend fun updateSale(sale: CoalSale, draft: CoalSaleDraft): String? = action {
        val updated = CoalRules.updateSale(sale, draft, books.first(), now())
        writer.update(Entities.coalSale, updated.id, RecordCodec.encode(updated))
    }

    /** «Отменить продажу»: a delete op (the site's `engine.remove`); refused on a closed day. */
    suspend fun deleteSale(sale: CoalSale): String? = action {
        CoalRules.checkDeletable(sale, books.first())
        writer.delete(Entities.coalSale, sale.id)
    }

    // MARK: Working days

    /** «ПОДТВЕРДИТЬ ЗАВЕРШЕНИЕ ДНЯ»: one record with the day's totals; nothing is paid. */
    suspend fun closeDay(day: LocalDate): String? = action {
        val wd = CoalRules.closeDay(day, books.first(), farmInfo.first(), now())
        writer.save(Entities.coalWorkDay, wd.id, RecordCodec.encode(wd))
    }

    /** «Открыть день для редактирования»: only the flag changes, so only `isClosed` is queued. */
    suspend fun reopenDay(day: LocalDate): String? = action {
        val wd = CoalRules.reopenDay(day, books.first())
        writer.save(Entities.coalWorkDay, wd.id, RecordCodec.encode(wd))
    }

    // MARK: Payments

    /** «Выплатить»: [id] is the form's, made once, so a second tap is refused instead of paying twice. */
    suspend fun pay(driver: Driver, amount: Long, date: Instant?, comment: String, id: String): String? = action {
        val p = CoalRules.pay(driver, amount, books.first(), now(), date, comment, id)
        writer.save(Entities.driverPayment, p.id, RecordCodec.encode(p))
    }

    /** Removes a payment entered by mistake; the debt grows back. */
    suspend fun deletePayment(p: DriverPayment): String? = action { writer.delete(Entities.driverPayment, p.id) }

    // MARK: Drivers

    /**
     * Saves the form: a new driver (`existing == null`, a new lowercase id) or the changed one. A new name is written
     * into the driver's sales and payments in the same transaction (one op each).
     */
    suspend fun saveDriver(existing: Driver?, draft: DriverDraft): Result<Driver> = result {
        if (existing == null) {
            val d = CoalRules.createDriver(draft, RecordWriter.newId(), now())
            writer.save(Entities.driver, d.id, RecordCodec.encode(d))
            d
        } else {
            val change = CoalRules.updateDriver(existing, draft, books.first())
            val changes = mutableListOf(Triple(Entities.driver, change.driver.id, RecordCodec.encode(change.driver)))
            for (s in change.sales) changes += Triple(Entities.coalSale, s.id, RecordCodec.encode(s))
            for (p in change.payments) changes += Triple(Entities.driverPayment, p.id, RecordCodec.encode(p))
            writer.saveAll(changes, edited = setOf(Entities.driver to existing.id))
            change.driver
        }
    }

    suspend fun archiveDriver(d: Driver): String? = action {
        writer.update(Entities.driver, d.id, RecordCodec.encode(CoalRules.archive(d)))
    }

    suspend fun restoreDriver(d: Driver): String? = action {
        writer.update(Entities.driver, d.id, RecordCodec.encode(CoalRules.restore(d)))
    }

    /** Only a driver without deliveries and payments is deleted; the others are archived instead. */
    suspend fun deleteDriver(d: Driver): String? = action {
        CoalRules.checkDeletable(d, books.first())
        writer.delete(Entities.driver, d.id)
    }

    // MARK: Prices and villages

    /** «Сохранить цену»: the same price writes nothing; saved sales keep their own. */
    suspend fun setPricePerTon(kopecks: Long): String? = action {
        val moment = now()
        val current = settings.first() ?: CoalSettings(createdAt = moment, updatedAt = moment)
        val changed = CoalRules.setPricePerTon(current, kopecks, moment)
        if (changed === current && settings.first() != null) return@action
        writer.save(Entities.coalSettings, changed.id, RecordCodec.encode(changed))
    }

    suspend fun addLocation(name: String, deliveryKopecks: Long, driverPaymentKopecks: Long?): String? = action {
        val l = CoalRules.addLocation(locations.first(), name, deliveryKopecks, driverPaymentKopecks, now())
        writer.save(Entities.deliveryLocation, l.id, RecordCodec.encode(l))
    }

    suspend fun updateLocation(location: DeliveryLocation, name: String, deliveryKopecks: Long, driverPaymentKopecks: Long?): String? = action {
        val l = CoalRules.updateLocation(location, locations.first(), name, deliveryKopecks, driverPaymentKopecks)
        writer.update(Entities.deliveryLocation, l.id, RecordCodec.encode(l))
    }

    /** «Удалить»: the village leaves the list of a new sale; saved sales keep its name and prices. */
    suspend fun removeLocation(location: DeliveryLocation): String? = action {
        val l = CoalRules.removeLocation(location)
        writer.update(Entities.deliveryLocation, l.id, RecordCodec.encode(l))
    }

    // MARK: Running

    /** Runs the change in the app's scope; the domain's refusal (or the role's) comes back as the text to show. */
    private suspend fun action(block: suspend () -> Unit): String? = actions.run {
        try {
            block()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            describe(e)
        }
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = actions.run {
        try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(if (e is CoalError) e else IllegalStateException(describe(e), e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is CoalError -> e.message ?: "Не получилось"
        is IllegalStateException -> e.message ?: "Не получилось"
        else -> "Не получилось: ${e.message ?: e::class.java.simpleName}"
    }
}
