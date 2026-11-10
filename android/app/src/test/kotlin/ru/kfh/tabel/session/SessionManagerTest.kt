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
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.RoomLocalStore
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.obj
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.ui.home.homeButtons
import ru.kfh.tabel.ui.home.reportsSubtitle
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
        // Launch: no token, so signed out.
        runBlocking { session.restore() }
        writer = RecordWriter(db, { session.role ?: Role.viewer })
    }

    private fun newSession() = SessionManager(store, tokens, server.url, OkHttpClient(), "Google Pixel 9")

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    private suspend fun signedIn(role: String = "owner") {
        server.role = role
        assertNull(session.signIn("zubakhin", "secret"))
        assertTrue(session.state.value is SessionState.SignedIn)
    }

    private suspend fun wiped() {
        assertTrue(db.records().all().isEmpty())
        assertEquals(0, db.outbox().count())
        assertEquals(0, db.outbox().refusedCount())
        assertNull(store.me())
        assertEquals(0L, store.cursor())
    }

    // MARK: Sign in

    @Test
    fun signInLoadsEverythingBeforeTheHomeScreen() = test {
        server.timezone = "Europe/Moscow"
        server.records = listOf(FarmServer.driver(driverA))
        server.pullGate = CountDownLatch(1)
        val signIn = async(Dispatchers.IO) { session.signIn("  zubakhin ", "secret") }
        assertTrue(server.pullStarted.await(10, TimeUnit.SECONDS))
        // The first pull is on its way: «Загрузка данных…».
        assertEquals(SessionState.Loading, session.state.value)
        server.pullGate!!.countDown()
        assertNull(signIn.await())

        val me = (session.state.value as SessionState.SignedIn).me
        assertEquals(Role.owner, me.role)
        assertEquals("КФХ Зубахин", me.farmName)
        assertEquals("tok-1", tokens.get())
        assertEquals(me, store.me())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals(ZoneId.of("Europe/Moscow"), session.farmTime.value.zone)

        val login = server.requests("/auth/login").single().json
        assertEquals("zubakhin", login["login"]!!.jsonPrimitive.content)
        assertEquals("android", login["platform"]!!.jsonPrimitive.content)
        assertEquals("Google Pixel 9", login["deviceName"]!!.jsonPrimitive.content)
        // The login itself goes without a token.
        assertNull(server.requests("/auth/login").single().authorization)
    }

    @Test
    fun wrongPasswordShowsTheErrorAndStaysSignedOut() = test {
        assertEquals("Неверный логин или пароль", session.signIn("zubakhin", "wrong"))
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(tokens.get())
        assertTrue(server.requests("/me").isEmpty())
    }

    @Test
    fun noNetworkOnSignIn() = test {
        server.server.shutdown()
        assertEquals("Нет связи с сервером", session.signIn("zubakhin", "secret"))
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(tokens.get())
    }

    @Test
    fun farmTimeIsRefreshedFromMeOnEverySync() = test {
        signedIn()
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
        signedIn(role = "timesheet")
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

    // MARK: Sign out

    @Test
    fun signOutWithPendingAsksConfirmation() = test {
        signedIn()
        writer.save("driver", driverA, obj("lastName" to "Мунгалов"))
        writer.save("driver", driverB, obj("lastName" to "Бянкин"))

        assertEquals(2, session.signOut(force = false))
        // Nothing went: still signed in, the data and the queue are whole.
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(2, db.outbox().count())
        assertNotNull(db.records().get("driver", driverA))
        assertEquals("tok-1", tokens.get())
        assertTrue(server.requests("/auth/logout").isEmpty())

        assertEquals(0, session.signOut(force = true))
        assertEquals(SessionState.SignedOut, session.state.value)
        wiped()
        assertNull(tokens.get())
        assertNull(session.engine.value)
        assertEquals("Bearer tok-1", server.requests("/auth/logout").single().authorization)
        assertNull(session.signOutNotice.value)
    }

    @Test
    fun signOutWithNothingPendingSignsOutAtOnce() = test {
        signedIn()
        assertEquals(0, session.signOut(force = false))
        assertEquals(SessionState.SignedOut, session.state.value)
        wiped()
    }

    @Test
    fun signOutWaitsForTheRunningSyncBeforeWiping() = test {
        signedIn()
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

        server.pullGate!!.countDown()
        assertEquals(0, signOut.await())
        sync.await()
        // The pulled page came after the stop: nothing of it was written.
        wiped()
        assertNull(tokens.get())
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun unauthorizedWipes() = test {
        signedIn()
        writer.save("driver", driverA, obj("lastName" to "Мунгалов"))
        val first = session.engine.value
        server.unauthorized = true

        // The engine stops and wipes under its own lock; onSignedOut only clears the session (no deadlock).
        session.syncNow()
        assertEquals(SessionState.SignedOut, session.state.value)
        wiped()
        assertNull(tokens.get())
        assertNull(session.engine.value)
        assertEquals("Доступ к хозяйству отключён.", session.signOutNotice.value)
        assertTrue(first!!.stopped)

        // A new sign-in gets a new engine (a stopped one never restarts) and the notice goes.
        server.unauthorized = false
        val pulls = server.requests("/sync/pull").size
        signedIn()
        val second = session.engine.value
        assertNotNull(second)
        assertNotSame(first, second)
        assertFalse(second!!.stopped)
        assertNull(session.signOutNotice.value)
        assertTrue(server.requests("/sync/pull").size > pulls)
    }

    @Test
    fun unauthorizedOnMeSignsOut() = test {
        signedIn()
        // The sync itself goes through; only /me answers 401 (the device was revoked in between).
        server.server.dispatcher.let { d ->
            server.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.requestUrl!!.encodedPath == "/me") okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    else d.dispatch(request)
            }
        }
        session.syncNow()
        assertEquals(SessionState.SignedOut, session.state.value)
        wiped()
        assertNull(tokens.get())
        assertEquals("Доступ к хозяйству отключён.", session.signOutNotice.value)
    }

    // MARK: Launch

    @Test
    fun launchWithTokenOpensSignedInOffline() = test {
        signedIn(role = "coal")
        val again = newSession()
        server.server.shutdown()
        again.restore()
        val me = (again.state.value as SessionState.SignedIn).me
        assertEquals(Role.coal, me.role)
        assertNotNull(again.engine.value)
    }

    @Test
    fun launchWithoutTokenWipesLeftovers() = test {
        signedIn()
        writer.save("driver", driverA, obj("lastName" to "Мунгалов"))
        tokens.set(null)
        val again = newSession()
        again.restore()
        assertEquals(SessionState.SignedOut, again.state.value)
        wiped()
    }
}
