package ru.kfh.tabel.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import ru.kfh.core.livestock.LivestockBooks
import ru.kfh.core.livestock.LivestockLedger
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.livestock.LivestockReport
import ru.kfh.core.livestock.LivestockRules
import ru.kfh.core.livestock.SlaughterReason
import ru.kfh.core.livestock.Species
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.tabel.ui.coal.DayPickerRow
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.livestock.LivestockViewModel
import ru.kfh.tabel.ui.timesheet.segmentedColors

/** Which livestock report. */
enum class LivestockReportKind(val title: String) { HERD("Остаток"), SLAUGHTER("Забой"), MOVEMENT("Движение") }

/** The chosen livestock report as blocks (`LivestockReportsView.document`). */
fun livestockDocument(
    kind: LivestockReportKind, period: LivestockPeriod, day: LocalDate, books: LivestockBooks, info: FarmInfo, now: Instant, farm: FarmTime,
): ReportDocument {
    val categories = LivestockRules.categories(books)
    val byId = categories.associateBy { it.id }
    return when (kind) {
        LivestockReportKind.HERD -> {
            val end = farm.startOf(day.plusDays(1))
            val balances = LivestockLedger.balances(LivestockRules.moves(books), before = end)
            val lines = categories.map { LivestockReport.HerdLine(Species.fromWire(it.species), it.name, balances[it.id] ?: 0) }
            LivestockReport.herd(lines, day, info, now, farm)
        }
        LivestockReportKind.SLAUGHTER -> {
            val p = LivestockRules.period(period, farm.startOf(day), farm)
            val summary = LivestockRules.slaughterSummary(books, p.start, p.end)
            val details = books.slaughters.associateBy { it.operationId }
            val lines = books.operations
                .filter { it.kind == LivestockOperationKind.SLAUGHTER.wire && it.date >= p.start && it.date < p.end }
                .sortedBy { it.date }
                .map { o ->
                    val s = details[o.id]
                    LivestockReport.SlaughterLine(
                        date = o.date, species = byId[o.categoryId]?.let { Species.fromWire(it.species) } ?: Species.CATTLE,
                        category = byId[o.categoryId]?.name ?: "?", heads = o.heads,
                        reason = s?.let { SlaughterReason.fromWire(it.reason).title } ?: "", liveGrams = s?.liveWeightGrams,
                        meatGrams = s?.meatWeightGrams, comment = o.comment,
                    )
                }
            LivestockReport.slaughter(summary, lines, period, p.start, info, now, farm)
        }
        LivestockReportKind.MOVEMENT -> {
            val p = LivestockRules.period(period, farm.startOf(day), farm)
            LivestockReport.movement(LivestockRules.movement(books, p.start, p.end), period, p.start, info, now, farm)
        }
    }
}

/** «Отчёты по скоту» (`LivestockReportsView`): Остаток / Забой / Движение, the period and the date, the export buttons and a preview. */
@Composable
fun LivestockReportsScreen(vm: LivestockViewModel, onBack: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(LivestockReportKind.HERD) }
    var period by rememberSaveable { mutableStateOf(LivestockPeriod.MONTH) }
    var day by remember { mutableStateOf(vm.farm.today()) }
    val books by vm.books.collectAsState(initial = null)
    val info by vm.farmInfo.collectAsState(initial = FarmInfo.DEFAULT)
    val farm = vm.farm
    val document = books?.let { b -> remember(kind, period, day, b, info) { livestockDocument(kind, period, day, b, info, vm.now(), farm) } }

    TitledScreen("Отчёты по скоту", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        for ((index, k) in LivestockReportKind.entries.withIndex()) {
                            SegmentedButton(
                                selected = kind == k, onClick = { kind = k },
                                shape = SegmentedButtonDefaults.itemShape(index, LivestockReportKind.entries.size),
                                colors = segmentedColors(), modifier = Modifier.testTag("livestockReport-${k.name}"),
                            ) { Text(k.title) }
                        }
                    }
                    if (kind != LivestockReportKind.HERD) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            for ((index, p) in LivestockPeriod.entries.withIndex()) {
                                SegmentedButton(
                                    selected = period == p, onClick = { period = p },
                                    shape = SegmentedButtonDefaults.itemShape(index, LivestockPeriod.entries.size),
                                    colors = segmentedColors(), modifier = Modifier.testTag("livestockPeriod-${p.name}"),
                                ) { Text(when (p) { LivestockPeriod.DAY -> "День"; LivestockPeriod.MONTH -> "Месяц"; LivestockPeriod.YEAR -> "Год" }) }
                            }
                        }
                    }
                    DayPickerRow(if (kind == LivestockReportKind.HERD) "На дату" else "Дата в периоде", day) { day = it }
                }
            }
            document?.let {
                ReportExportActions(it)
                ReportPreview(it)
            }
        }
    }
}
