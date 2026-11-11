package ru.kfh.tabel.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kfh.tabel.ui.settings.UpdateCheck
import java.io.File

/** What the screens show about the next version: nothing, a download on its way, or a verified file to install. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [total] is -1 when the server did not say. */
    data class Downloading(val update: UpdateCheck.Update, val received: Long, val total: Long) : UpdateState

    /** The banner «Доступна новая версия …» and «Установить». */
    data class Ready(val update: UpdateCheck.Update, val file: File) : UpdateState
}

/** The moment of the last successful look at `version.json`, kept across process starts (the throttle). */
interface LastCheckStore {
    var at: Long
}

class MemoryLastCheck(override var at: Long = 0L) : LastCheckStore

class PrefsLastCheck(context: Context) : LastCheckStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override var at: Long
        get() = prefs.getLong(KEY, 0L)
        set(value) {
            prefs.edit().putLong(KEY, value).apply()
        }

    companion object {
        const val PREFS = "updates"
        const val KEY = "lastCheckAt"
    }
}

/**
 * The self-update, up to the moment the person taps «Установить» ([UpdateInstaller] takes it from there):
 * - [check]: `version.json` (through [UpdateCheck]), at app start and on every return to the foreground
 *   ([attach]: process `ON_START`, at most once per [throttleMs]), and at once from «Проверить обновления»;
 * - a newer `versionCode` is downloaded in the background into [dir] (`tabel-kfh-<code>.apk`, resume-safe through
 *   [ApkDownloader]), only from an address under one of [allowedPrefixes] (our https host; a test's own server);
 * - the file is judged ([ApkJudge]) before it is offered: our package, newer, the installed app's own signing
 *   certificate; a rejected file is deleted;
 * - [start] cleans up after an installed update (files whose code is not above the installed one) and offers a
 *   complete file downloaded earlier without asking the network.
 * Every step runs off the main thread, one at a time ([mutex]); failures are silent (the state goes back to [UpdateState.Idle]).
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val check: UpdateCheck,
    private val downloader: ApkDownloader,
    private val facts: PackageFactsSource,
    private val dir: File,
    private val allowedPrefixes: List<String>,
    private val lastCheck: LastCheckStore = MemoryLastCheck(),
    private val now: () -> Long = System::currentTimeMillis,
    private val throttleMs: Long = THROTTLE_MS,
) {
    /** The answer of one [check]; «Проверить обновления» turns it into a sentence. */
    sealed interface Outcome {
        /** Checked less than [throttleMs] ago (automatic checks only). */
        data object Throttled : Outcome
        data object Unreachable : Outcome
        data object UpToDate : Outcome
        data class Ready(val update: UpdateCheck.Update) : Outcome
        data class DownloadFailed(val update: UpdateCheck.Update) : Outcome
        data class Rejected(val update: UpdateCheck.Update, val reason: String) : Outcome
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** App start: the leftovers of the last update go, a complete newer file is offered right away. */
    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val installed = facts.installed()
                var best: Pair<Long, File>? = null
                for (f in dir.listFiles().orEmpty()) {
                    val code = codeOf(f.name)
                    if (code == null || code <= installed.versionCode) {
                        f.delete()
                    } else if (f.name.endsWith(".apk") && (best == null || code > best.first)) {
                        best = code to f
                    }
                }
                val (code, file) = best ?: return@withLock
                when (val v = ApkJudge.judge(facts.archive(file), installed)) {
                    Verdict.Accepted -> _state.value = UpdateState.Ready(UpdateCheck.Update(code, "1.0.$code", ""), file)
                    is Verdict.Rejected -> {
                        Log.w(TAG, "kept file rejected: ${v.reason}")
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "start: ${e::class.java.name}: ${e.message}")
            }
        }
    }

    /**
     * One look at the server, then the download and the judgement when there is something newer. [force] skips the
     * throttle («Проверить обновления»). Returns once the file is ready, or when nothing (more) can be done.
     */
    suspend fun check(force: Boolean): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                checkLocked(force).also { if (it != Outcome.Throttled) Log.i(TAG, "check(force=$force): $it") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "check: ${e::class.java.name}: ${e.message}")
                if (_state.value !is UpdateState.Ready) _state.value = UpdateState.Idle
                Outcome.Unreachable
            }
        }
    }

    private suspend fun checkLocked(force: Boolean): Outcome {
        if (!force && now() - lastCheck.at < throttleMs) return Outcome.Throttled
        val ready = _state.value as? UpdateState.Ready
        if (ready == null) _state.value = UpdateState.Checking
        val latest = check.latest()
        if (latest == null) {
            _state.value = ready ?: UpdateState.Idle
            return Outcome.Unreachable
        }
        lastCheck.at = now()
        val installed = facts.installed()
        if (latest.versionCode <= installed.versionCode) {
            // A file offered earlier is for a build the server no longer has above us: forget it.
            ready?.file?.delete()
            _state.value = UpdateState.Idle
            return Outcome.UpToDate
        }
        if (ready != null && ready.update.versionCode == latest.versionCode) {
            _state.value = UpdateState.Ready(latest, ready.file)
            return Outcome.Ready(latest)
        }
        val target = File(dir, fileName(latest.versionCode))
        if (target.isFile) {
            when (val v = judge(target, installed, latest)) {
                Verdict.Accepted -> {
                    _state.value = UpdateState.Ready(latest, target)
                    return Outcome.Ready(latest)
                }
                is Verdict.Rejected -> {
                    Log.w(TAG, "kept ${target.name} rejected: ${v.reason}")
                    target.delete()
                }
            }
        }
        if (allowedPrefixes.none { latest.url.startsWith(it) }) {
            Log.w(TAG, "download address refused: ${latest.url}")
            _state.value = ready ?: UpdateState.Idle
            return Outcome.Rejected(latest, "адрес не наш")
        }
        _state.value = UpdateState.Downloading(latest, 0, -1)
        val complete = downloader.download(latest.url, target) { received, total ->
            _state.value = UpdateState.Downloading(latest, received, total)
        }
        if (!complete) {
            _state.value = ready ?: UpdateState.Idle
            return Outcome.DownloadFailed(latest)
        }
        return when (val v = judge(target, installed, latest)) {
            Verdict.Accepted -> {
                ready?.file?.takeIf { it != target }?.delete()
                _state.value = UpdateState.Ready(latest, target)
                Outcome.Ready(latest)
            }
            is Verdict.Rejected -> {
                Log.w(TAG, "${target.name} rejected: ${v.reason}")
                target.delete()
                _state.value = ready ?: UpdateState.Idle
                Outcome.Rejected(latest, v.reason)
            }
        }
    }

    /** [ApkJudge] over the file, and the file must be the build `version.json` announced (the banner names it). */
    private fun judge(target: File, installed: PackageFacts, announced: UpdateCheck.Update): Verdict {
        val archive = facts.archive(target)
        val v = ApkJudge.judge(archive, installed)
        if (v != Verdict.Accepted || archive == null) return v
        if (archive.versionCode != announced.versionCode) {
            return Verdict.Rejected("в файле версия ${archive.versionCode}, а объявлена ${announced.versionCode}")
        }
        return v
    }

    /** The installer found the file unusable: it goes, and the next check downloads again. */
    fun discard(file: File) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                file.delete()
                ApkDownloader.partOf(file).delete()
                if ((_state.value as? UpdateState.Ready)?.file == file) _state.value = UpdateState.Idle
            }
        }
    }

    // MARK: Android

    private var attached = false

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            scope.launch { check(force = false) }
        }
    }

    /** Process `ON_START` (at once when added after the start: the check at app start) → a throttled [check]. */
    suspend fun attach() = withContext(Dispatchers.Main.immediate) {
        if (attached) return@withContext
        attached = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun detach() {
        if (!attached) return
        attached = false
        val remove = Runnable { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else Handler(Looper.getMainLooper()).post(remove)
    }

    companion object {
        const val THROTTLE_MS = 6 * 60 * 60 * 1000L
        private const val TAG = "TabelKFH.update"
        private val NAME = Regex("""tabel-kfh-(\d+)\.apk(\.part)?""")

        fun fileName(code: Long) = "tabel-kfh-$code.apk"

        /** The code in `tabel-kfh-<code>.apk` / `.apk.part`; null for any other file. */
        fun codeOf(name: String): Long? = NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    }
}
