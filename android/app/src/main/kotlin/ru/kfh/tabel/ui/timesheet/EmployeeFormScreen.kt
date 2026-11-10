package ru.kfh.tabel.ui.timesheet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kfh.core.model.Employee
import ru.kfh.core.timesheet.EmployeeDraft
import ru.kfh.core.timesheet.WorkSchedule
import ru.kfh.core.timesheet.initials
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.theme.Tabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Adds a new employee (`employeeId == null`) or edits one (`EmployeeFormView`). Closes itself after saving. The
 * hire date of a new employee is today at the farm. The photo (gallery or camera) stays on this phone only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeFormScreen(vm: TimesheetViewModel, employeeId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val existing by remember(employeeId) { vm.employee(employeeId ?: "") }.collectAsState(initial = null)
    var draft by remember { mutableStateOf<EmployeeDraft?>(null) }
    var loaded by remember { mutableStateOf<Employee?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var triedSave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditEmployees

    LaunchedEffect(existing, employeeId) {
        if (draft == null) {
            if (employeeId == null) {
                draft = EmployeeDraft(hireDate = vm.today())
            } else if (existing != null) {
                loaded = existing
                draft = EmployeeDraft.from(existing!!, vm.farm)
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val small = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(::downscaledJpeg) }.getOrNull()
            }
            if (small == null) message = "Не удалось загрузить фото" else { photo = small; removePhoto = false }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        scope.launch {
            photo = withContext(Dispatchers.Default) { downscaledJpeg(bitmap) }
            removePhoto = false
        }
    }

    val d = draft
    val hasPhoto = photo != null || (!removePhoto && loaded?.let { vm.photo(it.id) } != null)
    val errors = if (triedSave && d != null) d.validationErrors else emptyList()

    fun save() {
        val current = draft ?: return
        triedSave = true
        if (!current.isValid) return
        busy = true
        scope.launch {
            val result = vm.saveEmployee(loaded, current, photo, removePhoto)
            busy = false
            result.fold(onSuccess = { onDone() }, onFailure = { message = it.message ?: "Не получилось" })
        }
    }

    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (employeeId == null) "Новый сотрудник" else "Изменить данные", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onDone, modifier = Modifier.testTag("cancel")) { Text("Отмена", color = Tabel.colors.primary) } },
                actions = {
                    if (canEdit) {
                        TextButton(onClick = ::save, enabled = d != null && !busy, modifier = Modifier.testTag("save")) {
                            Text("Сохранить", fontWeight = FontWeight.Bold, color = Tabel.colors.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shownPhoto = if (photo != null || removePhoto) null else loaded?.let { vm.photo(it.id) }
                    val pending = photo
                    if (pending != null) {
                        PendingAvatar(pending)
                    } else {
                        EmployeeAvatar(d.initials(), shownPhoto, 72)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = canEdit) {
                            Text(if (hasPhoto) "Заменить фото" else "Выбрать фото")
                        }
                        TextButton(onClick = { takePhoto.launch(null) }, enabled = canEdit) { Text("Снять фото") }
                        if (hasPhoto) {
                            TextButton(onClick = { photo = null; removePhoto = true }, enabled = canEdit) {
                                Text("Убрать фото", color = Tabel.colors.error)
                            }
                        }
                    }
                }
            }

            SectionTitle("ФИО и должность")
            NameField("Фамилия", d.lastName, "lastName", canEdit) { draft = d.copy(lastName = it) }
            NameField("Имя", d.firstName, "firstName", canEdit) { draft = d.copy(firstName = it) }
            NameField("Отчество", d.patronymic, "patronymic", canEdit) { draft = d.copy(patronymic = it) }
            NameField("Должность", d.position, "position", canEdit) { draft = d.copy(position = it) }

            SectionTitle("Работа в хозяйстве")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateRow("Дата приёма", d.hireDate, canEdit) { draft = d.copy(hireDate = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Уволен", modifier = Modifier.weight(1f))
                        Switch(
                            checked = d.dismissalDate != null, enabled = canEdit,
                            onCheckedChange = { on ->
                                draft = d.copy(dismissalDate = if (on) maxOf(vm.today(), d.hireDate) else null)
                            },
                            modifier = Modifier.testTag("dismissed"),
                        )
                    }
                    d.dismissalDate?.let { dismissal ->
                        DateRow("Дата увольнения", dismissal, canEdit) { draft = d.copy(dismissalDate = it) }
                    }
                }
            }

            SectionTitle("График и норма")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleRow(d.workSchedule, canEdit) { draft = d.copy(workSchedule = it) }
                    Stepper("Норма: ${hoursText(d.standardWorkHours)} ч в день", canEdit,
                            onMinus = { draft = d.copy(standardWorkHours = (d.standardWorkHours - 0.5).coerceAtLeast(EmployeeDraft.HOURS_RANGE.start)) },
                            onPlus = { draft = d.copy(standardWorkHours = (d.standardWorkHours + 0.5).coerceAtMost(EmployeeDraft.HOURS_RANGE.endInclusive)) })
                    Stepper("Перерыв: ${d.defaultBreakMinutes} мин", canEdit,
                            onMinus = { draft = d.copy(defaultBreakMinutes = (d.defaultBreakMinutes - 5).coerceAtLeast(EmployeeDraft.BREAK_RANGE.first)) },
                            onPlus = { draft = d.copy(defaultBreakMinutes = (d.defaultBreakMinutes + 5).coerceAtMost(EmployeeDraft.BREAK_RANGE.last)) })
                }
            }

            if (errors.isNotEmpty()) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (text in errors) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = Tabel.colors.accent)
                                Text(text, color = Tabel.colors.accent)
                            }
                        }
                    }
                }
            }
            if (!canEdit) ReadOnlyNote()
        }
    }

    ErrorDialog(message) { message = null }
}

/** «8», «7,5». */
fun hoursText(h: Double): String = if (h == Math.rint(h)) h.toInt().toString() else "%.1f".format(h).replace('.', ',')

private fun EmployeeDraft.initials(): String = Employee(firstName = firstName, lastName = lastName).initials

/** The photo just chosen, before the form is saved. */
@Composable
private fun PendingAvatar(jpeg: ByteArray) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
    Box(modifier = Modifier.size(72.dp).clip(CircleShape)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tabel.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun NameField(label: String, value: String, tag: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: String, date: LocalDate, enabled: Boolean, onChange: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { picking = true }.testTag("date-$label").padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(dateText(date), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ScheduleRow(schedule: WorkSchedule, enabled: Boolean, onChange: (WorkSchedule) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { open = true }.testTag("schedule").padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Рабочий график", modifier = Modifier.weight(1f))
            Text(schedule.title, color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in WorkSchedule.entries) {
                DropdownMenuItem(text = { Text(s.title) }, onClick = { open = false; onChange(s) })
            }
        }
    }
}

@Composable
private fun Stepper(text: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus, enabled = enabled) { Text("−", style = MaterialTheme.typography.titleLarge, color = Tabel.colors.primary) }
        IconButton(onClick = onPlus, enabled = enabled) { Text("+", style = MaterialTheme.typography.titleLarge, color = Tabel.colors.primary) }
    }
}
