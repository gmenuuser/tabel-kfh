package ru.kfh.tabel

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import ru.kfh.tabel.session.MemoryTokenStore
import ru.kfh.tabel.update.MemoryLastCheck
import ru.kfh.tabel.update.PackageFactsSource
import java.io.File
import java.net.InetAddress
import java.util.Collections

/**
 * The shared harness of the walkthroughs ([WalkthroughTest], [SmallScreenLargeFontTest]): a MockWebServer on the
 * device answering the farm's endpoints for [role] (the same answers as the flow tests of Tasks 9–15), an [AppGraph]
 * over it (production stays https://kfh.cubely.ru), and the activity. The server and the graph start with the
 * test; the activity starts when the test calls [launch], so a test can first change the display or the theme.
 *
 * Screenshots ([shot]) go to `<external files>/review-samples/screens/<name>.png` of the app, next to the report
 * samples of [ReportSamplesTest]: CI pulls the whole folder into the «review-samples» artifact.
 */
@OptIn(ExperimentalTestApi::class)
class FarmHarness(
    private val compose: ComposeTestRule,
    private val dbName: String,
    @Volatile var role: String = "owner",
    /** The self-update's judgement of a downloaded file; null is the package manager's own ([UpdateBannerTest] fakes it). */
    private val packageFacts: PackageFactsSource? = null,
) : TestWatcher() {
    val app: TabelApp = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Answers asked before the farm's endpoints (the self-update's `version.json` and `.apk`); null falls through. */
    @Volatile var extraRoutes: (path: String) -> MockResponse? = { null }
    lateinit var graph: AppGraph
        private set
    /** Where the self-update puts its files for this test (empty at the start). */
    lateinit var updatesDir: File
        private set
    private var scenario: ActivityScenario<MainActivity>? = null
    private var tag = "FarmHarness"

    override fun starting(description: Description) {
        tag = description.testClass.simpleName
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                seen += "${request.method} $path"
                extraRoutes(path)?.let { return it }
                return when (path) {
                    "/devices/register" -> json("""{"token":"tok-ui","deviceId":"d9","farmId":"f1","role":"manager","kind":"app"}""").setResponseCode(201)
                    "/me" -> json(
                        """{"userId":"u1","role":"$role","farm":{"id":"f1","name":"КФХ Зубахин",""" +
                            """"region":"Приаргунский район","managerName":"Зубахин А. В.","timezone":"Asia/Chita"}}""",
                    )
                    "/sync/pull" -> json("""{"changes":[],"next":0,"more":false}""")
                    "/sync/push" -> json("""{"results":[]}""")
                    "/conflicts" -> json("""{"conflicts":[]}""")
                    "/members" -> json("""[{"id":"o1","login":"zubakhin","role":"owner"},{"id":"m1","login":"anna","role":"timesheet"}]""")
                    "/devices" -> json(
                        """[{"id":"d1","name":"Google Pixel 9","platform":"android","login":"zubakhin","lastSeenAt":"2026-11-06T03:00:00.000Z","revoked":false,"current":true}]""",
                    )
                    "/backups" -> json("""[]""")
                    "/auth/logout" -> json("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Не найдено"}""")
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // The previous test closed its graph: nothing holds the file any more.
        app.deleteDatabase(dbName)
        app.getSharedPreferences("agro", 0).edit().clear().commit()
        val photos = File(app.cacheDir, "uitest-$dbName-photos").apply { deleteRecursively() }
        updatesDir = File(app.cacheDir, "uitest-$dbName-updates").apply { deleteRecursively() }
        graph = AppGraph(
            app, baseUrl = server.url("/").toString(), tokens = MemoryTokenStore(), dbName = dbName, photosDir = photos,
            packageFacts = packageFacts ?: ru.kfh.tabel.update.AndroidPackageFacts(app), updatesDir = updatesDir,
            lastUpdateCheck = MemoryLastCheck(),
        )
        app.useGraph(graph)
    }

    /** Starts the activity over the graph (after any display or theme change of the test). */
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    override fun failed(e: Throwable, description: Description) {
        Log.e(tag, "failed: $e; session=${app.graph.session.state.value}; requests=$seen")
        runCatching { compose.onRoot(useUnmergedTree = true).printToLog(tag) }
        // The compose rule is already gone here: no waitForIdle, just the picture.
        runCatching { save("failed-${description.methodName}") }
    }

    override fun finished(description: Description) {
        scenario?.close()
        // Synchronous: the triggers, the coroutines and the database are gone before the next test deletes the file.
        graph.close()
        server.shutdown()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    /** The phone connects itself at launch: returns once the main screen offers «ТАБЕЛЬ РАБОТНИКОВ». */
    fun connected() {
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 30_000)
    }

    /** «Назад» until the main screen shows (at most five screens deep). */
    fun backToMain() {
        repeat(5) {
            if (compose.onAllNodes(hasTestTag("openTimesheet")).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNodeWithTag("back").performClick()
            compose.waitForIdle()
        }
        compose.waitUntilExactlyOneExists(hasTestTag("openTimesheet"), 5_000)
    }

    /** The screen as the owner sees it, into the review samples (always: the walkthroughs exist for the pictures). */
    fun shot(name: String) {
        compose.waitForIdle()
        // Compose is idle before the frame is on the display; on the software-rendered emulator UiAutomation's
        // capture was otherwise a frame behind (the loading screen instead of the main screen).
        Thread.sleep(600)
        compose.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(screensDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    val screensDir: File
        get() = File(app.getExternalFilesDir(null), "review-samples/screens").apply { mkdirs() }

    companion object {
        /** Runs [command] as the shell (the instrumentation's UiAutomation) and returns its output. */
        fun shell(command: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}

/** The text node of [text] inside the node tagged [tag] (a button's own label, not the merged button). */
fun ComposeTestRule.onLabel(tag: String, text: String): SemanticsNodeInteraction =
    onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

/**
 * The text is shown whole: its layout neither overflows its box (clipped lines) nor ends in «…».
 * Fails with the text, so the report names the button.
 */
fun SemanticsNodeInteraction.assertTextNotCut(): SemanticsNodeInteraction {
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    assertTrue("no text layout for the node", results.isNotEmpty())
    val r = results.first()
    val text = r.layoutInput.text.text
    // Not `hasVisualOverflow`: for a short single line Compose keeps the paragraph at the max width while the node
    // shrinks to the line, and `didOverflowWidth` is then true without anything being cut. Compare the lines' edges
    // with the node's width instead; the height check is Compose's own (lines beyond maxLines or the box).
    val lines = (0 until r.lineCount).joinToString { "[${r.getLineLeft(it)}..${r.getLineRight(it)}]" }
    // A centered line sits at an offset inside the paragraph's width: compare the line's own width, not its right edge.
    val widthCut = (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 0.5f }
    assertFalse(
        "«$text» is clipped: ${r.lineCount} lines $lines, size=${r.size}, widthCut=$widthCut, heightCut=${r.didOverflowHeight}",
        widthCut || r.didOverflowHeight,
    )
    for (line in 0 until r.lineCount) assertFalse("«$text» is ellipsized on line ${line + 1}", r.isLineEllipsized(line))
    assertFalse("«$text» ends in «…»", text.endsWith("…"))
    return this
}
