package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import ru.kfh.tabel.clear.ClearSection
import ru.kfh.tabel.clear.ClearViewModel
import ru.kfh.tabel.clear.PeriodMode
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val RU = Locale("ru", "RU")

/** «17.11.2026, вторник» — чтобы промах датой был виден до нажатия. */
private fun dayText(day: LocalDate): String =
    "${day.format(dayFormat)}, ${day.dayOfWeek.getDisplayName(TextStyle.FULL, RU)}"

/** «ноябрь 2026». */
private fun monthText(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL, RU) + " " + month.year

/**
 * «Очистка данных» (owner): стирает записи раздела за день или месяц. Месяц требует набрать «очистить»;
 * день — обычное подтверждение. Отменить очистку нельзя, поэтому всё, что можно, показано до нажатия.
 */
@Composable
fun ClearDataScreen(vm: ClearViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    var asking by remember { mutableStateOf(false) }

    TitledScreen(title = "Очистка данных", onBack = onBack) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Что чистим", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClearSection.values().forEach { section ->
                    FilterChip(
                        selected = s.section == section,
                        onClick = { vm.chooseSection(section) },
                        label = { Text(section.title) },
                        modifier = Modifier.testTag("clearSection-${section.name}"),
                    )
                }
            }

            Text("За какой период", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodMode.values().forEach { mode ->
                    FilterChip(
                        selected = s.mode == mode,
                        onClick = { vm.chooseMode(mode) },
                        label = { Text(mode.title) },
                        modifier = Modifier.testTag("clearMode-${mode.name}"),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (s.mode == PeriodMode.Day) vm.chooseDay(s.day.minusDays(1)) else vm.chooseMonth(s.month.minusMonths(1))
                    },
                    modifier = Modifier.testTag("clearPrev"),
                ) { Text("‹") }
                Text(
                    if (s.mode == PeriodMode.Day) dayText(s.day) else monthText(s.month),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("clearPeriod"),
                )
                OutlinedButton(
                    onClick = {
                        if (s.mode == PeriodMode.Day) vm.chooseDay(s.day.plusDays(1)) else vm.chooseMonth(s.month.plusMonths(1))
                    },
                    modifier = Modifier.testTag("clearNext"),
                ) { Text("›") }
            }

            Text(
                if (s.count > 0) "Будет удалено записей: ${s.count}" else "За этот период записей нет",
                color = if (s.count > 0) Tabel.colors.accent else Tabel.colors.secondaryText,
                modifier = Modifier.testTag("clearCount"),
            )

            if (s.mode == PeriodMode.Month && s.count > 0) {
                OutlinedTextField(
                    value = s.typed,
                    onValueChange = vm::type,
                    label = { Text("Наберите «${ClearViewModel.WORD}»") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("clearWord"),
                )
            }

            Button(
                onClick = { asking = true },
                enabled = s.ready,
                colors = ButtonDefaults.buttonColors(containerColor = Tabel.colors.error),
                modifier = Modifier.fillMaxWidth().testTag("clearGo"),
            ) { Text("Очистить ${s.mode.title.lowercase(RU)}") }

            s.note?.let {
                Text(it, color = Tabel.colors.secondaryText, modifier = Modifier.testTag("clearNote"))
            }
        }

        if (asking) {
            ConfirmDialog(
                title = "Очистить ${s.section.title}?",
                message = "Период: ${if (s.mode == PeriodMode.Day) dayText(s.day) else monthText(s.month)}. " +
                    "Записей: ${s.count}. Отменить это будет нельзя.",
                confirm = "Очистить",
                onConfirm = { asking = false; vm.clear() },
                onDismiss = { asking = false },
            )
        }
    }
}
