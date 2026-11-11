package ru.kfh.tabel.settings

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.kfh.tabel.ui.settings.UpdateCheck
import java.util.concurrent.TimeUnit

/** «О приложении»: `version.json` next to the `.apk` says whether a newer build is there to download. */
class UpdateCheckTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun check(current: Long) = UpdateCheck(client, server.url("/android/version.json").toString(), current)

    @Test fun newerVersionOffered() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode": 42, "versionName": "1.0.42"}"""))
        val u = check(41).newer()
        assertEquals(42L, u?.versionCode)
        assertEquals("1.0.42", u?.versionName)
        assertEquals(server.url("/android/tabel-kfh.apk").toString(), u?.url)
        assertEquals("/android/version.json", server.takeRequest().path)
    }

    @Test fun sameVersionSilent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode": 41, "versionName": "1.0.41"}"""))
        assertNull(check(41).newer())
    }

    @Test fun olderVersionSilent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode": 40, "versionName": "1.0.40"}"""))
        assertNull(check(41).newer())
    }

    @Test fun ourUrlAndMissingNameTolerated() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode": 43, "url": "https://kfh.cubely.ru/android/TabelKFH-43.apk", "extra": true}"""))
        val u = check(41).newer()
        assertEquals("https://kfh.cubely.ru/android/TabelKFH-43.apk", u?.url)
        assertEquals("1.0.43", u?.versionName)
    }

    /** The download address from the answer is opened only on our own https host; anything else is the `.apk` next to `version.json`. */
    @Test fun foreignUrlFallsBackToTheApkNextToVersionJson() = runBlocking {
        for (url in listOf("https://example.ru/x/TabelKFH.apk", "http://kfh.cubely.ru/android/tabel-kfh.apk", "https://kfh.cubely.ru.evil.ru/a.apk", "")) {
            server.enqueue(MockResponse().setBody("""{"versionCode": 43, "url": "$url"}"""))
            assertEquals(url, server.url("/android/tabel-kfh.apk").toString(), check(41).newer()?.url)
        }
        assertEquals(true, UpdateCheck.isOurs("https://kfh.cubely.ru/android/tabel-kfh.apk"))
        assertEquals(false, UpdateCheck.isOurs("https://kfh.cubely.ru.evil.ru/android/tabel-kfh.apk"))
    }

    /** The versionCode offset (build.gradle.kts: 1000 + run number) keeps every CI build above the hand-signed 1.0.101 (101). */
    @Test fun ciBuildIsNewerThanTheHandSignedFirstVersion() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"versionCode": 1001, "versionName": "1.0.1001"}"""))
        assertEquals(1001L, check(101).newer()?.versionCode)
        server.enqueue(MockResponse().setBody("""{"versionCode": 1001, "versionName": "1.0.1001"}"""))
        assertNull(check(1001).newer())
    }

    @Test fun serverTroubleSilent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(check(41).newer())
        server.enqueue(MockResponse().setBody("not json"))
        assertNull(check(41).newer())
        server.enqueue(MockResponse().setBody("""{"versionName": "1.0.9"}"""))
        assertNull(check(41).newer())
    }
}
