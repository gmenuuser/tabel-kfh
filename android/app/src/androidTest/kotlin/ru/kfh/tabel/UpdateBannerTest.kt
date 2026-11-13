package ru.kfh.tabel

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import ru.kfh.tabel.update.AndroidPackageFacts
import ru.kfh.tabel.update.ApkJudge
import ru.kfh.tabel.update.PackageFacts
import ru.kfh.tabel.update.PackageFactsSource
import ru.kfh.tabel.update.Verdict
import java.io.File

/**
 * The self-update on the device, against the [FarmHarness]'s MockWebServer: a newer `versionCode` in `version.json`
 * is downloaded and the main screen shows «Доступна новая версия …» with «Установить» (also in «О приложении»);
 * the same code shows nothing and «Проверить обновления» says so. The file served is not an `.apk` (a real one
 * signed with the test key is out of reach here): [BumpedFacts] reads the installed app's real facts and reports the
 * downloaded file as the same package, same certificates, one code higher — the judgement itself is on the JVM
 * (`ApkJudgeTest`) and the package manager's reading of a real `.apk` in [PackageFactsDeviceTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateBannerTest {
    /** The installed app as it is; a non-empty downloaded file counts as the next build with the same key. */
    class BumpedFacts : PackageFactsSource {
        private val real = AndroidPackageFacts(ApplicationProvider.getApplicationContext())
        override fun installed(): PackageFacts = real.installed()
        override fun archive(file: File): PackageFacts? =
            if (file.isFile && file.length() > 0) installed().copy(versionCode = installed().versionCode + 1) else null
    }

    private val compose = createEmptyComposeRule()
    private val farm = FarmHarness(compose, "uitest-update.db", packageFacts = BumpedFacts())

    @get:Rule val rules: RuleChain = RuleChain.outerRule(farm).around(compose)

    private val installed: Long get() = AppGraph.appVersionCode(farm.app)

    private fun serve(code: Long) {
        farm.extraRoutes = { path ->
            when (path) {
                "/android/version.json" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"versionCode": $code, "versionName": "1.0.$code", "url": "https://example.com/other.apk"}""")
                "/android/tabel-kfh.apk" -> MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive")
                    .setBody(Buffer().write(ByteArray(64 * 1024) { 7 }))
                else -> null
            }
        }
    }

    @Test
    fun bannerForANewerVersion() {
        val next = installed + 1
        serve(next)
        farm.launch()
        farm.connected()
        compose.waitUntilExactlyOneExists(hasTestTag("updateBanner"), 20_000)
        compose.onNodeWithTag("updateTitle", useUnmergedTree = true).assertTextEquals("Доступна новая версия 1.0.$next")
        compose.onNodeWithTag("installUpdate").assertIsDisplayed()
        farm.shot("autoupdate-banner")
        val file = File(farm.updatesDir, "tabel-kfh-$next.apk")
        assertTrue("downloaded file ${file.path}", file.isFile)
        assertEquals(64L * 1024, file.length())
        // The foreign `url` in version.json was not followed: the download came from the file next to version.json.
        assertTrue(farm.seen.toString(), "GET /android/tabel-kfh.apk" in farm.seen)
        // «О приложении» offers the same button, and the old way to open the file.
        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("installUpdateAbout").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("openUpdateFile").performScrollTo().assertIsDisplayed()
        farm.shot("autoupdate-about")
    }

    @Test
    fun nothingForTheSameVersionAndTheCheckSaysSo() {
        serve(installed)
        farm.launch()
        farm.connected()
        compose.waitUntil(10_000) { farm.seen.any { it == "GET /android/version.json" } }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag("updateBanner")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("checkUpdates").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("У вас последняя версия."), 10_000)
        assertEquals(0, farm.updatesDir.listFiles().orEmpty().size)
    }
}

/** The package manager reads a real signed `.apk` — this app's own file — the way the self-update does. */
@RunWith(AndroidJUnit4::class)
class PackageFactsDeviceTest {
    @Test
    fun theInstalledApkReadsAsTheInstalledApp() {
        val app: TabelApp = ApplicationProvider.getApplicationContext()
        val facts = AndroidPackageFacts(app)
        val installed = facts.installed()
        val archive = facts.archive(File(app.applicationInfo.sourceDir))
        assertEquals(installed, archive)
        assertEquals("ru.kfh.tabel", installed.packageName)
        assertNotEquals(0, installed.certificates.size)
        assertTrue(installed.certificates.all { it.length == 64 })
        // The same build is not an upgrade; the same file would be one for a phone one build behind.
        assertTrue(ApkJudge.judge(archive, installed) is Verdict.Rejected)
        assertEquals(Verdict.Accepted, ApkJudge.judge(archive, installed.copy(versionCode = installed.versionCode - 1)))
        // Another key is turned down even for a newer code.
        assertEquals(Verdict.Rejected("другая подпись"), ApkJudge.judge(archive, installed.copy(versionCode = 1, certificates = setOf("0".repeat(64)))))
        // A file that is not an archive reads as nothing.
        val junk = File(app.cacheDir, "junk.apk").apply { writeText("not an apk") }
        assertEquals(null, facts.archive(junk))
        junk.delete()
    }
}
