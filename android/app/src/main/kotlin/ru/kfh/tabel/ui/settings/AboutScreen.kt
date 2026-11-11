package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.kfh.tabel.ui.common.BigButton

/**
 * «О приложении»: «Табель КФХ — Версия 1.0.7 (7)» (`SettingsForm.versionText`). Once per opening of the screen
 * [newer] asks `version.json` on the server; a newer build adds «Доступна версия 1.0.42» and «Скачать новую
 * версию», which hands the `.apk` address to [onDownload] (the browser downloads it; Android offers to install
 * over the current app, and the data stays because the key is the same).
 */
@Composable
fun AboutSection(
    versionText: String,
    newer: suspend () -> UpdateCheck.Update? = { null },
    onDownload: (String) -> Unit = {},
) {
    var update by remember { mutableStateOf<UpdateCheck.Update?>(null) }
    LaunchedEffect(Unit) { update = newer() }
    SettingsSection(
        header = "О приложении",
        footer = update?.let { "Доступна версия ${it.versionName}. Скачайте файл и откройте его: приложение обновится, записи останутся." },
    ) {
        LabeledRow("Табель КФХ", versionText, tag = "version")
        update?.let { u ->
            RowDivider()
            BigButton("Скачать новую версию", onClick = { onDownload(u.url) },
                      modifier = Modifier.padding(vertical = 6.dp).testTag("downloadUpdate"))
        }
    }
}
