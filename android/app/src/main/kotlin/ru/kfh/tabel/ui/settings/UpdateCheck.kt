package ru.kfh.tabel.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks `https://kfh.cubely.ru/android/version.json` (`{"versionCode": 42, "versionName": "1.0.42"}`, written by
 * `server/deploy/deploy-android.sh` next to the `.apk`) whether a newer build than [currentCode] is there.
 * Anything short of a well-formed answer with a greater `versionCode` is silence: «О приложении» then shows the
 * version alone, as before.
 */
class UpdateCheck(private val client: OkHttpClient, private val url: String, private val currentCode: Long) {
    /** The newer build: its code, its name («1.0.42») and where the `.apk` is. */
    data class Update(val versionCode: Long, val versionName: String, val url: String)

    suspend fun newer(): Update? = latest()?.takeIf { it.versionCode > currentCode }

    /** The build on the server whatever its code (the self-update tells «up to date» from «no answer»); null on trouble. */
    suspend fun latest(): Update? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = Json.parseToJsonElement(r.body?.string() ?: return@withContext null).jsonObject
                val code = o["versionCode"]?.jsonPrimitive?.longOrNull ?: return@withContext null
                val name = o["versionName"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: "1.0.$code"
                // Only our own https address is opened from the answer; anything else falls back to the `.apk` next
                // to `version.json` (the answer is data from the network, not a place to send the person).
                val apk = o["url"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf(::isOurs) ?: defaultApkUrl(url)
                Update(code, name, apk)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val VERSION_URL = "https://kfh.cubely.ru/android/version.json"

        /** The only place a download address from `version.json` may point to. */
        const val APK_URL_PREFIX = "https://kfh.cubely.ru/"

        /** The `.apk` lies next to `version.json`. */
        fun defaultApkUrl(versionUrl: String): String = versionUrl.substringBeforeLast('/') + "/tabel-kfh.apk"

        /** `https://kfh.cubely.ru/…` and nothing else (no http, no other host). */
        fun isOurs(url: String): Boolean = url.startsWith(APK_URL_PREFIX)
    }
}
