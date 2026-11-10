package ru.kfh.tabel.coal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.coal.CoalError
import ru.kfh.core.coal.CoalSaleDraft
import ru.kfh.core.coal.DriverDraft
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.DEFAULT_PRICE_PER_TON_KOPECKS
import ru.kfh.core.model.Driver
import ru.kfh.core.model.Entities
import ru.kfh.core.roles.Role
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.coal.CoalViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** The coal module over a real Room store: what a tap writes (one record, one op) and what the screens then see. */
class CoalViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 2026-11-06 16:30 UTC: 19:30 in Moscow, already 01:30 of 7 November at the farm (Asia/Chita, UTC+9). */
    private val moment: Instant = Instant.parse("2026-11-06T16:30:00Z")
    private val chita: ZoneId = ZoneId.of("Asia/Chita")
    private val farm = FarmTime(chita, Clock.fixed(moment, chita))
    private val farmDay: LocalDate = LocalDate.of(2026, 11, 7)

    private lateinit var db: TabelDb
    private lateinit var scope: CoroutineScope
    private lateinit var vm: CoalViewModel
    private var role = Role.owner
    private lateinit var defaultZone: TimeZone
    private var clockNow: Instant = moment

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        db = openTestDb(tmp.newFile("c.db").also { it.delete() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = CoalViewModel(
            dao = db.records(),
            writer = RecordWriter(db) { role },
            farmTime = MutableStateFlow(farm),
            scope = scope,
            now = { clockNow },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        TimeZone.setDefault(defaultZone)
    }

    private suspend fun ops() = db.outbox().all()

    private suspend fun opsOf(entity: String) = ops().filter { it.entity == entity }

    private suspend fun addDriver(lastName: String = "Мунгалов", firstName: String = ""): Driver =
        vm.saveDriver(null, DriverDraft(lastName = lastName, firstName = firstName)).getOrThrow()

    /** A filled form: [tons] to [place] by [driver]'s truck, or the farm's own one when null. */
    private suspend fun draft(tons: String, place: String, driver: Driver?): CoalSaleDraft {
        val location = vm.activeLocations.first().first { it.name == place }
        val d = vm.newDraft().chooseLocation(location).copy(tonsText = tons)
        return if (driver != null) d.chooseDriver(driver) else d.copy(vehicle = VehicleType.OWN)
    }

    private suspend fun sell(tons: String, place: String, driver: Driver?): CoalSale {
        val sale = vm.saveSale(draft(tons, place, driver)).getOrThrow()
        clockNow = clockNow.plusSeconds(60)
        return sale
    }

    @Test
    fun todayIsFarmDayAndDefaultsAreSeededOnce() = runTest {
        // The phone is in Moscow, where it is still 6 November: «Сегодня» is the farm's 7 November.
        assertEquals(farmDay, vm.today())
        assertEquals(farmDay, vm.todayFlow.value)
        assertEquals(0, ops().size)
        // The first opening of the module makes the settings row and the owner's two villages, stable ids, lowercase.
        assertNull(vm.ensureDefaults())
        val settings = opsOf(Entities.coalSettings).single()
        assertEquals(StableId.coalSettings, settings.recordId)
        assertEquals(JsonPrimitive(DEFAULT_PRICE_PER_TON_KOPECKS), settings.fieldsJson()["pricePerTonKopecks"])
        val villages = opsOf(Entities.deliveryLocation)
        assertEquals(
            setOf(StableId.deliveryLocation("Новоцурухайтуй"), StableId.deliveryLocation("Приаргунск")),
            villages.map { it.recordId }.toSet(),
        )
        assertTrue(ops().all { it.recordId == it.recordId.lowercase() })
        assertEquals(listOf("Новоцурухайтуй", "Приаргунск"), vm.activeLocations.first().map { it.name })
        assertEquals(listOf(100_000L, 120_000L), vm.activeLocations.first().map { it.deliveryPriceKopecks })
        assertEquals(DEFAULT_PRICE_PER_TON_KOPECKS, vm.pricePerTon.first())
        // A second opening writes nothing.
        assertNull(vm.ensureDefaults())
        assertEquals(3, ops().size)
    }

    @Test
    fun villagesAreSeededOnlyWithTheSettingsRow() = runTest {
        // A village made before the module was ever opened (another device, synced in): the settings row is still
        // created, but the owner's two villages are not added next to it.
        assertNull(vm.addLocation("Забайкальск", 150_000, null))
        assertNull(vm.ensureDefaults())
        assertEquals(1, opsOf(Entities.coalSettings).size)
        assertEquals(listOf("Забайкальск"), vm.activeLocations.first().map { it.name })
    }

    @Test
    fun doubleTapSaveCreatesOneSale() = runTest {
        vm.ensureDefaults()
        val mungalov = addDriver()
        val d = draft("2", "Новоцурухайтуй", mungalov)
        val before = ops().size
        // Two taps on «СОХРАНИТЬ ПРОДАЖУ» before the first write is through: the same form id is saved once.
        val results = coroutineScope {
            val a = async { vm.saveSale(d) }
            val b = async { vm.saveSale(d) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(CoalError.AlreadySaved.message, results.first { it.isFailure }.exceptionOrNull()?.message)
        val saleOps = ops().drop(before)
        assertEquals(1, saleOps.size)
        val op = saleOps.single()
        assertEquals(Entities.coalSale, op.entity)
        assertEquals(d.id, op.recordId)
        assertEquals(op.recordId.lowercase(), op.recordId)
        assertEquals(1, vm.sales.first().size)
        // The fields as the site writes them: whole kilograms, kopecks, the sale's moment, the driver and his pay.
        val f = op.fieldsJson()
        assertEquals(JsonPrimitive(2000), f["kilograms"])
        assertEquals(JsonPrimitive(1_300_000), f["coalAmount"])
        assertEquals(JsonPrimitive(100_000), f["deliveryPrice"])
        assertEquals(JsonPrimitive(1_400_000), f["totalAmount"])
        assertEquals(JsonPrimitive(650_000), f["coalPricePerTonSnapshot"])
        assertEquals(JsonPrimitive(100_000), f["deliveryPriceSnapshot"])
        assertEquals(JsonPrimitive("hired"), f["vehicleType"])
        assertEquals(JsonPrimitive(mungalov.id), f["driverId"])
        assertEquals(JsonPrimitive("Мунгалов"), f["driverNameSnapshot"])
        assertEquals(JsonPrimitive(100_000), f["driverPaymentAmount"])
        assertEquals(JsonPrimitive("Новоцурухайтуй"), f["locationNameSnapshot"])
        assertEquals(JsonPrimitive("2026-11-06T16:30:00.000Z"), f["saleDate"])
        // Today's list and totals at the farm: the sale belongs to 7 November, not to the phone's 6th.
        val state = vm.todayState.first()
        assertEquals(farmDay, state.day)
        assertEquals(1, state.sales.size)
        assertEquals(1_400_000L, state.totals.revenue)
        assertEquals(100_000L, state.totals.driverPayments)
        assertEquals(0, vm.dayState(farmDay.minusDays(1)).first().sales.size)
    }

    @Test
    fun doubleTapPayCreatesOnePayment() = runTest {
        vm.ensureDefaults()
        val mungalov = addDriver()
        sell("2", "Новоцурухайтуй", mungalov)
        assertEquals(100_000L, vm.balance(mungalov.id).first().debt)
        val before = ops().size
        val id = RecordWriter.newId()
        // Two taps on «Выплатить» of the same form: one payment.
        val results = coroutineScope {
            val a = async { vm.pay(mungalov, 100_000, null, "", id) }
            val b = async { vm.pay(mungalov, 100_000, null, "", id) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it == null })
        assertTrue(results.contains(CoalError.AlreadySaved.message))
        val payOps = ops().drop(before)
        assertEquals(1, payOps.size)
        assertEquals(Entities.driverPayment, payOps.single().entity)
        assertEquals(id, payOps.single().recordId)
        val f = payOps.single().fieldsJson()
        assertEquals(JsonPrimitive(100_000), f["amount"])
        assertEquals(JsonPrimitive(mungalov.id), f["driverId"])
        assertEquals(JsonPrimitive("Мунгалов"), f["driverNameSnapshot"])
        assertEquals(JsonPrimitive("2026-11-06T16:31:00.000Z"), f["paymentDate"])
        val balance = vm.balance(mungalov.id).first()
        assertEquals(100_000L, balance.earned)
        assertEquals(100_000L, balance.paid)
        assertEquals(0L, balance.debt)
        // Nothing owed: a new payment is refused and nothing is written.
        assertEquals(CoalError.NothingToPay.message, vm.pay(mungalov, 10_000, null, "", RecordWriter.newId()))
        assertEquals(before + 1, ops().size)
        // Deleting the payment brings the debt back (a delete op).
        val payment = vm.payments.first().single()
        assertNull(vm.deletePayment(payment))
        assertEquals("delete", ops().last().kind)
        assertEquals(100_000L, vm.balance(mungalov.id).first().debt)
    }

    @Test
    fun closedDayRefusesEditUntilReopened() = runTest {
        vm.ensureDefaults()
        val mungalov = addDriver()
        val sale = sell("2", "Новоцурухайтуй", mungalov)
        assertFalse(vm.todayState.first().isClosed)
        val before = ops().size

        assertNull(vm.closeDay(farmDay))
        val close = ops().drop(before).single()
        assertEquals(Entities.coalWorkDay, close.entity)
        assertEquals(StableId.coalWorkDay(farmDay), close.recordId)
        val f = close.fieldsJson()
        assertEquals(JsonPrimitive(true), f["isClosed"])
        assertEquals(JsonPrimitive("2026-11-06T15:00:00.000Z"), f["date"])
        assertEquals(JsonPrimitive(1_400_000), f["totalRevenue"])
        assertEquals(JsonPrimitive(2000), f["totalKilograms"])
        assertEquals(JsonPrimitive(100_000), f["totalDriverPayments"])
        assertEquals(JsonPrimitive(0), f["ownVehicleDeliveryRevenue"])
        val state = vm.todayState.first()
        assertTrue(state.isClosed)
        assertEquals(clockNow, state.closedAt)
        assertNotNull(vm.dayReport(farmDay).first().closedAt)

        // Closed: no new sale, no edit, no cancel, and the drivers' debts are untouched.
        val refusal = CoalError.DayClosed(farmDay).message
        assertEquals(refusal, vm.saveSale(draft("1", "Приаргунск", null)).exceptionOrNull()?.message)
        assertEquals(refusal, vm.updateSale(sale, CoalSaleDraft.from(sale).copy(tonsText = "3")))
        assertEquals(refusal, vm.deleteSale(sale))
        assertEquals(CoalError.DayAlreadyClosed.message, vm.closeDay(farmDay))
        assertEquals(before + 1, ops().size)
        assertEquals(100_000L, vm.balance(mungalov.id).first().debt)

        // «Открыть день для редактирования» writes only the flag; then the sale can change.
        assertNull(vm.reopenDay(farmDay))
        val reopen = ops().last()
        assertEquals(StableId.coalWorkDay(farmDay), reopen.recordId)
        assertEquals(setOf("isClosed"), reopen.fieldsJson().keys)
        assertFalse(vm.todayState.first().isClosed)
        assertEquals(CoalError.DayNotClosed.message, vm.reopenDay(farmDay))
        assertNull(vm.updateSale(sale, CoalSaleDraft.from(sale).copy(tonsText = "3")))
        val updated = vm.sales.first().single()
        assertEquals(3000L, updated.kilograms)
        assertEquals(1_950_000L + 100_000L, updated.totalAmount)
        assertEquals(setOf("kilograms", "coalAmount", "totalAmount", "updatedAt"), ops().last().fieldsJson().keys)
        // Closing again stores the new totals.
        assertNull(vm.closeDay(farmDay))
        assertEquals(JsonPrimitive(2_050_000), ops().last().fieldsJson()["totalRevenue"])
        assertEquals(1, vm.closedDays.first().size)
    }

    @Test
    fun ownTruckNoDriverNoDebt() = runTest {
        vm.ensureDefaults()
        val mungalov = addDriver()
        sell("1", "Приаргунск", null)
        val f = opsOf(Entities.coalSale).single().fieldsJson()
        assertEquals(JsonPrimitive("own"), f["vehicleType"])
        assertEquals(JsonNull, f["driverId"])
        assertEquals(JsonPrimitive(""), f["driverNameSnapshot"])
        assertEquals(JsonPrimitive(0), f["driverPaymentAmount"])
        assertEquals(JsonPrimitive(770_000), f["totalAmount"])
        val t = vm.todayState.first().totals
        assertEquals(1, t.ownDeliveries)
        assertEquals(0, t.hiredDeliveries)
        assertEquals(120_000L, t.ownVehicleDeliveryRevenue)
        assertEquals(0L, t.driverPayments)
        assertEquals(770_000L, t.remainderAfterDrivers)
        assertEquals(0L, vm.balance(mungalov.id).first().debt)
        assertEquals(0L, vm.totalDebt.first())
        assertTrue(vm.dayReport(farmDay).first().drivers.isEmpty())
        assertEquals(CoalError.NothingToPay.message, vm.pay(mungalov, 1_000, null, "", RecordWriter.newId()))
        assertEquals(0, opsOf(Entities.driverPayment).size)
    }

    @Test
    fun priceChangeKeepsOldSales() = runTest {
        vm.ensureDefaults()
        val old = sell("2", "Новоцурухайтуй", null)
        assertEquals(1_300_000L, old.coalAmount)
        val before = ops().size
        assertNull(vm.setPricePerTon(700_000))
        val op = ops().last()
        assertEquals(Entities.coalSettings, op.entity)
        assertEquals(StableId.coalSettings, op.recordId)
        assertEquals(setOf("pricePerTonKopecks", "updatedAt"), op.fieldsJson().keys)
        assertEquals(700_000L, vm.pricePerTon.first())
        assertEquals(700_000L, vm.newDraft().pricePerTon)
        // The saved sale keeps its own price; a new one takes the new price.
        assertEquals(1_300_000L, vm.sales.first().single().coalAmount)
        val new = sell("2", "Новоцурухайтуй", null)
        assertEquals(1_400_000L, new.coalAmount)
        assertEquals(700_000L, new.coalPricePerTonSnapshot)
        // The same price again writes nothing; a bad price is refused.
        assertNull(vm.setPricePerTon(700_000))
        assertEquals(before + 2, ops().size)
        assertEquals("Цена угля — больше нуля и не больше 10 000 000 ₽ за тонну", vm.setPricePerTon(0))
        assertEquals(before + 2, ops().size)
    }

    @Test
    fun viewerCannotChangeAnything() = runTest {
        role = Role.viewer
        assertFalse(vm.canSell || vm.canCloseDay || vm.canPay || vm.canEditDrivers || vm.canEditPrices)
        // The defaults are read, never written by a viewer (the server would refuse the rows).
        assertNull(vm.ensureDefaults())
        assertEquals(0, ops().size)
        assertEquals(DEFAULT_PRICE_PER_TON_KOPECKS, vm.pricePerTon.first())
        assertEquals(0, vm.activeLocations.first().size)
        assertTrue(vm.saveDriver(null, DriverDraft(lastName = "Мунгалов")).isFailure)
        assertNotNull(vm.closeDay(farmDay))
        assertNotNull(vm.setPricePerTon(700_000))
        assertNotNull(vm.addLocation("Забайкальск", 150_000, null))
        assertEquals(0, ops().size)
    }

    @Test
    fun renamingADriverRewritesHisSalesAndPaymentsInOneGo() = runTest {
        vm.ensureDefaults()
        val mungalov = addDriver()
        sell("2", "Новоцурухайтуй", mungalov)
        vm.pay(mungalov, 40_000, null, "аванс", RecordWriter.newId())
        val before = ops().size
        val renamed = vm.saveDriver(mungalov, DriverDraft.from(mungalov).copy(firstName = "Сергей")).getOrThrow()
        assertEquals(mungalov.id, renamed.id)
        val changes = ops().drop(before)
        assertEquals(listOf(Entities.driver, Entities.coalSale, Entities.driverPayment).sorted(), changes.map { it.entity }.sorted())
        assertEquals(JsonPrimitive("Мунгалов Сергей"), changes.first { it.entity == Entities.coalSale }.fieldsJson()["driverNameSnapshot"])
        assertEquals(JsonPrimitive("Мунгалов Сергей"), changes.first { it.entity == Entities.driverPayment }.fieldsJson()["driverNameSnapshot"])
        val account = vm.driverAccount(mungalov.id).first()!!
        assertEquals(1, account.sales.size)
        assertEquals(1, account.payments.size)
        assertEquals(60_000L, account.balance.debt)
        // With deliveries he can only be archived; archived, he leaves the picker and takes no new deliveries.
        assertEquals(CoalError.DriverHasHistory.message, vm.deleteDriver(renamed))
        assertNull(vm.archiveDriver(renamed))
        assertTrue(vm.activeDrivers.first().isEmpty())
        assertEquals(1, vm.drivers.first().size)
        val refused = vm.saveSale(draft("1", "Приаргунск", renamed))
        assertEquals(CoalError.DriverArchived.message, refused.exceptionOrNull()?.message)
        assertNull(vm.restoreDriver(vm.driver(mungalov.id).first()!!))
        assertEquals(1, vm.activeDrivers.first().size)
        // A driver without history is deleted outright.
        val fresh = addDriver("Кайдолов")
        assertNull(vm.deleteDriver(fresh))
        assertEquals("delete", ops().last().kind)
        assertNull(vm.driver(fresh.id).first())
    }

    @Test
    fun villagesAddChangeAndHide() = runTest {
        vm.ensureDefaults()
        assertNull(vm.addLocation(" Забайкальск ", 150_000, 140_000))
        val added = ops().last()
        assertEquals(StableId.deliveryLocation("Забайкальск"), added.recordId)
        assertEquals(JsonPrimitive(140_000), added.fieldsJson()["driverPaymentKopecks"])
        assertEquals("Населённый пункт «забайкальск» уже есть", vm.addLocation("забайкальск", 1, null))
        val l = vm.activeLocations.first().first { it.name == "Забайкальск" }
        assertNull(vm.updateLocation(l, "Забайкальск", 160_000, null))
        assertEquals(JsonNull, ops().last().fieldsJson()["driverPaymentKopecks"])
        assertNull(vm.removeLocation(l))
        assertEquals(JsonPrimitive(false), ops().last().fieldsJson()["isActive"])
        assertEquals(listOf("Новоцурухайтуй", "Приаргунск"), vm.activeLocations.first().map { it.name })
        // Added again: the hidden row comes back under its stable id with the new prices.
        assertNull(vm.addLocation("Забайкальск", 170_000, null))
        assertEquals(StableId.deliveryLocation("Забайкальск"), ops().last().recordId)
        assertEquals(170_000L, vm.activeLocations.first().first { it.name == "Забайкальск" }.deliveryPriceKopecks)
        assertEquals(3, vm.locations.first().size)
    }
}
