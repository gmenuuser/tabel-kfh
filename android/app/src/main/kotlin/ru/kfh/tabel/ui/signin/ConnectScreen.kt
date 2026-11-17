package ru.kfh.tabel.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.kfh.tabel.session.ConnectReason
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.theme.Tabel

/** Does this screen need the four digits before it can try? A phone with a token only needs «Повторить». */
fun needsCode(reason: ConnectReason): Boolean = when (reason) {
    ConnectReason.NeedsCode, ConnectReason.CodeWrong, ConnectReason.CodeExpired, ConnectReason.CodeUsed,
    ConnectReason.Revoked -> true
    ConnectReason.Offline, ConnectReason.Closed -> false
}

/** The connect screen's main line for [reason] ([notice] stands instead of «Нет связи с сервером» when given). */
fun connectText(reason: ConnectReason, notice: String?): String = when (reason) {
    ConnectReason.Offline -> notice ?: SessionManager.OFFLINE
    ConnectReason.NeedsCode -> SessionManager.NEEDS_CODE
    ConnectReason.CodeWrong -> SessionManager.CODE_WRONG
    ConnectReason.CodeExpired -> SessionManager.CODE_EXPIRED
    ConnectReason.CodeUsed -> SessionManager.CODE_USED
    ConnectReason.Closed -> SessionManager.CLOSED
    ConnectReason.Revoked -> SessionManager.REVOKED
}

/** «Подключиться» with a new code, «Повторить» with a token, «Подключиться снова» after the owner switched it off. */
fun connectButton(reason: ConnectReason): String = when (reason) {
    ConnectReason.NeedsCode -> "Подключиться"
    ConnectReason.Revoked -> "Подключиться снова"
    ConnectReason.CodeWrong, ConnectReason.CodeExpired, ConnectReason.CodeUsed -> "Попробовать снова"
    else -> "Повторить"
}

/**
 * The phone is not connected. With no code yet (or a bad one) it asks for the four digits the owner handed out and
 * sends them with [connect]; with a token it only says what is wrong and offers «Повторить». While a try runs the
 * button is a spinner. With [ConnectReason.Revoked] the [notice] (the unsent changes that went with the wipe) is a
 * second line.
 */
@Composable
fun ConnectScreen(reason: ConnectReason, notice: String?, connect: suspend (String) -> String?) {
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val withCode = needsCode(reason)

    fun retry() {
        if (busy) return
        if (withCode && code.length != 4) return
        busy = true
        scope.launch {
            try {
                // The outcome is the session's new state (this screen again with its reason, or the farm).
                connect(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to add: the state says what is wrong.
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .testTag("connectScreen"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Табель КФХ", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
             color = Tabel.colors.primary)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(if (reason == ConnectReason.Offline) Icons.Filled.Warning else Icons.Filled.Lock,
                 contentDescription = null, tint = Tabel.colors.accent, modifier = Modifier.size(44.dp))
            Text(connectText(reason, notice), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                 modifier = Modifier.testTag("connectText"))
            if (reason == ConnectReason.Revoked && notice != null) {
                Text(notice, style = MaterialTheme.typography.bodyLarge, color = Tabel.colors.secondaryText,
                     textAlign = TextAlign.Center, modifier = Modifier.testTag("connectNotice"))
            }
        }
        if (withCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { text -> code = text.filter { it.isDigit() }.take(4) },
                label = { Text("Код доступа") },
                singleLine = true,
                textStyle = TextStyle(fontSize = 28.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().testTag("codeField"),
            )
            Text("Четыре цифры выдаёт владелец хозяйства.",
                 style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText,
                 textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        BigButton(connectButton(reason), onClick = ::retry, busy = busy,
                  enabled = !withCode || code.length == 4, modifier = Modifier.testTag("connectButton"))
    }
}
