package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.kfh.core.time.Iso
import ru.kfh.tabel.ui.theme.Tabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A section of the iPhone's `Form`: a header, a card with its rows, a footer. */
@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(header.uppercase(RU), style = MaterialTheme.typography.labelMedium, color = Tabel.colors.secondaryText,
                 modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tabel.colors.card).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** A row that opens another screen (`NavigationLink`). */
@Composable
fun NavRow(title: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Tabel.colors.secondaryText)
    }
}

/** `LabeledContent`: a title and its value on one line. */
@Composable
fun LabeledRow(title: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, color = Tabel.colors.secondaryText)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = Tabel.colors.secondaryText.copy(alpha = 0.2f))

/** A caption over a text field (`field(_:text:)` of the settings form). */
@Composable
fun CaptionedField(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    enabled: Boolean = true,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    minLines: Int = 1,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = minLines == 1,
            minLines = minLines,
            placeholder = placeholder?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

/** The iPhone's `.alert(note)`: a sentence and «OK». */
@Composable
fun NoteDialog(note: String?, onDismiss: () -> Unit) {
    if (note == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(note, modifier = Modifier.testTag("noteText")) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("noteOk")) { Text("OK") } },
    )
}

/** «Список не загружен: …», «Помощников пока нет.» and the like, in grey. */
@Composable
fun GreyLine(text: String, tag: String? = null) {
    Text(text, color = Tabel.colors.secondaryText, modifier = Modifier.padding(vertical = 10.dp).let { if (tag != null) it.testTag(tag) else it })
}

@Composable
fun Headline(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)

val RU: Locale = Locale.forLanguageTag("ru")

/** `OwnerText`: a date of the server («2026-11-07T10:00:00.000Z») as «07.11.2026 13:00» in the farm's zone. */
object OwnerText {
    private val format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", RU)

    fun moment(iso: String?, zone: ZoneId): String {
        val i = iso?.let(Iso::parse) ?: return ""
        return moment(i, zone)
    }

    fun moment(i: Instant, zone: ZoneId): String = format.format(i.atZone(zone))
}
