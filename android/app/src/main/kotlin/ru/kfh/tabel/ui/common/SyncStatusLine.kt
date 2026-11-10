package ru.kfh.tabel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.ui.theme.Tabel

/**
 * The sync in one line on the main screen (`SyncStatusLine.swift`): [SyncStatus.line] and «Синхронизировать сейчас»;
 * a tap syncs now. A problem is in the accent colour. The `Failed` message is not shown here (detail views).
 */
@Composable
fun SyncStatusLine(status: SyncStatus, role: Role, onSync: () -> Unit) {
    val problem = status.isProblem
    val syncing = status == SyncStatus.Syncing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tabel.colors.card)
            .clickable(enabled = !syncing, role = SemanticsRole.Button, onClick = onSync)
            .testTag("syncStatus")
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon(status), contentDescription = null,
             tint = if (problem) Tabel.colors.accent else Tabel.colors.primary, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status.line(role), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 color = if (problem) Tabel.colors.accent else MaterialTheme.colorScheme.onBackground)
            Text(SyncStatus.ACTION, style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.primary)
        }
        if (syncing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
}

@Suppress("DEPRECATION") // Icons.Filled.Send: the auto-mirrored one is not in material-icons-core 1.7.
private fun icon(s: SyncStatus): ImageVector = when (s) {
    SyncStatus.Syncing -> Icons.Filled.Refresh
    is SyncStatus.Offline, is SyncStatus.Failed, is SyncStatus.Conflicts -> Icons.Filled.Warning
    is SyncStatus.Pending -> Icons.Filled.Send
    SyncStatus.Synced -> Icons.Filled.CheckCircle
}
