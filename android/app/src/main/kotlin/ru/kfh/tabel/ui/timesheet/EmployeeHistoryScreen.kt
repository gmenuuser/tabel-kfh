package ru.kfh.tabel.ui.timesheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.core.timesheet.WorkSchedule
import ru.kfh.core.timesheet.fullName
import ru.kfh.core.timesheet.initials
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.home.longDate
import ru.kfh.tabel.ui.theme.Tabel
import java.time.LocalDate
import java.time.YearMonth

/**
 * One employee's work history by month (`EmployeeHistoryView`): each day of employment with its hours (0, 4, 8, 12)
 * or no mark; tapping a day opens the choice of hours. «Изменить» opens the form; «В архив» asks first.
 */
@Composable
fun EmployeeHistoryScreen(vm: TimesheetViewModel, employeeId: String, onBack: () -> Unit, edit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf(YearMonth.from(vm.today())) }
    val history by remember(month) { vm.history(employeeId, month) }.collectAsState(initial = null)
    var picking by remember { mutableStateOf<LocalDate?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val h = history
    val e = h?.employee

    TitledScreen(
        e?.fullName ?: "", onBack,
        actions = {
            if (vm.canEditEmployees && e != null) {
                TextButton(onClick = edit, modifier = Modifier.testTag("edit")) { Text("Изменить", color = Tabel.colors.primary) }
            }
        },
    ) { padding ->
        if (h == null || e == null) return@TitledScreen
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmployeeAvatar(e.initials, h.photo, 56)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (e.position.isNotEmpty()) Text(e.position, color = Tabel.colors.secondaryText)
                        val hire = dateText(vm.farm.dayOf(e.hireDate))
                        val dismissal = e.dismissalDate?.let { ", уволен ${dateText(vm.farm.dayOf(it))}" }.orEmpty()
                        Text("Принят $hire$dismissal", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                        Text("График ${WorkSchedule.fromRaw(e.workSchedule).title} · перерыв ${e.defaultBreakMinutes} мин",
                             style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                        if (e.isArchived) {
                            Text("В архиве", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Tabel.colors.accent)
                        }
                    }
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.testTag("prevMonth")) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц", tint = Tabel.colors.primary)
                        }
                        Text(RuMonths.title(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                             textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("historyMonth"))
                        IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.testTag("nextMonth")) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц", tint = Tabel.colors.primary)
                        }
                    }
                    LabeledValue("Часов за месяц", h.totals.hours.toString(), "monthHours")
                    LabeledValue("Рабочих дней", h.totals.workedDays.toString(), "monthDays")
                }
            }

            Text("ДНИ", style = MaterialTheme.typography.labelMedium, color = Tabel.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
            Card {
                Column {
                    if (h.days.isEmpty()) {
                        Text("В этом месяце сотрудник не работал в хозяйстве", color = Tabel.colors.secondaryText)
                    }
                    for ((index, d) in h.days.withIndex()) {
                        if (index > 0) HorizontalDivider(color = separatorColor())
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = vm.canEditMarks, role = Role.Button) { picking = d.day }
                                .testTag("day-${d.day.dayOfMonth}").padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(shortDay(d.day), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                            if (d.hours != null) {
                                Text(HoursStyle.title(d.hours), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                     color = HoursStyle.color(d.hours))
                            } else {
                                Text("Нет отметки", style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                            }
                        }
                    }
                }
            }

            if (vm.canEditEmployees) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (e.isArchived) {
                            TextButton(onClick = { scope.launch { message = vm.restore(e) } }, modifier = Modifier.testTag("restore")) {
                                Text("Вернуть из архива", color = Tabel.colors.primary)
                            }
                        } else {
                            TextButton(onClick = { confirmArchive = true }, modifier = Modifier.testTag("archive")) {
                                Text("В архив", color = Tabel.colors.accent)
                            }
                        }
                        Text("Архив только скрывает сотрудника с Главной; отметки и табели остаются.",
                             style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                    }
                }
            } else {
                ReadOnlyNote()
            }
        }
    }

    picking?.let { day ->
        val current = h?.days?.firstOrNull { it.day == day }?.hours
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Часы за день") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(e?.fullName.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(longDate(day), color = Tabel.colors.secondaryText)
                    HoursButtons(current = current, enabled = vm.canEditMarks && e != null, tagSuffix = "pick", minHeight = 60) { hours ->
                        picking = null
                        scope.launch { message = e?.let { vm.setHours(it, day, hours) } }
                    }
                    if (current != null && vm.canEditMarks) {
                        TextButton(onClick = {
                            picking = null
                            scope.launch { message = e?.let { vm.setHours(it, day, null) } }
                        }, modifier = Modifier.testTag("clearHours")) { Text("Снять отметку", color = Tabel.colors.error) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Отмена") } },
        )
    }

    if (confirmArchive && e != null) {
        ConfirmDialog(
            title = "Убрать ${e.fullName} в архив?",
            message = "Сотрудник пропадёт с Главной. Вернуть его можно здесь или во вкладке «Архив».",
            confirm = "В архив",
            onConfirm = {
                confirmArchive = false
                scope.launch { message = vm.archive(e) }
            },
            onDismiss = { confirmArchive = false },
        )
    }

    ErrorDialog(message) { message = null }
}

@Composable
private fun LabeledValue(label: String, value: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, color = Tabel.colors.secondaryText, modifier = Modifier.testTag(tag))
    }
}

/** «6, пт». */
private fun shortDay(d: LocalDate): String = "${d.dayOfMonth}, ${ruWeekday(d)}"
