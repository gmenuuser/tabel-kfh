package ru.kfh.tabel.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.theme.Tabel

/**
 * «Табель КФХ»: «Почта или логин», «Пароль», «Войти» (`SignInView.swift`). The farm is made on the site; helpers get
 * their login from the owner. The server's error is shown as its text. [signIn] returns the error or null.
 */
@Composable
fun SignInScreen(notice: String?, signIn: suspend (login: String, password: String) -> String?) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val canSubmit = !busy && login.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        error = null
        busy = true
        focus.clearFocus()
        scope.launch {
            try {
                error = signIn(login.trim(), password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Не удалось войти"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tabel.colors.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Табель КФХ", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
             color = Tabel.colors.primary)
        if (notice != null) {
            IconLine(Icons.Filled.Lock, notice, Tabel.colors.accent, Modifier.testTag("signOutNotice"))
        }
        Field("Почта или логин") {
            TextField(
                value = login,
                onValueChange = { login = it },
                placeholder = { Text("Почта или логин") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                                                  autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("signInLogin"),
            )
        }
        Field("Пароль") {
            TextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("signInPassword"),
            )
        }
        error?.let { IconLine(Icons.Filled.Warning, it, Tabel.colors.error, Modifier.testTag("signInError")) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Войти", onClick = ::submit, enabled = canSubmit, busy = busy,
                      modifier = Modifier.testTag("signInButton"))
            Text("Логин выдаёт владелец хозяйства", style = MaterialTheme.typography.bodySmall,
                 color = Tabel.colors.secondaryText, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Field(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
        field()
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Tabel.colors.card,
    unfocusedContainerColor = Tabel.colors.card,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
