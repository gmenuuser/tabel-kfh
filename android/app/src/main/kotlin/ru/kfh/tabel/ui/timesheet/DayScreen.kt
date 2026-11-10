package ru.kfh.tabel.ui.timesheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.timesheet.DayStatus
import ru.kfh.core.timesheet.fullName
import ru.kfh.core.timesheet.initials
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.home.longDate
import ru.kfh.tabel.ui.theme.Tabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

/**
 * «Табель работников» (`HomeView`): the chosen day (Вчера / Сегодня / Завтра or any date) and everyone who works
 * then, each with the four buttons 0 ч, 4 ч, 8 ч, 12 ч, the large «Я» button and the menu of the other statuses.
 * No mark until one is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    vm: TimesheetViewModel,
    onBack: () -> Unit,
    openMonth: () -> Unit,
    openEmployees: () -> Unit,
    addEmployee: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val day by vm.day.collectAsState()
    val state by remember(day) { vm.dayState(day) }.collectAsState(initial = null)
    val today = state?.today ?: vm.today()
    var search by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditMarks

    fun run(block: suspend () -> String?) {
        scope.launch { message = block() }
    }

    TitledScreen(
        "Табель работников", onBack,
        actions = {
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = Tabel.colors.primary)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text("Табель за месяц") },
                        leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        onClick = { more = false; openMonth() },
                        modifier = Modifier.testTag("openMonth"),
                    )
                    DropdownMenuItem(
                        text = { Text("Сотрудники и история") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        onClick = { more = false; openEmployees() },
                        modifier = Modifier.testTag("openEmployees"),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateCard(day, today, onPick = { picking = true }) { vm.day.value = it }
            val s = state
            if (s != null) {
                if (s.rows.isEmpty()) {
                    Text(
                        "В этот день нет работающих сотрудников.", color = Tabel.colors.secondaryText,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                } else {
                    Summary(s, canEdit) { run { vm.markUnmarked(day) } }
                    if (s.rows.size > 5) {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it }, singleLine = true,
                            placeholder = { Text("Поиск по фамилии") }, modifier = Modifier.fillMaxWidth().testTag("search"),
                        )
                    }
                }
                if (!canEdit) ReadOnlyNote()
                val q = search.trim()
                val shown = if (q.isEmpty()) s.rows else s.rows.filter { it.employee.fullName.lowercase(RU).contains(q.lowercase(RU)) }
                for (row in shown) {
                    EmployeeDayRow(
                        row = row, statuses = s.statuses, canEdit = canEdit,
                        onHours = { h -> run { vm.tapHours(row.employee, day, h) } },
                        onToggle = { run { vm.togglePresence(row.employee, day) } },
                        onStatus = { st -> run { vm.setStatus(row.employee, day, st) } },
                    )
                }
            }
            if (vm.canEditEmployees) {
                BigButton("Добавить сотрудника", onClick = addEmployee, modifier = Modifier.padding(top = 4.dp).testTag("addEmployee"))
            }
        }
    }

    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.day.value = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { DatePicker(state = pickerState) }
    }

    ErrorDialog(message) { message = null }
}

/** The date, «Вчера / Сегодня / Завтра» and «Другая дата». */
@Composable
private fun DateCard(day: LocalDate, today: LocalDate, onPick: () -> Unit, choose: (LocalDate) -> Unit) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(longDate(day), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                 color = Tabel.colors.primary, modifier = Modifier.testTag("dayTitle"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayButton("Вчера", today.minusDays(1), day, choose)
                DayButton("Сегодня", today, day, choose)
                DayButton("Завтра", today.plusDays(1), day, choose)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onPick)
                    .testTag("otherDate").padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Другая дата", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(dateText(day), style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.primary,
                     fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RowScope.DayButton(title: String, target: LocalDate, day: LocalDate, choose: (LocalDate) -> Unit) {
    val selected = day == target
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Tabel.colors.primary else Tabel.colors.background)
            .clickable(role = Role.Button) { choose(target) }
            .semantics { this.selected = selected }
            .testTag("day-$title"),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (selected) Color.White else Tabel.colors.primary,
             style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** «Отмечено: N из M», «Часов: H» and «Всем неотмеченным — 8 ч». */
@Composable
private fun Summary(s: TimesheetViewModel.DayState, canEdit: Boolean, markAll: () -> Unit) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Отмечено: ${s.marked} из ${s.rows.size}", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("summary"))
                Text("Часов: ${s.hours}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                     color = Tabel.colors.primary)
            }
            if (s.unmarked > 0 && canEdit) {
                OutlinedButton(onClick = markAll, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("markAll")) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Всем неотмеченным — 8 ч")
                }
            }
        }
    }
}

/**
 * One person: photo, ФИО, the day's hours and status; the four hour buttons; the large «Я» button and «Статус»
 * with the other statuses (`EmployeeHoursRow` + `EmployeeMarkRow`). Tapping the selected hours again removes the mark.
 */
@Composable
private fun EmployeeDayRow(
    row: TimesheetViewModel.DayRow,
    statuses: List<DayStatus>,
    canEdit: Boolean,
    onHours: (Int) -> Unit,
    onToggle: () -> Unit,
    onStatus: (DayStatus?) -> Unit,
) {
    val e = row.employee
    val isPresent = row.status == DayStatus.PRESENT
    val canPresent = DayStatus.PRESENT in statuses
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.testTag("row-${e.lastName}")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                EmployeeAvatar(e.initials, row.photo, 44)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        row.hours?.let { "Отмечено: $it ч" } ?: "Не отмечен",
                        style = MaterialTheme.typography.bodyMedium,
                        color = row.hours?.let { HoursStyle.color(it) } ?: Tabel.colors.secondaryText,
                        modifier = Modifier.testTag("hoursText-${e.lastName}"),
                    )
                    StatusChip(row.status, Modifier.testTag("status-${e.lastName}"))
                }
            }
            HoursButtons(current = row.hours, enabled = canEdit, tagSuffix = e.lastName, onTap = onHours)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canPresent) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPresent) Tabel.colors.primary else StatusStyle.fill(DayStatus.PRESENT))
                            .clickable(enabled = canEdit, role = Role.Button, onClick = onToggle)
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (isPresent) "Снять явку ${e.fullName}" else "Явка ${e.fullName}"
                            }
                            .testTag("mark-${e.lastName}")
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null,
                             tint = if (isPresent) Color.White else Tabel.colors.primary, modifier = Modifier.size(20.dp))
                        Text(if (isPresent) "Я — был на работе" else "Отметить: Я",
                             color = if (isPresent) Color.White else Tabel.colors.primary,
                             style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(modifier = if (canPresent) Modifier.width(76.dp) else Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Tabel.colors.background)
                            .clickable(enabled = canEdit, role = Role.Button) { menu = true }
                            .semantics(mergeDescendants = true) { contentDescription = "Статус ${e.fullName}" }
                            .testTag("statusMenu-${e.lastName}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Tabel.colors.primary)
                        Text("Статус", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                             color = Tabel.colors.primary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (s in statuses) {
                            DropdownMenuItem(text = { Text("${s.code} — ${s.title}") }, onClick = { menu = false; onStatus(s) },
                                             modifier = Modifier.testTag("set-${s.code}-${e.lastName}"))
                        }
                        if (row.status != null) {
                            DropdownMenuItem(text = { Text("Снять отметку", color = Tabel.colors.error) },
                                             onClick = { menu = false; onStatus(null) })
                        }
                    }
                }
            }
        }
    }
}
