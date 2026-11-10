package ru.kfh.tabel.ui.timesheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import ru.kfh.core.model.Employee
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.EmployeeRules
import ru.kfh.core.timesheet.fullName
import ru.kfh.core.timesheet.initials
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import java.time.LocalDate
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

/** «Сотрудники» (`EmployeesView`): working and archived employees, search by ФИО, add, archive, restore. */
@Composable
fun EmployeesScreen(vm: TimesheetViewModel, onBack: () -> Unit, open: (String) -> Unit, add: () -> Unit) {
    val scope = rememberCoroutineScope()
    val employees by vm.employees.collectAsState(initial = null)
    var archive by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditEmployees
    val today = vm.today()

    TitledScreen(
        "Сотрудники", onBack,
        actions = {
            if (canEdit) {
                IconButton(onClick = add, modifier = Modifier.testTag("addEmployee")) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить сотрудника", tint = Tabel.colors.primary)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SegmentedButton(selected = !archive, onClick = { archive = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = segmentedColors(),
                                modifier = Modifier.testTag("segment-working")) { Text("Работают") }
                SegmentedButton(selected = archive, onClick = { archive = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = segmentedColors(),
                                modifier = Modifier.testTag("segment-archive")) { Text("Архив") }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Поиск по ФИО") }, modifier = Modifier.fillMaxWidth().testTag("search"),
            )
            val all = employees
            if (all != null) {
                val q = search.trim().lowercase(RU)
                val shown = EmployeeRules.sortedByName(all.filter { e ->
                    e.isArchived == archive && (q.isEmpty() || e.fullName.lowercase(RU).contains(q))
                })
                if (shown.isEmpty()) {
                    val text = when {
                        q.isNotEmpty() -> "Никого не нашлось"
                        archive -> "В архиве пусто"
                        else -> "Сотрудников пока нет. Нажмите «+», чтобы добавить."
                    }
                    Text(text, color = Tabel.colors.secondaryText, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("emptyText"))
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { e ->
                        EmployeeListRow(
                            e, vm.photo(e.id), employmentNote(e, today, vm.farm), canEdit,
                            onOpen = { open(e.id) },
                            onArchiveToggle = { scope.launch { message = if (e.isArchived) vm.restore(e) else vm.archive(e) } },
                        )
                        HorizontalDivider(color = separatorColor())
                    }
                }
            }
        }
    }

    ErrorDialog(message) { message = null }
}

/** Avatar, ФИО, position and «Принят с 10.11.2026» / «Уволен 05.11.2026» when not employed today. */
@Composable
private fun EmployeeListRow(
    e: Employee, photo: java.io.File?, note: String?, canEdit: Boolean, onOpen: () -> Unit, onArchiveToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("employee-${e.lastName}")
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmployeeAvatar(e.initials, photo, 44)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (e.position.isNotEmpty()) {
                Text(e.position, style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.accent) }
        }
        if (canEdit) {
            TextButton(onClick = onArchiveToggle, modifier = Modifier.testTag("archiveToggle-${e.lastName}")) {
                Text(if (e.isArchived) "Вернуть" else "В архив", color = if (e.isArchived) Tabel.colors.primary else Tabel.colors.accent)
            }
        }
    }
}

/** «Принят с 10.11.2026» / «Уволен 05.11.2026» when not employed today; the farm's days are compared. */
fun employmentNote(e: Employee, today: LocalDate, farm: FarmTime): String? {
    val hire = farm.dayOf(e.hireDate)
    if (hire.isAfter(today)) return "Принят с ${dateText(hire)}"
    val dismissal = e.dismissalDate?.let { farm.dayOf(it) }
    if (dismissal != null && dismissal.isBefore(today)) return "Уволен ${dateText(dismissal)}"
    return null
}
