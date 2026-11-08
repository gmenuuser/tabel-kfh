package ru.kfh.core.livestock

import java.time.Instant
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.day
import ru.kfh.core.timesheet.farmIn

/** A stand-in for the store: applies the records the rules return, the way the later persistence layer will. */
class LivestockWorld {
    val farm: FarmTime = farmIn()
    val info = FarmInfo.DEFAULT

    /** 6 November 2026, 10:00 at the farm. */
    var clock: Instant = farm.startOf(day(6)).plusSeconds(10 * 3600)
    var settings: LivestockSettings? = null
    var categories: List<LivestockCategory> = emptyList()
    var operations: List<LivestockOperation> = emptyList()
    var slaughters: List<SlaughterRecord> = emptyList()
    private var counter = 0

    val books get() = LivestockBooks(categories, operations, slaughters, farm, settings)

    fun nextId() = "id-${++counter}"

    init {
        ensureDefaults()
    }

    fun ensureDefaults() {
        val made = LivestockRules.ensureDefaults(books, clock)
        made.settings?.let { settings = it }
        categories = categories + made.categories
    }

    fun cat(species: Species, name: String): LivestockCategory =
        LivestockRules.categories(books, species).first { it.name == name }

    fun heads(c: LivestockCategory): Long = LivestockRules.balances(books)[c.id] ?: 0

    fun total(species: Species): Long = LivestockRules.total(books, species)

    fun draft(
        kind: LivestockOperationKind, c: LivestockCategory, n: Long, to: LivestockCategory? = null,
        configure: (LivestockDraft) -> LivestockDraft = { it },
    ): LivestockDraft = configure(
        LivestockDraft(id = nextId(), kind = kind, date = clock, categoryId = c.id, targetCategoryId = to?.id, headsText = "$n"),
    )

    /** Saves an operation at `clock`, then moves the clock on a minute. */
    fun record(
        kind: LivestockOperationKind, c: LivestockCategory, n: Long, to: LivestockCategory? = null,
        configure: (LivestockDraft) -> LivestockDraft = { it },
    ): LivestockOperation {
        val change = LivestockRules.save(draft(kind, c, n, to, configure), books, clock, nextId())
        apply(change)
        clock = clock.plusSeconds(60)
        return change.operation
    }

    fun apply(change: LivestockChange) {
        operations = operations.filter { it.id != change.operation.id } + change.operation
        change.removedSlaughter?.let { r -> slaughters = slaughters.filter { it.id != r.id } }
        change.slaughter?.let { s -> slaughters = slaughters.filter { it.id != s.id } + s }
    }

    fun update(o: LivestockOperation, d: LivestockDraft) = apply(LivestockRules.update(o, d, books, clock))

    fun delete(o: LivestockOperation) {
        val gone = LivestockRules.delete(o, books)
        operations = operations.filter { it.id != gone.operation.id }
        gone.slaughter?.let { s -> slaughters = slaughters.filter { it.id != s.id } }
    }
}
