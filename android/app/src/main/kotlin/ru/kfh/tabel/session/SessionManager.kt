package ru.kfh.tabel.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.Api
import ru.kfh.core.sync.ApiError
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.SyncEngine
import ru.kfh.core.sync.Unauthorized
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RoomLocalStore
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** Signed out: the sign-in screen; [Loading]: «Загрузка данных…» (the first pull, or reading the store at launch). */
sealed interface SessionState {
    data object SignedOut : SessionState
    data object Loading : SessionState
    data class SignedIn(val me: Me) : SessionState
}

/**
 * The signed-in device, as `TabelKFH/Sync/SessionStore.swift`: the token ([TokenStore]), `Me` (kept in the store's
 * meta, so the app opens offline as the same person), the farm's time and one [SyncEngine] per sign-in.
 *
 * Rules:
 * - Every sign-in makes a new engine (a stopped engine never restarts). Its requests carry the token only while it is
 *   the current engine (a gate closed on sign-out), so a run of an earlier sign-in never sends a later token.
 * - Sign-out: `engine.stopAndWait()` first (the run on its way finishes its last write), then `POST /auth/logout`,
 *   then `store.wipe()`, then the token is cleared.
 * - A 401 inside a sync: the engine has already stopped and wiped under its own lock and calls `onSignedOut`, which
 *   only clears the token and the session (never `stopAndWait`: the engine's mutex is not reentrant).
 * - After every sync `GET /me` refreshes the role, the farm and [farmTime].
 */
class SessionManager(
    private val store: RoomLocalStore,
    private val tokens: TokenStore,
    private val base: String,
    private val client: OkHttpClient,
    private val deviceName: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _farmTime = MutableStateFlow(FarmTime(DEFAULT_FARM_ZONE, clock))
    /** «Today» and day keys: the farm's zone from `Me`, refreshed with every sync. */
    val farmTime: StateFlow<FarmTime> = _farmTime.asStateFlow()

    private val _engine = MutableStateFlow<SyncEngine?>(null)
    /** The engine of this sign-in (its `status` is the main screen's line); null when signed out. */
    val engine: StateFlow<SyncEngine?> = _engine.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Shown on the sign-in screen after the server refused the device. */
    val signOutNotice: StateFlow<String?> = _notice.asStateFlow()

    /** The token as the current engine may use it. */
    private var gate: AtomicBoolean? = null
    private var engineApi: Api? = null

    /** Sign-in, sign-out, launch and the store writes of `/me`: one at a time. Never taken by `onSignedOut`. */
    private val mutex = Mutex()

    /** The logout of a sign-out is a courtesy: offline it must not hold the sign-out for long. */
    private val logoutClient: OkHttpClient by lazy { client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build() }

    /** The signed-in role, null when not signed in. */
    val role: Role? get() = (_state.value as? SessionState.SignedIn)?.me?.role

    // MARK: Launch

    /**
     * At launch: a token and a stored `Me` — signed in with them (the sync then runs on its triggers); otherwise
     * signed out, and whatever a sign-in left behind (a wipe that failed, a sign-in that never finished) is wiped.
     */
    suspend fun restore() = off { restoreLocked() }

    private suspend fun restoreLocked() = mutex.withLock {
        // A token file or a store that cannot be read: the phone is signed out, never left on «Загрузка данных…».
        val token = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
        val me = if (token != null) try { store.me() } catch (e: Exception) { if (e is CancellationException) throw e; null } else null
        if (token != null && me != null) {
            _farmTime.value = FarmTime(me.timeZone, clock)
            val engine = makeEngine()
            _state.value = SessionState.SignedIn(me)
            engine.refreshStatus()
        } else {
            wipeQuietly()
            clearTokenQuietly()
            _state.value = SessionState.SignedOut
        }
    }

    // MARK: Sign in

    /**
     * `POST /auth/login` → token → `GET /me` → wipe → `Me` saved → the first full pull («Загрузка данных…») →
     * signed in. Returns the error text for the sign-in screen, or null. A wrong password, no network, a server
     * error: signed out, the phone unchanged. The first pull failing offline: signed in all the same.
     */
    suspend fun signIn(login: String, password: String): String? = off { signInLocked(login, password) }

    private suspend fun signInLocked(login: String, password: String): String? = mutex.withLock {
        if (_state.value != SessionState.SignedOut) return "Вход уже выполнен"
        val token = try {
            Api(base, client) { null }.login(login.trim(), password, deviceName)
        } catch (e: ApiError) {
            // The login's 401 is a wrong login or password, not a revoked device.
            return if (e.status == 401) "Неверный логин или пароль" else e.text
        } catch (e: Exception) {
            return message(e)
        }
        try {
            saveToken(token)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return "Не удалось сохранить вход на телефоне"
        }
        val me = try {
            Api(base, client) { token }.me()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            clearTokenQuietly()
            return message(e)
        }
        _notice.value = null
        _state.value = SessionState.Loading
        // From here the sign-in screen is gone («Загрузка данных…»): an error is shown through the notice, which
        // the fresh sign-in screen reads, not through the return value alone.
        try {
            store.wipe()
            store.saveMe(me)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            clearTokenQuietly()
            return failLoading("Не удалось подготовить телефон к входу")
        }
        _farmTime.value = FarmTime(me.timeZone, clock)
        val engine = makeEngine()
        engine.syncNow()
        // A 401 during the download signed the phone out again (onSignedOut, with its own notice).
        if (_engine.value !== engine) return "Нужно войти заново"
        val still = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
        if (still == null) {
            // The token file broke during the download: not inside a run of the engine, stopAndWait is safe.
            engine.stopAndWait()
            closeGate()
            wipeQuietly()
            return failLoading("Не удалось сохранить вход на телефоне")
        }
        _state.value = SessionState.SignedIn(me)
        null
    }

    /** A sign-in that failed after «Загрузка данных…» began: signed out, [text] on the sign-in screen and returned. */
    private fun failLoading(text: String): String {
        endSession(notice = text)
        return text
    }

    // MARK: Sign out

    /** Changes that a sign-out would delete: waiting to be sent, and refused by the server. */
    data class Unsent(val pending: Int, val refused: Int) {
        val total: Int get() = pending + refused
    }

    suspend fun unsent(): Unsent = Unsent(store.pendingCount(), store.refusedCount())

    /**
     * «Выйти». `force = false`: returns N > 0 and changes nothing when N changes (waiting or refused) would be
     * deleted — the screen asks first and calls again with `force = true`. Otherwise signs out and returns 0.
     */
    suspend fun signOut(force: Boolean): Int = off { signOutLocked(force) }

    private suspend fun signOutLocked(force: Boolean): Int = mutex.withLock {
        if (_state.value == SessionState.SignedOut) return 0
        if (!force) {
            val n = unsent().total
            if (n > 0) return n
        }
        val engine = _engine.value
        // The run on its way finishes its last write; nothing of this engine touches the store afterwards.
        engine?.stopAndWait()
        closeGate()
        // From here the engine is dead: whatever fails below, the session ends as signed out (never «signed in»
        // with an engine that will not sync again).
        try {
            val token = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (token != null) {
                try {
                    Api(base, logoutClient) { token }.logout()
                } catch (e: Exception) {
                    // Offline, or already revoked: the phone signs out all the same.
                    if (e is CancellationException) throw e
                }
            }
            wipeQuietly()
            clearTokenQuietly()
        } finally {
            endSession(notice = null)
        }
        0
    }

    // MARK: Sync

    /** A sync now, then `GET /me` (the role, the farm and its zone may have changed). Nothing when not signed in. */
    suspend fun syncNow() = off {
        if (_state.value !is SessionState.SignedIn) return@off
        val engine = _engine.value ?: return@off
        if (engine.stopped) return@off
        engine.syncNow()
        refreshMe(engine)
    }

    /**
     * The store, the pull pages' JSON and the token file are never worked on the caller's thread: the sign-in screen
     * calls from the main thread.
     */
    private suspend fun <T> off(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Default, block)

    private suspend fun readToken(): String? = withContext(Dispatchers.IO) { tokens.get() }

    /** Throws when the token could not be saved (the sign-in then fails with a message). */
    private suspend fun saveToken(token: String) = withContext(Dispatchers.IO) { tokens.set(token) }

    /** Sign-out: the token goes; a store that cannot even forget it must not stop the sign-out. */
    private suspend fun clearTokenQuietly() {
        try {
            withContext(Dispatchers.IO) { tokens.set(null) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /** `GET /me` of the sign-in [engine] belongs to: nothing when that sign-in ended before or while it was asked. */
    private suspend fun refreshMe(engine: SyncEngine) {
        fun current() = _engine.value === engine && !engine.stopped && _state.value is SessionState.SignedIn
        val api = engineApi
        if (!current() || api == null) return
        val me = try {
            api.me()
        } catch (e: Unauthorized) {
            revoked(engine)
            return
        } catch (e: Exception) {
            // Offline or a server error: the role and the farm stay as they were.
            if (e is CancellationException) throw e
            return
        }
        mutex.withLock {
            // A sign-out that began meanwhile has wiped: nothing of the old sign-in is written back.
            if (!current()) return
            try {
                store.saveMe(me)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            if (_farmTime.value.zone != me.timeZone) _farmTime.value = FarmTime(me.timeZone, clock)
            if ((_state.value as? SessionState.SignedIn)?.me != me) _state.value = SessionState.SignedIn(me)
        }
    }

    /**
     * The server refused the device outside a run of the engine (`/me`, or an owner's request): not inside a run,
     * so stopAndWait is safe. The sign-in screen says how many unsent changes went with the wipe.
     */
    private suspend fun revoked(engine: SyncEngine) {
        mutex.withLock {
            if (_engine.value !== engine || engine.stopped || _state.value !is SessionState.SignedIn) return
            engine.stopAndWait()
            closeGate()
            val lost = try { store.pendingCount() } catch (e: Exception) { if (e is CancellationException) throw e; 0 }
            try {
                wipeQuietly()
                clearTokenQuietly()
            } finally {
                endSession(notice = revokedNotice(lost))
            }
        }
    }

    // MARK: Owner's requests

    /**
     * A request of this sign-in besides the sync (the owner's lists `/members`, `/devices`, `/conflicts`,
     * `/backups`, `PATCH /farm`), as `SessionStore.send`: a 401 signs the phone out the way the sync does, and is
     * rethrown as [Unauthorized]. Not signed in: [Unauthorized] at once.
     */
    suspend fun <T> request(block: suspend (Api) -> T): T {
        val engine = _engine.value
        val api = engineApi
        if (_state.value !is SessionState.SignedIn || engine == null || api == null || engine.stopped) throw Unauthorized()
        try {
            return block(api)
        } catch (e: Unauthorized) {
            revoked(engine)
            throw e
        }
    }

    /**
     * The owner renames the farm or its manager (`PATCH /farm`). The server has the names once it answers, so `Me`
     * carries them at once (queuing nothing: they are not synced fields); `/me` then brings the rest, and a `/me`
     * that fails leaves the new names shown. Returns the error text, or null.
     */
    suspend fun updateFarm(name: String, managerName: String): String? = off {
        try {
            request { it.patchFarm(name = name, managerName = managerName) }
        } catch (e: Exception) {
            return@off errorText(e)
        }
        mutex.withLock {
            val s = _state.value as? SessionState.SignedIn ?: return@withLock
            val me = s.me.copy(farmName = name, managerName = managerName)
            try {
                store.saveMe(me)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            _state.value = SessionState.SignedIn(me)
        }
        _engine.value?.let { refreshMe(it) }
        null
    }

    /**
     * The next pull starts from the first change (after a server restore, or a refused change removed), so every
     * record takes the server's version again; then a sync now.
     */
    suspend fun resyncFromStart() = off {
        if (_state.value !is SessionState.SignedIn) return@off
        try {
            store.setCursor(0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        syncNow()
    }

    // MARK: Engine

    private fun makeEngine(): SyncEngine {
        _engine.value?.stop()
        closeGate()
        val open = AtomicBoolean(true)
        val api = Api(base, client) { if (open.get()) tokens.get() else null }
        var self: SyncEngine? = null
        val engine = SyncEngine(api, store) { lost ->
            // Inside the engine's run, under its lock, after it stopped and wiped: never stopAndWait here.
            val me = self
            if (me != null && _engine.value === me) {
                closeGate()
                try {
                    clearTokenQuietly()
                } finally {
                    endSession(notice = revokedNotice(lost))
                }
            }
        }
        self = engine
        gate = open
        engineApi = api
        _engine.value = engine
        return engine
    }

    private fun closeGate() {
        gate?.set(false)
        gate = null
        engineApi = null
    }

    private fun endSession(notice: String?) {
        _engine.value = null
        _farmTime.value = FarmTime(DEFAULT_FARM_ZONE, clock)
        _notice.value = notice
        _state.value = SessionState.SignedOut
    }

    /** Once more on failure; failing again, the next launch wipes (signed out with a sign-in's rows left). */
    private suspend fun wipeQuietly() {
        try {
            store.wipe()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            try {
                store.wipe()
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
            }
        }
    }

    private fun message(e: Exception): String = when (e) {
        is CancellationException -> throw e
        is Unauthorized, is ApiError, is IOException -> errorText(e)
        else -> "Не удалось войти"
    }

    /** The text of a failed request, as the iPhone's `error.localizedDescription`. */
    fun errorText(e: Exception): String = when (e) {
        is CancellationException -> throw e
        is Unauthorized -> e.message!!
        is ApiError -> e.text
        is SSLException -> "Ошибка соединения с сервером"
        is IOException -> "Нет связи с сервером"
        else -> e.message ?: e::class.java.simpleName
    }

    override fun toString(): String = "SessionManager(${_state.value::class.simpleName})"

    companion object {
        const val REVOKED = "Доступ к хозяйству отключён."

        /** The iPhone's notice: with the unsent changes that were lost, when there were any. */
        fun revokedNotice(lost: Int): String =
            if (lost > 0) "Доступ к хозяйству отключён. Неотправленных изменений удалено: $lost." else REVOKED
    }
}
