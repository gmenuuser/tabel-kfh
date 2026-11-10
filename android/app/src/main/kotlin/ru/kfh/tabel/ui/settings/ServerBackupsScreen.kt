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
import ru.kfh.core.sync.Backup
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel

/**
 * «Копии на сервере» (owner, `ServerBackupsView`): the farm's copies on the server; «Создать копию», «Проверить»
 * (the checksum) and «Восстановить» (every device of the farm gets the copy's data) behind a warning.
 */
@Composable
fun ServerBackupsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<Backup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<Pair<Backup, String>?>(null) }
    val zone = vm.zone

    suspend fun load() {
        loading = true
        vm.backups().onSuccess { backups = it; problem = null }.onFailure { problem = "Список не загружен: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    fun run(request: suspend () -> String) {
        busy = true
        scope.launch {
            note = request()
            load()
            busy = false
        }
    }

    /** Unsent changes of this phone become conflicts after a restore — say so before it. */
    suspend fun unsentWarning(): String {
        val n = vm.unsent().pending
        if (n <= 0) return ""
        return "\n\nНа этом телефоне $n ${plural(n, "изменение", "изменения", "изменений")} ещё не " +
            plural(n, "отправлено", "отправлены", "отправлены") +
            ": после восстановления они попадут в «Конфликты». Лучше сначала нажать «Синхронизировать сейчас»."
    }

    TitledScreen("Копии на сервере", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(footer = "Копия всех данных хозяйства хранится на сервере. Сервер и сам делает копии каждую ночь.") {
                Button(onClick = { run { vm.createBackup() } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("createBackup")) {
                    Text("Создать копию")
                }
            }
            SettingsSection(header = "Копии") {
                when {
                    loading && backups.isEmpty() -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    problem != null -> GreyLine(problem!!, tag = "backupsProblem")
                    backups.isEmpty() -> GreyLine("Копий пока нет.", tag = "noBackups")
                }
                backups.forEachIndexed { i, b ->
                    if (i > 0) RowDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("backup-${b.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Headline(OwnerText.moment(b.createdAt, zone))
                        Text("Записей: ${b.records}", style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(onClick = { run { vm.verifyBackup(b.id) } }, enabled = !busy, modifier = Modifier.testTag("verify-${b.id}")) {
                                Text("Проверить")
                            }
                            OutlinedButton(
                                onClick = { scope.launch { restoring = b to unsentWarning() } },
                                enabled = !busy,
                                modifier = Modifier.testTag("restore-${b.id}"),
                            ) { Text("Восстановить", color = Tabel.colors.error) }
                        }
                    }
                }
            }
        }
    }

    restoring?.let { (b, warning) ->
        ConfirmDialog(
            title = "Восстановить копию?",
            message = "Данные хозяйства на сервере и на всех устройствах станут такими, как в копии от ${OwnerText.moment(b.createdAt, zone)}. " +
                "Всё, что внесли после неё, будет удалено. Отменить это нельзя — при сомнении сначала создайте новую копию." + warning,
            confirm = "Восстановить",
            onConfirm = { restoring = null; run { vm.restoreBackup(b.id) } },
            onDismiss = { restoring = null },
        )
    }
    NoteDialog(note) { note = null }
}
