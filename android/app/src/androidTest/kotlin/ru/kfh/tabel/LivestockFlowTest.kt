package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import ru.kfh.core.ids.StableId
import ru.kfh.tabel.session.MemoryTokenStore
import java.io.File
import java.net.InetAddress
import java.util.Collections

/**
 * Sign in → «СКОТ И ЗАБОЙ» → the default categories are seeded, the herd is 0 → «Поголовье» → «Добавить» Коровы → 5 →
 * «СОХРАНИТЬ» → Коровы 5 → «Записать забой» → Коровы, 2 → «СОХРАНИТЬ ЗАБОЙ» → «Продолжить» → «Записано: …» → the
 * home shows КРС 3, «3 головы». Against a MockWebServer on the device, as [SignInHomeTest]. With the instrumentation
 * argument `shots=true` the home screen is saved to `files/shots/` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LivestockFlowTest {
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
                    "/devices/register" -> json("""{"token":"tok-ui","deviceId":"d9","farmId":"f1","role":"manager","kind":"app"}""").setResponseCode(201)
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
        graph = AppGraph(
            app,
            baseUrl = server.url("/").toString(),
            tokens = MemoryTokenStore(),
            dbName = DB,
            photosDir = File(app.cacheDir, "uitest-livestock-photos"),
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

    /** Waits for the home's cattle count to be [heads], then asserts it (the failure names the node). */
    private fun herdShows(heads: String) {
        runCatching {
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("herd-cattle").assertTextEquals(heads) }.isSuccess
            }
        }
        compose.onNodeWithTag("herd-cattle").assertTextEquals(heads)
    }

    @Test
    fun addFiveCowsAndSlaughterTwo() {
        compose.waitUntilExactlyOneExists(hasTestTag("openLivestock"), 20_000)
        compose.onNodeWithTag("openLivestock").performClick()

        // The home: the herd is empty; the first opening seeds the settings row and the 15 default categories.
        compose.waitUntilExactlyOneExists(hasText("Скот и забой"), 10_000)
        compose.onNodeWithTag("herd-cattle").assertTextEquals("0")
        compose.onNodeWithTag("herdTotal").assertTextEquals("0 голов")
        compose.waitUntil(10_000) { ops().count { it.entity == "livestockCategory" } == 15 }

        // «Поголовье» → «Добавить» Коровы → 5 → «СОХРАНИТЬ».
        compose.onNodeWithTag("openHerd").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("heads-Коровы"), 10_000)
        compose.onNodeWithTag("heads-Коровы").assertTextEquals("0")
        compose.onNodeWithTag("decrease-Коровы").assertIsNotEnabled()
        compose.onNodeWithTag("add-Коровы").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("headsField"), 10_000)
        compose.onNodeWithTag("formCategory").assertTextEquals("Коровы")
        compose.onNodeWithTag("kind-purchase").assertIsSelected()
        compose.onNodeWithTag("headsField").performTextInput("5")
        compose.onNodeWithTag("saveOperation").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("heads-Коровы"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "livestockOperation" } == 1 }
        compose.onNodeWithTag("heads-Коровы").assertTextEquals("5")
        compose.onNodeWithTag("speciesTotal").assertTextEquals("Всего: 5 голов")
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openSlaughter"), 10_000)
        // The home opens on an empty herd (`collectAsState(initial = Herd.EMPTY)`) until the count arrives.
        herdShows("5")

        // «Записать забой»: КРС, Коровы (есть 5 голов), 2 heads, «На мясо» → confirm → the note; the form is clean.
        compose.onNodeWithTag("openSlaughter").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("category-Коровы"), 10_000)
        compose.onNodeWithTag("species-cattle").assertIsSelected()
        compose.onNodeWithTag("category-Телята").assertIsNotEnabled()
        compose.onNodeWithTag("category-Коровы").performClick()
        compose.onNodeWithTag("category-Коровы").assertIsSelected()
        compose.onNodeWithTag("quick-2").performClick()
        compose.onNodeWithTag("reason-meat").assertIsSelected()
        compose.onNodeWithTag("saveSlaughter").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Будет списано: 2 головы КРС (Коровы). Продолжить?"), 5_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("savedNote"), 10_000)
        compose.onNodeWithTag("savedNote").assertTextEquals("Записано: забой 2 головы КРС (Коровы)")
        compose.onNodeWithTag("species-cattle").performScrollTo()
        compose.onNodeWithTag("category-Коровы").assertIsNotSelected()
        compose.onNodeWithTag("back").performClick()

        // The home counts the herd from the operations: 5 − 2.
        compose.waitUntilExactlyOneExists(hasTestTag("herdSummary"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "slaughterRecord" } == 1 }
        herdShows("3")
        compose.onNodeWithTag("herd-goats").assertTextEquals("0")
        compose.onNodeWithTag("herdTotal").assertTextEquals("3 головы")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        shot("task-12-home")

        // The history lists both, newest first; the slaughter shows its reason.
        compose.onNodeWithTag("openLivestockHistory").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("operationsCount"), 10_000)
        compose.onNodeWithTag("operationsCount").assertTextEquals("Операции (2)")
        val slaughterId = ops().single { it.entity == "slaughterRecord" }.fieldsJson()["operationId"].toString().trim('"')
        compose.onNodeWithTag("opHeads-$slaughterId", useUnmergedTree = true).assertTextEquals("−2")

        // What was queued: the settings row (stable id), 15 categories (stable ids), 2 operations, 1 slaughter record — all lowercase.
        val all = ops()
        assertEquals(1, all.count { it.entity == "livestockSettings" })
        assertEquals(StableId.livestockSettings, all.single { it.entity == "livestockSettings" }.recordId)
        assertEquals(15, all.count { it.entity == "livestockCategory" })
        assertTrue(StableId.livestockCategory("cattle", "Коровы") in all.map { it.recordId })
        assertEquals(2, all.count { it.entity == "livestockOperation" })
        assertEquals(1, all.count { it.entity == "slaughterRecord" })
        assertTrue(all.all { it.recordId == it.recordId.lowercase() })
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
        const val DB = "uitest-livestock.db"
        const val TAG = "LivestockFlowTest"
    }
}
