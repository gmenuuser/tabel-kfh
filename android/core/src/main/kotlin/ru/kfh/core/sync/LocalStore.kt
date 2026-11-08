package ru.kfh.core.sync

/**
 * What [SyncEngine] needs from the phone's store (the Room implementation is in the app). Everything here is
 * persistent: a new engine over the same store (an app restart) sends the same queue with the same opIds.
 */
interface LocalStore {
    /** The oldest [limit] queued ops in queue order, refused ones left out. Never merges into or re-ids an op. */
    suspend fun pendingOps(limit: Int): List<Op>

    /** Ops waiting to be sent (refused ones not counted). */
    suspend fun pendingCount(): Int

    /** Ops the server refused, kept for «Не принятые изменения». */
    suspend fun refusedCount(): Int

    /**
     * The server's definite answers (every status in [OpResult.SETTLED]), in one transaction: each op leaves the
     * queue; `rejected` goes to the refused list with its errors; with a `version` the record's version becomes at
     * least it, and for `applied`/`partial` the later queued ops of that record get it as their `baseVersion`.
     */
    suspend fun acknowledge(results: List<OpResult>)

    /**
     * One pulled page, in one transaction, queueing nothing: the server's records with this device's queued fields
     * kept on top (a queued delete keeps the record deleted); a `hoursMark` with `hours: null` removes the mark.
     */
    suspend fun applyPulled(changes: List<RemoteRecord>)

    /** The last pulled change number (`GET /sync/pull?since=`). */
    suspend fun cursor(): Long

    suspend fun setCursor(v: Long)

    /** Sign-out: every record, op, refused op and the cursor go. */
    suspend fun wipe()
}
