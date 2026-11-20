package ru.kfh.tabel.ui.fields

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.fields.FieldText
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog

/**
 * The form of one field: a new one (id = null) or a saved one. The area is typed in hectares and kept in whole
 * square metres (`FieldText`); the same checks and texts as the site and the server.
 */
@Composable
fun FieldFormScreen(vm: FieldsViewModel, id: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val all by vm.fields.collectAsState(initial = null)
    var name by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var culture by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val existing = all?.firstOrNull { it.id.equals(id, ignoreCase = true) }
    LaunchedEffect(existing, id) {
        if (!loaded && id == null) loaded = true
        val e = existing ?: return@LaunchedEffect
        if (!loaded) {
            name = e.name
            area = FieldText.areaHa(e.areaSqM)
            culture = e.culture.orEmpty()
            note = e.note.orEmpty()
            loaded = true
        }
    }

    val sqM = FieldText.parseAreaSqM(area)
    val errors = FieldText.errors(name, sqM ?: 0L, culture.takeIf { it.isNotBlank() }, note.takeIf { it.isNotBlank() })

    TitledScreen(if (id == null) "Новое поле" else "Поле", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Название или номер") }, singleLine = true,
                isError = errors.any { it.startsWith("Название") },
                modifier = Modifier.fillMaxWidth().testTag("fieldName"),
            )
            OutlinedTextField(
                value = area, onValueChange = { area = it }, label = { Text("Площадь, га") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errors.any { it.startsWith("Площадь") },
                modifier = Modifier.fillMaxWidth().testTag("fieldArea"),
            )
            OutlinedTextField(
                value = culture, onValueChange = { culture = it }, label = { Text("Культура (необязательно)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("fieldCulture"),
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it }, label = { Text("Заметка (необязательно)") },
                modifier = Modifier.fillMaxWidth().testTag("fieldNote"),
            )
            for (e in errors) Text(e, color = Tabel.colors.error)
            BigButton(
                if (id == null) "Добавить поле" else "Сохранить",
                onClick = {
                    if (sqM != null && errors.isEmpty()) {
                        saving = true
                        scope.launch {
                            try {
                                vm.save(id, name, sqM, culture, note)
                                onBack()
                            } catch (e: IllegalStateException) {
                                message = e.message
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                enabled = sqM != null && errors.isEmpty() && !saving, busy = saving,
                modifier = Modifier.testTag("saveField"),
            )
        }
    }
    ErrorDialog(message) { message = null }
}
