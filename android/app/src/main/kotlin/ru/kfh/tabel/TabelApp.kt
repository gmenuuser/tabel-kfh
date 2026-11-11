package ru.kfh.tabel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.VisibleForTesting

/** Holds the one [AppGraph] of the process, made when first asked for (the activity's onCreate). */
class TabelApp : Application() {
    @Volatile private var current: AppGraph? = null

    val graph: AppGraph
        get() = current ?: synchronized(this) {
            current ?: AppGraph(this, baseUrl = debugBaseUrl(this) ?: AppGraph.BASE_URL).also { current = it }
        }

    /** Instrumented tests: a graph over a MockWebServer, set before the activity starts. */
    @VisibleForTesting
    fun useGraph(graph: AppGraph) {
        synchronized(this) {
            current?.close()
            current = graph
        }
    }

    companion object {
        /**
         * Debug builds only (`applicationInfo` debuggable): `adb shell setprop debug.kfh.base_url http://10.0.2.2:8765`
         * points the whole app — the farm server and the self-update's `version.json` — at a server on the emulator's
         * host, for the end-to-end check of the update. A release build never reads it.
         */
        fun debugBaseUrl(context: Context): String? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", "debug.kfh.base_url"))
                val value = p.inputStream.bufferedReader().use { it.readText() }.trim().trimEnd('/')
                p.waitFor()
                value.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.also {
                    Log.w("TabelKFH", "debug.kfh.base_url: the app talks to $it")
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
