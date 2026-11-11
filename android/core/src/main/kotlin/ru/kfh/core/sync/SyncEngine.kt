package ru.kfh.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import javax.net.ssl.SSLException

/**
 * Offline first, as the site (`web/src/lib/sync.ts`) and the iPhone (`TabelKFH/Sync/SyncEngine.swift`): every change
 * is already in [store] with its op; [syncNow] sends the queue (`POST /sync/push`), takes what other devices changed
 * (`GET /sync/pull`) and counts open conflicts (`GET /conflicts`).
 *
 * Nothing leaves the queue without the server's definite answer (applied / partial / conflict / rejected): a dropped
 * connection, a 5xx or an op missing from the answer keeps the op, and it goes again with the same opId (the server
 * answers a repeat with the old result, so a sale is never lost or doubled). One sync at a time.
 */
class SyncEngine(
    private val api: Api,
    private val store: LocalStore,
    /** After a 401: the store is wiped; [lost] is how many changes were waiting to be sent and went with it. */
    private val onSignedOut: suspend (lost: Int) -> Unit,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Synced)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _lastSyncAt = MutableStateFlow<Instant?>(null)
    /** When a run last reached the server and finished («Последняя синхронизация» in the settings). */
    val lastSyncAt: StateFlow<Instant?> = _lastSyncAt.asStateFlow()

    private val mutex = Mutex()

    /** Set by [stop] or a 401: nothing more is sent, and nothing fetched afterwards is written. */
    @Volatile
    var stopped = false
        private set

    /** Thrown inside a run once [stopped]: the store is being wiped, nothing more is written. */
    private class Stopped : Exception()

    /** One sync; a call during a sync waits for it and then runs its own (a change just made goes too). */
    suspend fun syncNow() {
        if (stopped) return
        mutex.withLock {
            if (stopped) return
            run()
        }
    }

    /**
     * No new run starts and the run on its way writes nothing after its next check. Does not wait: a store write
     * already past its check may still land, so before wiping use [stopAndWait].
     */
    fun stop() {
        stopped = true
    }

    /**
     * Sign-out: stops, then waits until the run on its way (if any) has finished its last store write. After it
     * returns nothing of this engine touches the store again, so the caller can `store.wipe()` safely.
     * The app must call this (not [stop]) before wiping on sign-out.
     */
    suspend fun stopAndWait() {
        stopped = true
        mutex.withLock { }
    }

    /**
     * The next pull from the first change, then a run: every record takes the server's version again (after a
     * server restore, or a refused change removed). The cursor is reset under the lock, so a page of a run on its
     * way cannot land a moment later and overwrite the reset, leaving the refused value on the phone.
     */
    suspend fun resyncFromStart() {
        if (stopped) return
        mutex.withLock {
            if (stopped) return
            try {
                store.setCursor(0)
            } catch (e: Exception) {
                // The store refused: the run below pulls from where it was, as before.
                if (e is CancellationException) throw e
            }
            run()
        }
    }

    /** The status from the store alone, without the network (at start, after a refused change was removed). */
    suspend fun refreshStatus() {
        if (stopped) return
        _status.value = settled(conflicts = (_status.value as? SyncStatus.Conflicts)?.count ?: 0)
    }

    // MARK: One run

    private suspend fun run() {
        val before = _status.value
        _status.value = SyncStatus.Syncing
        try {
            pushAll()
            pullAll()
            val open = api.conflicts().size
            check()
            _lastSyncAt.value = Instant.now()
            _status.value = settled(open)
        } catch (e: CancellationException) {
            // Cancelled from outside (the app's scope ended): the queue is as it was, so is the line.
            _status.value = before
            throw e
        } catch (e: Stopped) {
            // Signed out meanwhile: nothing is written.
        } catch (e: Unauthorized) {
            stopped = true
            _status.value = SyncStatus.Failed(e.message!!)
            // Counted before the wipe: the sign-in screen tells how many unsent changes were lost.
            val lost = try { store.pendingCount() } catch (_: Exception) { 0 }
            try {
                store.wipe()
            } catch (e: Exception) {
                // A wipe that failed is not a reason to stay «signed in» with a stopped engine: the session ends
                // all the same, and the next launch wipes the leftovers (no token, rows left).
                if (e is CancellationException) throw e
            } finally {
                onSignedOut(lost)
            }
        } catch (e: SSLException) {
            // TLS, certificate: the network is there, the connection is wrong.
            _status.value = SyncStatus.Failed("Ошибка соединения с сервером")
        } catch (e: IOException) {
            _status.value = SyncStatus.Offline(store.pendingCount())
        } catch (e: ApiError) {
            _status.value = SyncStatus.Failed(e.text)
        } catch (e: Exception) {
            // The store failed: the queue is whatever it saved, the line must not stay at «Синхронизация…».
            _status.value = SyncStatus.Failed("Ошибка синхронизации")
        }
    }

    /** After a run that reached the server (or from the store): refused → conflicts → waiting → synced. */
    private suspend fun settled(conflicts: Int): SyncStatus {
        val refused = store.refusedCount()
        val pending = store.pendingCount()
        return when {
            refused > 0 -> SyncStatus.Failed("Не принятые изменения: $refused")
            conflicts > 0 -> SyncStatus.Conflicts(conflicts)
            pending > 0 -> SyncStatus.Pending(pending)
            else -> SyncStatus.Synced
        }
    }

    private fun check() {
        if (stopped) throw Stopped()
    }

    // MARK: Push

    /**
     * The queue in order, [BATCH_SIZE] at most and at most one op per record in a push: a later op of a record goes
     * after the answer to the earlier one, with the version that answer gave. A push ends before the second op of a
     * record, so no op queued after it overtakes it (the server checks the herd against what it already has).
     */
    private suspend fun pushAll() {
        while (true) {
            check()
            val queue = store.pendingOps(BATCH_SIZE)
            val records = HashSet<String>()
            val batch = ArrayList<Op>()
            for (op in queue) {
                if (!records.add("${op.entity}:${op.recordId}")) break
                batch += op
            }
            if (batch.isEmpty()) return

            // A failure here (no answer) leaves the queue as it is: the same ops, the same opIds, next time.
            val results = api.push(batch)
            check()
            val ids = batch.mapTo(HashSet()) { it.opId }
            val seen = HashSet<String>()
            val settled = results.filter { it.isSettled && it.opId in ids && seen.add(it.opId) }
            store.acknowledge(settled)
            // A refused change may already show here: the next pull starts over, so every record takes the server's
            // version again.
            if (settled.any { it.status == "rejected" }) store.setCursor(0)
            // Sent without an answer of its own: queued again, as after a lost connection.
            if (settled.size < batch.size) return
        }
    }

    // MARK: Pull

    private suspend fun pullAll() {
        while (true) {
            check()
            val since = store.cursor()
            val page = api.pull(since)
            check()
            store.applyPulled(page.changes)
            store.setCursor(page.next)
            // A page that does not move on would be asked for forever.
            if (!page.more || page.next <= since) return
        }
    }

    companion object {
        /** Ops in one push (the server takes up to 500). */
        const val BATCH_SIZE = 100
    }
}
