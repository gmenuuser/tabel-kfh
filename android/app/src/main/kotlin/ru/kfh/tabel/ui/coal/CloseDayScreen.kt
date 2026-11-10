package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.home.longDate
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import java.time.LocalDate

/** «Завершить рабочий день» (`CloseDayView`): the day's figures and one button that closes the day. Nothing is paid to anyone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseDayScreen(vm: CoalViewModel, day: LocalDate, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val report by remember(day) { vm.dayReport(day) }.collectAsState(initial = null)
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Итоги рабочего дня", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onDone, modifier = Modifier.testTag("cancel")) { Text("Отмена", color = Tabel.colors.primary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
    ) { padding ->
        val r = report ?: return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(longDate(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Всего продано угля", modifier = Modifier.weight(1f))
                        Text(CoalMath.tonsText(r.totals.kilograms), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                             modifier = Modifier.testTag("closeTons"))
                    }
                    CoalMoneyRow("Общая выручка", r.totals.revenue, bold = true, color = Tabel.colors.primary, tag = "closeRevenue")
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (r.drivers.isEmpty()) Text("Наёмным водителям платить нечего.", color = Tabel.colors.secondaryText)
                    for (d in r.drivers) CoalMoneyRow("${d.name}: к выплате", d.debtAfter, color = CoalTheme.hired())
                    HorizontalDivider()
                    CoalMoneyRow("Всего выплатить наёмным водителям", r.totalDebtAfter, bold = true, color = CoalTheme.hired())
                    CoalMoneyRow("Доход от собственной машины", r.totals.ownVehicleDeliveryRevenue, color = CoalTheme.own())
                    HorizontalDivider()
                    CoalMoneyRow("Остаток после выплат водителям", r.totals.remainderAfterDrivers, bold = true)
                    Text("Остаток — это ещё не прибыль: стоимость угля и другие расходы здесь не вычтены.",
                         style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                }
            }
            Text("Деньги водителям этим не выплачиваются: долг останется, пока вы не отметите выплату.",
                 style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            BigButton(
                "ПОДТВЕРДИТЬ ЗАВЕРШЕНИЕ ДНЯ",
                onClick = {
                    if (busy) return@BigButton
                    busy = true
                    scope.launch {
                        val result = vm.closeDay(day)
                        busy = false
                        if (result == null) onDone() else message = result
                    }
                },
                enabled = vm.canCloseDay, busy = busy, modifier = Modifier.testTag("confirmClose"),
            )
        }
    }
    ErrorDialog(message) { message = null }
}
