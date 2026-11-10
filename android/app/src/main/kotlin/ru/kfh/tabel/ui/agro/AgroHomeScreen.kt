package ru.kfh.tabel.ui.agro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.agro.AgroKind
import ru.kfh.core.coal.CoalText
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.model.AgroCalculation
import ru.kfh.tabel.ui.coal.DayPickerRow
import ru.kfh.tabel.ui.coal.TwoColumns
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.livestock.ChipRow
import ru.kfh.tabel.ui.livestock.LivestockCaption
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote

/** A Material stand-in for the iPhone's SF symbol of a calculator (`AgroKind.icon`). */
fun agroIcon(kind: AgroKind): ImageVector = when (kind) {
    AgroKind.SEEDING -> Icons.Filled.Star
    AgroKind.FERTILIZER -> Icons.Filled.AddCircle
    AgroKind.SPRAYING -> Icons.Filled.Place
    AgroKind.YIELD -> Icons.AutoMirrored.Filled.List
    AgroKind.MOISTURE -> Icons.Filled.Info
    AgroKind.FUEL -> Icons.Filled.Build
    AgroKind.AREA -> Icons.Filled.DateRange
    AgroKind.TRANSPORT -> Icons.AutoMirrored.Filled.Send
}

/** «Агрономические калькуляторы» (`AgroHomeView`): eight large buttons and «История расчётов (N)». */
@Composable
fun AgroHomeScreen(vm: AgroViewModel, onBack: () -> Unit, openCalculator: (AgroKind) -> Unit, openHistory: () -> Unit) {
    val saved by vm.calculations.collectAsState(initial = emptyList())
    TitledScreen("Агрономические калькуляторы", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val kinds = AgroKind.entries
            TwoColumns(kinds.size) { i -> KindTile(kinds[i], Modifier.weight(1f)) { openCalculator(kinds[i]) } }
            BigButton("История расчётов (${saved.size})", onClick = openHistory, color = Tabel.colors.accent, modifier = Modifier.testTag("openAgroHistory"))
        }
    }
}

@Composable
private fun KindTile(kind: AgroKind, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tabel.colors.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = kind.title }
            .testTag("agro-${kind.wire}")
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(agroIcon(kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(kind.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3)
    }
}

private val PERIODS: List<LivestockPeriod?> = listOf(null, LivestockPeriod.DAY, LivestockPeriod.MONTH, LivestockPeriod.YEAR)

private fun periodTitle(p: LivestockPeriod?): String = when (p) {
    null -> "Всё время"
    LivestockPeriod.DAY -> "День"
    LivestockPeriod.MONTH -> "Месяц"
    LivestockPeriod.YEAR -> "Год"
}

/**
 * «История расчётов» (`AgroHistoryView`): search, the calculator and the period; a tap opens the calculation for
 * correction; «Удалить расчёт?» from the row's menu (only for a role that may write). The PDF / Excel of the list
 * come with the reports module (Task 15).
 */
@Composable
fun AgroHistoryScreen(vm: AgroViewModel, onBack: () -> Unit, open: (AgroCalculation) -> Unit) {
    val scope = rememberCoroutineScope()
    val all by vm.calculations.collectAsState(initial = emptyList())
    var search by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<AgroKind?>(null) }
    var period by rememberSaveable { mutableStateOf<LivestockPeriod?>(null) }
    var day by remember { mutableStateOf(vm.farm.today()) }
    var deleting by remember { mutableStateOf<AgroCalculation?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val zone = vm.farm.zone
    val canEdit = vm.canSave
    val shown = vm.history(all, kind, search, period, day)

    TitledScreen("История расчётов", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true, placeholder = { Text("Поиск по полю или комментарию") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("agroSearch"),
            )
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LivestockCaption("Калькулятор")
                    ChipRow(listOf<AgroKind?>(null) + AgroKind.entries, kind, { it?.title ?: "Все" }, { "filterKind-${it?.wire ?: "all"}" }) { kind = it }
                    LivestockCaption("Период")
                    ChipRow(PERIODS, period, ::periodTitle, { "filterPeriod-${it?.name?.lowercase() ?: "all"}" }) { period = it }
                    if (period != null) DayPickerRow("Дата", day) { day = it }
                }
            }
            Text("Расчёты (${shown.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("agroCount"))
            if (!canEdit) ReadOnlyNote()
            if (shown.isEmpty()) Text("Сохранённых расчётов нет", color = Tabel.colors.secondaryText, modifier = Modifier.testTag("noCalculations"))
            for (c in shown) {
                CalculationRow(c, zone, canEdit, onClick = { open(c) }, onDelete = { deleting = c })
            }
        }
    }

    deleting?.let { c ->
        ConfirmDialog(
            title = "Удалить расчёт?",
            message = "${AgroKind.fromWire(c.kind).title}, ${CoalText.dateTime(c.createdAt, zone)}",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                scope.launch { message = vm.delete(c) }
            },
            onDismiss = { deleting = null },
        )
    }
    ErrorDialog(message) { message = null }
}

/** The calculator, the time, the title and the main line of a saved calculation (`AgroHistoryView`'s row). */
@Composable
private fun CalculationRow(c: AgroCalculation, zone: java.time.ZoneId, canDelete: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val kind = AgroKind.fromWire(c.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tabel.colors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("calc-${c.id}")
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(CoalText.dateTime(c.createdAt, zone), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            }
            if (c.title.isNotEmpty()) Text(c.title, style = MaterialTheme.typography.bodyMedium)
            c.lines.firstOrNull { it.main }?.let {
                Text("${it.title}: ${it.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Tabel.colors.primary,
                     modifier = Modifier.testTag("calcMain-${c.id}"))
            }
        }
        if (canDelete) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("calcMenu-${c.id}")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = Tabel.colors.secondaryText)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Удалить", color = Tabel.colors.error) }, onClick = { menu = false; onDelete() },
                                     modifier = Modifier.testTag("deleteCalc-${c.id}"))
                }
            }
        } else {
            Spacer(Modifier.size(10.dp))
        }
    }
}
