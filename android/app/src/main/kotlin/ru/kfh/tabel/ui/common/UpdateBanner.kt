package ru.kfh.tabel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.update.UpdateInstaller
import ru.kfh.tabel.update.UpdateState
import java.io.File

/**
 * «Доступна новая версия 1.0.42» with «Установить» on the main screen, only while a verified file waits
 * ([UpdateState.Ready]); nothing otherwise. Non-blocking: the rest of the screen works as before.
 */
@Composable
fun UpdateBanner(state: UpdateState, installer: UpdateInstaller?, modifier: Modifier = Modifier) {
    val ready = state as? UpdateState.Ready ?: return
    if (installer == null) return
    Card(modifier = modifier.testTag("updateBanner")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Tabel.colors.primary, modifier = Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Доступна новая версия ${ready.update.versionName}", style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("updateTitle"))
                    Text("Записи и вход останутся. Установка занимает около минуты.",
                         style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                }
            }
            InstallUpdateButton(installer, ready.file, tag = "installUpdate")
        }
    }
}

/**
 * «Установить» over [file], with everything around the tap: the one-time «install unknown apps» permission (an
 * explanation, the system's page, and the install goes on by itself when the person comes back with it granted),
 * the sync before the install, and the system's answer when something went wrong (in the accent colour below).
 */
@Composable
fun InstallUpdateButton(installer: UpdateInstaller, file: File, tag: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by installer.busy.collectAsState()
    val failure by installer.failure.collectAsState()
    var askPermission by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    /** The person went to the settings: the install goes on when the screen resumes with the permission granted. */
    var resumeInstall by remember { mutableStateOf(false) }

    fun install() {
        note = null
        installer.clearFailure()
        scope.launch {
            when (val o = installer.install(context, file)) {
                UpdateInstaller.Outcome.Started -> Unit
                UpdateInstaller.Outcome.NeedsPermission -> askPermission = true
                is UpdateInstaller.Outcome.Failed -> note = o.text
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && resumeInstall) {
                resumeInstall = false
                if (installer.canInstall()) install()
                else note = "Разрешение не дано: без него телефон не поставит обновление."
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BigButton("Установить", onClick = ::install, busy = busy, modifier = Modifier.testTag(tag))
        (note ?: failure)?.let {
            Text(it, color = Tabel.colors.accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("$tag.note"))
        }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Разрешите установку") },
            text = {
                Text("Один раз разрешите телефону ставить обновления из «Табель КФХ»: откроется экран настроек, " +
                         "включите переключатель и вернитесь назад — установка продолжится сама.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askPermission = false
                        if (installer.openPermissionSettings(context)) resumeInstall = true
                        else note = "Не удалось открыть настройки. Разрешите установку из неизвестных источников для «Табель КФХ» вручную."
                    },
                    modifier = Modifier.testTag("openUnknownSources"),
                ) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Отмена") } },
        )
    }
}
