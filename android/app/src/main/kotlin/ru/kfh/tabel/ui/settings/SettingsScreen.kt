package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.update.UpdateInstaller
import ru.kfh.tabel.update.UpdateState
import java.time.Instant

/** 1 изменение, 2 изменения, 5 изменений. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** The question before «Выйти» (`SettingsView.signOutQuestion`). */
fun signOutQuestion(unsent: SessionManager.Unsent): String {
    val n = unsent.pending
    val r = unsent.refused
    val parts = buildList {
        if (n > 0) add("$n ${plural(n, "изменение", "изменения", "изменений")} ещё не " +
                       plural(n, "отправлено", "отправлены", "отправлены") + ".")
        if (r > 0) add("$r ${plural(r, "изменение", "изменения", "изменений")} сервер не принял — " +
                       "при выходе они удалятся.")
    }
    return if (parts.isEmpty()) "Выйти из хозяйства?" else (parts + "Выйти всё равно?").joinToString(" ")
}

/** Where the rows of the settings lead. */
class SettingsLinks(
    val openConflicts: () -> Unit,
    val openCoalPrices: () -> Unit,
    val openDrivers: () -> Unit,
    val openHelpers: () -> Unit,
    val openDevices: () -> Unit,
    val openServerBackups: () -> Unit,
)

/**
 * «Настройки» (`SettingsView`), reached by the gear for every role: the farm's details, the sync (state, refused
 * changes, conflicts), the coal module's lists, the owner's helpers, devices and server copies, a copy to a file,
 * «Выйти» (with its question), the app version.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, me: Me, links: SettingsLinks, onBack: () -> Unit, installer: UpdateInstaller? = null) {
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState(initial = null)
    val sync by vm.sync.collectAsState(initial = SettingsViewModel.SyncRow(SyncStatus.Synced, null, null))
    val refused by vm.refused.collectAsState(initial = emptyList())
    var note by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf<String?>(null) }
    /** The total the question named; the sign-out goes with the force only for that count. */
    var asked by remember { mutableStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val zone = vm.zone

    fun ask() {
        scope.launch {
            val u = vm.unsent()
            asked = u.total
            forceNext = false
            question = signOutQuestion(u)
        }
    }

    /** «Выйти» in the question: `signOut(false)` first — a count that changed meanwhile is asked again. */
    fun confirmSignOut() {
        question = null
        busy = true
        scope.launch {
            val n = if (forceNext) vm.signOut(force = true) else vm.signOut(force = false)
            if (n == 0) return@launch
            if (n == asked) {
                vm.signOut(force = true)
                return@launch
            }
            val u = vm.unsent()
            asked = u.total
            forceNext = true
            busy = false
            question = signOutQuestion(u)
        }
    }

    TitledScreen("Настройки", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FarmSection(vm, me, settings) { note = it }

            SyncSection(vm, me, sync, zone, links.openConflicts)

            if (refused.isNotEmpty()) RefusedSection(vm, refused) { note = it }

            if (vm.seesCoal) {
                SettingsSection(
                    header = "Продажа угля",
                    footer = "Новые водители и населённые пункты сразу появляются кнопками в продаже угля.",
                ) {
                    NavRow("Цена угля и населённые пункты", tag = "openCoalPrices", onClick = links.openCoalPrices)
                    RowDivider()
                    NavRow("Водители", tag = "openDrivers", onClick = links.openDrivers)
                }
            }

            if (vm.isOwner) {
                SettingsSection(header = "Владелец", footer = "Помощники входят по логину и паролю и видят только свой раздел.") {
                    NavRow("Помощники", tag = "openHelpers", onClick = links.openHelpers)
                    RowDivider()
                    NavRow("Устройства", tag = "openDevices", onClick = links.openDevices)
                    RowDivider()
                    NavRow("Копии на сервере", tag = "openServerBackups", onClick = links.openServerBackups)
                }
            }

            LocalBackupSection(vm) { note = it }

            SettingsSection(
                footer = "Вы вошли в «${me.farmName}»: ${me.role.title.lowercase(RU)}. " +
                    "При выходе данные хозяйства удаляются с этого телефона; на сервере они остаются.",
            ) {
                BigButton("Выйти", onClick = ::ask, busy = busy, color = Tabel.colors.error,
                          modifier = Modifier.padding(vertical = 6.dp).testTag("signOut"))
            }

            val update by vm.update.collectAsState(initial = UpdateState.Idle)
            AboutSection(vm.versionText, update, installer, onCheck = vm::checkForUpdates)
        }
    }

    question?.let { q ->
        ConfirmDialog(
            title = q,
            message = "Данные хозяйства будут удалены с этого телефона. На сервере они сохранятся; войти можно снова.",
            confirm = "Выйти",
            onConfirm = ::confirmSignOut,
            onDismiss = { question = null },
        )
    }
    NoteDialog(note) { note = null }
}

/** «Синхронизация» (`SettingsForm.syncSection`): the line, the last error, the last moment, the conflicts, the button. */
@Composable
private fun SyncSection(vm: SettingsViewModel, me: Me, sync: SettingsViewModel.SyncRow, zone: java.time.ZoneId, openConflicts: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status = sync.status
    val problem = status.isProblem
    SettingsSection(header = "Синхронизация", footer = "Изменения сохраняются на телефоне сразу и уходят на сервер, когда есть связь.") {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (problem) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null,
                 tint = if (problem) Tabel.colors.accent else Tabel.colors.primary, modifier = Modifier.size(22.dp))
            Text(status.line(me.role), color = if (problem) Tabel.colors.accent else MaterialTheme.colorScheme.onBackground,
                 fontWeight = FontWeight.Medium, modifier = Modifier.testTag("syncLine"))
        }
        (status as? SyncStatus.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText,
                 modifier = Modifier.padding(bottom = 6.dp).testTag("syncError"))
        }
        sync.lastSyncAt?.let { last: Instant ->
            RowDivider()
            LabeledRow("Последняя синхронизация", OwnerText.moment(last, zone), tag = "lastSync")
        }
        sync.conflicts?.let { c ->
            RowDivider()
            if (c.canResolve) {
                NavRow(c.text, tag = "openConflicts", onClick = openConflicts)
            } else {
                Text(c.text, color = Tabel.colors.accent, modifier = Modifier.padding(vertical = 10.dp).testTag("conflictsNote"))
            }
        }
        RowDivider()
        Button(
            onClick = { scope.launch { vm.syncNow() } },
            enabled = status != SyncStatus.Syncing,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("syncNow"),
        ) { Text(SyncStatus.ACTION) }
    }
}
