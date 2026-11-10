package ru.kfh.tabel.ui.timesheet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.kfh.core.timesheet.DayStatus
import ru.kfh.core.timesheet.HoursRules
import ru.kfh.tabel.ui.theme.Tabel
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru")

/** «06.11.2026» (`CoalText.date`, `EmployeeFormatting.date`). */
fun dateText(d: LocalDate): String = "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)

/** «пн» … «вс». */
fun ruWeekday(d: LocalDate): String = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[d.dayOfWeek.value - 1]

/**
 * Material's date picker speaks in UTC midnights (`selectedDateMillis`), not in any zone's instants: a farm day
 * goes in and comes out as that calendar date, whatever zone the phone is in. Pure, so it is unit-tested.
 */
object PickerDates {
    fun millis(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}

/** The one date picker of the module («Другая дата», «Дата приёма», «Дата увольнения»): Готово applies, Отмена closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmDatePickerDialog(date: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = PickerDates.millis(date))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(PickerDates.date(it)) }
                onDismiss()
            }, modifier = Modifier.testTag("pickerDone")) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) { DatePicker(state = state) }
}

/** Colours of the hours (`HoursStyle`): 0 grey, 4 yellow, 8 green, 12 blue. */
object HoursStyle {
    @Composable
    fun color(h: Int): Color = when (h) {
        0 -> Color(0xFF8E8E93)
        4 -> if (isSystemInDarkTheme()) Color(0xFFD9B31A) else Color(0xFFCC9900)
        8 -> Tabel.colors.primary
        else -> if (isSystemInDarkTheme()) Color(0xFF6BA3FF) else Color(0xFF1A61CC)
    }

    /** The light fill of a cell of the month table. */
    @Composable
    fun fill(h: Int): Color = color(h).copy(alpha = 0.25f)

    fun title(h: Int): String = "$h ч"
}

/** Colours and words of the day statuses (`StatusStyle.swift`), the same on every screen. */
object StatusStyle {
    /** Next to the name: «Был на работе», «Выходной», «Больничный»… */
    fun dayTitle(s: DayStatus): String = if (s == DayStatus.PRESENT) "Был на работе" else s.title

    /** The cell background: Я light green, В light grey, Б light red, О light blue, ОТ light yellow, ПР orange, К light purple. */
    @Composable
    fun fill(s: DayStatus): Color {
        val dark = isSystemInDarkTheme()
        return when (s) {
            DayStatus.PRESENT -> if (dark) Color(0xFF2B6138) else Color(0xFFCCEDCC)
            DayStatus.DAY_OFF -> if (dark) Color(0xFF4D4D52) else Color(0xFFE6E6E8)
            DayStatus.SICK -> if (dark) Color(0xFF733333) else Color(0xFFFAD1D1)
            DayStatus.VACATION -> if (dark) Color(0xFF2B527A) else Color(0xFFCCE6FC)
            DayStatus.TIME_OFF -> if (dark) Color(0xFF736624) else Color(0xFFFCF2B8)
            DayStatus.ABSENT -> if (dark) Color(0xFF99591A) else Color(0xFFFCBD78)
            DayStatus.BUSINESS_TRIP -> if (dark) Color(0xFF574280) else Color(0xFFE3D6FA)
        }
    }

    /** `tertiarySystemFill` of the chip without a status. */
    @Composable
    fun noneFill(): Color = if (isSystemInDarkTheme()) Color(0x3D767680) else Color(0x1F767680)
}

/** `UIColor.separator` and `systemGray4` of the month table. */
@Composable
fun separatorColor(): Color = if (isSystemInDarkTheme()) Color(0x99545458) else Color(0x4A3C3C43)

@Composable
fun unemployedFill(): Color = if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)

/** A rounded chip with the status of the day; «Не отмечен» when there is none. */
@Composable
fun StatusChip(status: DayStatus?, modifier: Modifier = Modifier) {
    Text(
        status?.let { StatusStyle.dayTitle(it) } ?: "Не отмечен",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (status == null) Tabel.colors.secondaryText else MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(status?.let { StatusStyle.fill(it) } ?: StatusStyle.noneFill())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The codes and their colours, under the monthly timesheet of codes. */
@Composable
fun StatusLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in DayStatus.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    s.code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(32.dp).clip(RoundedCornerShape(6.dp)).background(StatusStyle.fill(s))
                        .padding(vertical = 4.dp),
                )
                Text(s.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The employee's photo, or the initials on a green circle (`EmployeeAvatar`). */
@Composable
fun EmployeeAvatar(initials: String, photo: File?, size: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo, photo?.lastModified()) {
        value = photo?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() } }
    }
    val image = bitmap
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(Tabel.colors.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                  modifier = Modifier.size(size.dp))
        } else {
            Text(initials, color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold,
                 style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** «Не получилось» with the domain's message and «OK». */
@Composable
fun ErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не получилось") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("errorOk")) { Text("OK") } },
    )
}

/** «Только просмотр»: a note on a screen the role reads but does not change (`ReadOnlyNote`). */
@Composable
fun ReadOnlyNote() {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Tabel.colors.secondaryText, modifier = Modifier.size(16.dp))
        Text("Только просмотр: изменять данные может владелец или помощник с нужной ролью.",
             style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
    }
}

/** The four hour buttons in a row; the selected one is filled (`EmployeeHoursRow`, `HoursPickerView`). */
@Composable
fun HoursButtons(current: Int?, enabled: Boolean, tagSuffix: String, minHeight: Int = 50, onTap: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (h in HoursRules.allowed) {
            val selected = current == h
            val color = HoursStyle.color(h)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = minHeight.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) color else Tabel.colors.background)
                    .border(1.5.dp, color.copy(alpha = if (selected) 0f else 0.5f), RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, role = Role.Button) { onTap(h) }
                    .semantics(mergeDescendants = true) { contentDescription = "$h ч $tagSuffix"; this.selected = selected }
                    .testTag("hours-$h-$tagSuffix"),
                contentAlignment = Alignment.Center,
            ) {
                Text(HoursStyle.title(h), color = if (selected) Color.White else color,
                     style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** JPEG no larger than 600 px on the long side, so the phone's photos stay small (`EmployeeFormView.downscaled`). */
fun downscaledJpeg(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    return downscaledJpeg(decoded)
}

fun downscaledJpeg(image: Bitmap): ByteArray {
    val longest = maxOf(image.width, image.height)
    val scale = if (longest > MAX_PHOTO_SIDE) MAX_PHOTO_SIDE.toFloat() / longest else 1f
    val resized = if (scale < 1f) {
        Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1),
                                  (image.height * scale).toInt().coerceAtLeast(1), true)
    } else {
        image
    }
    val out = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}

private const val MAX_PHOTO_SIDE = 600

/** The theme's green for a segmented choice («Часы / Обозначения», «Работают / Архив»), not Material's default tint. */
@Composable
fun segmentedColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = Tabel.colors.primary.copy(alpha = 0.15f),
    activeContentColor = Tabel.colors.primary,
    activeBorderColor = Tabel.colors.primary,
    inactiveContainerColor = Tabel.colors.background,
    inactiveContentColor = MaterialTheme.colorScheme.onBackground,
    inactiveBorderColor = Tabel.colors.primary.copy(alpha = 0.5f),
)
