package ru.kfh.tabel.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.report.ReportAlign
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.roles.Module
import ru.kfh.core.roles.Role as FarmRole
import ru.kfh.core.roles.modules
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.tabel.ui.coal.CoalTheme
import ru.kfh.tabel.ui.coal.DayPickerRow
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ModuleButton
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.MonthYearDialog
import ru.kfh.tabel.ui.timesheet.segmentedColors

/** «Отчёты» (`ReportsView`): the monthly timesheet, coal sales and payments to drivers, livestock — each as PDF and Excel ready to print. */
@Composable
fun ReportsScreen(
    role: FarmRole,
    onBack: () -> Unit,
    openTimesheet: () -> Unit,
    openCoal: () -> Unit,
    openPayments: () -> Unit,
    openLivestock: () -> Unit,
) {
    val modules = role.modules()
    TitledScreen("Отчёты", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Only the reports of the parts the role sees.
            if (Module.timesheet in modules) {
                ModuleButton("Табель работников", "Табель за месяц по всему хозяйству или по сотруднику", Icons.Filled.Person, Tabel.colors.primary, "report-timesheet", openTimesheet)
            }
            if (Module.coal in modules) {
                ModuleButton("Продажи угля", "За день или за месяц: продажи, выручка, итоги", Icons.Filled.ShoppingCart, Tabel.colors.accent, "report-coal", openCoal)
                ModuleButton("Выплаты водителям", "Сколько заработал, получил и кому сколько ещё выплатить", Icons.Filled.List, CoalTheme.hired(), "report-payments", openPayments)
            }
            if (Module.livestock in modules) {
                ModuleButton("Скот и забой", "Остаток поголовья, забой, движение поголовья", Icons.Filled.Favorite, Tabel.colors.livestock, "report-livestock", openLivestock)
            }
        }
    }
}

/** Day or month of a report. */
enum class ReportPeriodKind { DAY, MONTH }

/** Day or month, chosen with a date or a month picker (`ReportPeriodPicker`). */
@Composable
fun ReportPeriodPicker(
    kind: ReportPeriodKind, day: LocalDate, month: YearMonth, currentYear: Int,
    onKind: (ReportPeriodKind) -> Unit, onDay: (LocalDate) -> Unit, onMonth: (YearMonth) -> Unit,
) {
    var pickingMonth by remember { mutableStateOf(false) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                for ((index, k) in ReportPeriodKind.entries.withIndex()) {
                    SegmentedButton(
                        selected = kind == k, onClick = { onKind(k) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReportPeriodKind.entries.size),
                        colors = segmentedColors(), modifier = Modifier.testTag("period-${k.name}"),
                    ) { Text(if (k == ReportPeriodKind.DAY) "За день" else "За месяц") }
                }
            }
            if (kind == ReportPeriodKind.DAY) {
                DayPickerRow("День", day, onDay)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pickingMonth = true }.testTag("pickMonth").padding(vertical = 6.dp),
                ) {
                    Text("Месяц", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(RuMonths.title(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tabel.colors.primary,
                         modifier = Modifier.testTag("pickedMonth"))
                }
            }
        }
    }
    if (pickingMonth) {
        MonthYearDialog(month, currentYear, onDismiss = { pickingMonth = false }) {
            onMonth(it)
            pickingMonth = false
        }
    }
}

/** The report's blocks on screen, simply: headings, figures and rows (`ReportPreview`). */
@Composable
fun ReportPreview(document: ReportDocument) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("reportPreview")) {
        for (block in document.blocks) {
            when (block) {
                is ReportBlock.Title -> Text(block.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                is ReportBlock.BoldText -> Text(block.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                is ReportBlock.Text -> Text(block.text)
                is ReportBlock.Note -> Text(block.text, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                is ReportBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                               color = Tabel.colors.primary, modifier = Modifier.padding(top = 6.dp))
                is ReportBlock.Pairs -> for (p in block.pairs) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal,
                             modifier = Modifier.weight(1f))
                        Text(p.value.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                             fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                is ReportBlock.Table -> {
                    val t = block.table
                    Column(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (c in t.columns) {
                                Text(c.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Tabel.colors.secondaryText,
                                     modifier = Modifier.widthIn(min = 64.dp))
                            }
                        }
                        val rows = t.rows + (t.total?.let { listOf(it) } ?: emptyList())
                        for ((i, row) in rows.withIndex()) {
                            val isTotal = t.total != null && i == rows.size - 1
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                for ((j, cell) in row.withIndex()) {
                                    val align = when (t.columns.getOrNull(j)?.align) {
                                        ReportAlign.RIGHT -> TextAlign.End
                                        ReportAlign.CENTER -> TextAlign.Center
                                        else -> TextAlign.Start
                                    }
                                    Text(cell.text, style = MaterialTheme.typography.bodySmall, textAlign = align,
                                         fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.widthIn(min = 64.dp))
                                }
                            }
                        }
                    }
                }
                is ReportBlock.Signature -> Text(block.label + (if (block.name.isEmpty()) "" else "  ${block.name}"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
