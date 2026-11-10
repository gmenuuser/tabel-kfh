package ru.kfh.tabel

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.kfh.core.roles.Role
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.tabel.session.farmInfoFlow
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.RoomLocalStore
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.session.EncryptedTokenStore
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.session.SyncScheduler
import ru.kfh.tabel.session.TokenStore
import ru.kfh.tabel.ui.agro.AgroViewModel
import ru.kfh.tabel.ui.agro.PrefsAgroDraftStore
import ru.kfh.tabel.ui.coal.CoalViewModel
import ru.kfh.tabel.ui.livestock.LivestockViewModel
import ru.kfh.tabel.ui.timesheet.TimesheetViewModel
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Everything the screens use, made once per process ([TabelApp.graph]). [baseUrl] is the farm server
 * (`https://kfh.cubely.ru`); only tests pass another one (a MockWebServer), with their own [tokens] and [dbName].
 */
class AppGraph(
    context: Context,
    val baseUrl: String = BASE_URL,
    val tokens: TokenStore = EncryptedTokenStore(context),
    dbName: String = TabelDb.FILE,
    photosDir: File = File(context.filesDir, "photos"),
) {
    private val app = context.applicationContext

    /**
     * Lives as long as the graph; the launch, the sync triggers and the sign-out run in it, off the main thread
     * (a pull page is parsed and written here). The screens only collect the session's flows. Nothing launched here
     * may take the process down: a failure is logged (its class and message, never a token) and the other
     * coroutines go on.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "unhandled in the app's scope: ${e::class.java.name}: ${e.message}")
        },
    )

    val db: TabelDb = TabelDb.open(app, dbName)
    val photos = PhotoStore(photosDir)
    val store = RoomLocalStore(db, photos)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val session = SessionManager(store, tokens, baseUrl, client, deviceName())

    val scheduler = SyncScheduler(scope) { session.syncNow() }

    /** Every change of the screens goes through here; a queued change syncs 1 s later. */
    val writer = RecordWriter(db, { session.role ?: Role.viewer }, scheduler::changed)

    /** The header of every document: the farm's name and manager from `Me`, the rest from the synced settings row. */
    val farmInfo: Flow<FarmInfo> = farmInfoFlow(session.state, db.records())

    /** «Табель работников»: its flows and actions, over the store and the farm's time. */
    val timesheet: TimesheetViewModel by lazy { TimesheetViewModel(db.records(), writer, session.farmTime, scope, photos, farmInfo = farmInfo) }

    /** «Продажа угля»: its flows and actions, over the store and the farm's time. */
    val coal: CoalViewModel by lazy { CoalViewModel(db.records(), writer, session.farmTime, scope, farmInfo = farmInfo) }

    /** «Скот и забой»: its flows and actions, over the store and the farm's time. */
    val livestock: LivestockViewModel by lazy { LivestockViewModel(db.records(), writer, session.farmTime, scope, farmInfo = farmInfo) }

    /** «Агрономические калькуляторы»: the saved calculations and the drafts (in `SharedPreferences`). */
    val agro: AgroViewModel by lazy { AgroViewModel(db.records(), writer, session.farmTime, scope, PrefsAgroDraftStore(app), farmInfo = farmInfo) }

    init {
        scope.launch {
            session.restore()
            // ON_START comes at once once attached: the sync at app start.
            scheduler.attach(app)
        }
        // Every sign-in opens the timesheet on the farm's today and this month (another farm may be in another zone);
        // a refreshed `Me` of the same sign-in does not touch a day picked by hand.
        timesheet.resetOnSignIn(session.state)
        coal.resetOnSignIn(scope, session.state)
    }

    /** Tests only (a graph per test): the triggers go, the coroutines are cancelled, the database is closed. */
    fun close() {
        scheduler.detach()
        scope.cancel()
        db.close()
    }

    companion object {
        const val BASE_URL = "https://kfh.cubely.ru"
        private const val TAG = "TabelKFH"

        fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
