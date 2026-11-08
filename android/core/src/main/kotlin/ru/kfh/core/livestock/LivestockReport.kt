package ru.kfh.core.livestock

import java.time.Instant
import java.time.YearMonth
import java.util.Locale
import ru.kfh.core.coal.CoalText
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.RuMonths

/**
 * The livestock reports as printable documents: the same blocks as on the iPhone (`LivestockReportLayout`), as
 * plain data. Rendering to PDF / XLSX belongs to the app layer.
 */
object LivestockReport {
    const val SIGNATURE_LABEL = "Руководитель КФХ: ___________________"

    enum class Align { LEFT, CENTER, RIGHT }

    /** `Count` is a whole number, `Tons` is grams shown as kilograms («120,5»); both stay numbers in a spreadsheet. */
    sealed class Cell {
        data class Text(val text: String) : Cell()
        data class Count(val value: Long) : Cell()
        data class Tons(val grams: Long) : Cell()
    }

    data class Column(val title: String, val weight: Int, val align: Align)

    data class Table(val columns: List<Column>, val rows: List<List<Cell>>, val total: List<Cell>?)

    data class Pair(val label: String, val value: Cell, val bold: Boolean)

    sealed class Block {
        data class Title(val text: String) : Block()
        data class BoldText(val text: String) : Block()
        data class Text(val text: String) : Block()
        data class Heading(val text: String) : Block()
        data class Table(val table: LivestockReport.Table) : Block()
        data class Pairs(val pairs: List<LivestockReport.Pair>) : Block()
        data class Note(val text: String) : Block()
        data class Signature(val label: String, val name: String) : Block()
    }

    data class Document(val fileName: String, val runningHeader: String, val blocks: List<Block>)

    data class HerdLine(val species: Species, val name: String, val heads: Long)

    data class SlaughterLine(
        val date: Instant,
        val species: Species,
        val category: String,
        val heads: Long,
        val reason: String,
        val liveGrams: Long?,
        val meatGrams: Long?,
        val comment: String,
    )

    /** «28.11.2026», «Ноябрь 2026», «2026 год». */
    fun periodTitle(kind: LivestockPeriod, start: Instant, farm: FarmTime): String {
        val day = farm.dayOf(start)
        return when (kind) {
            LivestockPeriod.DAY -> CoalText.date(day)
            LivestockPeriod.MONTH -> RuMonths.title(YearMonth.from(day))
            LivestockPeriod.YEAR -> "${day.year} год"
        }
    }

    private fun fileStamp(kind: LivestockPeriod, start: Instant, farm: FarmTime): String {
        val d = farm.dayOf(start)
        return when (kind) {
            LivestockPeriod.DAY -> String.format(Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
            LivestockPeriod.MONTH -> String.format(Locale.ROOT, "%04d-%02d", d.year, d.monthValue)
            LivestockPeriod.YEAR -> String.format(Locale.ROOT, "%04d", d.year)
        }
    }

    /** «06.11.2026 14:05» at the farm. */
    private fun dateTime(at: Instant, farm: FarmTime): String {
        val z = at.atZone(farm.zone)
        return CoalText.date(z.toLocalDate()) + String.format(Locale.ROOT, " %02d:%02d", z.hour, z.minute)
    }

    private fun footer(manager: String, createdAt: Instant, farm: FarmTime): List<Block> = listOf(
        Block.Signature(SIGNATURE_LABEL, manager),
        Block.Note("Дата формирования: ${dateTime(createdAt, farm)}"),
    )

    // ---- Остаток поголовья ----

    fun herd(lines: List<HerdLine>, on: java.time.LocalDate, info: FarmInfo, createdAt: Instant, farm: FarmTime): Document {
        val blocks = mutableListOf<Block>(
            Block.Title("ОСТАТОК ПОГОЛОВЬЯ"), Block.BoldText(info.farmName), Block.Text("На дату: ${CoalText.date(on)}"),
        )
        val columns = listOf(Column("Категория", 50, Align.LEFT), Column("Голов", 20, Align.RIGHT))
        val pairs = mutableListOf<Pair>()
        for (species in Species.entries) {
            val rows = lines.filter { it.species == species }
            val total = rows.sumOf { it.heads }
            blocks += Block.Heading(species.title)
            blocks += Block.Table(
                Table(columns, rows.map { listOf(Cell.Text(it.name), Cell.Count(it.heads)) }, listOf(Cell.Text("Итого ${species.title}"), Cell.Count(total))),
            )
            pairs += Pair(species.title, Cell.Count(total), false)
        }
        pairs += Pair("Всего животных", Cell.Count(lines.sumOf { it.heads }), true)
        blocks += Block.Heading("Итого")
        blocks += Block.Pairs(pairs)
        blocks += footer(info.managerName, createdAt, farm)
        return Document(
            fileName = String.format(Locale.ROOT, "Поголовье_%04d-%02d-%02d", on.year, on.monthValue, on.dayOfMonth),
            runningHeader = "${info.farmName} · Остаток поголовья на ${CoalText.date(on)}",
            blocks = blocks,
        )
    }

    // ---- Забой ----

    fun slaughter(
        summary: List<SlaughterSummaryRow>, lines: List<SlaughterLine>, period: LivestockPeriod, start: Instant,
        info: FarmInfo, createdAt: Instant, farm: FarmTime,
    ): Document {
        val title = periodTitle(period, start, farm)
        val blocks = mutableListOf<Block>(Block.Title("ОТЧЁТ ПО ЗАБОЮ"), Block.BoldText(info.farmName), Block.Text("Период: $title"))
        fun weight(g: Long): Cell = if (g == 0L) Cell.Text("—") else Cell.Tons(g)
        val columns = listOf(
            Column("Вид", 10, Align.LEFT), Column("Категория", 22, Align.LEFT), Column("Голов", 10, Align.RIGHT),
            Column("Живой вес, кг", 16, Align.RIGHT), Column("После забоя, кг", 16, Align.RIGHT), Column("Мясо, кг", 14, Align.RIGHT),
        )
        blocks += Block.Heading("По категориям")
        if (summary.isEmpty()) {
            blocks += Block.Text("Забоя за период не было.")
        } else {
            blocks += Block.Table(
                Table(
                    columns,
                    summary.map {
                        listOf(Cell.Text(it.species.title), Cell.Text(it.name), Cell.Count(it.heads), weight(it.liveGrams), weight(it.carcassGrams), weight(it.meatGrams))
                    },
                    listOf(
                        Cell.Text("Итого"), Cell.Text(""), Cell.Count(summary.sumOf { it.heads }), weight(summary.sumOf { it.liveGrams }),
                        weight(summary.sumOf { it.carcassGrams }), weight(summary.sumOf { it.meatGrams }),
                    ),
                ),
            )
            blocks += Block.Pairs(
                Species.entries.map { s ->
                    Pair("${s.title}: забито голов", Cell.Count(summary.filter { it.species == s }.sumOf { it.heads }), false)
                },
            )
            blocks += Block.Note("Веса суммируются только по записям, где они указаны.")
        }
        if (lines.isNotEmpty()) {
            blocks += Block.Heading("Записи забоя")
            blocks += Block.Table(
                Table(
                    listOf(
                        Column("Дата", 12, Align.CENTER), Column("Вид", 8, Align.LEFT), Column("Категория", 16, Align.LEFT),
                        Column("Голов", 8, Align.RIGHT), Column("Причина", 16, Align.LEFT), Column("Живой, кг", 11, Align.RIGHT),
                        Column("Мясо, кг", 11, Align.RIGHT), Column("Комментарий", 18, Align.LEFT),
                    ),
                    lines.map { l ->
                        listOf(
                            Cell.Text(CoalText.date(farm.dayOf(l.date))), Cell.Text(l.species.title), Cell.Text(l.category),
                            Cell.Count(l.heads), Cell.Text(l.reason), l.liveGrams?.let { Cell.Tons(it) } ?: Cell.Text("—"),
                            l.meatGrams?.let { Cell.Tons(it) } ?: Cell.Text("—"), Cell.Text(l.comment),
                        )
                    },
                    null,
                ),
            )
        }
        blocks += footer(info.managerName, createdAt, farm)
        return Document("Забой_${fileStamp(period, start, farm)}", "${info.farmName} · Отчёт по забою $title", blocks)
    }

    // ---- Движение поголовья ----

    fun movement(
        rows: List<HerdMovementRow>, period: LivestockPeriod, start: Instant, info: FarmInfo, createdAt: Instant, farm: FarmTime,
    ): Document {
        val title = periodTitle(period, start, farm)
        val blocks = mutableListOf<Block>(Block.Title("ДВИЖЕНИЕ ПОГОЛОВЬЯ"), Block.BoldText(info.farmName), Block.Text("Период: $title"))
        val columns = listOf(
            Column("Категория", 20, Align.LEFT), Column("На начало", 9, Align.RIGHT), Column("Поступ-ло", 9, Align.RIGHT),
            Column("Приплод", 9, Align.RIGHT), Column("Перевод +", 9, Align.RIGHT), Column("Перевод −", 9, Align.RIGHT),
            Column("Забой", 8, Align.RIGHT), Column("Продажа", 9, Align.RIGHT), Column("Падёж", 8, Align.RIGHT),
            Column("Прочее", 8, Align.RIGHT), Column("На конец", 9, Align.RIGHT),
        )
        fun cells(r: HerdMovementRow, name: String): List<Cell> = listOf(
            Cell.Text(name), Cell.Count(r.start), Cell.Count(r.initial + r.purchase), Cell.Count(r.birth), Cell.Count(r.transferIn),
            Cell.Count(r.transferOut), Cell.Count(r.slaughter), Cell.Count(r.sale), Cell.Count(r.death), Cell.Count(r.other),
            Cell.Count(r.end),
        )
        var all: HerdMovementRow? = null
        for (species in Species.entries) {
            val speciesRows = rows.filter { it.species == species }
            val first = speciesRows.firstOrNull() ?: continue
            val total = speciesRows.drop(1).fold(first) { a, b -> a + b }
            all = all?.let { it + total } ?: total
            blocks += Block.Heading(species.title)
            blocks += Block.Table(Table(columns, speciesRows.map { cells(it, it.name) }, cells(total, "Итого ${species.title}")))
        }
        val sum = all
        if (sum != null) {
            blocks += Block.Heading("Всё хозяйство")
            blocks += Block.Pairs(
                listOf(
                    Pair("Остаток на начало периода", Cell.Count(sum.start), true),
                    Pair("Поступление (и начальное поголовье)", Cell.Count(sum.initial + sum.purchase), false),
                    Pair("Приплод", Cell.Count(sum.birth), false),
                    Pair("Забой", Cell.Count(sum.slaughter), false),
                    Pair("Продажи", Cell.Count(sum.sale), false),
                    Pair("Падёж", Cell.Count(sum.death), false),
                    Pair("Прочее списание", Cell.Count(sum.other), false),
                    Pair("Остаток на конец периода", Cell.Count(sum.end), true),
                ),
            )
            blocks += Block.Note("Переводы между категориями не меняют общее поголовье.")
        } else {
            blocks += Block.Text("Животных и операций за период нет.")
        }
        blocks += footer(info.managerName, createdAt, farm)
        return Document("Движение_поголовья_${fileStamp(period, start, farm)}", "${info.farmName} · Движение поголовья $title", blocks)
    }
}
