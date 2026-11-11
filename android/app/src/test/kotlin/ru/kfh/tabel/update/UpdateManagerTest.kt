package ru.kfh.tabel.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.tabel.ui.settings.UpdateCheck
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A fake `.apk`: its bytes spell the facts the package manager would read (`pkg=…;code=…;cert=…`), so the whole
 * path — `version.json`, the download, the judgement, the file in the cache — runs on the JVM.
 */
class FakeFacts(private val installedFacts: PackageFacts) : PackageFactsSource {
    override fun installed() = installedFacts

    override fun archive(file: File): PackageFacts? {
        if (!file.isFile) return null
        val text = file.readText().trim()
        val m = Regex("""pkg=([^;]*);code=(\d+);cert=(.*)""").matchEntire(text) ?: return null
        return PackageFacts(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].split(',').filter { it.isNotEmpty() }.toSet())
    }

    companion object {
        fun apk(code: Long, pkg: String = "ru.kfh.tabel", cert: String = "cert-kfh", padding: Int = 3000): ByteArray =
            "pkg=$pkg;code=$code;cert=$cert".padEnd(padding, ' ').toByteArray()
    }
}

/** The self-update up to the verified file: what is downloaded, what is refused, when the server is asked at all. */
class UpdateManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val installed = PackageFacts("ru.kfh.tabel", 41, setOf("cert-kfh"))
    private lateinit var dir: File

    @Volatile private var versionJson: MockResponse = MockResponse().setBody("""{"versionCode": 42, "versionName": "1.0.42"}""")
    @Volatile private var apk: ByteArray = FakeFacts.apk(42)
    /** The apk answer: the whole file, or the tail a `Range` asks for; [cutFirst] cuts the first full answer short. */
    @Volatile private var cutFirst = false

    @Before fun start() {
        dir = tmp.newFolder("updates")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/android/version.json" -> versionJson
                    "/android/tabel-kfh.apk" -> {
                        val range = request.getHeader("Range")
                        if (range != null) {
                            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                            MockResponse().setResponseCode(206)
                                .setHeader("Content-Range", "bytes $from-${apk.size - 1}/${apk.size}")
                                .setBody(Buffer().write(apk, from, apk.size - from))
                        } else if (cutFirst) {
                            cutFirst = false
                            // The size is announced whole, the body stops at a third: a lost connection.
                            MockResponse().setBody(Buffer().write(apk, 0, apk.size / 3))
                                .setHeader("Content-Length", apk.size.toString())
                                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                        } else {
                            MockResponse().setBody(Buffer().write(apk))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private var now = 1_000_000_000_000L
    private val lastCheck = MemoryLastCheck()

    private fun manager(
        allowed: List<String> = listOf(server.url("/").toString()),
        facts: PackageFactsSource = FakeFacts(installed),
    ) = UpdateManager(
        scope,
        check = UpdateCheck(client, server.url("/android/version.json").toString(), installed.versionCode),
        downloader = ApkDownloader(client),
        facts = facts,
        dir = dir,
        allowedPrefixes = allowed,
        lastCheck = lastCheck,
        now = { now },
    )

    private fun paths() = requests.map { it.path }

    @Test fun newerVersionIsDownloadedVerifiedAndReady() = runBlocking {
        val m = manager()
        val o = m.check(force = true)
        assertEquals(UpdateManager.Outcome.Ready(UpdateCheck.Update(42, "1.0.42", server.url("/android/tabel-kfh.apk").toString())), o)
        val ready = m.state.value as UpdateState.Ready
        assertEquals("tabel-kfh-42.apk", ready.file.name)
        assertEquals(dir, ready.file.parentFile)
        assertArrayEquals(apk, ready.file.readBytes())
        assertFalse(ApkDownloader.partOf(ready.file).exists())
        assertEquals(listOf("/android/version.json", "/android/tabel-kfh.apk"), paths())
    }

    @Test fun sameAndOlderVersionsAreUpToDateWithoutDownload() = runBlocking {
        for (code in listOf(41, 40)) {
            requests.clear()
            versionJson = MockResponse().setBody("""{"versionCode": $code, "versionName": "1.0.$code"}""")
            val m = manager()
            assertEquals(UpdateManager.Outcome.UpToDate, m.check(force = true))
            assertEquals(UpdateState.Idle, m.state.value)
            assertEquals(listOf("/android/version.json"), paths())
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun otherSignatureIsRejectedAndDeleted() = runBlocking {
        apk = FakeFacts.apk(42, cert = "cert-debug")
        val m = manager()
        val o = m.check(force = true) as UpdateManager.Outcome.Rejected
        assertEquals("другая подпись", o.reason)
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
    }

    @Test fun otherPackageAndNotNewerFilesAreRejected() = runBlocking {
        apk = FakeFacts.apk(42, pkg = "ru.kfh.other")
        assertTrue(manager().check(force = true) is UpdateManager.Outcome.Rejected)
        // version.json promises 42, the file says 41: not an upgrade.
        apk = FakeFacts.apk(41)
        assertTrue(manager().check(force = true) is UpdateManager.Outcome.Rejected)
        // Newer than the installed app, but not the build that was announced (the banner would name the wrong one).
        apk = FakeFacts.apk(43)
        val o = manager().check(force = true) as UpdateManager.Outcome.Rejected
        assertTrue(o.reason, o.reason.contains("объявлена 42"))
        apk = "not an apk at all".toByteArray()
        assertTrue(manager().check(force = true) is UpdateManager.Outcome.Rejected)
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
    }

    /** `version.json` on a test server names a foreign address: UpdateCheck falls back to the file next to it; a manager that allows only our https host downloads nothing. */
    @Test fun addressOutsideTheAllowedHostsIsNeverFetched() = runBlocking {
        val m = manager(allowed = listOf(UpdateCheck.APK_URL_PREFIX))
        val o = m.check(force = true)
        assertTrue(o.toString(), o is UpdateManager.Outcome.Rejected)
        assertEquals(listOf("/android/version.json"), paths())
        assertEquals(UpdateState.Idle, m.state.value)
    }

    @Test fun automaticChecksAreThrottledToOncePerSixHours() = runBlocking {
        versionJson = MockResponse().setBody("""{"versionCode": 41, "versionName": "1.0.41"}""")
        val m = manager()
        assertEquals(UpdateManager.Outcome.UpToDate, m.check(force = false))
        assertEquals(1, requests.size)
        now += TimeUnit.HOURS.toMillis(1)
        assertEquals(UpdateManager.Outcome.Throttled, m.check(force = false))
        assertEquals(1, requests.size)
        // «Проверить обновления» asks whatever the throttle.
        assertEquals(UpdateManager.Outcome.UpToDate, m.check(force = true))
        assertEquals(2, requests.size)
        now += TimeUnit.HOURS.toMillis(6) + 1
        assertEquals(UpdateManager.Outcome.UpToDate, m.check(force = false))
        assertEquals(3, requests.size)
        // The moment survives a new manager (a new process) through the store.
        assertEquals(UpdateManager.Outcome.Throttled, manager().check(force = false))
        assertEquals(3, requests.size)
    }

    @Test fun aFailedCheckIsNotCountedForTheThrottle() = runBlocking {
        versionJson = MockResponse().setResponseCode(500)
        val m = manager()
        assertEquals(UpdateManager.Outcome.Unreachable, m.check(force = false))
        assertEquals(UpdateState.Idle, m.state.value)
        now += 1
        assertEquals(UpdateManager.Outcome.Unreachable, m.check(force = false))
        assertEquals(2, requests.size)
        assertEquals(0L, lastCheck.at)
    }

    @Test fun aCutDownloadIsResumedNextTime() = runBlocking {
        cutFirst = true
        val m = manager()
        assertEquals(UpdateManager.Outcome.DownloadFailed(UpdateCheck.Update(42, "1.0.42", server.url("/android/tabel-kfh.apk").toString())), m.check(force = true))
        assertEquals(UpdateState.Idle, m.state.value)
        val part = File(dir, "tabel-kfh-42.apk.part")
        assertTrue(part.isFile)
        val have = part.length()
        assertTrue("have=$have", have in 1 until apk.size)

        assertTrue(m.check(force = true) is UpdateManager.Outcome.Ready)
        val resume = requests.last()
        assertEquals("/android/tabel-kfh.apk", resume.path)
        assertEquals("bytes=$have-", resume.getHeader("Range"))
        assertArrayEquals(apk, File(dir, "tabel-kfh-42.apk").readBytes())
        assertFalse(part.exists())
    }

    @Test fun aCompleteFileIsNotDownloadedAgain() = runBlocking {
        File(dir, "tabel-kfh-42.apk").writeBytes(apk)
        val m = manager()
        assertTrue(m.check(force = true) is UpdateManager.Outcome.Ready)
        assertEquals(listOf("/android/version.json"), paths())
        // And a second check with the file already offered asks only version.json again.
        assertTrue(m.check(force = true) is UpdateManager.Outcome.Ready)
        assertEquals(listOf("/android/version.json", "/android/version.json"), paths())
    }

    @Test fun startOffersAKeptNewerFileWithoutTheNetworkAndCleansUpTheRest() = runBlocking {
        File(dir, "tabel-kfh-42.apk").writeBytes(apk)
        File(dir, "tabel-kfh-41.apk").writeBytes(FakeFacts.apk(41))
        File(dir, "tabel-kfh-40.apk.part").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "tabel-kfh-43.apk.part").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "junk.txt").writeText("x")
        val m = manager()
        m.start()
        val ready = m.state.value as UpdateState.Ready
        assertEquals("tabel-kfh-42.apk", ready.file.name)
        assertEquals(42L, ready.update.versionCode)
        assertEquals(setOf("tabel-kfh-42.apk", "tabel-kfh-43.apk.part"), dir.list()!!.toSet())
        assertEquals(0, requests.size)
    }

    /** After the update the installed code is 42: the file downloaded for it is a leftover. */
    @Test fun startAfterTheUpdateDeletesTheInstalledVersionsFile() = runBlocking {
        File(dir, "tabel-kfh-42.apk").writeBytes(apk)
        val m = manager(facts = FakeFacts(installed.copy(versionCode = 42)))
        m.start()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun startRejectsAKeptFileWithAnotherSignature() = runBlocking {
        File(dir, "tabel-kfh-42.apk").writeBytes(FakeFacts.apk(42, cert = "cert-debug"))
        val m = manager()
        m.start()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun serverWithoutANewerBuildForgetsAnOfferedFile() = runBlocking {
        val m = manager()
        assertTrue(m.check(force = true) is UpdateManager.Outcome.Ready)
        versionJson = MockResponse().setBody("""{"versionCode": 41, "versionName": "1.0.41"}""")
        assertEquals(UpdateManager.Outcome.UpToDate, m.check(force = true))
        assertEquals(UpdateState.Idle, m.state.value)
        assertNull(dir.listFiles()!!.firstOrNull())
    }

    @Test fun discardDropsTheOfferedFile() = runBlocking {
        val m = manager()
        val ready = (m.check(force = true) as UpdateManager.Outcome.Ready)
        val file = (m.state.value as UpdateState.Ready).file
        m.discard(file)
        val deadline = System.currentTimeMillis() + 5_000
        while (m.state.value != UpdateState.Idle && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(UpdateState.Idle, m.state.value)
        assertFalse(file.exists())
        assertEquals(42L, ready.update.versionCode)
    }
}
