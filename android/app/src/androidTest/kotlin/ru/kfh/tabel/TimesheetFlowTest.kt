package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * Sign in → «Табель работников» → «Добавить сотрудника» → 8 ч for today, 4 ч for tomorrow → «Табель за месяц»
 * shows both cells → «Старые отметки (архив)» is empty. Only hours marks are written, never a status record.
 * Against a MockWebServer on the device, as [SignInHomeTest]. With the instrumentation argument `shots=true` the
 * day and month screens are saved to `files/shots/` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimesheetFlowTest {
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
        File(app.cacheDir, "uitest-photos").deleteRecursively()
        graph = AppGraph(
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
        scenario.close()
        graph.close()
        server.shutdown()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    @Test
    fun addEmployeeMarkHoursAndSeeTheMonth() {
        compose.waitUntilExactlyOneExists(hasTestTag("signInLogin"), 10_000)
        compose.onNodeWithTag("signInLogin").performTextInput("anna")
        compose.onNodeWithTag("signInPassword").performTextInput("secret")
        compose.onNodeWithTag("signInButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 20_000)
        compose.onNodeWithTag("openTimesheet").performClick()

        // The day screen: today at the farm, nobody yet.
        compose.waitUntilExactlyOneExists(hasText("Табель работников"), 10_000)
        compose.onNodeWithText("В этот день нет работающих сотрудников.").assertIsDisplayed()
        compose.onNodeWithTag("day-Сегодня").assertIsDisplayed()
        val today = graph.timesheet.today()
        compose.onNodeWithText(ru.kfh.tabel.ui.home.longDate(today)).assertIsDisplayed()

        // «Добавить сотрудника»: the form, hired today, saved.
        compose.onNodeWithTag("addEmployee").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Новый сотрудник"), 10_000)
        // Below the fold on a 360×640 dp screen (the CI's Galaxy Nexus profile).
        compose.onNodeWithText("Дата приёма").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lastName").performTextInput("Зубахин")
        compose.onNodeWithTag("firstName").performTextInput("Иван")
        compose.onNodeWithTag("position").performTextInput("Скотник")
        // The activity is destroyed and rebuilt from its saved state (what a process death behind the camera leaves):
        // the typed form must still be there.
        scenario.recreate()
        compose.waitUntilExactlyOneExists(hasTestTag("lastName"), 10_000)
        compose.onNodeWithTag("lastName").assertTextContains("Зубахин")
        compose.onNodeWithTag("firstName").assertTextContains("Иван")
        compose.onNodeWithTag("position").assertTextContains("Скотник")
        compose.onNodeWithTag("save").performClick()

        // Back on the day: the person, «Не отмечен» and the four hours — no «Я» button, no status menu or chip.
        // (The keyboard the rebuilt field raised may still be on its way down: scroll to the row rather than assume
        // it is in view.)
        compose.waitUntilExactlyOneExists(hasTestTag("row-Зубахин"), 10_000)
        compose.onNodeWithText("Зубахин Иван").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отмечено: 0 из 1").assertIsDisplayed()
        compose.onNodeWithTag("hoursText-Зубахин").assertTextEquals("Не отмечен")
        compose.onNodeWithTag("mark-Зубахин").assertDoesNotExist()
        compose.onNodeWithTag("statusMenu-Зубахин").assertDoesNotExist()
        compose.onNodeWithTag("status-Зубахин").assertDoesNotExist()
        compose.onNodeWithText("Был на работе").assertDoesNotExist()
        compose.onNodeWithTag("hours-8-Зубахин").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 8 ч"), 10_000)
        compose.onNodeWithText("Отмечено: 1 из 1").assertIsDisplayed()
        compose.onNodeWithText("Часов: 8").assertIsDisplayed()
        compose.onNodeWithTag("markAll").assertDoesNotExist()
        shot("hours-only-day")

        // «Завтра»: the same four buttons (hours can be set ahead, as on the iPhone).
        compose.onNodeWithTag("day-Завтра").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText(ru.kfh.tabel.ui.home.longDate(today.plusDays(1))), 10_000)
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 0 из 1"), 10_000)
        compose.onNodeWithTag("hours-4-Зубахин").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 4 ч"), 10_000)
        compose.onNodeWithText("Часов: 4").assertIsDisplayed()
        compose.onNodeWithTag("day-Сегодня").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 8 ч"), 10_000)

        // «Табель за месяц»: the two hours cells, no «Часы / Обозначения» switch.
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openMonth").performClick()
        compose.waitUntilExactlyOneExists(hasText("Табель за месяц"), 10_000)
        val employeeId = ops().single { it.entity == "employee" }.recordId
        val cell = "cell-$employeeId-${today.dayOfMonth}"
        compose.waitUntilExactlyOneExists(hasTestTag(cell), 10_000)
        compose.onNodeWithTag(cell).assertTextEquals("8")
        if (today.plusDays(1).month == today.month) {
            compose.onNodeWithTag("cell-$employeeId-${today.plusDays(1).dayOfMonth}").assertTextEquals("4")
        }
        compose.onNodeWithTag("kind-HOURS").assertDoesNotExist()
        compose.onNodeWithTag("kind-CODES").assertDoesNotExist()
        compose.onNodeWithText("Всего отработано").assertExists()
        shot("hours-only-month")

        // «Старые отметки (архив)»: the old status records, read only; this farm has none.
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("more"), 10_000)
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openOldMarks").performClick()
        compose.waitUntilExactlyOneExists(hasText("Старые отметки"), 10_000)
        compose.waitUntilExactlyOneExists(hasText("В этом месяце старых отметок нет"), 10_000)
        compose.onNodeWithTag("oldMarksTotal").assertTextEquals("ОТМЕТКИ (0)")

        // What was queued: the employee and the two hours marks on their stable ids; never a status record.
        val all = ops()
        assertEquals(listOf("employee"), all.filter { it.entity == "employee" }.map { it.entity })
        assertEquals(
            setOf(StableId.hoursMark(employeeId, today), StableId.hoursMark(employeeId, today.plusDays(1))),
            all.filter { it.entity == "hoursMark" }.map { it.recordId }.toSet(),
        )
        assertEquals(0, all.count { it.entity == "attendanceRecord" })
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
        const val DB = "uitest-timesheet.db"
        const val TAG = "TimesheetFlowTest"
    }
}
