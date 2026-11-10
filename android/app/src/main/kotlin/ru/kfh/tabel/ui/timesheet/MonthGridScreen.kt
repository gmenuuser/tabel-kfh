package ru.kfh.tabel.ui.timesheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.kfh.core.timesheet.DayMarkRules
import ru.kfh.core.timesheet.DayStatus
import ru.kfh.core.timesheet.HoursRules
import ru.kfh.core.timesheet.HoursTotals
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.core.timesheet.TimesheetBuilder
import ru.kfh.core.timesheet.TimesheetCell
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.TimesheetKind
import ru.kfh.core.timesheet.TimesheetRow
import ru.kfh.core.timesheet.TimesheetTotals
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 1.8f

/**
 * «Табель за месяц» (`MonthTimesheetView`): rows are employees, columns the days, each cell the hours of the day
 * (0, 4, 8, 12) in its colour — or, in «Обозначения», the status code of the day in the status's colour. The names
 * stay on the left while the days scroll sideways; «−» / «+» or two fingers zoom; a tap on a cell changes that day.
 * The chosen month is kept between screens.
 */
@Composable
fun MonthGridScreen(vm: TimesheetViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val month by vm.month.collectAsState()
    val kind by vm.kind.collectAsState()
    val document by remember(month, kind) { vm.monthDocument(month, kind) }.collectAsState(initial = null)
    var scale by remember { mutableFloatStateOf(1f) }
    var pickingMonth by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CellTarget?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditMarks

    TitledScreen("Табель за месяц", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MonthBar(month, onPick = { pickingMonth = true }) { vm.month.value = it }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                for ((index, k) in TimesheetKind.entries.withIndex()) {
                    SegmentedButton(
                        selected = kind == k, onClick = { vm.kind.value = k },
                        shape = SegmentedButtonDefaults.itemShape(index, TimesheetKind.entries.size),
                        colors = segmentedColors(), modifier = Modifier.testTag("kind-${k.name}"),
                    ) { Text(if (k == TimesheetKind.HOURS) "Часы" else "Обозначения") }
                }
            }
            val d = document
            if (d != null) {
                if (d.rows.isEmpty()) {
                    Text("В этом месяце нет работающих сотрудников.", color = Tabel.colors.secondaryText,
                         modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (canEdit) "Нажмите на клетку, чтобы изменить день" else "", style = MaterialTheme.typography.bodySmall,
                             color = Tabel.colors.secondaryText, modifier = Modifier.weight(1f))
                        ZoomButton("−", "Уменьшить", "zoomOut") { scale = (scale - 0.2f).coerceIn(MIN_SCALE, MAX_SCALE) }
                        ZoomButton("+", "Увеличить", "zoomIn") { scale = (scale + 0.2f).coerceIn(MIN_SCALE, MAX_SCALE) }
                    }
                    // Two fingers zoom (centroid, pan, zoom, rotation: only the zoom is used); one finger scrolls the days.
                    Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE) }
                    }) {
                        Grid(d, scale, canEdit) { row, day ->
                            editing = CellTarget(row.employee.id, row.employee.fullName, YearMonth.of(d.year, d.month).atDay(day))
                        }
                    }
                    if (d.isHours) FarmHoursTotals(d) else FarmCodeTotals(d)
                }
                if (!canEdit) ReadOnlyNote()
                Legend(d.isHours)
            }
        }
    }

    if (pickingMonth) {
        MonthYearDialog(month, vm.today().year, onDismiss = { pickingMonth = false }) {
            vm.month.value = it
            pickingMonth = false
        }
    }

    editing?.let { target ->
        val isHours = kind == TimesheetKind.HOURS
        val current = document?.rows?.firstOrNull { it.employee.id == target.employeeId }?.cells?.getOrNull(target.day.dayOfMonth - 1)
        val employees by vm.employees.collectAsState(initial = emptyList())
        fun apply(block: suspend () -> String?) {
            editing = null
            scope.launch { message = block() }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${target.name}, ${dateText(target.day)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val e = employees.firstOrNull { it.id == target.employeeId }
                    if (isHours) {
                        for (h in HoursRules.allowed) {
                            ChoiceButton(HoursStyle.title(h), HoursStyle.fill(h), "choose-$h") {
                                apply { e?.let { vm.setHours(it, target.day, h) } }
                            }
                        }
                    } else {
                        for (s in DayMarkRules.statuses(target.day, vm.today())) {
                            ChoiceButton("${s.code} — ${s.title}", StatusStyle.fill(s), "choose-${s.code}") {
                                apply { e?.let { vm.setStatus(it, target.day, s) } }
                            }
                        }
                    }
                    val marked = if (isHours) current?.hours != null else current?.status != null
                    if (marked) {
                        ChoiceButton("Снять отметку", Tabel.colors.error.copy(alpha = 0.15f), "choose-none", Tabel.colors.error) {
                            apply { e?.let { if (isHours) vm.setHours(it, target.day, null) else vm.setStatus(it, target.day, null) } }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }

    ErrorDialog(message) { message = null }
}

private data class CellTarget(val employeeId: String, val name: String, val day: LocalDate)

@Composable
private fun ChoiceButton(text: String, fill: Color, tag: String, color: Color = MaterialTheme.colorScheme.onBackground, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(fill)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = color, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ZoomButton(text: String, label: String, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Tabel.colors.card)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleLarge, color = Tabel.colors.primary) }
}

/** ‹ НОЯБРЬ 2026 › — the title opens the month picker. */
@Composable
fun MonthBar(month: YearMonth, onPick: () -> Unit, change: (YearMonth) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { change(month.minusMonths(1)) }, modifier = Modifier.testTag("prevMonth")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц", tint = Tabel.colors.primary)
            }
            Text(
                RuMonths.title(month).uppercase(RU), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = Tabel.colors.primary, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onPick).testTag("monthTitle").padding(8.dp),
            )
            IconButton(onClick = { change(month.plusMonths(1)) }, modifier = Modifier.testTag("nextMonth")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц", tint = Tabel.colors.primary)
            }
        }
    }
}

/** «Месяц и год»: the months of a year with «‹ год ›», «Готово» applies (`MonthYearPicker`). */
@Composable
fun MonthYearDialog(initial: YearMonth, currentYear: Int, onDismiss: () -> Unit, onDone: (YearMonth) -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    val years = minOf(currentYear - 10, initial.year)..maxOf(currentYear + 2, initial.year)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Месяц и год") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (chosen.year > years.first) chosen = chosen.minusYears(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий год")
                    }
                    Text(chosen.year.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                         textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (chosen.year < years.last) chosen = chosen.plusYears(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий год")
                    }
                }
                for (rowStart in 1..12 step 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in rowStart until rowStart + 3) {
                            val selected = chosen.monthValue == m
                            Text(
                                RuMonths.name(m), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Tabel.colors.primary else Tabel.colors.card)
                                    .clickable(role = Role.Button) { chosen = chosen.withMonth(m) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Готово", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// MARK: Grid

@Composable
private fun Grid(d: TimesheetDocument, scale: Float, canEdit: Boolean, onCell: (TimesheetRow, Int) -> Unit) {
    val cellWidth = (34 * scale).dp
    val rowHeight = (42 * scale).dp
    val nameWidth = (128 * scale).dp
    val totalWidth = (32 * scale).dp
    val separator = separatorColor()
    val headerFill = Tabel.colors.primary.copy(alpha = 0.15f)
    val titles = d.totalsTitles
    val month = YearMonth.of(d.year, d.month)
    val wide = if (d.isHours) 2 else 0

    fun totalWidthOf(index: Int) = if (index < wide) totalWidth * 1.5f else totalWidth

    Row(verticalAlignment = Alignment.Top) {
        // The names stay in place.
        Column {
            HeaderCell("Сотрудник", nameWidth, rowHeight, scale, Alignment.CenterStart, headerFill, separator)
            for (row in d.rows) {
                Box(
                    modifier = Modifier.width(nameWidth).height(rowHeight).background(Tabel.colors.card)
                        .border(0.5.dp, separator).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(TimesheetBuilder.shortName(row.employee.fullName), fontSize = (14 * scale).sp, lineHeight = (16 * scale).sp,
                         fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                modifier = Modifier.width(nameWidth).height(rowHeight).background(Tabel.colors.card).border(0.5.dp, separator)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Итого", fontSize = (14 * scale).sp, fontWeight = FontWeight.Bold) }
        }
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                for (day in 1..d.daysInMonth) DayHeader(month.atDay(day), cellWidth, rowHeight, scale, separator)
                for ((index, t) in titles.withIndex()) {
                    HeaderCell(t, totalWidthOf(index), rowHeight, scale, Alignment.Center, headerFill, separator)
                }
            }
            for (row in d.rows) {
                Row {
                    for ((index, cell) in row.cells.withIndex()) {
                        DayCell(cell, row, index + 1, cellWidth, rowHeight, scale, separator, canEdit) { onCell(row, index + 1) }
                    }
                    TotalsCells(d.totalsValues(row), d, wide, totalWidth, rowHeight, scale, separator, bold = false)
                }
            }
            Row {
                for (day in 1..d.daysInMonth) {
                    val text = if (d.isHours) {
                        d.rows.sumOf { r -> r.cells[day - 1].let { c -> if (c.employed) c.hours ?: 0 else 0 } }.let { if (it == 0) "" else it.toString() }
                    } else {
                        d.rows.count { r -> r.cells[day - 1].let { c -> c.employed && c.status == DayStatus.PRESENT } }.let { if (it == 0) "" else it.toString() }
                    }
                    Box(
                        modifier = Modifier.width(cellWidth).height(rowHeight).background(Tabel.colors.card).border(0.5.dp, separator),
                        contentAlignment = Alignment.Center,
                    ) { Text(text, fontSize = (12 * scale).sp, fontWeight = FontWeight.SemiBold, color = Tabel.colors.secondaryText) }
                }
                TotalsCells(d.farmTotalsValues, d, wide, totalWidth, rowHeight, scale, separator, bold = true)
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, scale: Float,
    alignment: Alignment, fill: Color, separator: Color,
) {
    Box(
        modifier = Modifier.width(width).height(height).background(fill).border(0.5.dp, separator)
            .padding(horizontal = if (alignment == Alignment.CenterStart) 6.dp else 0.dp),
        contentAlignment = alignment,
    ) { Text(text, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun DayHeader(date: LocalDate, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, scale: Float, separator: Color) {
    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val weekday = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[date.dayOfWeek.value - 1]
    val color = if (weekend) Tabel.colors.accent else MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier.width(width).height(height).background(Tabel.colors.primary.copy(alpha = if (weekend) 0.08f else 0.15f))
            .border(0.5.dp, separator),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(date.dayOfMonth.toString(), fontSize = (13 * scale).sp, lineHeight = (14 * scale).sp, fontWeight = FontWeight.Bold, color = color)
        Text(weekday, fontSize = (9 * scale).sp, lineHeight = (10 * scale).sp, color = color)
    }
}

@Composable
private fun DayCell(
    cell: TimesheetCell, row: TimesheetRow, day: Int, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
    scale: Float, separator: Color, canEdit: Boolean, onClick: () -> Unit,
) {
    val hours = cell.hours
    val status = cell.status
    val fill = when {
        !cell.employed -> unemployedFill()
        hours != null -> HoursStyle.fill(hours)
        status != null -> StatusStyle.fill(status)
        else -> Tabel.colors.background
    }
    val label = hours?.let { "$it ч" } ?: status?.title?.lowercase(RU) ?: "не отмечен"
    Box(
        modifier = Modifier.width(width).height(height).background(fill).border(0.5.dp, separator)
            .clickable(enabled = cell.employed && canEdit, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${row.employee.fullName}, $day: $label" }
            .testTag("cell-${row.employee.id}-$day"),
        contentAlignment = Alignment.Center,
    ) {
        Text(cell.text, fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun TotalsCells(
    values: List<Int>, d: TimesheetDocument, wide: Int, totalWidth: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
    scale: Float, separator: Color, bold: Boolean,
) {
    for ((index, value) in values.withIndex()) {
        val fill = if (d.isHours) {
            if (index < wide) Tabel.colors.primary.copy(alpha = 0.12f) else HoursStyle.fill(HoursRules.allowed[index - wide])
        } else {
            StatusStyle.fill(TimesheetTotals.columnStatuses[index])
        }
        Box(
            modifier = Modifier.width(if (index < wide) totalWidth * 1.5f else totalWidth).height(height).background(fill)
                .border(0.5.dp, separator),
            contentAlignment = Alignment.Center,
        ) {
            Text(value.toString(), fontSize = (13 * scale).sp, maxLines = 1,
                 fontWeight = if (bold || index == 0) FontWeight.ExtraBold else FontWeight.SemiBold)
        }
    }
}

// MARK: Totals and legend

@Composable
private fun FarmHoursTotals(d: TimesheetDocument) {
    val t = d.farmHoursTotals ?: HoursTotals()
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Итоги по хозяйству", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Всего отработано", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${t.hours} ч", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Tabel.colors.primary)
            }
            for (pair in HoursRules.allowed.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in pair) {
                        Row(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(HoursStyle.fill(h))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Отметок $h ч", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(t.days(h).toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text("Сотрудников в табеле: ${d.rows.size}", style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
        }
    }
}

@Composable
private fun FarmCodeTotals(d: TimesheetDocument) {
    val t = d.farmTotals
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Итоги по хозяйству", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (pair in TimesheetTotals.columnStatuses.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in pair) {
                        Row(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(StatusStyle.fill(s))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(t.days(s).toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
            Text("Сотрудников в табеле: ${d.rows.size}", style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
        }
    }
}

@Composable
private fun Legend(isHours: Boolean) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обозначения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (isHours) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in HoursRules.allowed) {
                        Text(
                            HoursStyle.title(h), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(HoursStyle.fill(h)).padding(vertical = 6.dp),
                        )
                    }
                }
                Text("Пустая клетка — отметки нет (это не 0 часов).", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            } else {
                StatusLegend()
            }
        }
    }
}
