package ru.kfh.tabel.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * When the sync runs (as `SessionStore.startTriggers` / `scenePhaseChanged` on the iPhone), only while the app is
 * used — there is no background sync:
 * - [changed]: 1 s after the last `RecordWriter` write (debounce);
 * - [foreground] (process `ON_START`, also at app start): at once, then every 60 s until [background] (`ON_STOP`);
 * - [networkAvailable] after [networkLost]: the network came back;
 * - [syncNow]: the button of the status line.
 *
 * Requests are conflated: during a sync any number of them make one more sync after it. [sync] is
 * `SessionManager.syncNow` (not connected for want of a network, it tries to connect instead).
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val periodMs: Long = PERIOD_MS,
    private val sync: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var debounce: Job? = null
    private var ticker: Job? = null
    @Volatile private var networkWasDown = false

    init {
        scope.launch {
            for (r in requests) {
                try {
                    sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The engine reports its own failures in its status; nothing may stop the triggers.
                }
            }
        }
    }

    fun syncNow() {
        requests.trySend(Unit)
    }

    /** A change was queued (any thread). */
    fun changed() {
        synchronized(lock) {
            debounce?.cancel()
            debounce = scope.launch {
                delay(debounceMs)
                syncNow()
            }
        }
    }

    fun foreground() {
        synchronized(lock) {
            ticker?.cancel()
            ticker = scope.launch {
                while (true) {
                    syncNow()
                    delay(periodMs)
                }
            }
        }
    }

    fun background() {
        synchronized(lock) {
            ticker?.cancel()
            ticker = null
        }
    }

    fun networkLost() {
        networkWasDown = true
    }

    /** The first callback after registering is the network already there: a sync only after [networkLost]. */
    fun networkAvailable() {
        if (networkWasDown) {
            networkWasDown = false
            syncNow()
        }
    }

    // MARK: Android

    private var attached: Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = foreground()
        override fun onStop(owner: LifecycleOwner) = background()
    }

    /**
     * Process `ON_START`/`ON_STOP` and the default network. Added after the process has started, the observer gets
     * `ON_START` at once: that is the sync at app start.
     */
    suspend fun attach(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (attached != null) return@withContext
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkAvailable()
            override fun onLost(network: Network) = networkLost()
        }
        cm.registerDefaultNetworkCallback(callback)
        attached = cm to callback
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    /** Synchronous (a test's teardown): the lifecycle observer goes on the main thread, the rest right here. */
    fun detach() {
        attached?.let { (cm, cb) -> runCatching { cm.unregisterNetworkCallback(cb) } }
        attached = null
        background()
        val remove = Runnable { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else Handler(Looper.getMainLooper()).post(remove)
    }

    companion object {
        const val DEBOUNCE_MS = 1_000L
        const val PERIOD_MS = 60_000L
    }
}
