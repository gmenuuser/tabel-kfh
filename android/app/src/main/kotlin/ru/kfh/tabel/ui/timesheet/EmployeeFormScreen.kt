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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Adds a new employee (`employeeId == null`) or edits one (`EmployeeFormView`). Closes itself after saving. The
 * hire date of a new employee is today at the farm. The photo (gallery or camera) stays on this phone only.
 *
 * The typed form, the photo's cache file and «Убрать фото» survive a recreate and the process' death behind the
 * camera or the gallery (a cheap phone evicts the app while they are up); the bitmap itself never goes into the Bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeFormScreen(vm: TimesheetViewModel, employeeId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val existing by remember(employeeId) { vm.employee(employeeId ?: "") }.collectAsState(initial = null)
    var draft by rememberSaveable(stateSaver = EmployeeDraftSaver) { mutableStateOf<EmployeeDraft?>(null) }
    var loaded by remember { mutableStateOf<Employee?>(null) }
    // The photo just chosen, as a JPEG in `cache/PendingPhoto/` (its path is what the Bundle keeps).
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable { mutableStateOf(false) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditEmployees

    LaunchedEffect(existing, employeeId) {
        // The saved employee the form edits; after a recreate the draft is already there, the base still has to come.
        if (employeeId != null && loaded == null && existing != null) loaded = existing
        if (draft == null) {
            if (employeeId == null) {
                draft = EmployeeDraft(hireDate = vm.today())
            } else if (existing != null) {
                draft = EmployeeDraft.from(existing!!, vm.farm)
            }
        }
    }

    fun dropPendingPhoto() {
        photoPath?.let { path -> photoPath = null; File(path).delete() }
    }

    /** [jpeg] to a fresh cache file: the form shows it from there and saves it from there. */
    suspend fun takePending(jpeg: ByteArray) {
        val file = withContext(Dispatchers.IO) { pendingPhotoFile(context).apply { writeBytes(jpeg) } }
        photoPath?.let { File(it).delete() }
        photoPath = file.path
        removePhoto = false
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val small = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(::downscaledJpeg) }.getOrNull()
            }
            if (small == null) message = "Не удалось загрузить фото" else takePending(small)
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        scope.launch { takePending(withContext(Dispatchers.Default) { downscaledJpeg(bitmap) }) }
    }

    val d = draft
    val hasPhoto = photoPath != null || (!removePhoto && loaded?.let { vm.photo(it.id) } != null)
    val errors = if (triedSave && d != null) d.validationErrors else emptyList()

    fun save() {
        val current = draft ?: return
        triedSave = true
        if (!current.isValid) return
        busy = true
        scope.launch {
            val path = photoPath
            val photo = withContext(Dispatchers.IO) { path?.let { File(it).takeIf(File::isFile)?.readBytes() } }
            if (path != null && photo == null) {
                // The cache was cleared while the form was away: ask for the photo again rather than save without it.
                busy = false
                photoPath = null
                message = "Фото не сохранилось на телефоне, выберите его ещё раз"
                return@launch
            }
            val result = vm.saveEmployee(loaded, current, photo, removePhoto)
            busy = false
            result.fold(onSuccess = { dropPendingPhoto(); onDone() }, onFailure = { message = it.message ?: "Не получилось" })
        }
    }

    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (employeeId == null) "Новый сотрудник" else "Изменить данные", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = { dropPendingPhoto(); onDone() }, modifier = Modifier.testTag("cancel")) { Text("Отмена", color = Tabel.colors.primary) } },
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
                    val shownPhoto = if (photoPath != null || removePhoto) null else loaded?.let { vm.photo(it.id) }
                    val pending = photoPath
                    if (pending != null) {
                        PendingAvatar(pending)
                    } else {
                        EmployeeAvatar(d.initials(), shownPhoto, 72)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // No gallery / no camera app on the phone: a message, not a crash (ActivityNotFoundException).
                        TextButton(onClick = {
                            runCatching { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                .onFailure { message = "Нет приложения, чтобы выбрать фото" }
                        }, enabled = canEdit) {
                            Text(if (hasPhoto) "Заменить фото" else "Выбрать фото")
                        }
                        TextButton(onClick = {
                            runCatching { takePhoto.launch(null) }.onFailure { message = "Нет приложения камеры, чтобы снять фото" }
                        }, enabled = canEdit) { Text("Снять фото") }
                        if (hasPhoto) {
                            TextButton(onClick = { dropPendingPhoto(); removePhoto = true }, enabled = canEdit) {
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
fun hoursText(h: Double): String =
    if (h == Math.rint(h)) h.toInt().toString() else String.format(Locale.ROOT, "%.1f", h).replace('.', ',')

private fun EmployeeDraft.initials(): String = Employee(firstName = firstName, lastName = lastName).initials

/** The photo just chosen (its cache file), before the form is saved. */
@Composable
private fun PendingAvatar(path: String) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    Box(modifier = Modifier.size(72.dp).clip(CircleShape)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
        }
    }
}

/**
 * `cache/PendingPhoto/<uuid>.jpg` for the photo chosen in the form: a file, so only its path goes into the saved
 * state. Files older than a day (forms abandoned after a process death) are swept on the way.
 */
private fun pendingPhotoFile(context: android.content.Context): File {
    val dir = File(context.cacheDir, "PendingPhoto").apply { mkdirs() }
    val stale = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    dir.listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
    return File(dir, "${UUID.randomUUID()}.jpg")
}

/** The form in the saved state: four strings, two ISO dates, the schedule's name, the norm and the break. */
private val EmployeeDraftSaver = Saver<EmployeeDraft?, ArrayList<Any?>>(
    save = { d ->
        d?.let {
            arrayListOf(it.lastName, it.firstName, it.patronymic, it.position, it.hireDate.toString(), it.dismissalDate?.toString(),
                        it.workSchedule.name, it.standardWorkHours, it.defaultBreakMinutes)
        }
    },
    restore = { l ->
        EmployeeDraft(
            lastName = l[0] as String, firstName = l[1] as String, patronymic = l[2] as String, position = l[3] as String,
            hireDate = LocalDate.parse(l[4] as String), dismissalDate = (l[5] as String?)?.let(LocalDate::parse),
            workSchedule = WorkSchedule.valueOf(l[6] as String), standardWorkHours = l[7] as Double, defaultBreakMinutes = l[8] as Int,
        )
    },
)

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
    if (picking) FarmDatePickerDialog(date, onDismiss = { picking = false }, onPick = onChange)
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
