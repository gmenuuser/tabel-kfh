package ru.kfh.tabel.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * «Установить»: hands a verified `.apk` ([UpdateManager]) to Android.
 * - Without the «install unknown apps» permission for this app ([canInstall], API 26+) the screen first explains
 *   and opens [openPermissionSettings]; the screen calls [install] again when the person comes back.
 * - [beforeInstall] runs first (a sync with a short timeout, best effort): unsent changes reach the server; the
 *   upgrade keeps the local data anyway (same key, in place).
 * - The `PackageInstaller` session API: the file is written into a session, the commit's status comes to
 *   [InstallReceiver]; `STATUS_PENDING_USER_ACTION` carries the system's confirmation dialog, which is started
 *   (newer Android shows it by itself while the app is in front, and the status comes later). When the system
 *   shows nothing in [NO_UI_MS] — no status, and the app never left the foreground (some Honor/EMUI builds) — or
 *   the dialog cannot be started, the session is abandoned and the old way is used: `ACTION_VIEW` of the file
 *   through the FileProvider.
 * - Everything the system reports back is in [failure] (a sentence for the screen, null when nothing is wrong).
 * Nothing installs without the person's tap: the system's dialog is Android's own; only an app that already
 * installed the previous version may be allowed to skip it (`setRequireUserAction`, API 31+, the system decides).
 */
class UpdateInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val authority: String,
    private val beforeInstall: suspend () -> Unit = {},
    /** The installer found the file bad (`STATUS_FAILURE_INVALID`): the manager drops it. */
    private val onInvalidFile: (File) -> Unit = {},
) {
    private val app = context.applicationContext

    /** The person's tap ended how. */
    sealed interface Outcome {
        /** Android took over (its dialog, or the install itself). */
        data object Started : Outcome
        data object NeedsPermission : Outcome
        data class Failed(val text: String) : Outcome
    }

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clearFailure() {
        _failure.value = null
    }

    fun canInstall(): Boolean = try {
        app.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    /** The system's page for this app's «install unknown apps» switch; false when no such page opens. */
    fun openPermissionSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in intents) {
            try {
                context.startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                // the next one
            } catch (e: SecurityException) {
                // the next one
            }
        }
        return false
    }

    suspend fun install(context: Context, file: File): Outcome {
        if (!canInstall()) return Outcome.NeedsPermission
        if (!file.isFile) return Outcome.Failed("Файл обновления не найден. Проверьте обновления ещё раз.")
        _busy.value = true
        _failure.value = null
        try {
            withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                try {
                    beforeInstall()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            return withContext(Dispatchers.IO) {
                try {
                    commitSession(file)
                    Outcome.Started
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "session install: ${e::class.java.name}: ${e.message}")
                    if (viewFile(context, file)) Outcome.Started
                    else Outcome.Failed("Не удалось запустить установку: ${e.message ?: e::class.java.simpleName}")
                }
            }
        } finally {
            _busy.value = false
        }
    }

    /** The old way, also offered by hand in «О приложении»: the file opens in the system's installer. */
    fun viewFile(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(app, authority, file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context === app) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "view install: ${e::class.java.name}: ${e.message}")
            false
        }
    }

    // MARK: PackageInstaller session

    @Volatile private var pending: Pending? = null

    private class Pending(val sessionId: Int, val file: File, val watchdog: Job) {
        /** A status came, or something (the system's dialog) covered the app: the session is alive, no fallback. */
        @Volatile var statusSeen = false
    }

    /** While a session is out: the app going behind another window means the system's UI is there. */
    private val coveredObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            pending?.statusSeen = true
        }
    }

    private suspend fun watchCovered(on: Boolean) = withContext(Dispatchers.Main.immediate) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (on) lifecycle.addObserver(coveredObserver) else lifecycle.removeObserver(coveredObserver)
    }

    private fun commitSession(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            }
        }
        pending?.let { old -> runCatching { installer.abandonSession(old.sessionId) }; old.watchdog.cancel() }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(file.name, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(app, InstallReceiver::class.java).setAction(InstallReceiver.ACTION).putExtra(EXTRA_SESSION, id)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
                val sender = PendingIntent.getBroadcast(app, id, intent, flags).intentSender
                val p = Pending(id, file, scope.launch {
                    watchCovered(true)
                    try {
                        delay(NO_UI_MS)
                    } finally {
                        watchCovered(false)
                    }
                    noUi(id)
                })
                pending = p
                current = this
                session.commit(sender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            pending?.takeIf { it.sessionId == id }?.watchdog?.cancel()
            pending = null
            throw e
        }
    }

    /** Nothing came back from the session in time: the old way. */
    private fun noUi(sessionId: Int) {
        val p = pending?.takeIf { it.sessionId == sessionId && !it.statusSeen } ?: return
        pending = null
        Log.w(TAG, "no status from session $sessionId: falling back to ACTION_VIEW")
        runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
        if (!viewFile(app, p.file)) _failure.value = "Телефон не показал окно установки. Откройте файл обновления в «О приложении»."
    }

    /** [InstallReceiver]: a status of the committed session. */
    internal fun onStatus(sessionId: Int, status: Int, message: String?, confirm: Intent?) {
        val p = pending?.takeIf { it.sessionId == sessionId } ?: return
        p.statusSeen = true
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    noUiNow(p)
                    return
                }
                try {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "confirm dialog: ${e::class.java.name}: ${e.message}")
                    noUiNow(p)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                p.watchdog.cancel()
                pending = null
                // The process is replaced by the system right after this; nothing to show.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // «Отмена» in the system's dialog: no message, the banner stays.
                p.watchdog.cancel()
                pending = null
            }
            else -> {
                p.watchdog.cancel()
                pending = null
                Log.w(TAG, "install failed: status=$status $message")
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
                    onInvalidFile(p.file)
                    _failure.value = "Файл обновления повреждён. Проверьте обновления ещё раз — он скачается заново."
                } else {
                    _failure.value = "Установка не удалась" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
                }
            }
        }
    }

    private fun noUiNow(p: Pending) {
        p.watchdog.cancel()
        pending = null
        runCatching { app.packageManager.packageInstaller.abandonSession(p.sessionId) }
        if (!viewFile(app, p.file)) _failure.value = "Телефон не показал окно установки. Откройте файл обновления в «О приложении»."
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_SESSION = "ru.kfh.tabel.update.SESSION"
        const val SYNC_TIMEOUT_MS = 8_000L
        const val NO_UI_MS = 20_000L
        private const val TAG = "TabelKFH.update"

        /** The installer whose session is out with the system (one process, one install at a time). */
        @Volatile internal var current: UpdateInstaller? = null
    }
}

/** The status of a `PackageInstaller` session committed by [UpdateInstaller] (declared in the manifest, not exported). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(UpdateInstaller.EXTRA_SESSION, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        UpdateInstaller.current?.onStatus(sessionId, status, message, confirm)
    }

    companion object {
        const val ACTION = "ru.kfh.tabel.update.INSTALL_STATUS"
    }
}
