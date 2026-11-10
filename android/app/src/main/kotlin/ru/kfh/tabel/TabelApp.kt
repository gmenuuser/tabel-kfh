package ru.kfh.tabel

import android.app.Application
import androidx.annotation.VisibleForTesting

/** Holds the one [AppGraph] of the process, made when first asked for (the activity's onCreate). */
class TabelApp : Application() {
    @Volatile private var current: AppGraph? = null

    val graph: AppGraph
        get() = current ?: synchronized(this) { current ?: AppGraph(this).also { current = it } }

    /** Instrumented tests: a graph over a MockWebServer, set before the activity starts. */
    @VisibleForTesting
    fun useGraph(graph: AppGraph) {
        synchronized(this) {
            current?.close()
            current = graph
        }
    }
}
