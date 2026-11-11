package ru.kfh.tabel.ui.livestock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kfh.core.coal.CoalText
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockText
import ru.kfh.core.livestock.SlaughterReason
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.tabel.ui.coal.CoalTheme
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.FarmDatePickerDialog
import java.time.Instant
import java.time.ZoneId
import ru.kfh.core.time.FarmTime

/** Colours of the livestock module (`LivestockTheme`): КРС green, козы blue, свиньи orange, забой red. */
object LivestockTheme {
    @Composable
    fun slaughter(): Color = if (isSystemInDarkTheme()) Color(0xFFED6661) else Color(0xFFC72929)

    @Composable
    fun color(s: Species): Color = when (s) {
        Species.CATTLE -> Tabel.colors.primary
        Species.GOATS -> CoalTheme.hired()
        Species.PIGS -> Tabel.colors.accent
    }

    /** The colour of an operation in the history (`OperationRow.color`). */
    @Composable
    fun kind(k: LivestockOperationKind): Color = when {
        k == LivestockOperationKind.SLAUGHTER -> slaughter()
        k.isAddition -> Tabel.colors.primary
        k == LivestockOperationKind.TRANSFER -> CoalTheme.hired()
        else -> Tabel.colors.accent
    }
}

/** «+», «→» or «−» before the heads of an operation (`OperationRow.sign`). */
fun kindSign(k: LivestockOperationKind): String = if (k.isAddition) "+" else if (k == LivestockOperationKind.TRANSFER) "→" else "−"

/** A grey caption above a group of fields («1. Вид животного»). */
@Composable
fun LivestockCaption(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tabel.colors.secondaryText)
}

/** The three species as tiles with their heads; the chosen one is filled with its colour. */
@Composable
fun SpeciesTiles(herd: Herd, selected: Species, enabled: Boolean = true, onSelect: (Species) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (s in Species.entries) {
            val color = LivestockTheme.color(s)
            val on = s == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) color else Tabel.colors.card)
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(s) }
                    .semantics(mergeDescendants = true) { this.selected = on; contentDescription = "Вид ${s.title}" }
                    .testTag("species-${s.wire}")
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else color)
                Text("${herd.heads(s)}", style = MaterialTheme.typography.bodySmall, color = if (on) Color.White else color)
            }
        }
    }
}

/** A rounded tile with one line, filled with [color] when selected (the reason and category buttons of «Записать забой»). */
@Composable
fun SimpleTile(
    title: String, selected: Boolean, color: Color, tag: String, modifier: Modifier = Modifier, subtitle: String? = null,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else Tabel.colors.card)
            .border(1.dp, color.copy(alpha = if (selected) 0f else 0.35f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(tag)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        val fg = if (selected) Color.White else if (subtitle == null) color else MaterialTheme.colorScheme.onBackground
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2,
             textAlign = if (subtitle == null) TextAlign.Center else TextAlign.Start,
             modifier = if (subtitle == null) Modifier.fillMaxWidth() else Modifier)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** A choice of one among [options] as chips in rows (the pickers of the iPhone's forms); `null` selected = none yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T?, title: (T) -> String, tag: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        for (o in options) {
            val on = o == selected
            FilterChip(
                selected = on, onClick = { onSelect(o) }, enabled = enabled, label = { Text(title(o)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tabel.colors.primary, selectedLabelColor = Color.White),
                modifier = Modifier.testTag(tag(o)),
            )
        }
    }
}

/** Whole heads, large and bold, on the number keyboard. */
@Composable
fun HeadsField(value: String, onChange: (String) -> Unit, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
        placeholder = { Text("0", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
        textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

/** «Живой вес, кг» and a decimal field with «—» as the placeholder; weights may stay empty. */
@Composable
fun WeightField(title: String, value: String, onChange: (String) -> Unit, tag: String, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, enabled = enabled, placeholder = { Text("—") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            modifier = Modifier.width(140.dp).testTag(tag),
        )
    }
}

/**
 * «Дата — 07.11.2026 14:05» on the farm's clock; a tap opens the farm date picker. A picked day becomes the site's
 * moment for it ([FarmTime.momentOf]): now for today, the farm's midnight for an earlier day, so a backdated
 * decrease is not ordered after a same-day 00:00 operation and refused by the server after the phone accepted it.
 */
@Composable
fun DateTimeRow(label: String, date: Instant, farm: FarmTime, enabled: Boolean = true, tag: String = "pickDate", onPick: (Instant) -> Unit) {
    val zone = farm.zone
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { picking = true }.testTag(tag).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(CoalText.dateTime(date, zone), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
    }
    if (picking) {
        FarmDatePickerDialog(date.atZone(zone).toLocalDate(), onDismiss = { picking = false }) { day -> onPick(farm.momentOf(day)) }
    }
}

/** Date, species and category, type, ± heads, weights, comment (`OperationRow`). */
@Composable
fun OperationRow(
    o: LivestockOperation, category: LivestockCategory?, target: LivestockCategory?, slaughter: SlaughterRecord?, zone: ZoneId,
    enabled: Boolean = true, onClick: (() -> Unit)? = null,
) {
    val kind = LivestockOperationKind.fromWire(o.kind)
    val color = LivestockTheme.kind(kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tabel.colors.card)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .testTag("op-${o.id}")
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
            val species = category?.let { Species.fromWire(it.species).title }.orEmpty()
            Text("$species · ${category?.name ?: "?"}" + (target?.let { " → ${it.name}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(CoalText.dateTime(o.date, zone), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            if (slaughter != null) {
                val parts = listOfNotNull(
                    SlaughterReason.fromWire(slaughter.reason).title,
                    slaughter.liveWeightGrams?.let { "живой ${LivestockText.kilograms(it)}" },
                    slaughter.carcassWeightGrams?.let { "после забоя ${LivestockText.kilograms(it)}" },
                    slaughter.meatWeightGrams?.let { "мясо ${LivestockText.kilograms(it)}" },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            }
            if (o.comment.isNotEmpty()) Text(o.comment, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        }
        Text("${kindSign(kind)}${o.heads}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color,
             modifier = Modifier.testTag("opHeads-${o.id}"))
    }
}
