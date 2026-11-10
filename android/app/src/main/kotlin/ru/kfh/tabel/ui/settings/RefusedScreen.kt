package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.theme.Tabel

/**
 * «Не принятые изменения» (`SettingsForm.refusedSection`): what the server refused, its text and when; «Удалить»
 * removes the change from the phone and the server's data is loaded again (the refused value goes with it).
 */
@Composable
fun RefusedSection(vm: SettingsViewModel, refused: List<SettingsViewModel.RefusedChange>, note: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<SettingsViewModel.RefusedChange?>(null) }
    var busy by remember { mutableStateOf(false) }
    val zone = vm.zone

    SettingsSection(
        header = "Не принятые изменения",
        footer = "Сервер не принял эти изменения (например, не хватает прав или данные неполные). Удалите изменение — данные с сервера загрузятся заново. Если непонятно, почему, обратитесь к владельцу.",
        modifier = Modifier.testTag("refusedSection"),
    ) {
        refused.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Headline(r.title)
                    Text(r.error, style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                    Text(OwnerText.moment(r.at, zone), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                }
                TextButton(onClick = { deleting = r }, enabled = !busy, modifier = Modifier.testTag("deleteRefused-${r.opId}")) {
                    Text("Удалить", color = Tabel.colors.error)
                }
            }
        }
    }

    deleting?.let { r ->
        ConfirmDialog(
            title = "Удалить изменение?",
            message = "Сервер его не принял. Изменение будет удалено с телефона, а данные с сервера загрузятся заново.",
            confirm = "Удалить",
            onConfirm = {
                deleting = null
                busy = true
                scope.launch {
                    vm.deleteRefused(r)?.let(note)
                    busy = false
                }
            },
            onDismiss = { deleting = null },
        )
    }
}
