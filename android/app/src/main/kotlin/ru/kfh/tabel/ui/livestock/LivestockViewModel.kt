package ru.kfh.tabel.ui.livestock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.livestock.HerdMove
import ru.kfh.core.livestock.LivestockBooks
import ru.kfh.core.livestock.LivestockChange
import ru.kfh.core.livestock.LivestockDraft
import ru.kfh.core.livestock.LivestockError
import ru.kfh.core.livestock.LivestockLedger
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockRules
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.Entities
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.observe
import ru.kfh.tabel.ui.common.ActionQueue
import java.time.Instant

/** Current heads of every category and species, read from all operations (`HerdCounts`). Never stored. */
data class Herd(val byCategory: Map<String, Long>, val bySpecies: Map<Species, Long>) {
    val total: Long get() = bySpecies.values.sum()

    fun heads(c: LivestockCategory): Long = byCategory[c.id] ?: 0

    fun heads(s: Species): Long = bySpecies[s] ?: 0

    companion object {
        val EMPTY = Herd(emptyMap(), emptyMap())

        fun of(categories: List<LivestockCategory>, operations: List<LivestockOperation>): Herd {
            val b = LivestockLedger.balances(operations.map(HerdMove::of))
            val s = HashMap<Species, Long>()
            for (c in categories) {
                val species = Species.fromWire(c.species)
                s[species] = (s[species] ?: 0) + (b[c.id] ?: 0)
            }
            return Herd(b, s)
        }
    }
}

/**
 * «Скот и забой»: the store's livestock records as flows the screens collect, and the screens' actions as the rules
 * of the domain ([LivestockRules]) written through [RecordWriter]. One per process ([ru.kfh.tabel.AppGraph.livestock]).
 *
 * - The herd is never stored: every screen counts it from the operations ([herd]).
 * - A decrease that would take the herd below zero — now or at any later moment of the history — is refused
 *   ([LivestockRules.save] / [LivestockRules.update] / [LivestockRules.delete] check the whole history).
 * - Every action runs in [scope] (the app's) one at a time: leaving a screen never cancels a write, and a second
 *   tap on the same form is refused («Эта запись уже сохранена»).
 * - One user action = one Room transaction with one op per touched record: a slaughter writes the operation and its
 *   details together; deleting it deletes both.
 * - An action returns the text for «Не получилось» (the domain's Russian message), or null when it went through.
 */
class LivestockViewModel(
    private val dao: RecordDao,
    private val writer: RecordWriter,
    private val farmTime: StateFlow<FarmTime>,
    scope: CoroutineScope,
    private val now: () -> Instant = { Instant.now() },
    /** The farm's name and manager (from `Me`) and the settings row: the header of the reports. */
    val farmInfo: Flow<FarmInfo> = kotlinx.coroutines.flow.flowOf(FarmInfo.DEFAULT),
) {
    private val actions = ActionQueue(scope)

    val farm: FarmTime get() = farmTime.value

    fun now(): Instant = now.invoke()

    // MARK: What the role may do

    val canEditOperations: Boolean get() = writer.canWrite(Entities.livestockOperation)
    val canSlaughter: Boolean get() = writer.canWrite(Entities.slaughterRecord) && writer.canWrite(Entities.livestockOperation)
    val canEditCategories: Boolean get() = writer.canWrite(Entities.livestockCategory)

    // MARK: What the screens show

    // Decoding runs off the main thread (the screens collect with collectAsState).
    private inline fun <reified T : Any> records(entity: String): Flow<List<T>> =
        dao.observe(entity).map { rows -> rows.map { RecordCodec.decode<T>(entity, it.id, it.json()) } }.flowOn(Dispatchers.Default)

    /** The settings row; null until the default categories were made. */
    val settings: Flow<LivestockSettings?> = records<LivestockSettings>(Entities.livestockSettings).map { it.firstOrNull() }

    /** Every category: species, then sort order, then name. */
    val categories: Flow<List<LivestockCategory>> =
        records<LivestockCategory>(Entities.livestockCategory).map { LivestockRules.categories(LivestockBooks(it, emptyList(), emptyList(), farm)) }

    /** Every operation, newest first (`LivestockService.operations`). */
    val operations: Flow<List<LivestockOperation>> =
        records<LivestockOperation>(Entities.livestockOperation).map { list -> list.sortedWith(compareByDescending<LivestockOperation> { it.date }.thenByDescending { it.createdAt }) }

    val slaughters: Flow<List<SlaughterRecord>> = records(Entities.slaughterRecord)

    /** Everything the rules read, rebuilt after any change. */
    val books: Flow<LivestockBooks> = combine(categories, operations, slaughters, settings, farmTime) { c, o, s, st, f ->
        LivestockBooks(c, o, s, f, st)
    }

    /** Heads of every category and species now. */
    val herd: Flow<Herd> = combine(categories, operations) { c, o -> Herd.of(c, o) }

    fun categories(species: Species): Flow<List<LivestockCategory>> = categories.map { list -> list.filter { Species.fromWire(it.species) == species } }

    fun operation(id: String): Flow<LivestockOperation?> = operations.map { list -> list.firstOrNull { it.id.equals(id, ignoreCase = true) } }

    // MARK: Opening the module

    /**
     * `LivestockService.ensureDefaults`: on the first opening by a role that may write the settings and the categories,
     * the settings row and — when the farm has no categories at all yet — the 15 default ones are written in one
     * transaction, under stable ids. Any other opening, or a viewer's, writes nothing.
     */
    suspend fun ensureDefaults(): String? = action {
        if (!writer.canWrite(Entities.livestockSettings) || !writer.canWrite(Entities.livestockCategory)) return@action
        val made = LivestockRules.ensureDefaults(books.first(), now())
        val settings = made.settings ?: return@action
        val changes = mutableListOf(Triple(Entities.livestockSettings, settings.id, RecordCodec.encode(settings)))
        for (c in made.categories) changes += Triple(Entities.livestockCategory, c.id, RecordCodec.encode(c))
        writer.saveAll(changes)
    }

    // MARK: Operations

    /** An empty form of [kind] for [categoryId], dated now, with its own id (a second save of the same form is refused). */
    fun newDraft(kind: LivestockOperationKind, categoryId: String? = null): LivestockDraft =
        LivestockDraft(id = RecordWriter.newId(), kind = kind, date = now(), categoryId = categoryId)

    /** The form of the saved operation [id] (its slaughter details too), or null when it is gone. */
    suspend fun draftFor(id: String): LivestockDraft? {
        val b = books.first()
        val o = b.operations.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return null
        return LivestockRules.draftFor(o, b)
    }

    /** «СОХРАНИТЬ» / «СОХРАНИТЬ ЗАБОЙ»: the operation (and the slaughter details) in one transaction. */
    suspend fun save(d: LivestockDraft): Result<LivestockOperation> = result {
        val change = LivestockRules.save(d, books.first(), now(), RecordWriter.newId())
        write(change)
        change.operation
    }

    /** «СОХРАНИТЬ ИЗМЕНЕНИЯ»: the corrected operation; the herd is recounted from the corrected history. */
    suspend fun update(o: LivestockOperation, d: LivestockDraft): String? = action {
        // A fresh id is needed only when an operation without details becomes a slaughter.
        write(LivestockRules.update(o, d, books.first(), now(), RecordWriter.newId()))
    }

    /** «Удалить операцию»: the operation and its details go in one transaction; refused when the herd would go below zero. */
    suspend fun delete(o: LivestockOperation): String? = action {
        val gone = LivestockRules.delete(o, books.first())
        val deletes = mutableListOf(Entities.livestockOperation to gone.operation.id)
        gone.slaughter?.let { deletes += Entities.slaughterRecord to it.id }
        writer.saveAll(emptyList(), deletes)
    }

    private suspend fun write(change: LivestockChange) {
        val changes = mutableListOf(Triple(Entities.livestockOperation, change.operation.id, RecordCodec.encode(change.operation)))
        change.slaughter?.let { changes += Triple(Entities.slaughterRecord, it.id, RecordCodec.encode(it)) }
        val deletes = listOfNotNull(change.removedSlaughter?.let { Entities.slaughterRecord to it.id })
        writer.saveAll(changes, deletes)
    }

    // MARK: Categories

    /** «Своя категория»: a new category of [species] with a random lowercase id, as on the site. */
    suspend fun addCategory(name: String, species: Species): String? = action {
        val c = LivestockRules.addCategory(name, species, books.first(), RecordWriter.newId(), now())
        writer.save(Entities.livestockCategory, c.id, RecordCodec.encode(c))
    }

    /** «Удалить категорию»: only an own category without operations. */
    suspend fun deleteCategory(c: LivestockCategory): String? = action {
        LivestockRules.checkCategoryDeletable(c, books.first())
        writer.delete(Entities.livestockCategory, c.id)
    }

    // MARK: Running

    private suspend fun action(block: suspend () -> Unit): String? = actions.run {
        try {
            block()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            describe(e)
        }
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = actions.run {
        try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(if (e is LivestockError) e else IllegalStateException(describe(e), e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is LivestockError -> e.message ?: "Не получилось"
        is IllegalStateException -> e.message ?: "Не получилось"
        else -> "Не получилось: ${e.message ?: e::class.java.simpleName}"
    }
}
