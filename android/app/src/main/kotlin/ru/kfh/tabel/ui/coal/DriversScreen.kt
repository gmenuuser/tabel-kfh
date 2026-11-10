package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalRules
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.DriverBalance
import ru.kfh.core.coal.fullName
import ru.kfh.core.coal.letter
import ru.kfh.core.model.Driver
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.segmentedColors
import ru.kfh.tabel.ui.timesheet.separatorColor

/** «Водители» (`DriversView`): hired drivers with what the farm owes each of them. */
@Composable
fun DriversScreen(vm: CoalViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val drivers by vm.drivers.collectAsState(initial = null)
    val balances by vm.balances.collectAsState(initial = emptyMap())
    val totalDebt by vm.totalDebt.collectAsState(initial = 0L)
    var archive by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Driver?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditDrivers

    TitledScreen(
        "Водители", onBack,
        actions = {
            if (canEdit) {
                IconButton(onClick = { adding = true }, modifier = Modifier.testTag("addDriver")) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить водителя", tint = Tabel.colors.primary)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(modifier = Modifier.padding(top = 8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CoalMoneyRow("Общий долг перед водителями", totalDebt, bold = true, color = CoalTheme.hired(), tag = "totalDebt")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !archive, onClick = { archive = false }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                                        colors = segmentedColors(), modifier = Modifier.testTag("segment-working")) { Text("Работают") }
                        SegmentedButton(selected = archive, onClick = { archive = true }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                                        colors = segmentedColors(), modifier = Modifier.testTag("segment-archive")) { Text("Архив") }
                    }
                }
            }
            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                              placeholder = { Text("Поиск по фамилии") }, modifier = Modifier.fillMaxWidth().testTag("search"))
            val all = drivers
            if (all != null) {
                val shown = all.filter { it.isArchived == archive && CoalRules.matches(it, search) }
                if (shown.isEmpty()) {
                    Text(if (archive) "В архиве никого нет." else "Водителей пока нет. Нажмите «Добавить водителя».",
                         color = Tabel.colors.secondaryText, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("emptyText"))
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { d ->
                        DriverRow(d, balances[d.id] ?: DriverBalance(), canEdit, onOpen = { open(d.id) }, onDelete = { deleting = d })
                        HorizontalDivider(color = separatorColor())
                    }
                    if (canEdit) {
                        item {
                            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("addDriverButton")) {
                                Text("Добавить водителя")
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) DriverFormDialog(vm, null, onDismiss = { adding = false })
    deleting?.let { d ->
        ConfirmDialog(
            title = "Удалить водителя?", message = d.fullName, confirm = "Удалить",
            onConfirm = { deleting = null; scope.launch { message = vm.deleteDriver(d) } },
            onDismiss = { deleting = null },
        )
    }
    ErrorDialog(message) { message = null }
}

/** A round letter, the name, the deliveries and the debt (`DriverRow`); «Удалить» stands in for the iOS swipe. */
@Composable
private fun DriverRow(d: Driver, balance: DriverBalance, canEdit: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val hired = CoalTheme.hired()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("driverRow-${d.lastName}").padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(hired.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(d.letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(CoalText.deliveries(balance.deliveries), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            if (canEdit) {
                TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.testTag("deleteDriver-${d.lastName}")) {
                    Text("Удалить", color = Tabel.colors.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(CoalMath.rubles(balance.debt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 color = if (balance.debt > 0) hired else Tabel.colors.secondaryText, modifier = Modifier.testTag("debt-${d.lastName}"))
            Text("долг", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        }
    }
}
