package ru.kfh.core.livestock

import java.time.Instant
import ru.kfh.core.model.LivestockOperation

/** КРС, козы, свиньи. `wire` is how the site and the server spell it. */
enum class Species(val wire: String, val title: String, val defaultCategories: List<String>) {
    CATTLE("cattle", "КРС", listOf("Коровы", "Быки", "Телята", "Молодняк", "Другие")),
    GOATS("goats", "Козы", listOf("Козы", "Козлы", "Козлята", "Молодняк", "Другие")),
    PIGS("pigs", "Свиньи", listOf("Свиноматки", "Хряки", "Поросята", "Откормочные", "Другие"));

    companion object {
        /** An unknown species reads as КРС, as on the iPhone. */
        fun fromWire(s: String): Species = entries.firstOrNull { it.wire == s } ?: CATTLE
    }
}

/** What an operation does to the herd. */
enum class LivestockOperationKind(val wire: String, val title: String) {
    /** Начальное поголовье, поступление (покупка), приплод: + heads. */
    INITIAL("initial", "Начальное поголовье"),
    PURCHASE("purchase", "Поступление"),
    BIRTH("birth", "Приплод"),

    /** From one category to another: − in the source, + in the target, the total stays. */
    TRANSFER("transfer", "Перевод"),

    /** Забой, продажа, падёж, прочее списание: − heads. */
    SLAUGHTER("slaughter", "Забой"),
    SALE("sale", "Продажа"),
    DEATH("death", "Падёж"),
    OTHER("other", "Прочее списание");

    val isAddition: Boolean get() = this == INITIAL || this == PURCHASE || this == BIRTH

    companion object {
        /** Reasons of «Уменьшить». */
        val decreases: List<LivestockOperationKind> = listOf(SLAUGHTER, SALE, DEATH, TRANSFER, OTHER)
        val additions: List<LivestockOperationKind> = listOf(PURCHASE, BIRTH, INITIAL)

        /** An unknown kind reads as «Прочее списание», as on the iPhone. */
        fun fromWire(s: String): LivestockOperationKind = entries.firstOrNull { it.wire == s } ?: OTHER
    }
}

/** Why animals were slaughtered. */
enum class SlaughterReason(val wire: String, val title: String) {
    MEAT("meat", "На мясо"),
    FORCED("forced", "Вынужденный забой"),
    OTHER("other", "Другое");

    companion object {
        fun fromWire(s: String): SlaughterReason = entries.firstOrNull { it.wire == s } ?: OTHER
    }
}

enum class LivestockPeriod { DAY, MONTH, YEAR }

/** An operation reduced to what the counting needs (so the rules are tested without a store). */
data class HerdMove(
    val id: String,
    val kind: LivestockOperationKind,
    val date: Instant,
    val createdAt: Instant,
    val categoryId: String,
    val targetCategoryId: String?,
    val heads: Long,
) {
    /** The change of one category: + for additions and the target of a transfer, − for the rest. */
    fun delta(category: String): Long {
        if (kind == LivestockOperationKind.TRANSFER) {
            var d = 0L
            if (categoryId == category) d -= heads
            if (targetCategoryId == category) d += heads
            return d
        }
        if (categoryId != category) return 0
        return if (kind.isAddition) heads else -heads
    }

    /** The categories it changes. */
    val categories: List<String>
        get() = listOf(categoryId) + (if (kind == LivestockOperationKind.TRANSFER) listOfNotNull(targetCategoryId) else emptyList())

    companion object {
        fun of(o: LivestockOperation) = HerdMove(
            o.id, LivestockOperationKind.fromWire(o.kind), o.date, o.createdAt, o.categoryId, o.targetCategoryId, o.heads,
        )
    }
}

/** The first moment a category went below zero: `heads` is what it had just before `date`'s operation. */
data class HerdShortage(val categoryId: String, val date: Instant, val heads: Long)

/**
 * Остаток = начальное поголовье + поступление + приплод + переводы в категорию − забой − продажа − падёж −
 * прочее списание − переводы из категории. Always computed from the operations. Port of `LivestockLedger`; the
 * server applies the same rule to every herd operation (`firstHerdShortage` in `server/src/entities.ts`).
 */
object LivestockLedger {
    /** Heads of each category after every operation with `date < before` (all of them when null). */
    fun balances(moves: List<HerdMove>, before: Instant? = null): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for (m in moves) {
            if (before != null && m.date >= before) continue
            for (c in m.categories.distinct()) result[c] = (result[c] ?: 0) + m.delta(c)
        }
        return result
    }

    /**
     * The first moment a category goes below zero, in (date, creation) order; null when the history is consistent.
     * Works for a whole history with an operation added, changed (a correction of the past) or removed.
     */
    fun firstShortage(moves: List<HerdMove>): HerdShortage? {
        val ordered = moves.sortedWith(compareBy<HerdMove>({ it.date }, { it.createdAt }))
        val running = HashMap<String, Long>()
        for (m in ordered) {
            for (c in m.categories.distinct()) {
                val now = (running[c] ?: 0) + m.delta(c)
                running[c] = now
                if (now < 0) return HerdShortage(c, m.date, now - m.delta(c))
            }
        }
        return null
    }
}

/** «Движение поголовья» of one category over a period. */
data class HerdMovementRow(
    val categoryId: String,
    val species: Species,
    val name: String,
    val start: Long = 0,
    val initial: Long = 0,
    val purchase: Long = 0,
    val birth: Long = 0,
    val transferIn: Long = 0,
    val transferOut: Long = 0,
    val slaughter: Long = 0,
    val sale: Long = 0,
    val death: Long = 0,
    val other: Long = 0,
) {
    val end: Long get() = start + initial + purchase + birth + transferIn - transferOut - slaughter - sale - death - other

    fun add(m: HerdMove): HerdMovementRow {
        val mine = m.categoryId == categoryId
        return when (m.kind) {
            LivestockOperationKind.INITIAL -> if (mine) copy(initial = initial + m.heads) else this
            LivestockOperationKind.PURCHASE -> if (mine) copy(purchase = purchase + m.heads) else this
            LivestockOperationKind.BIRTH -> if (mine) copy(birth = birth + m.heads) else this
            LivestockOperationKind.SLAUGHTER -> if (mine) copy(slaughter = slaughter + m.heads) else this
            LivestockOperationKind.SALE -> if (mine) copy(sale = sale + m.heads) else this
            LivestockOperationKind.DEATH -> if (mine) copy(death = death + m.heads) else this
            LivestockOperationKind.OTHER -> if (mine) copy(other = other + m.heads) else this
            LivestockOperationKind.TRANSFER -> {
                var r = this
                if (mine) r = r.copy(transferOut = r.transferOut + m.heads)
                if (m.targetCategoryId == categoryId) r = r.copy(transferIn = r.transferIn + m.heads)
                r
            }
        }
    }

    operator fun plus(b: HerdMovementRow) = copy(
        start = start + b.start, initial = initial + b.initial, purchase = purchase + b.purchase, birth = birth + b.birth,
        transferIn = transferIn + b.transferIn, transferOut = transferOut + b.transferOut, slaughter = slaughter + b.slaughter,
        sale = sale + b.sale, death = death + b.death, other = other + b.other,
    )
}

/** Slaughter of one category over a period; weights only from the records that have them. */
data class SlaughterSummaryRow(
    val categoryId: String,
    val species: Species,
    val name: String,
    val heads: Long = 0,
    val operations: Long = 0,
    val liveGrams: Long = 0,
    val carcassGrams: Long = 0,
    val meatGrams: Long = 0,
)
