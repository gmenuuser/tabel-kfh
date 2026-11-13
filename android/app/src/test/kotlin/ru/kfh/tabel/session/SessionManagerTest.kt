package ru.kfh.tabel.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.roles.Module
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.Me
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.RoomLocalStore
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.obj
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.home.homeButtons
import ru.kfh.tabel.ui.home.reportsSubtitle
import ru.kfh.tabel.ui.signin.connectButton
import ru.kfh.tabel.ui.signin.connectText
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FarmServer
    private lateinit var db: TabelDb
    private lateinit var photos: PhotoStore
    private lateinit var store: RoomLocalStore
    private lateinit var tokens: MemoryTokenStore
    private lateinit var session: SessionManager
    private lateinit var writer: RecordWriter

    private val driverA = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01"
    private val driverB = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a02"

    @Before
    fun setUp() {
        server = FarmServer()
        db = openTestDb(tmp.newFile("t.db").also { it.delete() })
        photos = PhotoStore(tmp.newFolder("photos"))
        store = RoomLocalStore(db, photos)
        tokens = MemoryTokenStore()
        session = newSession()
        writer = RecordWriter(db, { session.role ?: Role.viewer })
    }

    private fun newSession() = SessionManager(store, tokens, server.url, OkHttpClient(), "Google Pixel 9")

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    /**
     * Launch without a token: the phone connects itself. [role] given: `/me` answers as the server before `kind`
     * (the role decides, as for a device of the old builds).
     */
    private suspend fun connected(role: String? = null) {
        if (role != null) {
            server.kind = null
            server.role = role
        }
        session.restore()
        assertTrue(session.state.value.toString(), session.state.value is SessionState.SignedIn)
    }

    private suspend fun wiped() {
        assertTrue(db.records().all().isEmpty())
        assertEquals(0, db.outbox().count())
        assertEquals(0, db.outbox().refusedCount())
        assertNull(store.me())
        assertEquals(0L, store.cursor())
    }

    private fun registers() = server.requests("/devices/register").size

    // MARK: Connect

    @Test
    fun firstLaunchConnectsByItselfAndLoadsEverything() = test {
        server.timezone = "Europe/Moscow"
        server.records = listOf(FarmServer.driver(driverA))
        server.pullGate = CountDownLatch(1)
        val launch = async(Dispatchers.IO) { session.restore() }
        assertTrue(server.pullStarted.await(10, TimeUnit.SECONDS))
        // The first pull is on its way: «Загрузка данных…».
        assertEquals(SessionState.Loading, session.state.value)
        server.pullGate!!.countDown()
        launch.await()

        val me = (session.state.value as SessionState.SignedIn).me
        assertEquals(Role.app, me.role)
        assertNull(me.userId)
        assertEquals("КФХ Зубахин", me.farmName)
        assertEquals("tok-1", tokens.get())
        assertEquals(me, store.me())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals(ZoneId.of("Europe/Moscow"), session.farmTime.value.zone)

        val register = server.requests("/devices/register").single()
        assertEquals("POST", register.method)
        assertEquals("android", register.json["platform"]!!.jsonPrimitive.content)
        assertEquals("Google Pixel 9", register.json["name"]!!.jsonPrimitive.content)
        // The registration itself goes without a token.
        assertNull(register.authorization)
        assertTrue(server.requests("/auth/login").isEmpty())
    }

    @Test
    fun appRoleSeesEveryModuleButWritesNoSettings() = test {
        connected()
        assertEquals(Role.app, session.role)
        assertEquals(
            listOf(Module.timesheet, Module.coal, Module.livestock, Module.agro, Module.reports),
            homeButtons(Role.app),
        )
        assertTrue(writer.canWrite("coalSale"))
        assertTrue(writer.canWrite("hoursMark"))
        assertFalse(writer.canWrite("coalSettings"))
        assertFalse(writer.canWrite("farmSettings"))
        assertFalse(writer.canWrite("livestockSettings"))
    }

    @Test
    fun noNetworkIsOfflineAndRetriedWhenTheNetworkReturns() = test {
        server.offline = true
        session.restore()
        assertEquals(SessionState.NotConnected(ConnectReason.Offline), session.state.value)
        assertNull(tokens.get())
        assertNull(session.engine.value)

        // The network came back: the scheduler's sync (network, foreground, the period) connects.
        server.offline = false
        session.syncNow()
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals("tok-1", tokens.get())
        assertEquals(1, registers())
    }

    @Test
    fun offlineConnectFromTheButtonReturnsTheText() = test {
        server.offline = true
        session.restore()
        assertEquals("Нет связи с сервером", session.connect())
        assertEquals(SessionState.NotConnected(ConnectReason.Offline), session.state.value)
        server.offline = false
        assertNull(session.connect())
        assertTrue(session.state.value is SessionState.SignedIn)
    }

    @Test
    fun closedRegistrationIsNotRetriedByItself() = test {
        server.registerClosed = true
        session.restore()
        assertEquals(SessionState.NotConnected(ConnectReason.Closed), session.state.value)
        assertEquals(1, registers())

        // Every attempt counts towards the server's 5 an hour: only «Повторить» asks again.
        session.syncNow()
        assertEquals(1, registers())

        assertEquals("Владелец сейчас не подключает новые устройства", session.connect())
        assertEquals(SessionState.NotConnected(ConnectReason.Closed), session.state.value)
        server.registerClosed = false
        assertNull(session.connect())
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(3, registers())
    }

    @Test
    fun tooManyAttemptsShowsTheServerTextAndWaitsForTheButton() = test {
        server.registerStatus = 429
        session.restore()
        assertEquals(SessionState.NotConnected(ConnectReason.Offline), session.state.value)
        assertEquals("Слишком много попыток, попробуйте позже", session.notice.value)
        session.syncNow()
        assertEquals(1, registers())
    }

    @Test
    fun connectWhileConnectedDoesNothing() = test {
        connected()
        assertNull(session.connect())
        assertEquals(1, registers())
        assertTrue(session.state.value is SessionState.SignedIn)
    }

    // MARK: Upgrade over an old build

    @Test
    fun upgradeKeepsTheTokenTheDataAndThePendingChanges() = test {
        // What the old build left: its token, its `Me` (the owner), a record and a change not yet sent.
        tokens.set("tok-1")
        store.saveMe(Me("u1", Role.owner, "КФХ Зубахин", null, null, ZoneId.of("Asia/Chita")))
        RecordWriter(db, { Role.owner }).save("driver", driverA, obj("lastName" to "Мунгалов"))

        session.restore()
        val me = (session.state.value as SessionState.SignedIn).me
        // Until the next /me the phone is as before.
        assertEquals(Role.owner, me.role)
        assertEquals(0, registers())
        assertEquals(1, db.outbox().count())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals("tok-1", tokens.get())

        // The next sync sends the change, and /me (kind app) makes the phone an app.
        session.syncNow()
        assertEquals(0, registers())
        assertEquals(0, db.outbox().count())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals(Role.app, session.role)
        assertEquals(Role.app, store.me()!!.role)
    }

    @Test
    fun farmTimeIsRefreshedFromMeOnEverySync() = test {
        connected()
        assertEquals(ZoneId.of("Asia/Chita"), session.farmTime.value.zone)
        server.timezone = "Europe/Moscow"
        server.farmName = "КФХ Новое"
        session.syncNow()
        assertEquals(ZoneId.of("Europe/Moscow"), session.farmTime.value.zone)
        assertEquals("КФХ Новое", (session.state.value as SessionState.SignedIn).me.farmName)
        assertEquals(ZoneId.of("Europe/Moscow"), store.me()!!.timeZone)
    }

    @Test
    fun timesheetRoleSeesOnlyTimesheet() = test {
        connected(role = "timesheet")
        val me = (session.state.value as SessionState.SignedIn).me
        assertEquals(Role.timesheet, me.role)
        assertEquals(listOf(Module.timesheet, Module.reports), homeButtons(me.role))
        assertEquals("Табель — PDF и Excel", reportsSubtitle(me.role))
        assertTrue(writer.canWrite("hoursMark"))
        assertFalse(writer.canWrite("coalSale"))

        assertEquals(
            listOf(Module.timesheet, Module.coal, Module.livestock, Module.agro, Module.reports),
            homeButtons(Role.owner),
        )
        assertEquals("Табель, продажи угля, выплаты водителям, скот и забой — PDF и Excel", reportsSubtitle(Role.owner))
        assertEquals("Продажи угля, выплаты водителям — PDF и Excel", reportsSubtitle(Role.coal))
        assertEquals(listOf(Module.livestock, Module.reports), homeButtons(Role.livestock))
    }

    // MARK: Sign out (until the settings lose «Выйти»)

    @Test
    fun signOutWithPendingAsksConfirmation() = test {
        connected()
        writer.save("driver", driverA, obj("lastName" to "Мунгалов"))
        writer.save("driver", driverB, obj("lastName" to "Бянкин"))

        assertEquals(2, session.signOut(force = false))
        // Nothing went: still connected, the data and the queue are whole.
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(2, db.outbox().count())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals("tok-1", tokens.get())
        assertTrue(server.requests("/auth/logout").isEmpty())

        val first = session.engine.value
        assertEquals(0, session.signOut(force = true))
        assertEquals("Bearer tok-1", server.requests("/auth/logout").single().authorization)
        // The phone forgot the farm and connected again as a new device.
        assertEquals(0, db.outbox().count())
        assertNull(db.records().get("driver", driverA))
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(2, registers())
        assertTrue(first!!.stopped)
        assertNull(session.notice.value)
    }

    @Test
    fun signOutWaitsForTheRunningSyncBeforeWiping() = test {
        connected()
        server.records = listOf(FarmServer.driver(driverA))
        server.pullStarted = CountDownLatch(1)
        server.pullGate = CountDownLatch(1)
        val sync = async(Dispatchers.IO) { session.syncNow() }
        assertTrue(server.pullStarted.await(10, TimeUnit.SECONDS))

        val signOut = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { session.signOut(force = true) }
        delay(300)
        // stopAndWait: the wipe waits until the run on its way is done.
        assertFalse(signOut.isCompleted)
        assertEquals("tok-1", tokens.get())

        // The connect after the sign-out is refused: the wipe is what is left to see.
        server.registerClosed = true
        server.pullGate!!.countDown()
        assertEquals(0, signOut.await())
        sync.await()
        // The pulled page came after the stop: nothing of it was written.
        wiped()
        assertNull(tokens.get())
        assertEquals(SessionState.NotConnected(ConnectReason.Closed), session.state.value)
    }

    // MARK: Revoked

    @Test
    fun unauthorizedWipesAndWaitsForTheButton() = test {
        connected()
        writer.save("driver", driverA, obj("lastName" to "Мунгалов"))
        val first = session.engine.value
        server.unauthorized = true

        // The engine stops and wipes under its own lock; onSignedOut only clears the session (no deadlock).
        session.syncNow()
        assertEquals(SessionState.NotConnected(ConnectReason.Revoked), session.state.value)
        wiped()
        assertNull(tokens.get())
        assertTrue(tokens.revoked())
        assertNull(session.engine.value)
        // The unsent change was lost with the wipe: the screen says how many.
        assertEquals("Неотправленных изменений удалено: 1.", session.notice.value)
        assertTrue(first!!.stopped)

        // Never again by itself: not on a sync, not at the next launch.
        server.unauthorized = false
        session.syncNow()
        assertEquals(1, registers())
        val again = newSession()
        again.restore()
        assertEquals(SessionState.NotConnected(ConnectReason.Revoked), again.state.value)
        assertEquals(1, registers())

        // «Подключиться снова»: a new registration, a new engine, the flag and the notice go.
        val pulls = server.requests("/sync/pull").size
        assertNull(again.connect())
        assertTrue(again.state.value is SessionState.SignedIn)
        assertEquals(2, registers())
        assertFalse(tokens.revoked())
        assertNull(again.notice.value)
        val second = again.engine.value
        assertNotNull(second)
        assertNotSame(first, second)
        assertFalse(second!!.stopped)
        assertTrue(server.requests("/sync/pull").size > pulls)
    }

    @Test
    fun unauthorizedOnMeIsRevoked() = test {
        connected()
        // The sync itself goes through; only /me answers 401 (the device was revoked in between).
        server.server.dispatcher.let { d ->
            server.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.requestUrl!!.encodedPath == "/me") okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    else d.dispatch(request)
            }
        }
        session.syncNow()
        assertEquals(SessionState.NotConnected(ConnectReason.Revoked), session.state.value)
        wiped()
        assertNull(tokens.get())
        assertTrue(tokens.revoked())
        assertNull(session.notice.value)
    }

    // MARK: Launch

    @Test
    fun launchWithTokenOpensSignedInOffline() = test {
        connected(role = "coal")
        val again = newSession()
        server.server.shutdown()
        again.restore()
        val me = (again.state.value as SessionState.SignedIn).me
        assertEquals(Role.coal, me.role)
        assertNotNull(again.engine.value)
    }

    // MARK: A token store that fails (the Keystore's known failures)

    /** Reads throw `SecurityException` (a value that no longer decrypts), writes throw when [broken]. */
    private class BrokenTokenStore : TokenStore {
        @Volatile var broken = false
        @Volatile var token: String? = null

        override fun get(): String? {
            if (broken) throw SecurityException("Could not decrypt value")
            return token
        }

        override fun set(token: String?) {
            if (broken) throw IllegalStateException("token not saved")
            this.token = token
        }

        @Volatile private var revoked = false

        override fun revoked(): Boolean = revoked

        override fun setRevoked(revoked: Boolean) {
            this.revoked = revoked
        }
    }

    @Test
    fun launchWithABrokenTokenStoreDoesNotCrash() = test {
        val broken = BrokenTokenStore().apply { broken = true }
        val again = SessionManager(store, broken, server.url, OkHttpClient(), "Google Pixel 9")
        again.restore()
        // The token cannot be kept: not connected, with the reason on the screen.
        assertEquals(SessionState.NotConnected(ConnectReason.Offline), again.state.value)
        assertEquals("Не удалось сохранить подключение на телефоне", again.notice.value)
        assertNull(again.engine.value)
    }

    @Test
    fun signOutCompletesWhenTheTokenStoreFails() = test {
        val broken = BrokenTokenStore()
        val again = SessionManager(store, broken, server.url, OkHttpClient(), "Google Pixel 9")
        again.restore()
        assertTrue(again.state.value is SessionState.SignedIn)
        val engine = again.engine.value!!
        val w = RecordWriter(db, { again.role ?: Role.viewer })
        w.save("driver", driverA, obj("lastName" to "Мунгалов"))
        broken.broken = true

        // Neither the token read nor its removal works: the phone still ends wiped, engine stopped.
        assertEquals(0, again.signOut(force = true))
        assertTrue(again.state.value is SessionState.NotConnected)
        wiped()
        assertTrue(engine.stopped)
        assertNull(again.engine.value)
        // No logout went: the token could not be read for it.
        assertTrue(server.requests("/auth/logout").isEmpty())
    }

    @Test
    fun tokenLostDuringTheFirstDownloadIsNotConnectedWithANotice() = test {
        val broken = BrokenTokenStore()
        val again = SessionManager(store, broken, server.url, OkHttpClient(), "Google Pixel 9")
        server.pullGate = CountDownLatch(1)
        val launch = async(Dispatchers.IO) { again.restore() }
        assertTrue(server.pullStarted.await(10, TimeUnit.SECONDS))
        assertEquals(SessionState.Loading, again.state.value)
        broken.broken = true
        server.pullGate!!.countDown()
        launch.await()

        assertEquals(SessionState.NotConnected(ConnectReason.Offline), again.state.value)
        assertEquals("Не удалось сохранить подключение на телефоне", again.notice.value)
        assertNull(again.engine.value)
        wiped()
    }

    @Test
    fun launchWithoutTokenWipesLeftoversThenConnects() = test {
        // A token gone, its farm's rows left behind (a wipe that failed).
        store.saveMe(Me("u1", Role.owner, "КФХ Старое", null, null, ZoneId.of("Asia/Chita")))
        RecordWriter(db, { Role.owner }).save("driver", driverA, obj("lastName" to "Мунгалов"))
        session.restore()
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(0, db.outbox().count())
        assertNull(db.records().get("driver", driverA))
        assertEquals("КФХ Зубахин", store.me()!!.farmName)
    }

    @Test
    fun connectScreenTexts() {
        assertEquals("Нет связи с сервером", connectText(ConnectReason.Offline, null))
        assertEquals("Слишком много попыток, попробуйте позже", connectText(ConnectReason.Offline, "Слишком много попыток, попробуйте позже"))
        assertEquals("Владелец сейчас не подключает новые устройства", connectText(ConnectReason.Closed, null))
        assertEquals("Это устройство отключено владельцем", connectText(ConnectReason.Revoked, "Неотправленных изменений удалено: 1."))
        assertEquals("Повторить", connectButton(ConnectReason.Offline))
        assertEquals("Повторить", connectButton(ConnectReason.Closed))
        assertEquals("Подключиться снова", connectButton(ConnectReason.Revoked))
    }
}
