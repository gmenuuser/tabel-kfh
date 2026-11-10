package ru.kfh.tabel.ui.livestock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.kfh.core.livestock.LivestockText
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.LivestockCategory
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ModuleButton
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote
import ru.kfh.tabel.ui.timesheet.segmentedColors

/** «Скот и забой» (`LivestockHomeView`): the herd now and four large sections. */
@Composable
fun LivestockHomeScreen(
    vm: LivestockViewModel,
    onBack: () -> Unit,
    openHerd: () -> Unit,
    openSlaughter: () -> Unit,
    openHistory: () -> Unit,
    openReports: () -> Unit,
) {
    val herd by vm.herd.collectAsState(initial = Herd.EMPTY)
    var message by remember { mutableStateOf<String?>(null) }
    // The settings row and the default categories are made on the first opening (`LivestockService.ensureDefaults`).
    LaunchedEffect(vm) { message = vm.ensureDefaults() }

    TitledScreen("Скот и забой", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(modifier = Modifier.testTag("herdSummary")) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Поголовье сейчас", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in Species.entries) {
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Tabel.colors.background).padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("${herd.heads(s)}", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = LivestockTheme.color(s), maxLines = 1,
                                     modifier = Modifier.testTag("herd-${s.wire}"))
                                Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Всего животных", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(LivestockText.heads(herd.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                             modifier = Modifier.testTag("herdTotal"))
                    }
                }
            }
            ModuleButton("Поголовье", "Категории, добавить и уменьшить", Icons.AutoMirrored.Filled.List, Tabel.colors.primary, "openHerd", openHerd)
            if (vm.canSlaughter) {
                ModuleButton("Записать забой", "Списать забитых животных", Icons.Filled.Clear, LivestockTheme.slaughter(), "openSlaughter", openSlaughter)
            }
            ModuleButton("История операций", "Все движения, исправление и удаление", Icons.Filled.Refresh, ru.kfh.tabel.ui.coal.CoalTheme.hired(),
                         "openLivestockHistory", openHistory)
            ModuleButton("Отчёты по скоту", "Остаток, забой, движение — PDF и Excel", Icons.Filled.Info, Tabel.colors.reports, "openLivestockReports", openReports)
        }
    }
    ErrorDialog(message) { message = null }
}

/** «Поголовье» (`HerdView`): one species at a time; every category with its heads and «Добавить», «Уменьшить», «История». */
@Composable
fun HerdScreen(
    vm: LivestockViewModel,
    onBack: () -> Unit,
    openForm: (categoryId: String, adding: Boolean) -> Unit,
    openHistory: (categoryId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val herd by vm.herd.collectAsState(initial = Herd.EMPTY)
    val categories by vm.categories.collectAsState(initial = emptyList())
    var species by remember { mutableStateOf(Species.CATTLE) }
    var addingCategory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditOperations
    val shown = categories.filter { Species.fromWire(it.species) == species }

    TitledScreen("Поголовье", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                for ((i, s) in Species.entries.withIndex()) {
                    SegmentedButton(selected = s == species, onClick = { species = s }, shape = SegmentedButtonDefaults.itemShape(i, Species.entries.size),
                                    colors = segmentedColors(), modifier = Modifier.testTag("species-${s.wire}")) { Text(s.title) }
                }
            }
            Text("Всего: ${LivestockText.heads(herd.heads(species))}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                 color = LivestockTheme.color(species), modifier = Modifier.testTag("speciesTotal"))
            if (!canEdit) ReadOnlyNote()
            for (c in shown) {
                CategoryCard(c, herd.heads(c), species, canEdit, vm.canEditCategories,
                             add = { openForm(c.id, true) }, decrease = { openForm(c.id, false) }, history = { openHistory(c.id) },
                             delete = { scope.launch { message = vm.deleteCategory(c) } })
            }
            if (vm.canEditCategories) {
                OutlinedButton(onClick = { addingCategory = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("addCategory")) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Своя категория", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (addingCategory) {
        NewCategoryDialog(species, onDismiss = { addingCategory = false }) { name ->
            addingCategory = false
            scope.launch { message = vm.addCategory(name, species) }
        }
    }
    ErrorDialog(message) { message = null }
}

@Composable
private fun CategoryCard(
    c: LivestockCategory, heads: Long, species: Species, canEdit: Boolean, canEditCategories: Boolean,
    add: () -> Unit, decrease: () -> Unit, history: () -> Unit, delete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.testTag("category-${c.name}")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$heads", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = LivestockTheme.color(species), modifier = Modifier.testTag("heads-${c.name}"))
                if (!c.isBuiltIn && canEditCategories) {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("categoryMenu-${c.name}")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = Tabel.colors.secondaryText)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Удалить категорию", color = Tabel.colors.error) }, onClick = { menu = false; delete() },
                                             modifier = Modifier.testTag("deleteCategory-${c.name}"))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = add, enabled = canEdit, colors = ButtonDefaults.buttonColors(containerColor = Tabel.colors.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Добавить ${c.name}" }.testTag("add-${c.name}"),
                ) { Text("Добавить") }
                OutlinedButton(
                    onClick = decrease, enabled = canEdit && heads > 0,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LivestockTheme.slaughter()),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Уменьшить ${c.name}" }.testTag("decrease-${c.name}"),
                ) { Text("Уменьшить") }
                OutlinedButton(onClick = history, contentPadding = ButtonDefaults.TextButtonContentPadding,
                               modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "История ${c.name}" }.testTag("history-${c.name}")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
            }
        }
    }
}

/** «Новая категория (КРС)»: a name, «Добавить» and «Отмена». */
@Composable
private fun NewCategoryDialog(species: Species, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая категория (${species.title})") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Название") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("categoryName"),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(name) }, modifier = Modifier.testTag("categoryAdd")) { Text("Добавить", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
