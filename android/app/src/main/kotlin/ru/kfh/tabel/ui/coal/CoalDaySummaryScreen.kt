package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalText
import ru.kfh.core.model.Driver
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import java.time.LocalDate

/**
 * «Итоги рабочего дня» (`CoalDaySummaryView`): the table of the day's sales, the totals А–Е, «Взаиморасчёты с
 * водителями» with «Выплатить водителю», and closing or reopening the day. Any day can be chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun CoalDaySummaryScreen(vm: CoalViewModel, initialDay: LocalDate, onBack: () -> Unit, closeDay: (LocalDate) -> Unit) {
    val scope = rememberCoroutineScope()
    val dayFlow = remember(initialDay) { MutableStateFlow(initialDay) }
    val day by dayFlow.collectAsState()
    val report by remember(vm, dayFlow) { dayFlow.flatMapLatest { vm.dayReport(it) } }.collectAsState(initial = null)
    val drivers by vm.drivers.collectAsState(initial = emptyList())
    val balances by vm.balances.collectAsState(initial = emptyMap())
    var paying by remember { mutableStateOf<Driver?>(null) }
    var confirmingReopen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    TitledScreen("Итоги рабочего дня", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card { DayPickerRow("День", day) { dayFlow.value = it } }
            val r = report ?: return@TitledScreen
            // «Выплатить водителю» only for a role that may pay.
            val onPay: ((ru.kfh.core.coal.DriverSettlement) -> Unit)? =
                if (vm.canPay) { s -> paying = drivers.firstOrNull { it.id == s.driverId } } else null
            CoalReportContent(r, vm.farm.zone, onPay)
            val closedAt = r.closedAt
            if (closedAt != null) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tabel.colors.primary)
                            Text("День завершён ${CoalText.dateTime(closedAt, vm.farm.zone)}", color = Tabel.colors.primary,
                                 style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("dayClosedLine"))
                        }
                        if (vm.canCloseDay) {
                            BigButton("Открыть день для редактирования", onClick = { confirmingReopen = true }, color = Tabel.colors.accent,
                                      modifier = Modifier.testTag("reopenDay"))
                        }
                    }
                }
            } else if (vm.canCloseDay) {
                BigButton("ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ", onClick = { closeDay(day) }, color = Tabel.colors.accent, modifier = Modifier.testTag("closeDay"))
            }
        }
    }

    paying?.let { d ->
        DriverPaymentDialog(vm, d, balances[d.id]?.debt ?: 0L) { paying = null }
    }
    if (confirmingReopen) {
        ReopenDialog(
            title = "Открыть день для редактирования?",
            message = "Продажи дня снова можно будет менять. Отчёт и долги пересчитаются.",
            onConfirm = { confirmingReopen = false; scope.launch { message = vm.reopenDay(day) } },
            onDismiss = { confirmingReopen = false },
        )
    }
    ErrorDialog(message) { message = null }
}
