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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.kfh.tabel.session.ConnectReason
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.theme.Tabel

/** The connect screen's main line for [reason] ([notice] stands instead of «Нет связи с сервером» when given). */
fun connectText(reason: ConnectReason, notice: String?): String = when (reason) {
    ConnectReason.Offline -> notice ?: SessionManager.OFFLINE
    ConnectReason.Closed -> SessionManager.CLOSED
    ConnectReason.Revoked -> SessionManager.REVOKED
}

/** «Повторить», or «Подключиться снова» after the owner switched the phone off. */
fun connectButton(reason: ConnectReason): String =
    if (reason == ConnectReason.Revoked) "Подключиться снова" else "Повторить"

/**
 * The phone is not connected (there is no sign-in: it connects itself): why, and one button that tries again
 * ([connect] returns the error or null; while it runs the button is a spinner). With [ConnectReason.Revoked] the
 * [notice] (the unsent changes that went with the wipe) is a second line.
 */
@Composable
fun ConnectScreen(reason: ConnectReason, notice: String?, connect: suspend () -> String?) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun retry() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // The outcome is the session's new state (this screen again with its reason, or the farm).
                connect()
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
        BigButton(connectButton(reason), onClick = ::retry, busy = busy, modifier = Modifier.testTag("connectButton"))
    }
}
