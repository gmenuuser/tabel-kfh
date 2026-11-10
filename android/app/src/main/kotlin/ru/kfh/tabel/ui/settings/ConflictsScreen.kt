package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import ru.kfh.core.sync.Conflict
import ru.kfh.core.sync.ConflictChoice
import ru.kfh.tabel.db.RefusedChanges
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel

/** `OwnerText.value`: a value of a conflict, readable. */
fun conflictValue(v: JsonElement?): String = when (v) {
    null, is JsonNull -> "пусто"
    is JsonPrimitive -> when {
        v.isString -> v.content.ifEmpty { "пусто" }
        v.booleanOrNull != null -> if (v.booleanOrNull == true) "да" else "нет"
        else -> v.content
    }
    is JsonArray -> v.joinToString(", ") { conflictValue(it) }
    is JsonObject -> "запись"
}

/** `OwnerText.field`: what the conflict is about. */
fun conflictField(c: Conflict): String = if (c.field == "__deleted") "удаление" else c.field

/**
 * «Конфликты» (owner, `ConflictsView`): the same thing was changed on two devices; for each, both versions and the
 * choice «Взять с сервера» / «Взять с устройства» (`POST /conflicts/:id/resolve`; the device's version may come
 * from a helper's phone, not this one). Nothing is lost until then.
 */
@Composable
fun ConflictsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var conflicts by remember { mutableStateOf<List<Conflict>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        vm.conflicts().onSuccess { conflicts = it; problem = null }.onFailure { problem = "Список не загружен: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    fun resolve(c: Conflict, choice: ConflictChoice) {
        busy = true
        scope.launch {
            // The chosen version comes to this phone with the sync; the count on the main screen too.
            vm.resolve(c, choice)?.let { note = it }
            load()
            busy = false
        }
    }

    TitledScreen("Конфликты", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(footer = "Одно и то же изменили на двух устройствах. Ничего не потеряно: обе версии сохранены, пока вы не выберете.") {
                when {
                    loading && conflicts.isEmpty() -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    problem != null -> GreyLine(problem!!, tag = "conflictsProblem")
                    conflicts.isEmpty() -> GreyLine("Конфликтов нет.", tag = "noConflicts")
                }
                conflicts.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("conflict-${c.id}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Headline("${RefusedChanges.title(c.entity)} · ${conflictField(c)}")
                        VersionRow("На сервере", conflictValue(c.serverValue))
                        VersionRow("С устройства", conflictValue(c.clientValue))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(onClick = { resolve(c, ConflictChoice.server) }, enabled = !busy, modifier = Modifier.testTag("takeServer-${c.id}")) {
                                Text("Взять с сервера")
                            }
                            Button(onClick = { resolve(c, ConflictChoice.client) }, enabled = !busy, modifier = Modifier.testTag("takeClient-${c.id}")) {
                                Text("Взять с устройства")
                            }
                        }
                    }
                }
            }
        }
    }
    NoteDialog(note) { note = null }
}

@Composable
private fun VersionRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        Text(value)
    }
}
