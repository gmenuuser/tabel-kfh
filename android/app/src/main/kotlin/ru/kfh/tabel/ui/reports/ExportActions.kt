package ru.kfh.tabel.ui.reports

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.tabel.export.ExportFiles
import ru.kfh.tabel.export.ExportedFiles
import ru.kfh.tabel.export.ShareAndPrint
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.theme.Tabel

/** One report's files are written at a time, off the main thread, so the files of two versions never mix. */
private val writing = Mutex()

/** «Скачать PDF», «Экспорт в Excel», «Распечатать», «Поделиться» for the shown timesheet (`ExportActions`). */
@Composable
fun TimesheetExportActions(document: TimesheetDocument, farm: FarmTime, surname: String?) {
    val context = LocalContext.current
    // `createdAt` is «now» on every emission of the document: without it in the key, a sync pulling a sale somewhere
    // else does not regenerate the files and grey the buttons for a moment.
    ExportActions(key = Triple(document.copy(createdAt = Instant.EPOCH), farm.zone, surname), what = "табеля", landscape = true) {
        ExportFiles.writeTimesheet(context, document, farm, surname)
    }
}

/** The same four buttons for a block report (`CoalExportActions`): coal, livestock, agro. */
@Composable
fun ReportExportActions(document: ReportDocument) {
    val context = LocalContext.current
    ExportActions(key = document, what = "отчёта", landscape = false) { ExportFiles.writeReport(context, document) }
}

/**
 * The buttons over files written by [write] whenever [key] changes; disabled until the files are ready. «Скачать»
 * opens the system's save dialog (`ACTION_CREATE_DOCUMENT`), «Распечатать» the print dialog, «Поделиться» the share
 * sheet with both files.
 *
 * The file being saved is kept by its path across a process death behind the picker (a cheap phone kills the app
 * while DocumentsUI is up): the recreated screen rewrites the same files under [writing] and the copy waits for them.
 */
@Composable
private fun ExportActions(key: Any, what: String, landscape: Boolean, write: suspend () -> ExportedFiles) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<ExportedFiles?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // The path of the file the picker is open for; the files land on the same deterministic path after a recreate.
    var saving by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        files = null
        val result = withContext(Dispatchers.IO) { writing.withLock { runCatching { write() } } }
        result.onSuccess { files = it }
            .onFailure { message = "Не удалось подготовить файлы $what: ${it.message ?: it::class.java.simpleName}" }
    }

    fun saved(uri: android.net.Uri?) {
        val path = saving ?: return
        saving = null
        if (uri == null) return
        scope.launch {
            // After a recreate the files are being rewritten: wait until they are ready, then copy under the lock.
            snapshotFlow { files }.filterNotNull().first()
            message = withContext(Dispatchers.IO) {
                writing.withLock { runCatching { copyTo(context, File(path), uri) } }
                    .fold({ "Файл сохранён" }, { "Не удалось сохранить файл: ${it.message}" })
            }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ShareAndPrint.PDF), ::saved)
    val saveXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ShareAndPrint.XLSX), ::saved)

    fun save(launcher: ActivityResultLauncher<String>, file: File) {
        saving = file.path
        runCatching { launcher.launch(file.name) }.onFailure {
            saving = null
            message = "Нет приложения, чтобы сохранить файл"
        }
    }

    val ready = files
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Скачать PDF", enabled = ready != null, modifier = Modifier.weight(1f).testTag("exportPdf"), onClick = {
                ready?.let { save(savePdf, it.pdf) }
            })
            BigButton("Экспорт в Excel", enabled = ready != null, modifier = Modifier.weight(1f).testTag("exportXlsx"), onClick = {
                ready?.let { save(saveXlsx, it.xlsx) }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Распечатать", enabled = ready != null, color = Tabel.colors.accent, modifier = Modifier.weight(1f).testTag("exportPrint"), onClick = {
                ready?.let {
                    runCatching { ShareAndPrint.print(context, it.pdf, it.pdf.name, landscape) }
                        .onFailure { e -> message = "Не удалось распечатать: ${e.message}" }
                }
            })
            BigButton("Поделиться", enabled = ready != null, color = Tabel.colors.accent, modifier = Modifier.weight(1f).testTag("exportShare"), onClick = {
                ready?.let {
                    runCatching { ShareAndPrint.share(context, it.all) }
                        .onFailure { e -> message = "Не удалось поделиться: ${e.message}" }
                }
            })
        }
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

private fun copyTo(context: Context, file: File, uri: android.net.Uri) {
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("нет доступа к файлу")
    out.use { file.inputStream().use { input -> input.copyTo(it) } }
}
