package ru.kfh.tabel.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** The resume-safe download by itself: a server that resumes, one that does not, and a stale part. */
class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val downloader = ApkDownloader(client)
    private val bytes = ByteArray(10_000) { (it % 251).toByte() }
    private lateinit var target: File

    @Before fun start() {
        server.start()
        target = File(tmp.newFolder(), "tabel-kfh-42.apk")
    }

    @After fun stop() = server.shutdown()

    private fun url() = server.url("/android/tabel-kfh.apk").toString()

    @Test fun wholeFileInOneGo() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Pair<Long, Long>>()
        assertTrue(downloader.download(url(), target) { r, t -> progress += r to t })
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(ApkDownloader.partOf(target).exists())
        assertEquals(10_000L to 10_000L, progress.last())
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test fun aServerThatDoesNotResumeStartsOver() = runBlocking {
        ApkDownloader.partOf(target).writeBytes(ByteArray(4_000) { 9 })
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertTrue(downloader.download(url(), target))
        assertEquals("bytes=4000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun aStalePartIsDroppedAndTheFileFetchedWhole() = runBlocking {
        ApkDownloader.partOf(target).writeBytes(ByteArray(20_000) { 9 })
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertTrue(downloader.download(url(), target))
        assertEquals("bytes=20000-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun serverTroubleLeavesNothingBehind() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(downloader.download(url(), target))
        assertFalse(target.exists())
        assertFalse(ApkDownloader.partOf(target).exists())
    }
}
