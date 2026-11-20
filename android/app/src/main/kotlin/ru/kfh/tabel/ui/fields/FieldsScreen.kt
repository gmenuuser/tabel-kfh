package ru.kfh.tabel.ui.fields

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.kfh.core.fields.FieldText
import ru.kfh.core.model.Field
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog

/** «Поля»: the farm's fields — name, area, culture, note. The agro calculators take the area from here. */
@Composable
fun FieldsScreen(vm: FieldsViewModel, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val fields by vm.fields.collectAsState(initial = null)
    val canSave = vm.canSave
    var deleting by remember { mutableStateOf<Field?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    TitledScreen(
        "Поля", onBack,
        actions = {
            if (canSave) {
                IconButton(onClick = onAdd, modifier = Modifier.testTag("addField")) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить поле", tint = Tabel.colors.primary)
                }
            }
        },
    ) { padding ->
        val list = fields
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (list != null && list.isEmpty()) {
                Text(
                    if (canSave) "Полей пока нет. Добавьте поле — калькуляторы возьмут из него площадь."
                    else "Полей пока нет.",
                    color = Tabel.colors.secondaryText, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("emptyText"),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.orEmpty(), key = { it.id }) { f ->
                    Card {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${FieldText.areaHa(f.areaSqM)} га", color = Tabel.colors.secondaryText)
                                val sub = listOfNotNull(
                                    f.culture?.takeIf { it.isNotBlank() },
                                    f.note?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, color = Tabel.colors.secondaryText)
                            }
                            if (canSave) {
                                TextButton(onClick = { onEdit(f.id) }, modifier = Modifier.testTag("editField")) { Text("Изменить") }
                                TextButton(onClick = { deleting = f }, modifier = Modifier.testTag("deleteField")) {
                                    Text("Удалить", color = Tabel.colors.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { f ->
        ConfirmDialog(
            "Удалить поле?", "${f.name} — расчёты, где оно выбрано, останутся.", "Удалить",
            onConfirm = {
                deleting = null
                scope.launch {
                    try {
                        vm.remove(f.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "Не получилось удалить поле"
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
    ErrorDialog(message) { message = null }
}
