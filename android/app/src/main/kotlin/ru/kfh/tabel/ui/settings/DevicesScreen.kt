package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import ru.kfh.core.sync.Device
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel

/** `DeviceDTO.title`: the name, else «iPhone» / «Сайт» / the platform / «Устройство». */
fun Device.title(): String = name?.takeIf { it.isNotEmpty() }
    ?: when (platform) { "ios" -> "iPhone"; "web" -> "Сайт"; else -> platform ?: "Устройство" }

fun Device.who(): String = login ?: email ?: ""

/**
 * «Устройства» (owner, `DevicesView`): every phone and browser signed in to the farm; «Отозвать» for a lost one
 * (its data is wiped there at its next sync).
 */
@Composable
fun DevicesScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<Device?>(null) }
    val zone = vm.zone

    suspend fun load() {
        loading = true
        vm.devices().onSuccess { devices = it; problem = null }.onFailure { problem = "Список не загружен: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    TitledScreen("Устройства", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(
                footer = "Отзовите доступ потерянного телефона: он больше не получит данные хозяйства, а при следующей связи данные на нём удалятся.",
            ) {
                when {
                    loading && devices.isEmpty() -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    problem != null -> GreyLine(problem!!, tag = "devicesProblem")
                }
                devices.forEachIndexed { i, d ->
                    if (i > 0) RowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("device-${d.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (d.platform == "web") Icons.Filled.Home else Icons.Filled.Phone,
                            contentDescription = null,
                            tint = if (d.revoked) Tabel.colors.secondaryText else Tabel.colors.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Headline(if (d.current) "${d.title()} (это устройство)" else d.title())
                            if (d.who().isNotEmpty()) Text(d.who(), style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                            if (d.revoked) {
                                Text("Доступ отозван", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.accent)
                            } else if (d.lastSeenAt != null) {
                                Text("Был в сети: ${OwnerText.moment(d.lastSeenAt, zone)}", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                            }
                        }
                        if (!d.revoked && !d.current) {
                            OutlinedButton(onClick = { revoking = d }, modifier = Modifier.testTag("revoke-${d.id}")) {
                                Text("Отозвать", color = Tabel.colors.error)
                            }
                        }
                    }
                }
            }
        }
    }

    revoking?.let { d ->
        ConfirmDialog(
            title = "Отозвать доступ этого устройства?",
            message = "${d.title()}${if (d.who().isEmpty()) "" else " · ${d.who()}"} выйдет из хозяйства. Войти снова можно по логину и паролю.",
            confirm = "Отозвать доступ",
            onConfirm = {
                revoking = null
                scope.launch {
                    note = vm.revoke(d.id)
                    load()
                }
            },
            onDismiss = { revoking = null },
        )
    }
    NoteDialog(note) { note = null }
}
