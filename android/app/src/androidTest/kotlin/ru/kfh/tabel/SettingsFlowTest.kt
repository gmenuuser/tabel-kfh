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
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertTrue
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

/**
 * «Настройки» against a MockWebServer on the device: the owner sees the farm fields, the sync with its conflicts,
 * the owner's rows, resolves a conflict, revokes a device and signs out; a helper sees the conflicts count with
 * «обратитесь к владельцу» and none of the owner's rows. With the instrumentation argument `shots=true` the
 * owner's settings screen is saved to `files/shots/settings.png` of the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
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
    @Volatile private var role = "owner"
    @Volatile private var conflicts = listOf("c1")
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var graph: AppGraph
    private val app = ApplicationProvider.getApplicationContext<TabelApp>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                seen += "${request.method} $path"
                return when (path) {
                    "/devices/register" -> json("""{"token":"tok-ui","deviceId":"d9","farmId":"f1","role":"manager","kind":"app"}""").setResponseCode(201)
                    "/me" -> json(
                        """{"userId":"u2","role":"$role","farm":{"id":"f1","name":"КФХ Зубахин",""" +
                            """"region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"Asia/Chita"}}""",
                    )
                    "/sync/pull" -> json("""{"changes":[],"next":0,"more":false}""")
                    "/sync/push" -> json("""{"results":[]}""")
                    "/conflicts" -> json(
                        """{"conflicts":[""" + conflicts.joinToString(",") {
                            """{"id":"$it","entity":"driver","recordId":"0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01","field":"lastName",""" +
                                """"serverValue":"Мунгалов","clientValue":"Мунгалов-Петров","createdAt":"2026-11-06T03:00:00.000Z"}"""
                        } + "]}",
                    )
                    "/conflicts/c1/resolve" -> { conflicts = emptyList(); json("""{"ok":true}""") }
                    "/members" -> json("""[{"id":"o1","login":"zubakhin","role":"owner"},{"id":"m1","login":"anna","role":"timesheet"}]""")
                    "/devices" -> json(
                        """[{"id":"d1","name":"Google Pixel 9","platform":"android","login":"zubakhin","lastSeenAt":"2026-11-06T03:00:00.000Z","revoked":false,"current":true},""" +
                            """{"id":"d2","name":"iPhone","platform":"ios","login":"anna","lastSeenAt":"2026-11-05T03:00:00.000Z","revoked":false,"current":false}]""",
                    )
                    "/devices/d2/revoke" -> json("""{"ok":true}""")
                    "/backups" -> json("""[{"id":"b1","records":120,"created_at":"2026-11-06T01:00:00.000Z"}]""")
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

    /** The phone connects itself at launch (`/me` answers [role] as for a device of the old builds). */
    private fun connectAndOpenSettings() {
        compose.waitUntilExactlyOneExists(hasTestTag("openSettings"), 15_000)
        compose.onNodeWithTag("openSettings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 5_000)
    }

    @Test
    fun ownerSeesEverythingResolvesAConflictRevokesADeviceAndSignsOut() {
        role = "owner"
        connectAndOpenSettings()

        // The farm: the owner's fields and the printed ones; the sync with its conflict; the owner's rows; the version.
        compose.onNodeWithTag("farmName").assertIsDisplayed()
        compose.onNodeWithTag("managerName").assertExists()
        compose.onNodeWithTag("responsiblePerson").assertExists()
        compose.waitUntilExactlyOneExists(hasTestTag("openConflicts"), 10_000)
        compose.onNodeWithText("Конфликты: 1 — выбрать версию").assertExists()
        compose.onNodeWithTag("openHelpers").assertExists()
        compose.onNodeWithTag("openDevices").assertExists()
        compose.onNodeWithTag("openServerBackups").assertExists()
        compose.onNodeWithTag("saveBackup").assertExists()
        compose.onNodeWithTag("version").assertExists()
        compose.onNodeWithTag("openCoalPrices").assertExists()
        shot("settings")

        // Helpers: the list from the server, without the owner.
        compose.onNodeWithTag("openHelpers").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("helper-m1"), 10_000)
        compose.onNodeWithText("anna").assertExists()
        compose.onNodeWithTag("helper-o1").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()

        // Devices: the lost iPhone is revoked after the question.
        compose.waitUntilExactlyOneExists(hasTestTag("openDevices"), 5_000)
        compose.onNodeWithTag("openDevices").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("revoke-d2"), 10_000)
        compose.onNodeWithText("Google Pixel 9 (это устройство)").assertExists()
        compose.onNodeWithTag("revoke-d2").performClick()
        compose.waitUntilExactlyOneExists(hasText("Отозвать доступ этого устройства?"), 5_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilExactlyOneExists(hasText("Доступ отозван"), 10_000)
        compose.onNodeWithTag("noteOk").performClick()
        compose.onNodeWithTag("back").performClick()

        // Conflicts: «Взять с сервера» resolves it; the sync after it clears the line.
        compose.waitUntilExactlyOneExists(hasTestTag("openConflicts"), 5_000)
        compose.onNodeWithTag("openConflicts").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("takeServer-c1"), 10_000)
        compose.onNodeWithText("Водитель · lastName").assertExists()
        compose.onNodeWithText("Мунгалов-Петров").assertExists()
        compose.onNodeWithTag("takeServer-c1").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("noConflicts"), 10_000)
        assertTrue(seen.contains("POST /conflicts/c1/resolve"))
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 5_000)
        compose.waitUntilDoesNotExist(hasTestTag("openConflicts"), 10_000)

        // «Выйти»: the question, then the phone forgets the farm and connects again as a new device.
        compose.onNodeWithTag("signOut").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Выйти из хозяйства?"), 5_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 15_000)
        assertTrue(seen.contains("POST /auth/logout"))
        assertEquals(2, seen.count { it == "POST /devices/register" })
    }

    @Test
    fun helperSeesTheConflictsCountOnly() {
        role = "coal"
        connectAndOpenSettings()
        compose.waitUntilExactlyOneExists(hasTestTag("conflictsNote"), 10_000)
        compose.onNodeWithTag("conflictsNote").assertTextEquals("Конфликты: 1 — обратитесь к владельцу")
        compose.onNodeWithTag("openConflicts").assertDoesNotExist()
        compose.onNodeWithTag("openHelpers").assertDoesNotExist()
        compose.onNodeWithTag("openDevices").assertDoesNotExist()
        compose.onNodeWithTag("openServerBackups").assertDoesNotExist()
        // The farm's name is read, not edited; the printed fields are not the coal helper's either.
        compose.onNodeWithTag("farmNameRow").assertExists()
        compose.onNodeWithTag("farmName").assertDoesNotExist()
        compose.onNodeWithTag("openCoalPrices").assertExists()
        compose.onNodeWithText("Вы вошли в «КФХ Зубахин»: только уголь. При выходе данные хозяйства удаляются с этого телефона; на сервере они остаются.").assertExists()
    }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val DB = "uitest-settings.db"
        const val TAG = "SettingsFlowTest"
    }
}
