package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import ru.kfh.tabel.session.MemoryTokenStore
import java.io.File
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone connects itself (no sign-in): launch → «Загрузка данных…» → the main screen with every module; the
 * owner closing new devices, no network and a switched-off phone show the connect screen with its one button.
 * Against a MockWebServer on the device (the graph's base URL is swapped; production stays https://kfh.cubely.ru).
 * With the instrumentation argument `shots=true` the screens are saved to `files/shots/` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectHomeTest {
    private val compose = createEmptyComposeRule()

    /** A failure prints what the screen showed, the session's state and the requests the server saw. */
    private val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(TAG, "failed: $e; session=${app.graph.session.state.value}; requests=$seen")
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog(TAG) }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(onFailure).around(compose)

    private val server = MockWebServer()
    private val pullGate = CountDownLatch(1)
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var holdPull = false
    @Volatile private var closed = false
    @Volatile private var offline = false
    @Volatile private var revoked = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private var graph: AppGraph? = null
    private val app = ApplicationProvider.getApplicationContext<TabelApp>()

    /** The server, then the graph (its launch connects at once), then the activity. */
    private fun launch() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                seen += "${request.method} $path"
                if (path == "/devices/register") bodies += request.body.readUtf8()
                if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                if (path == "/devices/register") {
                    return if (closed) MockResponse().setResponseCode(403)
                        .setBody("""{"error":"Владелец сейчас не подключает новые устройства"}""")
                    else json("""{"token":"tok-ui","deviceId":"d9","farmId":"f1","role":"manager","kind":"app"}""").setResponseCode(201)
                }
                if (revoked) return MockResponse().setResponseCode(401).setBody("""{"error":"Нужно войти заново"}""")
                return when (path) {
                    "/me" -> json(
                        """{"userId":null,"deviceId":"d9","role":"manager","kind":"app","farm":{"id":"f1","name":"КФХ Зубахин",""" +
                            """"region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"Asia/Chita"}}""",
                    )
                    "/sync/pull" -> {
                        if (holdPull) pullGate.await(20, TimeUnit.SECONDS)
                        json("""{"changes":[],"next":0,"more":false}""")
                    }
                    "/sync/push" -> json("""{"results":[]}""")
                    "/conflicts" -> json("""{"conflicts":[]}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // The previous test closed its graph in tearDown: nothing holds this file any more.
        app.deleteDatabase(DB)
        val g = AppGraph(
            app,
            baseUrl = server.url("/").toString(),
            tokens = MemoryTokenStore(),
            dbName = DB,
            photosDir = File(app.cacheDir, "uitest-photos"),
        )
        graph = g
        app.useGraph(g)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        pullGate.countDown()
        scenario?.close()
        // Synchronous: the triggers, the coroutines and the database are gone before the next test deletes the file.
        graph?.close()
        server.shutdown()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun registers() = seen.count { it == "POST /devices/register" }

    @Test
    fun connectsByItselfLoadsThenShowsEveryModule() {
        holdPull = true
        launch()

        // The first pull is held: «Загрузка данных…», no sign-in on the way.
        compose.waitUntilExactlyOneExists(hasText("Загрузка данных…"), 10_000)
        pullGate.countDown()

        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 10_000)
        compose.onNodeWithText("КФХ Зубахин").assertIsDisplayed()
        // A phone that connected itself has no role to name.
        compose.onNodeWithText("Ваша роль", substring = true).assertDoesNotExist()
        for (tag in listOf("openTimesheet", "openCoal", "openLivestock", "openAgro", "openReports", "openSettings")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)
        shot("home")

        assertEquals(1, registers())
        assertTrue(bodies.single(), bodies.single().contains("\"platform\":\"android\""))
        assertTrue(seen.none { it.endsWith("/auth/login") })
        assertEquals("tok-ui", app.graph.tokens.get())

        // A module opens with its title and «Назад» returns to the main screen.
        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasText("Табель работников"), 5_000)
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 5_000)
    }

    @Test
    fun closedShowsTheOwnersTextAndRetries() {
        closed = true
        launch()
        compose.waitUntilExactlyOneExists(hasTestTag("connectScreen"), 10_000)
        compose.onNodeWithTag("connectText").assertTextEquals("Владелец сейчас не подключает новые устройства")
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        shot("connect-closed")

        closed = false
        compose.onNodeWithTag("connectButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 15_000)
    }

    @Test
    fun offlineShowsNoConnectionAndRetries() {
        offline = true
        launch()
        compose.waitUntilExactlyOneExists(hasTestTag("connectScreen"), 15_000)
        compose.onNodeWithTag("connectText").assertTextEquals("Нет связи с сервером")
        compose.onNodeWithText("Повторить").assertIsDisplayed()

        offline = false
        compose.onNodeWithTag("connectButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 15_000)
    }

    @Test
    fun switchedOffWaitsForConnectAgain() {
        launch()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 15_000)
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)

        // The owner switched the phone off: the next sync is refused, the farm's data goes.
        revoked = true
        compose.onNodeWithText("Синхронизировать сейчас").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("connectScreen"), 15_000)
        compose.onNodeWithTag("connectText").assertTextEquals("Это устройство отключено владельцем")
        compose.onNodeWithText("Подключиться снова").assertIsDisplayed()
        assertEquals(null, app.graph.tokens.get())
        assertEquals(1, registers())
        shot("connect-revoked")

        // Only the button registers again.
        revoked = false
        compose.onNodeWithTag("connectButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 15_000)
        assertEquals(2, registers())
    }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val DB = "uitest.db"
        const val TAG = "ConnectHomeTest"
    }
}
