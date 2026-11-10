package ru.kfh.core.livestock

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.coal.CoalText
import ru.kfh.core.report.ReportAlign
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportColumn
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.report.ReportPair
import ru.kfh.core.report.ReportTable
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.RuMonths

/**
 * The livestock reports as printable documents (port of `LivestockReportLayout.swift`): the same blocks as the coal
 * reports ([ReportDocument]); the PDF / XLSX are rendered from them.
 */
object LivestockReport {
    const val SIGNATURE_LABEL = CoalReportLayout.SIGNATURE_LABEL

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

    private fun footer(manager: String, createdAt: Instant, farm: FarmTime): List<ReportBlock> = listOf(
        ReportBlock.Signature(SIGNATURE_LABEL, manager),
        ReportBlock.Note("Дата формирования: ${CoalText.dateTime(createdAt, farm.zone)}"),
    )

    /** Grams shown as kilograms («120,5»), a number in the spreadsheet. */
    private fun kilograms(grams: Long): ReportCell = ReportCell.tons(grams)

    // ---- Остаток поголовья ----

    fun herd(lines: List<HerdLine>, on: LocalDate, info: FarmInfo, createdAt: Instant, farm: FarmTime): ReportDocument {
        val blocks = mutableListOf<ReportBlock>(
            ReportBlock.Title("ОСТАТОК ПОГОЛОВЬЯ"), ReportBlock.BoldText(info.farmName), ReportBlock.Text("На дату: ${CoalText.date(on)}"),
        )
        val columns = listOf(ReportColumn("Категория", 50.0, ReportAlign.LEFT), ReportColumn("Голов", 20.0, ReportAlign.RIGHT))
        val pairs = mutableListOf<ReportPair>()
        for (species in Species.entries) {
            val rows = lines.filter { it.species == species }
            val total = rows.sumOf { it.heads }
            blocks += ReportBlock.Heading(species.title)
            blocks += ReportBlock.Table(
                ReportTable(
                    columns, rows.map { listOf(ReportCell.text(it.name), ReportCell.count(it.heads)) },
                    listOf(ReportCell.text("Итого ${species.title}"), ReportCell.count(total)),
                ),
            )
            pairs += ReportPair(species.title, ReportCell.count(total), false)
        }
        pairs += ReportPair("Всего животных", ReportCell.count(lines.sumOf { it.heads }), true)
        blocks += ReportBlock.Heading("Итого")
        blocks += ReportBlock.Pairs(pairs)
        blocks += footer(info.managerName, createdAt, farm)
        return ReportDocument(
            fileName = String.format(Locale.ROOT, "Поголовье_%04d-%02d-%02d", on.year, on.monthValue, on.dayOfMonth),
            runningHeader = "${info.farmName} · Остаток поголовья на ${CoalText.date(on)}",
            blocks = blocks,
        )
    }

    // ---- Забой ----

    fun slaughter(
        summary: List<SlaughterSummaryRow>, lines: List<SlaughterLine>, period: LivestockPeriod, start: Instant,
        info: FarmInfo, createdAt: Instant, farm: FarmTime,
    ): ReportDocument {
        val title = periodTitle(period, start, farm)
        val blocks = mutableListOf<ReportBlock>(ReportBlock.Title("ОТЧЁТ ПО ЗАБОЮ"), ReportBlock.BoldText(info.farmName), ReportBlock.Text("Период: $title"))
        fun weight(g: Long): ReportCell = if (g == 0L) ReportCell.text("—") else kilograms(g)
        val columns = listOf(
            ReportColumn("Вид", 10.0, ReportAlign.LEFT), ReportColumn("Категория", 22.0, ReportAlign.LEFT), ReportColumn("Голов", 10.0, ReportAlign.RIGHT),
            ReportColumn("Живой вес, кг", 16.0, ReportAlign.RIGHT), ReportColumn("После забоя, кг", 16.0, ReportAlign.RIGHT),
            ReportColumn("Мясо, кг", 14.0, ReportAlign.RIGHT),
        )
        blocks += ReportBlock.Heading("По категориям")
        if (summary.isEmpty()) {
            blocks += ReportBlock.Text("Забоя за период не было.")
        } else {
            blocks += ReportBlock.Table(
                ReportTable(
                    columns,
                    summary.map {
                        listOf(
                            ReportCell.text(it.species.title), ReportCell.text(it.name), ReportCell.count(it.heads), weight(it.liveGrams),
                            weight(it.carcassGrams), weight(it.meatGrams),
                        )
                    },
                    listOf(
                        ReportCell.text("Итого"), ReportCell.text(""), ReportCell.count(summary.sumOf { it.heads }), weight(summary.sumOf { it.liveGrams }),
                        weight(summary.sumOf { it.carcassGrams }), weight(summary.sumOf { it.meatGrams }),
                    ),
                ),
            )
            blocks += ReportBlock.Pairs(
                Species.entries.map { s ->
                    ReportPair("${s.title}: забито голов", ReportCell.count(summary.filter { it.species == s }.sumOf { it.heads }), false)
                },
            )
            blocks += ReportBlock.Note("Веса суммируются только по записям, где они указаны.")
        }
        if (lines.isNotEmpty()) {
            blocks += ReportBlock.Heading("Записи забоя")
            blocks += ReportBlock.Table(
                ReportTable(
                    listOf(
                        ReportColumn("Дата", 12.0, ReportAlign.CENTER), ReportColumn("Вид", 8.0, ReportAlign.LEFT),
                        ReportColumn("Категория", 16.0, ReportAlign.LEFT), ReportColumn("Голов", 8.0, ReportAlign.RIGHT),
                        ReportColumn("Причина", 16.0, ReportAlign.LEFT), ReportColumn("Живой, кг", 11.0, ReportAlign.RIGHT),
                        ReportColumn("Мясо, кг", 11.0, ReportAlign.RIGHT), ReportColumn("Комментарий", 18.0, ReportAlign.LEFT),
                    ),
                    lines.map { l ->
                        listOf(
                            ReportCell.text(CoalText.date(farm.dayOf(l.date))), ReportCell.text(l.species.title), ReportCell.text(l.category),
                            ReportCell.count(l.heads), ReportCell.text(l.reason), l.liveGrams?.let { kilograms(it) } ?: ReportCell.text("—"),
                            l.meatGrams?.let { kilograms(it) } ?: ReportCell.text("—"), ReportCell.text(l.comment),
                        )
                    },
                    null,
                ),
            )
        }
        blocks += footer(info.managerName, createdAt, farm)
        return ReportDocument("Забой_${fileStamp(period, start, farm)}", "${info.farmName} · Отчёт по забою $title", blocks)
    }

    // ---- Движение поголовья ----

    fun movement(
        rows: List<HerdMovementRow>, period: LivestockPeriod, start: Instant, info: FarmInfo, createdAt: Instant, farm: FarmTime,
    ): ReportDocument {
        val title = periodTitle(period, start, farm)
        val blocks = mutableListOf<ReportBlock>(ReportBlock.Title("ДВИЖЕНИЕ ПОГОЛОВЬЯ"), ReportBlock.BoldText(info.farmName), ReportBlock.Text("Период: $title"))
        val columns = listOf(
            ReportColumn("Категория", 20.0, ReportAlign.LEFT), ReportColumn("На начало", 9.0, ReportAlign.RIGHT), ReportColumn("Поступ-ло", 9.0, ReportAlign.RIGHT),
            ReportColumn("Приплод", 9.0, ReportAlign.RIGHT), ReportColumn("Перевод +", 9.0, ReportAlign.RIGHT), ReportColumn("Перевод −", 9.0, ReportAlign.RIGHT),
            ReportColumn("Забой", 8.0, ReportAlign.RIGHT), ReportColumn("Продажа", 9.0, ReportAlign.RIGHT), ReportColumn("Падёж", 8.0, ReportAlign.RIGHT),
            ReportColumn("Прочее", 8.0, ReportAlign.RIGHT), ReportColumn("На конец", 9.0, ReportAlign.RIGHT),
        )
        fun cells(r: HerdMovementRow, name: String): List<ReportCell> = listOf(
            ReportCell.text(name), ReportCell.count(r.start), ReportCell.count(r.initial + r.purchase), ReportCell.count(r.birth), ReportCell.count(r.transferIn),
            ReportCell.count(r.transferOut), ReportCell.count(r.slaughter), ReportCell.count(r.sale), ReportCell.count(r.death), ReportCell.count(r.other),
            ReportCell.count(r.end),
        )
        var all: HerdMovementRow? = null
        for (species in Species.entries) {
            val speciesRows = rows.filter { it.species == species }
            val first = speciesRows.firstOrNull() ?: continue
            val total = speciesRows.drop(1).fold(first) { a, b -> a + b }
            all = all?.let { it + total } ?: total
            blocks += ReportBlock.Heading(species.title)
            blocks += ReportBlock.Table(ReportTable(columns, speciesRows.map { cells(it, it.name) }, cells(total, "Итого ${species.title}")))
        }
        val sum = all
        if (sum != null) {
            blocks += ReportBlock.Heading("Всё хозяйство")
            blocks += ReportBlock.Pairs(
                listOf(
                    ReportPair("Остаток на начало периода", ReportCell.count(sum.start), true),
                    ReportPair("Поступление (и начальное поголовье)", ReportCell.count(sum.initial + sum.purchase), false),
                    ReportPair("Приплод", ReportCell.count(sum.birth), false),
                    ReportPair("Забой", ReportCell.count(sum.slaughter), false),
                    ReportPair("Продажи", ReportCell.count(sum.sale), false),
                    ReportPair("Падёж", ReportCell.count(sum.death), false),
                    ReportPair("Прочее списание", ReportCell.count(sum.other), false),
                    ReportPair("Остаток на конец периода", ReportCell.count(sum.end), true),
                ),
            )
            blocks += ReportBlock.Note("Переводы между категориями не меняют общее поголовье.")
        } else {
            blocks += ReportBlock.Text("Животных и операций за период нет.")
        }
        blocks += footer(info.managerName, createdAt, farm)
        return ReportDocument("Движение_поголовья_${fileStamp(period, start, farm)}", "${info.farmName} · Движение поголовья $title", blocks)
    }
}
