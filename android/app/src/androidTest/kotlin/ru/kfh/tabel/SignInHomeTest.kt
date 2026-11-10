package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
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
 * Sign-in → «Загрузка данных…» → the main screen with the buttons of the role, against a MockWebServer on the
 * device (the graph's base URL is swapped; production stays https://kfh.cubely.ru). With the instrumentation
 * argument `shots=true` the sign-in and main screens are saved to `files/shots/` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SignInHomeTest {
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
    @Volatile private var role = "timesheet"
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app = ApplicationProvider.getApplicationContext<TabelApp>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                seen += "${request.method} ${request.path}"
                return when (request.requestUrl!!.encodedPath) {
                    "/auth/login" ->
                        if (body.contains("\"password\":\"secret\"")) json("""{"token":"tok-ui","role":"$role"}""")
                        else MockResponse().setResponseCode(401).setBody("""{"error":"Неверный логин или пароль"}""")
                    "/me" -> json(
                        """{"userId":"u2","role":"$role","farm":{"id":"f1","name":"КФХ Зубахин",""" +
                            """"region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"Asia/Chita"}}""",
                    )
                    "/sync/pull" -> {
                        pullGate.await(20, TimeUnit.SECONDS)
                        json("""{"changes":[],"next":0,"more":false}""")
                    }
                    "/sync/push" -> json("""{"results":[]}""")
                    "/conflicts" -> json("""{"conflicts":[]}""")
                    "/auth/logout" -> json("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        app.deleteDatabase(DB)
        val graph = AppGraph(
            app,
            baseUrl = server.url("/").toString(),
            tokens = MemoryTokenStore(),
            dbName = DB,
            photosDir = File(app.cacheDir, "uitest-photos"),
        )
        app.useGraph(graph)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        pullGate.countDown()
        scenario.close()
        server.shutdown()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun signIn(password: String) {
        compose.waitUntilExactlyOneExists(hasTestTag("signInLogin"), 10_000)
        compose.onAllNodesWithText("Пароль", useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithTag("signInLogin").performTextInput("anna")
        compose.onNodeWithTag("signInPassword").performTextInput(password)
        shot("signin")
        compose.onNodeWithTag("signInButton").performClick()
    }

    @Test
    fun signInLoadsThenShowsTheButtonsOfTheRole() {
        signIn("secret")

        // The first pull is held: «Загрузка данных…».
        compose.waitUntilExactlyOneExists(hasText("Загрузка данных…"), 10_000)
        pullGate.countDown()

        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 10_000)
        compose.onNodeWithTag("farmName").assertIsDisplayed()
        compose.onNodeWithText("КФХ Зубахин").assertIsDisplayed()
        compose.onNodeWithText("Ваша роль: только табель").assertIsDisplayed()
        compose.onNodeWithText("ТАБЕЛЬ РАБОТНИКОВ").assertIsDisplayed()
        compose.onNodeWithText("Отметки за день, табель за месяц, сотрудники").assertIsDisplayed()
        compose.onNodeWithTag("openReports").assertIsDisplayed()
        compose.onNodeWithText("Табель — PDF и Excel").assertIsDisplayed()
        compose.onNodeWithTag("openCoal").assertDoesNotExist()
        compose.onNodeWithTag("openLivestock").assertDoesNotExist()
        compose.onNodeWithTag("openAgro").assertDoesNotExist()
        compose.onNodeWithTag("openSettings").assertIsDisplayed()
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)
        compose.onNodeWithText("Синхронизировать сейчас").assertIsDisplayed()
        shot("home")

        // A module opens with its title and «Назад» returns to the main screen.
        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasText("Табель работников"), 5_000)
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 5_000)

        // Settings for every role: «Выйти» asks, then the sign-in screen comes back.
        compose.onNodeWithTag("openSettings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 5_000)
        compose.onNodeWithTag("signOut").performClick()
        compose.waitUntilExactlyOneExists(hasText("Выйти из хозяйства?"), 5_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signInLogin"), 10_000)
        assertEquals(null, app.graph.tokens.get())
    }

    @Test
    fun wrongPasswordShowsTheServerText() {
        signIn("wrong")
        compose.waitUntilExactlyOneExists(hasText("Неверный логин или пароль"), 10_000)
        compose.onNodeWithTag("signInLogin").assertIsDisplayed()
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
        const val TAG = "SignInHomeTest"
    }
}
