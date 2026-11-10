package ru.kfh.tabel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.session.SessionState
import java.time.Instant
import java.time.LocalDate

/**
 * «Сегодня» at the farm as the screens see it (the farm's zone, never the phone's): cached in [flow], recomputed
 * by [refresh] when a screen resumes or the farm's midnight passes ([LiveFarmToday]), by [reset] on a sign-in
 * ([onSignIn]) and on its own when the farm's zone changes. Shared by the timesheet and the coal module.
 *
 * [onAdvance] is told the old and the new day when today moved (a day the screen sat on may follow).
 */
class FarmToday(
    private val farmTime: StateFlow<FarmTime>,
    scope: CoroutineScope,
    private val now: () -> Instant,
    private val onAdvance: (old: LocalDate, fresh: LocalDate) -> Unit = { _, _ -> },
) {
    val farm: FarmTime get() = farmTime.value

    /** The farm's day of this moment (the clock, not the cached [flow]). */
    fun today(): LocalDate = farm.dayOf(now())

    private val _today = MutableStateFlow(today())
    val flow: StateFlow<LocalDate> = _today.asStateFlow()

    init {
        // The farm's zone changed (another farm, or the server corrected it): the same moment may be another day.
        scope.launch { farmTime.drop(1).collect { refresh() } }
    }

    /** Recomputes today; when it moved, [onAdvance] runs. Returns true when it moved. */
    fun refresh(): Boolean {
        val fresh = today()
        val old = _today.value
        if (fresh == old) return false
        _today.value = fresh
        onAdvance(old, fresh)
        return true
    }

    /** Today again, whatever was cached (a sign-in). */
    fun reset(): LocalDate {
        val fresh = today()
        _today.value = fresh
        return fresh
    }

    /** Milliseconds until the farm's next midnight after the cached day (at least one second): when to refresh. */
    fun millisUntilNextFarmDay(): Long =
        (farm.startOf(_today.value.plusDays(1)).toEpochMilli() - now().toEpochMilli()).coerceAtLeast(1_000L)

    /**
     * Runs [block] on every sign-in — the step from «not signed in» to «signed in» — and never on the `SignedIn(me)`
     * the session re-emits after a sync when the role, the farm's name or its zone changed.
     */
    fun onSignIn(scope: CoroutineScope, states: Flow<SessionState>, block: () -> Unit) {
        scope.launch {
            states.map { it is SessionState.SignedIn }.distinctUntilChanged().filter { it }.collect { block() }
        }
    }
}

/**
 * Keeps «Сегодня» live on a screen, as the iPhone's: [refresh] when the screen comes back (the app slept past
 * midnight) and at the farm's next midnight while it stays open. A loop, not a one-shot: an early wake (same day)
 * or a zone change recomputes the next midnight and waits on.
 */
@Composable
fun LiveFarmToday(key: Any, refresh: () -> Unit, millisUntilNextFarmDay: () -> Long) {
    LifecycleResumeEffect(key) {
        refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(key) {
        while (true) {
            delay(millisUntilNextFarmDay())
            refresh()
        }
    }
}

/**
 * The writes of a module, one at a time, in the app's [scope]: leaving a screen in the middle never cancels a
 * write, and the second of two fast taps reads what the first one wrote.
 */
class ActionQueue(private val scope: CoroutineScope) {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = scope.async { mutex.withLock { block() } }.await()
}
