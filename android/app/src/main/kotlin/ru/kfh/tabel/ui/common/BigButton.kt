package ru.kfh.tabel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.kfh.tabel.ui.theme.Tabel

/** A module of the main screen: icon, title, subtitle, chevron, white on [color] (`MainScreen.bigButton`). */
@Composable
fun ModuleButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = title }
            .testTag(tag)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // No line limit: at font scale 2 on a 360 dp screen «АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ» needs three lines,
            // and a clipped title is worse than a taller button (SmallScreenLargeFontTest).
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.alpha(0.9f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White)
    }
}

/** Large full-width button for one-handed use (`BigButtonStyle`): inactive looks inactive, [busy] shows progress. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    color: Color = Tabel.colors.primary,
) {
    val active = enabled && !busy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled || busy) 1f else 0.45f))
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            // Centered also when huge text wraps the label onto two lines.
            Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.7f),
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/** Light-grey rounded card (`.card()`). */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tabel.colors.card)
            .padding(14.dp),
    ) { content() }
}
