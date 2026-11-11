package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.InstallUpdateButton
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.update.UpdateInstaller
import ru.kfh.tabel.update.UpdateState

/**
 * «О приложении»: «Табель КФХ — Версия 1.0.7 (7)» (`SettingsForm.versionText`), «Проверить обновления» ([onCheck]
 * asks the server now and answers in a sentence), and the self-update's state: «Загружается версия 1.0.42… 45 %»
 * while the file comes, «Установить» once it is verified (the same button as the main screen's banner), plus
 * «Открыть файл обновления» — the old way through the system's installer, for a phone whose session install
 * shows nothing.
 */
@Composable
fun AboutSection(
    versionText: String,
    update: UpdateState,
    installer: UpdateInstaller?,
    onCheck: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val footer = when (update) {
        is UpdateState.Ready -> "Доступна версия ${update.update.versionName}. Записи и вход останутся."
        is UpdateState.Downloading -> downloadingText(update)
        UpdateState.Checking -> "Проверяем…"
        UpdateState.Idle -> note
    }
    SettingsSection(header = "О приложении", footer = footer) {
        LabeledRow("Табель КФХ", versionText, tag = "version")
        RowDivider()
        if (update is UpdateState.Ready && installer != null) {
            InstallUpdateButton(installer, update.file, tag = "installUpdateAbout", modifier = Modifier.padding(vertical = 6.dp))
            TextButton(
                onClick = { if (!installer.viewFile(context, update.file)) note = "Телефон не нашёл, чем открыть файл обновления." },
                modifier = Modifier.testTag("openUpdateFile"),
            ) { Text("Открыть файл обновления", color = Tabel.colors.primary, style = MaterialTheme.typography.bodyMedium) }
        } else {
            BigButton(
                "Проверить обновления",
                onClick = {
                    note = null
                    checking = true
                    scope.launch {
                        try {
                            note = onCheck()
                        } finally {
                            checking = false
                        }
                    }
                },
                busy = checking || update is UpdateState.Downloading || update == UpdateState.Checking,
                modifier = Modifier.padding(vertical = 6.dp).testTag("checkUpdates"),
            )
        }
    }
}

/** «Загружается версия 1.0.42… 45 %» (no percent before the server named the size). */
fun downloadingText(d: UpdateState.Downloading): String {
    val percent = if (d.total > 0) " ${(d.received * 100 / d.total).coerceIn(0, 100)} %" else ""
    return "Загружается версия ${d.update.versionName}…$percent"
}
