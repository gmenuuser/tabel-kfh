package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.Member
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel

/** `MemberDTO.displayName`: the login, else the e-mail, else the name, else «Без имени». */
fun Member.displayName(): String = listOf(login, email, name).firstOrNull { !it.isNullOrEmpty() } ?: "Без имени"

/**
 * «Помощники» (owner, `HelpersView`): who else signs in to the farm, with their role; add a helper (login, password,
 * role), change the role, set a new password, switch a helper off. As the site's settings page.
 */
@Composable
fun HelpersScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.timesheet) }
    var newPasswordFor by remember { mutableStateOf<Member?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Member?>(null) }

    suspend fun load() {
        loading = true
        vm.members().onSuccess { members = it; problem = null }.onFailure { problem = "Список не загружен: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    /** Runs a request, then the list again; the server's text when it fails. */
    fun act(request: suspend () -> String) {
        busy = true
        scope.launch {
            note = request()
            load()
            busy = false
        }
    }

    TitledScreen("Помощники", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(
                header = "Помощники",
                footer = "Помощник войдёт на iPhone или на сайте kfh.cubely.ru по логину и паролю. После смены роли или пароля ему нужно войти заново.",
            ) {
                when {
                    loading && members.isEmpty() -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    problem != null -> GreyLine(problem!!, tag = "helpersProblem")
                    members.isEmpty() -> GreyLine("Помощников пока нет.", tag = "noHelpers")
                }
                members.forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Headline(m.displayName(), Modifier.testTag("helper-${m.id}"))
                        RolePicker(m.role, enabled = !busy, tag = "role-${m.id}") { r ->
                            if (r != m.role) act { vm.setRole(m.id, r) }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(onClick = { newPassword = ""; newPasswordFor = m }, enabled = !busy) { Text("Новый пароль") }
                            OutlinedButton(onClick = { removing = m }, enabled = !busy, modifier = Modifier.testTag("remove-${m.id}")) {
                                Text("Отключить", color = Tabel.colors.error)
                            }
                        }
                    }
                }
            }

            SettingsSection(
                header = "Новый помощник",
                footer = "«Только табель», «Только уголь», «Только скот» — помощник видит и меняет только свой раздел. «Только просмотр» — видит всё, ничего не меняет. «Управляющий» — всё, кроме этих настроек владельца.",
            ) {
                OutlinedTextField(value = login, onValueChange = { login = it }, singleLine = true, placeholder = { Text("Логин") },
                                  modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("newLogin"))
                OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                                  placeholder = { Text("Пароль (не короче 8 символов)") }, visualTransformation = PasswordVisualTransformation(),
                                  modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("newPassword"))
                RolePicker(role, enabled = !busy, tag = "newRole") { role = it }
                Button(
                    onClick = {
                        act {
                            val text = vm.addMember(login, password, role)
                            if (text == "Помощник добавлен") { login = ""; password = ""; role = Role.timesheet }
                            text
                        }
                    },
                    enabled = !busy && login.isNotBlank() && password.length >= 8,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("addHelper"),
                ) { Text("Добавить помощника") }
            }
        }
    }

    newPasswordFor?.let { m ->
        AlertDialog(
            onDismissRequest = { newPasswordFor = null },
            title = { Text("Новый пароль") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Для ${m.displayName()}. Его устройства выйдут из хозяйства, он войдёт с новым паролем.")
                    OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, singleLine = true,
                                      placeholder = { Text("Не короче 8 символов") }, visualTransformation = PasswordVisualTransformation(),
                                      modifier = Modifier.fillMaxWidth().testTag("passwordField"))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = newPassword
                    newPasswordFor = null
                    if (value.length < 8) note = "Пароль — не короче 8 символов"
                    else act { vm.setPassword(m.id, value) }
                }, modifier = Modifier.testTag("confirm")) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { newPasswordFor = null }) { Text("Отмена") } },
        )
    }
    removing?.let { m ->
        ConfirmDialog(
            title = "Отключить помощника?",
            message = "${m.displayName()} больше не сможет войти в хозяйство, его устройства выйдут. Данные хозяйства остаются.",
            confirm = "Отключить",
            onConfirm = { removing = null; act { vm.removeMember(m.id) } },
            onDismiss = { removing = null },
        )
    }
    NoteDialog(note) { note = null }
}

/** «Роль»: the helper roles in the site's order (`Roles.helperRoles`). */
@Composable
private fun RolePicker(role: Role, enabled: Boolean, tag: String, onPick: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = SemanticsRole.Button) { open = true }.testTag(tag).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Роль", modifier = Modifier.weight(1f))
            Text(role.title, color = Tabel.colors.primary)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Tabel.colors.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (r in Role.helperRoles) {
                DropdownMenuItem(text = { Text(r.title) }, onClick = { open = false; onPick(r) }, modifier = Modifier.testTag("$tag-${r.name}"))
            }
        }
    }
}
