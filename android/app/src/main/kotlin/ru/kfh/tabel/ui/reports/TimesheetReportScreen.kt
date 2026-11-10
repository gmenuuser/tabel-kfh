package ru.kfh.tabel.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import ru.kfh.core.model.Employee
import ru.kfh.core.timesheet.TimesheetBuilder
import ru.kfh.core.timesheet.TimesheetKind
import ru.kfh.core.timesheet.fullName

import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.livestock.ChipRow
import ru.kfh.tabel.ui.livestock.LivestockCaption
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.FarmTotals
import ru.kfh.tabel.ui.timesheet.Legend
import ru.kfh.tabel.ui.timesheet.MonthBar
import ru.kfh.tabel.ui.timesheet.MonthYearDialog
import ru.kfh.tabel.ui.timesheet.TimesheetViewModel
import ru.kfh.tabel.ui.timesheet.segmentedColors

/**
 * «Табель работников» of «Отчёты»: the month's timesheet for the whole farm or one employee (the file then carries
 * the surname), of hours (what the iPhone exports) or of the status codes, with «Скачать PDF», «Экспорт в Excel»,
 * «Распечатать», «Поделиться».
 */
@Composable
fun TimesheetReportScreen(vm: TimesheetViewModel, onBack: () -> Unit) {
    var month by remember { mutableStateOf(vm.month.value) }
    var kind by rememberSaveable { mutableStateOf(TimesheetKind.HOURS) }
    var employeeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingMonth by remember { mutableStateOf(false) }
    val employees by vm.employees.collectAsState(initial = emptyList())
    val farm = vm.farm
    // Those employed in the month, by ФИО, archived ones too (the rows of the timesheet).
    val people = remember(employees, month, farm) {
        val byId = employees.associateBy { it.id }
        TimesheetBuilder.workingIn(month, employees.map { TimesheetBuilder.snapshot(it, farm) }).mapNotNull { byId[it.id] }
    }
    val chosen = people.firstOrNull { it.id == employeeId }
    val document by remember(month, kind, chosen?.id) { vm.monthDocument(month, kind, chosen?.id) }.collectAsState(initial = null)

    TitledScreen("Табель за месяц", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MonthBar(month, onPick = { pickingMonth = true }) { month = it }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                for ((index, k) in TimesheetKind.entries.withIndex()) {
                    SegmentedButton(
                        selected = kind == k, onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(index, TimesheetKind.entries.size),
                        colors = segmentedColors(), modifier = Modifier.testTag("reportKind-${k.name}"),
                    ) { Text(if (k == TimesheetKind.HOURS) "Часы" else "Обозначения") }
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LivestockCaption("Сотрудник")
                    ChipRow(listOf<Employee?>(null) + people, chosen, { it?.fullName ?: "Все сотрудники" }, { "employee-${it?.id ?: "all"}" }) {
                        employeeId = it?.id
                    }
                }
            }
            val d = document
            if (d != null) {
                if (d.rows.isEmpty()) {
                    Text("В этом месяце нет работающих сотрудников.", color = Tabel.colors.secondaryText, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    Text(if (chosen == null) "Сотрудников в табеле: ${d.rows.size}" else chosen.fullName, modifier = Modifier.testTag("reportRows"))
                    FarmTotals(d)
                }
                Legend(d.isHours)
                TimesheetExportActions(d, farm, chosen?.lastName?.trim()?.ifEmpty { null })
            }
        }
    }

    if (pickingMonth) {
        MonthYearDialog(month, vm.today().year, onDismiss = { pickingMonth = false }) {
            month = it
            pickingMonth = false
        }
    }
}
