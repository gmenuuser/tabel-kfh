package ru.kfh.tabel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.ids.StableId
import ru.kfh.tabel.session.MemoryTokenStore
import java.io.File
import java.net.InetAddress
import java.util.Collections

/**
 * Sign in → «Продажа угля» → 2 т, Новоцурухайтуй, a new hired driver Мунгалов → «СОХРАНИТЬ ПРОДАЖУ» → the sale is
 * in «Продажи за сегодня» with «14 000 ₽» → «Итоги дня и выплаты» shows the day's totals and the driver's debt.
 * Against a MockWebServer on the device, as [SignInHomeTest]. With the instrumentation argument `shots=true`
 * the sale screen and the totals are saved to `files/shots/` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoalFlowTest {
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
        graph = AppGraph(
            app,
            baseUrl = server.url("/").toString(),
            tokens = MemoryTokenStore(),
            dbName = DB,
            photosDir = File(app.cacheDir, "uitest-coal-photos"),
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
    fun sellTwoTonsToNovotsurukhaituyWithMungalov() {
        compose.waitUntilExactlyOneExists(hasTestTag("signInLogin"), 10_000)
        compose.onNodeWithTag("signInLogin").performTextInput("anna")
        compose.onNodeWithTag("signInPassword").performTextInput("secret")
        compose.onNodeWithTag("signInButton").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openCoal"), 20_000)
        compose.onNodeWithTag("openCoal").performClick()

        // The sale screen: the price, no sales yet, the owner's two villages seeded on this first opening.
        compose.waitUntilExactlyOneExists(hasText("Продажа угля"), 10_000)
        compose.waitUntilExactlyOneExists(hasTestTag("location-Новоцурухайтуй"), 10_000)
        compose.onNodeWithTag("pricePerTon").assertTextEquals("${CoalMath.rubles(650_000)} за тонну")
        compose.onNodeWithTag("noSales").assertExists()
        compose.onNodeWithTag("salesCount").assertTextEquals("0")

        // 2 т, Новоцурухайтуй, a hired truck of a new driver Мунгалов.
        compose.onNodeWithTag("tonsField").performTextInput("2")
        compose.onNodeWithTag("location-Новоцурухайтуй").performScrollTo().performClick()
        compose.onNodeWithTag("location-Новоцурухайтуй").assertIsSelected()
        compose.onNodeWithTag("addDriver").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("driverLastName"), 5_000)
        compose.onNodeWithTag("driverLastName").performTextInput("Мунгалов")
        compose.onNodeWithTag("driverSave").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("driver-Мунгалов"), 10_000)
        compose.onNodeWithTag("driver-Мунгалов").assertIsSelected()
        compose.onNodeWithTag("saleTotal").performScrollTo().assertTextEquals(CoalMath.rubles(1_400_000))

        // «СОХРАНИТЬ ПРОДАЖУ» → the sale is in today's list with its total, the header counts it, the form is clean.
        compose.onNodeWithTag("saveSale").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("savedNote"), 10_000)
        compose.onNodeWithTag("savedNote").assertTextEquals("Сохранено: ${CoalMath.tonsText(2000)}, Новоцурухайтуй, ${CoalMath.rubles(1_400_000)}")
        val saleId = ops().single { it.entity == "coalSale" }.recordId
        compose.onNodeWithTag("sale-$saleId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("saleTotal-$saleId", useUnmergedTree = true).assertTextEquals(CoalMath.rubles(1_400_000))
        compose.onNodeWithText("Наёмная: Мунгалов", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("salesCount").performScrollTo().assertTextEquals("1")
        compose.onNodeWithTag("dayRevenue").assertTextEquals(CoalMath.rubles(1_400_000))
        compose.onNodeWithTag("noSales").assertDoesNotExist()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("sale-$saleId").performScrollTo()
        shot("task-11-sale")

        // «Итоги дня и выплаты»: the totals and Мунгалов's 1 000 ₽ to pay.
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openSummary").performClick()
        compose.waitUntilExactlyOneExists(hasText("Итоги рабочего дня"), 10_000)
        compose.waitUntilExactlyOneExists(hasTestTag("totalRevenue"), 10_000)
        compose.onNodeWithTag("totalRevenue").assertTextEquals(CoalMath.rubles(1_400_000))
        val driverId = ops().single { it.entity == "driver" }.recordId
        compose.onNodeWithTag("toPay-$driverId").performScrollTo().assertTextEquals(CoalMath.rubles(100_000))
        compose.onNodeWithTag("pay-$driverId").assertExists()
        compose.onNodeWithTag("closeDay").performScrollTo().assertExists()
        shot("task-11-totals")

        // What was queued: the settings row, the two villages (stable ids), the driver, the sale — all lowercase.
        val all = ops()
        assertEquals(1, all.count { it.entity == "coalSettings" })
        assertEquals(StableId.coalSettings, all.single { it.entity == "coalSettings" }.recordId)
        assertEquals(
            setOf(StableId.deliveryLocation("Новоцурухайтуй"), StableId.deliveryLocation("Приаргунск")),
            all.filter { it.entity == "deliveryLocation" }.map { it.recordId }.toSet(),
        )
        assertEquals(1, all.count { it.entity == "driver" })
        assertEquals(1, all.count { it.entity == "coalSale" })
        assertEquals(0, all.count { it.entity == "driverPayment" })
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
        const val DB = "uitest-coal.db"
        const val TAG = "CoalFlowTest"
    }
}
