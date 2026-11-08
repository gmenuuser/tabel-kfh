package ru.kfh.core.livestock

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalText
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.time.FarmTime

/** Every refusal of the livestock module, in Russian. Port of `LivestockError`. */
sealed class LivestockError(message: String) : Exception(message) {
    data class Invalid(val messages: List<String>) : LivestockError(messages.joinToString("\n"))

    /** Not enough heads: the category would go below zero on `day`; `available` heads were there. */
    data class NotEnough(val category: String, val day: LocalDate, val available: Long) :
        LivestockError("Не хватает голов: в категории «$category» на ${CoalText.date(day)} было ${LivestockText.heads(available)}.")

    data object AlreadySaved : LivestockError("Эта запись уже сохранена")
    data object CategoryNotFound : LivestockError("Категория не найдена")
    data object CategoryInUse : LivestockError("По этой категории есть операции, удалить её нельзя")
    data class DuplicateCategory(val name: String) : LivestockError("Категория «$name» уже есть")
}

object LivestockText {
    private const val NBSP = " "

    /** «1 голова», «2 головы», «5 голов». */
    fun heads(n: Long): String = "$n " + CoalText.plural((n % 1_000_000_000L).toInt(), "голова", "головы", "голов")

    /** «120,5 кг», «—» for null. */
    fun kilograms(grams: Long?): String = if (grams == null) "—" else CoalMath.tons(grams) + NBSP + "кг"
}

/** What the owner types into an operation form. */
data class LivestockDraft(
    /** The operation's id: a second save of the same form is refused instead of counting twice. */
    val id: String,
    val kind: LivestockOperationKind,
    val date: Instant,
    val categoryId: String? = null,
    val targetCategoryId: String? = null,
    val headsText: String = "",
    val comment: String = "",
    // Slaughter only.
    val reason: SlaughterReason = SlaughterReason.MEAT,
    val liveWeightText: String = "",
    val carcassWeightText: String = "",
    val meatWeightText: String = "",
) {
    /** Whole heads as typed; null for anything else («1,5», «abc», empty). */
    val heads: Long? get() = parseHeads(headsText)

    val validationErrors: List<String>
        get() {
            val errors = mutableListOf<String>()
            if (categoryId == null) errors += "Выберите категорию"
            val h = heads
            if (h != null) {
                if (h <= 0) errors += "Количество голов — больше нуля"
                if (h > 100_000) errors += "Слишком большое количество голов"
            } else {
                errors += if (headsText.trimBlank().isEmpty()) "Укажите количество голов" else "Количество голов — целое число"
            }
            if (kind == LivestockOperationKind.TRANSFER) {
                if (targetCategoryId == null) errors += "Выберите, в какую категорию перевести"
                if (targetCategoryId != null && targetCategoryId == categoryId) errors += "Категории перевода совпадают"
            }
            if (kind == LivestockOperationKind.SLAUGHTER) {
                for ((text, name) in listOf(liveWeightText to "живой вес", carcassWeightText to "вес после забоя", meatWeightText to "вес мяса")) {
                    if (!gramsValid(text)) errors += "Неверный $name: число килограммов, например 250,5"
                }
            }
            return errors
        }

    val isValid: Boolean get() = validationErrors.isEmpty()

    companion object {
        private fun String.trimBlank() = trim { it.isWhitespace() }

        private fun parseHeads(text: String): Long? {
            val t = text.trimBlank()
            if (!Regex("[+-]?[0-9]+").matches(t)) return null
            return t.toLongOrNull()
        }

        /** Kilograms with up to 3 decimals typed in a field are valid when empty or a number. */
        fun gramsValid(text: String): Boolean = text.trimBlank().isEmpty() || CoalMath.parseTons(text.trimBlank()) != null

        /** Kilograms with up to 3 decimals -> grams; null for an empty (or wrong) field. */
        fun grams(text: String): Long? {
            val t = text.trimBlank()
            return if (t.isEmpty()) null else CoalMath.parseTons(t)
        }
    }
}

/** Everything the livestock rules read: the saved records and the farm's calendar. */
class LivestockBooks(
    val categories: List<LivestockCategory>,
    val operations: List<LivestockOperation>,
    val slaughters: List<SlaughterRecord>,
    val farm: FarmTime,
    /** The settings row; null until the default categories were made. */
    val settings: LivestockSettings? = null,
) {
    fun slaughterOf(operationId: String): SlaughterRecord? = slaughters.firstOrNull { it.operationId == operationId }
}

/** The default settings row and categories to write (empty when they already exist). */
data class LivestockDefaults(val settings: LivestockSettings?, val categories: List<LivestockCategory>)

/** The records one save or correction writes: the operation, its slaughter details, and details to delete. */
data class LivestockChange(val operation: LivestockOperation, val slaughter: SlaughterRecord?, val removedSlaughter: SlaughterRecord?)

/** What deleting an operation removes. */
data class LivestockDeletion(val operation: LivestockOperation, val slaughter: SlaughterRecord?)

data class LivestockPeriodRange(val start: Instant, val end: Instant)

/**
 * The herd as pure functions: each takes the saved records and returns the record(s) to write, or throws a
 * [LivestockError]. Port of the domain parts of `LivestockService`. The herd is never stored, always computed.
 */
object LivestockRules {
    // ---- Categories ----

    /** Makes the default categories once (they can be renamed later; own ones can be added). */
    fun ensureDefaults(books: LivestockBooks, now: Instant): LivestockDefaults {
        if (books.settings != null) return LivestockDefaults(null, emptyList())
        val made = if (books.categories.isEmpty()) {
            Species.entries.flatMap { species ->
                species.defaultCategories.mapIndexed { index, name ->
                    LivestockCategory(
                        id = StableId.livestockCategory(species.wire, name), species = species.wire, name = name,
                        sortOrder = index, isBuiltIn = true, createdAt = now,
                    )
                }
            }
        } else {
            emptyList()
        }
        return LivestockDefaults(LivestockSettings(createdAt = now), made)
    }

    /** Species, then sort order, then name. */
    fun categories(books: LivestockBooks, species: Species? = null): List<LivestockCategory> =
        books.categories.filter { species == null || Species.fromWire(it.species) == species }
            .sortedWith(compareBy<LivestockCategory>({ Species.fromWire(it.species).ordinal }, { it.sortOrder }, { it.name }))

    /**
     * An own category gets a random `id` (the caller's), as on the site: one deleted and added again with the same
     * name is a new record, not the server's deleted one. Only the default categories have stable ids.
     */
    fun addCategory(name: String, species: Species, books: LivestockBooks, id: String, now: Instant): LivestockCategory {
        val trimmed = collapse(name)
        if (trimmed.isEmpty()) throw LivestockError.Invalid(listOf("Укажите название категории"))
        val existing = categories(books, species)
        if (existing.any { it.name.equals(trimmed, ignoreCase = true) }) throw LivestockError.DuplicateCategory(trimmed)
        return LivestockCategory(
            id = id, species = species.wire, name = trimmed, sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            createdAt = now,
        )
    }

    /** Only an own category without operations is deleted. */
    fun checkCategoryDeletable(c: LivestockCategory, books: LivestockBooks): LivestockCategory {
        if (c.isBuiltIn) throw LivestockError.CategoryInUse
        if (books.operations.any { it.categoryId == c.id || it.targetCategoryId == c.id }) throw LivestockError.CategoryInUse
        return c
    }

    /** Single spaces between the words; Unicode whitespace (U+00A0 too) separates them, not regex `\s`. */
    private fun collapse(s: String): String {
        val words = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    words += sb.toString()
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) words += sb.toString()
        return words.joinToString(" ")
    }

    // ---- Herd ----

    fun moves(books: LivestockBooks): List<HerdMove> = books.operations.map(HerdMove::of)

    /** Heads of each category now (after every operation). */
    fun balances(books: LivestockBooks): Map<String, Long> = LivestockLedger.balances(moves(books))

    /** Heads of a species now. */
    fun total(books: LivestockBooks, species: Species): Long {
        val b = balances(books)
        return categories(books, species).sumOf { b[it.id] ?: 0 }
    }

    /**
     * Saves a new operation. A decrease, a transfer or a slaughter is refused when there are not enough heads at its
     * date or it would make any later balance negative. `newSlaughterId` is the id of the details record.
     */
    fun save(d: LivestockDraft, books: LivestockBooks, now: Instant, newSlaughterId: String): LivestockChange {
        if (!d.isValid) throw LivestockError.Invalid(d.validationErrors)
        if (books.operations.any { it.id == d.id }) throw LivestockError.AlreadySaved
        val candidate = move(d, books, createdAt = now, id = d.id)
        check(books, replacing = null, candidate = candidate)
        val o = LivestockOperation(
            id = d.id, kind = d.kind.wire, date = d.date, categoryId = candidate.categoryId,
            targetCategoryId = candidate.targetCategoryId, heads = candidate.heads, comment = d.comment.trim { it.isWhitespace() },
            createdAt = now, updatedAt = now,
        )
        return finish(o, d, books, now, newSlaughterId)
    }

    /**
     * Saves the edited form over the operation; the herd is recomputed from the corrected history.
     * `newSlaughterId` is needed only when an operation without details becomes a slaughter.
     */
    fun update(o: LivestockOperation, d: LivestockDraft, books: LivestockBooks, now: Instant, newSlaughterId: String? = null): LivestockChange {
        if (!d.isValid) throw LivestockError.Invalid(d.validationErrors)
        val candidate = move(d, books, createdAt = o.createdAt, id = o.id)
        check(books, replacing = o.id, candidate = candidate)
        val updated = o.copy(
            kind = d.kind.wire, date = d.date, categoryId = candidate.categoryId, targetCategoryId = candidate.targetCategoryId,
            heads = candidate.heads, comment = d.comment.trim { it.isWhitespace() }, updatedAt = now,
        )
        return finish(updated, d, books, now, newSlaughterId)
    }

    /**
     * Deletes an operation entered by mistake (with its slaughter details). Refused when the herd would go below
     * zero without it (an addition the later decreases rely on).
     */
    fun delete(o: LivestockOperation, books: LivestockBooks): LivestockDeletion {
        check(books, replacing = o.id, candidate = null)
        return LivestockDeletion(o, books.slaughterOf(o.id))
    }

    /** The form of a saved operation. */
    fun draftFor(o: LivestockOperation, books: LivestockBooks): LivestockDraft {
        var d = LivestockDraft(
            id = o.id, kind = LivestockOperationKind.fromWire(o.kind), date = o.date, categoryId = o.categoryId,
            targetCategoryId = o.targetCategoryId, headsText = "${o.heads}", comment = o.comment,
        )
        books.slaughterOf(o.id)?.let { s ->
            d = d.copy(
                reason = SlaughterReason.fromWire(s.reason),
                liveWeightText = s.liveWeightGrams?.let(::kilogramsField) ?: "",
                carcassWeightText = s.carcassWeightGrams?.let(::kilogramsField) ?: "",
                meatWeightText = s.meatWeightGrams?.let(::kilogramsField) ?: "",
            )
        }
        return d
    }

    fun kilogramsField(grams: Long): String = CoalMath.tons(grams).replace(" ", "")

    private fun move(d: LivestockDraft, books: LivestockBooks, createdAt: Instant, id: String): HerdMove {
        val categoryId = d.categoryId
        if (categoryId == null || books.categories.none { it.id == categoryId }) throw LivestockError.CategoryNotFound
        val target = d.targetCategoryId
        if (d.kind == LivestockOperationKind.TRANSFER && target != null && books.categories.none { it.id == target }) {
            throw LivestockError.CategoryNotFound
        }
        return HerdMove(
            id, d.kind, d.date, createdAt, categoryId,
            if (d.kind == LivestockOperationKind.TRANSFER) d.targetCategoryId else null, d.heads ?: 0,
        )
    }

    /** The whole history with the change applied must never go below zero. */
    private fun check(books: LivestockBooks, replacing: String?, candidate: HerdMove?) {
        val all = moves(books).filter { it.id != replacing } + listOfNotNull(candidate)
        val shortage = LivestockLedger.firstShortage(all) ?: return
        val name = books.categories.firstOrNull { it.id == shortage.categoryId }?.name ?: "?"
        throw LivestockError.NotEnough(name, books.farm.dayOf(shortage.date), maxOf(0, shortage.heads))
    }

    private fun finish(o: LivestockOperation, d: LivestockDraft, books: LivestockBooks, now: Instant, newSlaughterId: String?): LivestockChange {
        val errors = serverErrors(o)
        if (errors.isNotEmpty()) throw LivestockError.Invalid(errors)
        val existing = books.slaughterOf(o.id)
        if (d.kind != LivestockOperationKind.SLAUGHTER) return LivestockChange(o, null, existing)
        val base = existing ?: SlaughterRecord(
            id = requireNotNull(newSlaughterId) { "a new slaughter record needs an id" }, operationId = o.id,
            reason = d.reason.wire, createdAt = now,
        )
        val s = base.copy(
            reason = d.reason.wire, liveWeightGrams = LivestockDraft.grams(d.liveWeightText),
            carcassWeightGrams = LivestockDraft.grams(d.carcassWeightText), meatWeightGrams = LivestockDraft.grams(d.meatWeightText),
        )
        return LivestockChange(o, s, null)
    }

    /** Port of the `livestockOperation` validate of `server/src/entities.ts`: empty when the server would accept it. */
    fun serverErrors(o: LivestockOperation): List<String> {
        val errors = mutableListOf<String>()
        if (o.heads <= 0) errors += "Количество голов — целое число больше нуля"
        if (o.categoryId.isEmpty()) errors += "Не указана категория"
        if (LivestockOperationKind.entries.none { it.wire == o.kind }) errors += "Неизвестный тип операции"
        if (o.kind == "transfer" && (o.targetCategoryId.isNullOrEmpty() || o.targetCategoryId == o.categoryId)) {
            errors += "Перевод — в другую категорию"
        }
        return errors
    }

    // ---- Reports ----

    /** «Движение поголовья» over `start <= date < end`, one row per category that had animals or moves. */
    fun movement(books: LivestockBooks, start: Instant, end: Instant): List<HerdMovementRow> {
        val all = moves(books)
        val before = LivestockLedger.balances(all, before = start)
        val cats = categories(books)
        val rows = HashMap<String, HerdMovementRow>()
        for (c in cats) {
            rows[c.id] = HerdMovementRow(c.id, Species.fromWire(c.species), c.name, start = before[c.id] ?: 0)
        }
        for (m in all) {
            if (m.date < start || m.date >= end) continue
            for (c in m.categories.distinct()) rows[c]?.let { rows[c] = it.add(m) }
        }
        return cats.mapNotNull { rows[it.id] }.filter { it != HerdMovementRow(it.categoryId, it.species, it.name) }
    }

    /** Slaughter over `start <= date < end` by category. */
    fun slaughterSummary(books: LivestockBooks, start: Instant, end: Instant): List<SlaughterSummaryRow> {
        val cats = categories(books)
        val rows = HashMap<String, SlaughterSummaryRow>()
        for (o in books.operations) {
            if (o.kind != LivestockOperationKind.SLAUGHTER.wire || o.date < start || o.date >= end) continue
            val c = cats.firstOrNull { it.id == o.categoryId } ?: continue
            var r = rows[c.id] ?: SlaughterSummaryRow(c.id, Species.fromWire(c.species), c.name)
            r = r.copy(heads = r.heads + o.heads, operations = r.operations + 1)
            books.slaughterOf(o.id)?.let { s ->
                r = r.copy(
                    liveGrams = r.liveGrams + (s.liveWeightGrams ?: 0),
                    carcassGrams = r.carcassGrams + (s.carcassWeightGrams ?: 0),
                    meatGrams = r.meatGrams + (s.meatWeightGrams ?: 0),
                )
            }
            rows[c.id] = r
        }
        return cats.mapNotNull { rows[it.id] }
    }

    /** Day, month or year of the farm containing `date`. */
    fun period(kind: LivestockPeriod, containing: Instant, farm: FarmTime): LivestockPeriodRange {
        val day = farm.dayOf(containing)
        val (from, to) = when (kind) {
            LivestockPeriod.DAY -> day to day.plusDays(1)
            LivestockPeriod.MONTH -> YearMonth.from(day).let { it.atDay(1) to it.plusMonths(1).atDay(1) }
            LivestockPeriod.YEAR -> LocalDate.of(day.year, 1, 1) to LocalDate.of(day.year + 1, 1, 1)
        }
        return LivestockPeriodRange(farm.startOf(from), farm.startOf(to))
    }
}
