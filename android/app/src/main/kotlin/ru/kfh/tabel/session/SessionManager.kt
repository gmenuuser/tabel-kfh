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

/** Why the phone is not connected: the connect screen's text ([SessionManager.OFFLINE], …). */
enum class ConnectReason {
    /** No network or no answer: «Нет связи с сервером», «Повторить» (and again by itself when the network returns). */
    Offline,

    /** The owner lets no new devices in: «Владелец сейчас не подключает новые устройства», «Повторить». */
    Closed,

    /** The owner switched this phone off (401): «Это устройство отключено владельцем», «Подключиться снова». */
    Revoked,
}

/** [NotConnected]: the connect screen; [Loading]: «Загрузка данных…» (connecting, the first pull, reading the store). */
sealed interface SessionState {
    data class NotConnected(val reason: ConnectReason) : SessionState
    data object Loading : SessionState
    data class SignedIn(val me: Me) : SessionState
}

/**
 * The connected device, as `TabelKFH/Sync/SessionStore.swift`: the token ([TokenStore]), `Me` (kept in the store's
 * meta, so the app opens offline as the same device), the farm's time and one [SyncEngine] per connection.
 *
 * Rules:
 * - No account: without a token the phone registers itself (`POST /devices/register` with [deviceName]). The token
 *   of an old build (with its `Me`) is kept as it is: never registered again, the data and the queue stay.
 * - Every connection makes a new engine (a stopped engine never restarts). Its requests carry the token only while
 *   it is the current engine (a gate closed when it ends), so a run of an earlier connection never sends a later token.
 * - Sign-out: `engine.stopAndWait()` first (the run on its way finishes its last write), then `POST /auth/logout`,
 *   then `store.wipe()`, then the token is cleared; then the phone connects again as a new device.
 * - A 401 inside a sync: the engine has already stopped and wiped under its own lock and calls `onSignedOut`, which
 *   only clears the token and the session (never `stopAndWait`: the engine's mutex is not reentrant). The phone is
 *   then [ConnectReason.Revoked] (kept in the token store across restarts) and registers again only by [connect].
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
    /** The engine of this connection (its `status` is the main screen's line); null when not connected. */
    val engine: StateFlow<SyncEngine?> = _engine.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /**
     * More for the connect screen. With [ConnectReason.Offline]: the text shown instead of «Нет связи с сервером»
     * (the server's own, «Не удалось сохранить подключение на телефоне»); with [ConnectReason.Revoked]: how many
     * unsent changes went with the wipe.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The last connect failed for want of a network or of a working server: a sync trigger tries again. */
    @Volatile private var retryBySync = false

    /** The token as the current engine may use it. */
    private var gate: AtomicBoolean? = null
    private var engineApi: Api? = null

    /** Connect, sign-out, launch and the store writes of `/me`: one at a time. Never taken by `onSignedOut`. */
    private val mutex = Mutex()

    /** The logout of a sign-out is a courtesy: offline it must not hold the sign-out for long. */
    private val logoutClient: OkHttpClient by lazy { client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build() }

    /** The connected role, null when not connected. */
    val role: Role? get() = (_state.value as? SessionState.SignedIn)?.me?.role

    // MARK: Launch

    /**
     * At launch: a token and a stored `Me` — connected with them (the sync then runs on its triggers; an old build's
     * token, data and queue are kept). Switched off by the owner — [ConnectReason.Revoked], nothing is asked.
     * Otherwise whatever an earlier connection left behind (a wipe that failed, a connection that never finished) is
     * wiped and the phone connects itself.
     */
    suspend fun restore() {
        off { restoreLocked() }
    }

    private suspend fun restoreLocked(): Unit = mutex.withLock {
        // A token file or a store that cannot be read: not connected, never left on «Загрузка данных…».
        val token = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
        val me = if (token != null) try { store.me() } catch (e: Exception) { if (e is CancellationException) throw e; null } else null
        if (token != null && me != null) {
            _farmTime.value = FarmTime(me.timeZone, clock)
            val engine = makeEngine()
            _state.value = SessionState.SignedIn(me)
            engine.refreshStatus()
            return@withLock
        }
        wipeQuietly()
        if (isRevoked()) {
            clearTokenQuietly()
            endSession(SessionState.NotConnected(ConnectReason.Revoked), notice = null)
            return@withLock
        }
        connectLocked()
    }

    // MARK: Connect

    /**
     * «Повторить», «Подключиться снова», the launch without a token: `POST /devices/register` (or the token already
     * kept) → `GET /me` → wipe → `Me` saved → the first full pull («Загрузка данных…») → connected. Returns null, or
     * the error text. No network, closed, a server error: not connected, the phone unchanged. The first pull failing
     * offline: connected all the same. Already connected: nothing, null.
     */
    suspend fun connect(): String? = off { mutex.withLock { connectLocked() } }

    private suspend fun connectLocked(): String? {
        if (_state.value is SessionState.SignedIn) return null
        retryBySync = false
        // A token kept from a connect whose /me never answered: that device exists, it is not registered twice.
        val kept = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
        val token = kept ?: try {
            Api(base, client) { null }.register(deviceName)
        } catch (e: Exception) {
            return failed(e)
        }
        if (kept == null) {
            try {
                saveToken(token)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return notConnected(ConnectReason.Offline, TOKEN_NOT_SAVED)
            }
        }
        val me = try {
            Api(base, client) { token }.me()
        } catch (e: Unauthorized) {
            clearTokenQuietly()
            setRevokedQuietly(true)
            return notConnected(ConnectReason.Revoked, null, REVOKED)
        } catch (e: Exception) {
            // The token stays: the next attempt asks /me again without registering another device.
            return failed(e)
        }
        setRevokedQuietly(false)
        _notice.value = null
        _state.value = SessionState.Loading
        // From here the connect screen is gone («Загрузка данных…»): an error is shown through the notice, which the
        // fresh connect screen reads, not through the return value alone.
        try {
            store.wipe()
            store.saveMe(me)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            clearTokenQuietly()
            return failLoading("Не удалось подготовить телефон")
        }
        _farmTime.value = FarmTime(me.timeZone, clock)
        val engine = makeEngine()
        engine.syncNow()
        // A 401 during the download: the owner switched the phone off meanwhile (onSignedOut ended the session).
        if (_engine.value !== engine) return REVOKED
        val still = try { readToken() } catch (e: Exception) { if (e is CancellationException) throw e; null }
        if (still == null) {
            // The token file broke during the download: not inside a run of the engine, stopAndWait is safe.
            engine.stopAndWait()
            closeGate()
            wipeQuietly()
            return failLoading(TOKEN_NOT_SAVED)
        }
        _state.value = SessionState.SignedIn(me)
        return null
    }

    /**
     * A request of the connect failed. 403: closed. No network, an answer that cannot be read, a server error (5xx):
     * offline, tried again by the sync triggers. Any other answer (429 «Слишком много попыток…»): its text, and only
     * the button asks again — every attempt counts towards the server's limit.
     */
    private fun failed(e: Exception): String {
        if (e is CancellationException) throw e
        if (e is ApiError && e.status == 403) return notConnected(ConnectReason.Closed, null, e.text)
        val text = errorText(e)
        val result = notConnected(ConnectReason.Offline, text.takeIf { it != OFFLINE }, text)
        retryBySync = e is IOException || (e is ApiError && (e.status >= 500 || e.status == 200))
        return result
    }

    /** Not connected for [reason] with [notice] on the screen; returns [text] (the notice, or the offline text). */
    private fun notConnected(reason: ConnectReason, notice: String?, text: String = notice ?: OFFLINE): String {
        endSession(SessionState.NotConnected(reason), notice)
        return text
    }

    /** A connect that failed after «Загрузка данных…» began: not connected, [text] on the screen and returned. */
    private fun failLoading(text: String): String = notConnected(ConnectReason.Offline, text)

    // MARK: Sign out

    /** Changes that a sign-out would delete: waiting to be sent, and refused by the server. */
    data class Unsent(val pending: Int, val refused: Int) {
        val total: Int get() = pending + refused
    }

    suspend fun unsent(): Unsent = Unsent(store.pendingCount(), store.refusedCount())

    /**
     * «Выйти» (until the settings lose it). `force = false`: returns N > 0 and changes nothing when N changes (waiting
     * or refused) would be deleted — the screen asks first and calls again with `force = true`. Otherwise the phone
     * forgets the farm, connects again as a new device, and 0 is returned.
     */
    suspend fun signOut(force: Boolean): Int = off {
        val n = signOutLocked(force)
        // Signed out just now (Loading): the phone connects again; nothing is left on «Загрузка данных…».
        if (n == 0) mutex.withLock { if (_state.value == SessionState.Loading) connectLocked() }
        n
    }

    private suspend fun signOutLocked(force: Boolean): Int = mutex.withLock {
        if (_state.value !is SessionState.SignedIn) return 0
        if (!force) {
            val n = unsent().total
            if (n > 0) return n
        }
        val engine = _engine.value
        // The run on its way finishes its last write; nothing of this engine touches the store afterwards.
        engine?.stopAndWait()
        closeGate()
        // From here the engine is dead: whatever fails below, the session ends (never «connected» with an engine
        // that will not sync again).
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
            endSession(SessionState.Loading, notice = null)
        }
        0
    }

    // MARK: Sync

    /**
     * A sync now, then `GET /me` (the role, the farm and its zone may have changed). Not connected for want of a
     * network: the connect is tried again instead (the triggers: the network back, the foreground, every minute).
     */
    suspend fun syncNow() = off {
        val s = _state.value
        if (s is SessionState.NotConnected) {
            if (s.reason == ConnectReason.Offline && retryBySync) {
                mutex.withLock {
                    val now = _state.value
                    if (now is SessionState.NotConnected && now.reason == ConnectReason.Offline && retryBySync) connectLocked()
                }
            }
            return@off
        }
        if (s !is SessionState.SignedIn) return@off
        val engine = _engine.value ?: return@off
        if (engine.stopped) return@off
        engine.syncNow()
        refreshMe(engine)
    }

    /**
     * The store, the pull pages' JSON and the token file are never worked on the caller's thread: the connect screen
     * calls from the main thread.
     */
    private suspend fun <T> off(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Default, block)

    private suspend fun readToken(): String? = withContext(Dispatchers.IO) { tokens.get() }

    /** Throws when the token could not be saved (the connect then fails with a message). */
    private suspend fun saveToken(token: String) = withContext(Dispatchers.IO) { tokens.set(token) }

    /** The token goes; a store that cannot even forget it must not stop the sign-out. */
    private suspend fun clearTokenQuietly() {
        try {
            withContext(Dispatchers.IO) { tokens.set(null) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private suspend fun isRevoked(): Boolean = try {
        withContext(Dispatchers.IO) { tokens.revoked() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    private suspend fun setRevokedQuietly(revoked: Boolean) {
        try {
            withContext(Dispatchers.IO) { tokens.setRevoked(revoked) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /** `GET /me` of the connection [engine] belongs to: nothing when that connection ended before or while it was asked. */
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
            // A sign-out that began meanwhile has wiped: nothing of the old connection is written back.
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
     * so stopAndWait is safe. The connect screen says how many unsent changes went with the wipe.
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
                setRevokedQuietly(true)
            } finally {
                endSession(SessionState.NotConnected(ConnectReason.Revoked), notice = lostNotice(lost))
            }
        }
    }

    // MARK: Owner's requests

    /**
     * A request of this connection besides the sync (the owner's lists `/devices`, `/conflicts`, `/backups`,
     * `PATCH /farm`), as `SessionStore.send`: a 401 switches the phone off the way the sync does, and is rethrown as
     * [Unauthorized]. Not connected: [Unauthorized] at once.
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
        val engine = _engine.value ?: return@off
        if (engine.stopped) return@off
        // The cursor goes to 0 inside the engine, under its lock: a pull page of a run on its way cannot overwrite it.
        engine.resyncFromStart()
        refreshMe(engine)
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
                    setRevokedQuietly(true)
                } finally {
                    endSession(SessionState.NotConnected(ConnectReason.Revoked), notice = lostNotice(lost))
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

    private fun endSession(state: SessionState, notice: String?) {
        _engine.value = null
        _farmTime.value = FarmTime(DEFAULT_FARM_ZONE, clock)
        _notice.value = notice
        _state.value = state
    }

    /** Once more on failure; failing again, the next launch wipes (not connected with a connection's rows left). */
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

    /** The text of a failed request, as the iPhone's `error.localizedDescription`. */
    fun errorText(e: Exception): String = when (e) {
        is CancellationException -> throw e
        is Unauthorized -> e.message!!
        is ApiError -> e.text
        is SSLException -> "Ошибка соединения с сервером"
        is IOException -> OFFLINE
        else -> e.message ?: e::class.java.simpleName
    }

    override fun toString(): String = "SessionManager(${_state.value::class.simpleName})"

    companion object {
        const val OFFLINE = "Нет связи с сервером"
        const val CLOSED = "Владелец сейчас не подключает новые устройства"
        const val REVOKED = "Это устройство отключено владельцем"
        const val TOKEN_NOT_SAVED = "Не удалось сохранить подключение на телефоне"

        /** The unsent changes that went with the wipe of a switched-off phone, when there were any. */
        fun lostNotice(lost: Int): String? = if (lost > 0) "Неотправленных изменений удалено: $lost." else null
    }
}
