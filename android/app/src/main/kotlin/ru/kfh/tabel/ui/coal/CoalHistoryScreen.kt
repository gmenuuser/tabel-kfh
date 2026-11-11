package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.tabel.ui.reports.ReportExportActions
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.CoalTotals
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.MonthYearDialog
import ru.kfh.tabel.ui.timesheet.RU
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote
import ru.kfh.tabel.ui.timesheet.segmentedColors
import java.time.LocalDate
import java.time.YearMonth

private enum class Mode(val title: String) { SALES("Продажи"), DAYS("Дни"), PAYMENTS("Выплаты") }
private enum class Period(val title: String) { DAY("День"), MONTH("Месяц") }
private enum class VehicleFilter(val title: String) { ALL("Все"), HIRED("Наёмные"), OWN("Своя") }

/**
 * «История продаж» (`CoalHistoryView`): sales of any day or month with search and the truck filter, the month's
 * report, closed days, payments to drivers.
 */
@Composable
fun CoalHistoryScreen(vm: CoalViewModel, onBack: () -> Unit, openSummary: (LocalDate) -> Unit, openSale: (String) -> Unit) {
    val books by vm.books.collectAsState(initial = null)
    val closedDays by vm.closedDays.collectAsState(initial = emptyList())
    var mode by remember { mutableStateOf(Mode.SALES) }
    var period by remember { mutableStateOf(Period.DAY) }
    var day by remember { mutableStateOf(vm.today()) }
    var month by remember { mutableStateOf(YearMonth.from(vm.today())) }
    var pickingMonth by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf(VehicleFilter.ALL) }
    val zone = vm.farm.zone

    TitledScreen("История продаж", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Segmented(Mode.entries, mode, { it.title }, "mode") { mode = it }
            val b = books ?: return@TitledScreen
            when (mode) {
                Mode.SALES -> {
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Segmented(Period.entries, period, { it.title }, "period") { period = it }
                            if (period == Period.DAY) {
                                DayPickerRow("День", day) { day = it }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pickingMonth = true }.testTag("pickMonth").padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Месяц", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(RuMonths.title(month), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                                              placeholder = { Text("Поиск: водитель или населённый пункт") }, modifier = Modifier.fillMaxWidth().testTag("historySearch"))
                            Segmented(VehicleFilter.entries, vehicle, { it.title }, "vehicle") { vehicle = it }
                        }
                    }
                    val range = if (period == Period.DAY) b.sales(day) else b.sales(month.atDay(1), month.plusMonths(1).atDay(1))
                    val q = search.trim().lowercase(RU)
                    val sales = range.filter { s ->
                        (when (vehicle) {
                            VehicleFilter.ALL -> true
                            VehicleFilter.HIRED -> s.vehicleType == VehicleType.HIRED
                            VehicleFilter.OWN -> s.vehicleType == VehicleType.OWN
                        }) && (q.isEmpty() || s.driverNameSnapshot.lowercase(RU).contains(q) || s.locationNameSnapshot.lowercase(RU).contains(q))
                    }.asReversed()
                    SectionHeader(if (search.isEmpty() && vehicle == VehicleFilter.ALL) "Итоги" else "Итоги по отбору")
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CoalTotalsHeader(CoalTotals.of(sales))
                            if (period == Period.DAY) {
                                Text("Итоги этого дня", color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold,
                                     modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { openSummary(day) }.testTag("openDaySummary").padding(vertical = 6.dp))
                            }
                        }
                    }
                    SectionHeader("Продажи (${sales.size})")
                    if (!vm.canSell) ReadOnlyNote()
                    if (sales.isEmpty()) Text("Продаж нет", color = Tabel.colors.secondaryText)
                    for (s in sales) {
                        CoalSaleCard(s, zone, showsDate = period == Period.MONTH, onClick = { openSale(s.id) })
                    }
                    if (period == Period.MONTH) {
                        val report by remember(month) { vm.monthReport(month) }.collectAsState(initial = null)
                        report?.let {
                            SectionHeader("Отчёт за месяц")
                            ReportExportActions(CoalReportLayout.document(it, vm.farm))
                            CoalReportContent(it, zone, onPay = null)
                        }
                    }
                }
                Mode.DAYS -> {
                    SectionHeader("Завершённые рабочие дни")
                    if (closedDays.isEmpty()) Text("Завершённых дней пока нет", color = Tabel.colors.secondaryText)
                    for (d in closedDays) {
                        val wdDay = vm.farm.dayOf(d.date)
                        Card(modifier = Modifier.clickable(role = Role.Button) { openSummary(wdDay) }.testTag("closedDay-$wdDay")) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row {
                                    Text(CoalText.date(wdDay), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(CoalMath.rubles(d.totalRevenue), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Text("${CoalMath.tonsText(d.totalKilograms)} · водителям ${CoalMath.rubles(d.totalDriverPayments)}" +
                                         " · своя машина ${CoalMath.rubles(d.ownVehicleDeliveryRevenue)}",
                                     style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                                d.closedAt?.let { Text("Закрыт ${CoalText.dateTime(it, zone)}", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText) }
                            }
                        }
                    }
                }
                Mode.PAYMENTS -> {
                    SectionHeader("Выплаты водителям")
                    val payments = b.payments.sortedByDescending { it.paymentDate }
                    if (payments.isEmpty()) Text("Выплат пока не было", color = Tabel.colors.secondaryText)
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in payments) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(p.driverNameSnapshot, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(CoalText.dateTime(p.paymentDate, zone) + if (p.comment.isEmpty()) "" else " · ${p.comment}",
                                             style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                                    }
                                    Text(CoalMath.rubles(p.amount))
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingMonth) {
        MonthYearDialog(month, vm.today().year, onDismiss = { pickingMonth = false }) { month = it; pickingMonth = false }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, title: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        for ((i, o) in options.withIndex()) {
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            colors = segmentedColors(), modifier = Modifier.testTag("$tag-$o")) { Text(title(o)) }
        }
    }
}
