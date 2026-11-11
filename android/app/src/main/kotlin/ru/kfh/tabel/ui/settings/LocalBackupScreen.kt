package ru.kfh.tabel.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kfh.tabel.export.LocalBackup
import java.io.File

/**
 * «Копия на телефоне» (`BackupView`): the phone's data as the iPhone's backup file, saved where the person chooses
 * (`ACTION_CREATE_DOCUMENT`). Restoring a file is not offered: with the sync on, it would overwrite the farm's data
 * on the server (the owner restores a server copy instead, «Копии на сервере»).
 */
@Composable
fun LocalBackupSection(vm: SettingsViewModel, note: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    // The path, saved across a configuration change while the system's file picker is open.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalBackup.JSON)) { uri ->
        val file = pending?.let(::File) ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("нет доступа к файлу")
                    out.use { file.inputStream().use { input -> input.copyTo(it) } }
                }
            }
            note(result.fold({ "Резервная копия сохранена" }, { "Не удалось сохранить копию: ${it.message ?: it::class.java.simpleName}" }))
        }
    }

    SettingsSection(
        header = "Копия на телефоне",
        footer = "Файл со всеми данными, которые есть на этом телефоне (сотрудники, отметки, уголь, скот). Храните его в «Файлах». Синхронизация — не замена копии.",
    ) {
        Button(
            onClick = {
                making = true
                scope.launch {
                    try {
                        val file = vm.makeBackupFile()
                        pending = file.path
                        save.launch(file.name)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        note("Не удалось создать копию: ${e.message ?: e::class.java.simpleName}")
                    } finally {
                        making = false
                    }
                }
            },
            enabled = !making,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("saveBackup"),
        ) { Text("Сохранить копию в файл") }
    }
}
