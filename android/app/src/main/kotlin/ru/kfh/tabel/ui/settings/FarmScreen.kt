package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.kfh.core.model.FarmSettings
import ru.kfh.core.sync.Me

/**
 * «Хозяйство» (`SettingsForm.farmSection`): the owner edits the farm's name and manager (`PATCH /farm`, a button);
 * the others read them. The four printed fields are the synced settings row: a role that may write it edits them,
 * and each pause in typing (or leaving the screen) saves what changed — one op, as the iPhone's `onChange`.
 */
@Composable
fun FarmSection(vm: SettingsViewModel, me: Me, settings: FarmSettings?, note: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var farmName by remember(me.farmName) { mutableStateOf(me.farmName) }
    var managerName by remember(me.managerName) { mutableStateOf(me.managerName.orEmpty()) }
    var savingFarm by remember { mutableStateOf(false) }
    val farmChanged = farmName.trim() != me.farmName || managerName.trim() != me.managerName.orEmpty()

    var draft by remember { mutableStateOf<FarmSettings?>(null) }
    /** Counts the keystrokes; the save 0.7 s after the last one is keyed on it, so finishing a save restarts nothing. */
    var edits by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        // The stored row, unless the person is in the middle of typing.
        if (!dirty) draft = settings ?: FarmSettings()
    }
    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(700)
        val d = draft ?: return@LaunchedEffect
        val saved = edits
        vm.saveSettings(d)?.let(note)
        // Typed on while saving: still dirty, the next save is on its way.
        if (edits == saved) dirty = false
    }
    DisposableEffect(Unit) {
        onDispose { if (dirty) draft?.let(vm::saveSettingsInBackground) }
    }

    fun change(f: (FarmSettings) -> FarmSettings) {
        draft = f(draft ?: FarmSettings())
        dirty = true
        edits++
    }

    SettingsSection(
        header = "Хозяйство",
        footer = if (vm.canEditFarm)
            "Эти данные печатаются в шапке и подписях табеля. Название и руководитель — общие для всех устройств хозяйства."
        else
            "Эти данные печатаются в шапке и подписях табеля. Название и руководителя меняет владелец.",
    ) {
        if (vm.canEditFarm) {
            CaptionedField("Название КФХ", farmName, { farmName = it }, tag = "farmName")
            CaptionedField("ФИО руководителя", managerName, { managerName = it }, tag = "managerName")
            Button(
                onClick = {
                    savingFarm = true
                    scope.launch {
                        note(vm.saveFarm(farmName, managerName))
                        savingFarm = false
                    }
                },
                enabled = !savingFarm && farmChanged && farmName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("saveFarm"),
            ) { Text("Сохранить название и руководителя") }
        } else {
            LabeledRow("Название КФХ", me.farmName, tag = "farmNameRow")
            RowDivider()
            LabeledRow("Руководитель", me.managerName.orEmpty().ifEmpty { "—" }, tag = "managerRow")
        }
        val d = draft ?: FarmSettings()
        val canEdit = vm.canEditSettings
        CaptionedField("Ответственное лицо", d.responsiblePerson, { v -> change { it.copy(responsiblePerson = v) } }, tag = "responsiblePerson", enabled = canEdit)
        CaptionedField("Адрес", d.address, { v -> change { it.copy(address = v) } }, tag = "address", enabled = canEdit)
        CaptionedField("Телефон", d.phone, { v -> change { it.copy(phone = v) } }, tag = "phone", enabled = canEdit, keyboard = KeyboardType.Phone)
        CaptionedField("Дополнительная информация", d.extraInfo, { v -> change { it.copy(extraInfo = v) } }, tag = "extraInfo",
                       enabled = canEdit, placeholder = "Например, ИНН или ОГРН", minLines = 2)
    }
}
