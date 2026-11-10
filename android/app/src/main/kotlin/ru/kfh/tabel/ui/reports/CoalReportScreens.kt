package ru.kfh.tabel.ui.reports

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalPeriodReport
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.DriverBalance
import ru.kfh.core.coal.fullName
import ru.kfh.core.model.Driver
import ru.kfh.core.timesheet.NameOrder
import ru.kfh.tabel.ui.coal.CoalReportContent
import ru.kfh.tabel.ui.coal.CoalTheme
import ru.kfh.tabel.ui.coal.CoalViewModel
import ru.kfh.tabel.ui.coal.DriverPaymentDialog
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel

/** The day or month report of the picker's state. */
@Composable
private fun periodReport(vm: CoalViewModel, kind: ReportPeriodKind, day: LocalDate, month: YearMonth): CoalPeriodReport? {
    val report by remember(kind, day, month) {
        if (kind == ReportPeriodKind.DAY) vm.dayReport(day) else vm.monthReport(month)
    }.collectAsState(initial = null)
    return report
}

/** «Продажи угля» (`CoalSalesReportView`): the day or month report on screen with the export buttons. */
@Composable
fun CoalSalesReportScreen(vm: CoalViewModel, onBack: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ReportPeriodKind.DAY) }
    var day by remember { mutableStateOf(vm.today()) }
    var month by remember { mutableStateOf(YearMonth.from(vm.today())) }
    val report = periodReport(vm, kind, day, month)

    TitledScreen("Продажи угля", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ReportPeriodPicker(kind, day, month, vm.today().year, { kind = it }, { day = it }, { month = it })
            report?.let {
                ReportExportActions(CoalReportLayout.document(it, vm.farm))
                CoalReportContent(it, vm.farm.zone, onPay = null)
            }
        }
    }
}

/** «Выплаты водителям» (`DriverPaymentsReportView`): who is owed how much now (with «Выплатить»), and the report of a day or a month. */
@Composable
fun DriverPaymentsReportScreen(vm: CoalViewModel, onBack: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ReportPeriodKind.MONTH) }
    var day by remember { mutableStateOf(vm.today()) }
    var month by remember { mutableStateOf(YearMonth.from(vm.today())) }
    var paying by remember { mutableStateOf<Driver?>(null) }
    val drivers by vm.drivers.collectAsState(initial = emptyList())
    val balances by vm.balances.collectAsState(initial = emptyMap())
    val report = periodReport(vm, kind, day, month)
    // Working drivers, and archived ones the farm still owes.
    val owed: List<Pair<Driver, DriverBalance>> = drivers.map { it to (balances[it.id] ?: DriverBalance()) }
        .filter { (d, b) -> b.debt != 0L || !d.isArchived }
        .sortedWith { a, b -> NameOrder.compare(a.first.fullName, b.first.fullName) }

    TitledScreen("Выплаты водителям", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Долг перед водителями сейчас", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (owed.isEmpty()) Text("Наёмных водителей пока нет.", color = Tabel.colors.secondaryText)
                    for ((driver, balance) in owed) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                Text(driver.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("заработал ${CoalMath.rubles(balance.earned)}, получил ${CoalMath.rubles(balance.paid)}",
                                     style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                            }
                            Text(CoalMath.rubles(balance.debt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                 fontFamily = FontFamily.Monospace, color = if (balance.debt > 0) CoalTheme.hired() else Tabel.colors.secondaryText,
                                 modifier = Modifier.testTag("debt-${driver.id}"))
                        }
                        if (balance.debt > 0 && vm.canPay) {
                            BigButton("Выплатить ${driver.lastName}", color = CoalTheme.hired(), modifier = Modifier.testTag("pay-${driver.id}"), onClick = { paying = driver })
                        }
                        HorizontalDivider()
                    }
                }
            }

            Text("Отчёт для печати", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ReportPeriodPicker(kind, day, month, vm.today().year, { kind = it }, { day = it }, { month = it })
            report?.let { r ->
                ReportExportActions(CoalReportLayout.paymentsDocument(r, vm.farm))
                if (r.payments.isNotEmpty()) {
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Выплаты за период", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            for (p in r.payments) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.driver)
                                        Text(CoalText.dateTime(p.date, vm.farm.zone) + (if (p.comment.isEmpty()) "" else " · ${p.comment}"),
                                             style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                                    }
                                    Text(CoalMath.rubles(p.amount), fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    paying?.let { driver ->
        DriverPaymentDialog(vm, driver, balances[driver.id]?.debt ?: 0L, onDismiss = { paying = null })
    }
}
