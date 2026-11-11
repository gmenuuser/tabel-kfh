package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import ru.kfh.core.agro.AgroNumber
import ru.kfh.tabel.session.MemoryTokenStore
import java.io.File
import java.net.InetAddress
import java.util.Collections

/**
 * Sign in → «АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ» → «Посев семян» → 10 га × 200 кг/га → «Нужно семян 2 000 кг» → «Сохранить» →
 * «Расчёт сохранён в истории» → «История расчётов (1)» lists it with its main line. Against a MockWebServer on the
 * device, as [SignInHomeTest]. With the instrumentation argument `shots=true` the calculator is saved to `files/shots/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AgroFlowTest {
    private val compose = createEmptyComposeRule()

    private val onFailure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e(TAG, "failed: $e; session=${app.graph.session.state.value}; requests=$seen")
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog(TAG) }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(onFailure).around(compose)

    private val server = MockWebServer()
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var graph: AppGraph
    private val app = ApplicationProvider.getApplicationContext<TabelApp>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.body.readUtf8()
                seen += "${request.method} ${request.path}"
                return when (request.requestUrl!!.encodedPath) {
                    "/auth/login" -> json("""{"token":"tok-ui","role":"owner"}""")
                    "/me" -> json(
                        """{"userId":"u1","role":"owner","farm":{"id":"f1","name":"КФХ Зубахин",""" +
                            """"region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"Asia/Chita"}}""",
                    )
                    "/sync/pull" -> json("""{"changes":[],"next":0,"more":false}""")
                    "/sync/push" -> json("""{"results":[]}""")
                    "/conflicts" -> json("""{"conflicts":[]}""")
                    "/auth/logout" -> json("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        app.deleteDatabase(DB)
        app.getSharedPreferences("agro", 0).edit().clear().commit()
        graph = AppGraph(
            app,
            baseUrl = server.url("/").toString(),
            tokens = MemoryTokenStore(),
            dbName = DB,
            photosDir = File(app.cacheDir, "uitest-agro-photos"),
        )
        app.useGraph(graph)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.close()
        server.shutdown()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Test
    fun seedingCalculationIsSavedToHistory() {
        compose.waitUntilExactlyOneExists(hasTestTag("signInLogin"), 10_000)
        compose.onNodeWithTag("signInLogin").performTextInput("anna")
        compose.onNodeWithTag("signInPassword").performTextInput("secret")
        compose.onNodeWithTag("signInButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openAgro"), 20_000)
        compose.onNodeWithTag("openAgro").performScrollTo().performClick()

        // The eight calculators; the history is empty.
        compose.waitUntilExactlyOneExists(hasTestTag("agro-seeding"), 10_000)
        compose.onNodeWithTag("openAgroHistory").performScrollTo().assertTextEquals("История расчётов (0)")
        compose.onNodeWithTag("agro-seeding").performScrollTo().performClick()

        // «Посев семян»: nothing yet → «Заполните: …», «Сохранить» inactive; 10 га × 200 кг/га → 2 000 кг.
        compose.waitUntilExactlyOneExists(hasTestTag("field-area"), 10_000)
        compose.onNodeWithTag("missing").assertTextEquals("Заполните: Площадь поля, Норма высева")
        compose.onNodeWithTag("saveCalc").assertIsNotEnabled()
        compose.onNodeWithTag("field-area").performTextInput("10")
        compose.onNodeWithTag("field-rate").performTextInput("200")
        compose.onNodeWithTag("agroTitle").performScrollTo().performTextInput("Поле у реки")
        compose.waitUntilExactlyOneExists(hasTestTag("line-0"), 5_000)
        compose.onNodeWithTag("line-0").assertTextEquals(AgroNumber.with(2000.0, "кг"))
        compose.onNodeWithTag("line-2").assertTextEquals(AgroNumber.with(2.0, "т", 3))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("agroResult").performScrollTo()
        shot("task-13-calc")

        // «Сохранить» → the note; one op with the site's fields; the draft is gone.
        compose.onNodeWithTag("saveCalc").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("savedNote"), 10_000)
        compose.onNodeWithTag("savedNote").assertTextEquals("Расчёт сохранён в истории")
        compose.waitUntil(10_000) { ops().count { it.entity == "agroCalculation" } == 1 }
        val op = ops().single()
        assertEquals(op.recordId.lowercase(), op.recordId)
        val f = op.fieldsJson()
        assertEquals("\"seeding\"", f["kind"].toString())
        assertEquals("\"Поле у реки\"", f["title"].toString())
        assertEquals("""{"area":"10","rate":"200"}""", f["inputs"].toString())
        assertTrue(f["lines"].toString().contains("\"Нужно семян\""))
        assertTrue(app.getSharedPreferences("agro", 0).all.isEmpty())

        // «Сохранить» again on the same form: the saved calculation is updated, not saved a second time.
        compose.waitUntilExactlyOneExists(hasText("Сохранить изменения"), 5_000)
        compose.onNodeWithTag("agroTitle").performScrollTo().performTextInput(" 2")
        compose.onNodeWithTag("saveCalc").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Изменения сохранены"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "agroCalculation" } == 2 }
        assertEquals(setOf(op.recordId), ops().map { it.recordId }.toSet())
        compose.onNodeWithTag("back").performClick()

        // The home counts it; the history lists it with the main line.
        compose.waitUntilExactlyOneExists(hasTestTag("openAgroHistory"), 10_000)
        compose.waitUntilExactlyOneExists(hasText("История расчётов (1)"), 10_000)
        compose.onNodeWithTag("openAgroHistory").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("agroCount"), 10_000)
        compose.onNodeWithTag("agroCount").assertTextEquals("Расчёты (1)")
        compose.onNodeWithTag("calcMain-${op.recordId}", useUnmergedTree = true).assertTextEquals("Нужно семян: " + AgroNumber.with(2000.0, "кг"))
        compose.onNodeWithTag("filterKind-fuel").performClick()
        compose.onNodeWithTag("agroCount").assertTextEquals("Расчёты (0)")
    }

    private fun ops() = runBlocking { graph.db.outbox().all() }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val DB = "uitest-agro.db"
        const val TAG = "AgroFlowTest"
    }
}
