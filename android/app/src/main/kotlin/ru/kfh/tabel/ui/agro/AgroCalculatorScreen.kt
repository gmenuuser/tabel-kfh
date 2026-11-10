package ru.kfh.tabel.ui.agro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.kfh.core.agro.AgroEngine
import ru.kfh.core.agro.AgroField
import ru.kfh.core.agro.AgroKind
import ru.kfh.core.agro.AgroPlots
import ru.kfh.core.model.AgroCalculation
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.livestock.LivestockCaption
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote
import ru.kfh.tabel.ui.timesheet.segmentedColors

/**
 * One calculator (`AgroCalculatorView`): the fields with their units, the result right below, «Сохранить»,
 * «Очистить», copy and share. A new calculation's inputs are kept as a draft when the screen is left (per device);
 * a saved one ([savedId]) is corrected in place or saved as a new one. Every number comes from [AgroEngine].
 */
@Composable
fun AgroCalculatorScreen(vm: AgroViewModel, kind: AgroKind, savedId: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The typed values survive a recreate (a rotation); a new calculation's also the process' death, as the draft.
    var inputs by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var title by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf<AgroCalculation?>(null) }
    // The id of this form: a second tap on «Сохранить» while it runs is refused; after a save a new one is drawn.
    var formId by rememberSaveable { mutableStateOf(vm.newId()) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val canSave = vm.canSave
    val isNew = savedId == null

    LaunchedEffect(savedId) {
        if (savedId != null) {
            val c = vm.calculation(savedId).first()
            if (c == null) { onBack(); return@LaunchedEffect }
            saved = c
            if (!loaded) { inputs = c.inputs; title = c.title }
        } else if (!loaded) {
            vm.draft(kind)?.let { inputs = it.inputs; title = it.title }
        }
        loaded = true
    }

    val result = remember(inputs) { AgroEngine.calculate(kind, inputs) }
    val shareText = AgroEngine.shareText(kind, title, inputs, result)

    fun storeDraft() { if (isNew && loaded) vm.storeDraft(kind, inputs, title) }

    fun set(key: String, value: String) {
        inputs = inputs + (key to value)
        note = null
        storeDraft()
    }

    fun clear() {
        inputs = inputs.filterKeys { k -> kind.choices.any { it.key == k } }
        title = ""
        note = null
        if (isNew) vm.clearDraft(kind)
    }

    fun removePlot(i: Int) {
        inputs = inputs.filterKeys { it != AgroPlots.key(i, "length") && it != AgroPlots.key(i, "width") && it != AgroPlots.key(i, "area") } +
            ("plots" to "${maxOf(1, i - 1)}")
        storeDraft()
    }

    fun save(asNew: Boolean) {
        if (saving || !result.isComplete) return
        saving = true
        scope.launch {
            val over = if (asNew) null else saved
            val r = vm.save(kind, inputs, title, formId, over = over, clearDraft = isNew)
            saving = false
            r.onSuccess {
                note = if (over == null) "Расчёт сохранён в истории" else "Изменения сохранены"
                formId = vm.newId()
            }.onFailure { message = it.message ?: "Не получилось" }
        }
    }

    fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
        context.startActivity(Intent.createChooser(send, kind.title))
    }

    TitledScreen(
        kind.title, onBack,
        actions = {
            if (result.isComplete) {
                IconButton(onClick = ::share, modifier = Modifier.testTag("share")) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться", tint = Tabel.colors.primary)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!canSave) ReadOnlyNote()
            for (choice in kind.choices) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LivestockCaption(choice.title)
                    val current = kind.choice(choice.key, inputs)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        for ((i, option) in choice.options.withIndex()) {
                            SegmentedButton(
                                selected = option.first == current, onClick = { set(choice.key, option.first) },
                                shape = SegmentedButtonDefaults.itemShape(i, choice.options.size), colors = segmentedColors(),
                                modifier = Modifier.testTag("choice-${choice.key}-${option.first}"),
                            ) { Text(option.second, maxLines = 1) }
                        }
                    }
                }
            }
            if (kind == AgroKind.AREA) Plots(inputs, ::set, ::removePlot)
            for (f in kind.fields(inputs)) {
                InputRow(f, inputs[f.key] ?: "") { set(f.key, it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LivestockCaption("Поле или комментарий")
                OutlinedTextField(
                    value = title, onValueChange = { title = it; note = null; storeDraft() }, singleLine = true,
                    placeholder = { Text("Например, поле у реки") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("agroTitle"),
                )
            }
            ResultCard(result.lines, result.errors, result.missing, result.warnings, result.isComplete) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(kind.title, shareText))
                note = "Результат скопирован"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    if (isNew) "Сохранить" else "Сохранить изменения", onClick = { save(asNew = false) },
                    enabled = canSave && result.isComplete, busy = saving, modifier = Modifier.weight(1f).testTag("saveCalc"),
                )
                BigButton("Очистить", onClick = ::clear, color = Tabel.colors.reports, modifier = Modifier.weight(1f).testTag("clearCalc"))
            }
            if (!isNew) {
                TextButton(onClick = { save(asNew = true) }, enabled = canSave && result.isComplete && !saving,
                           modifier = Modifier.fillMaxWidth().testTag("saveAsNew")) {
                    Text("Сохранить как новый расчёт", color = Tabel.colors.primary)
                }
            }
            note?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tabel.colors.primary)
                    Text(it, color = Tabel.colors.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("savedNote"))
                }
            }
        }
    }
    ErrorDialog(message) { message = null }
}

/** «Площадь поля (необязательно)» over a large number field with the unit on the right (`inputRow`). */
@Composable
private fun InputRow(f: AgroField, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LivestockCaption(f.title + (if (f.optional) " (необязательно)" else ""))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, placeholder = { Text("0") },
            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = { Text(f.unit, style = MaterialTheme.typography.titleMedium, color = Tabel.colors.secondaryText, modifier = Modifier.padding(end = 12.dp)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = f.title }.testTag("field-${f.key}"),
        )
    }
}

/** The plots of «Площадь поля»: «Участок N», «Длина × Ширина» or «или площадь части», «Убрать», «Добавить участок». */
@Composable
private fun Plots(inputs: Map<String, String>, set: (String, String) -> Unit, removePlot: (Int) -> Unit) {
    val count = AgroPlots.count(inputs)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 1..count) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Участок $i", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (i == count && i > 1) {
                            TextButton(onClick = { removePlot(i) }, modifier = Modifier.testTag("removePlot-$i")) { Text("Убрать", color = Tabel.colors.error) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlotField("Длина", AgroPlots.key(i, "length"), "м", inputs, set, Modifier.weight(1f))
                        Text("×", style = MaterialTheme.typography.titleMedium)
                        PlotField("Ширина", AgroPlots.key(i, "width"), "м", inputs, set, Modifier.weight(1f))
                    }
                    PlotField("или площадь части", AgroPlots.key(i, "area"), "м²", inputs, set, Modifier.fillMaxWidth())
                }
            }
        }
        OutlinedButton(onClick = { set("plots", "${count + 1}") }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("addPlot")) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Добавить участок", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun PlotField(placeholder: String, key: String, unit: String, inputs: Map<String, String>, set: (String, String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = inputs[key] ?: "", onValueChange = { set(key, it) }, singleLine = true, placeholder = { Text(placeholder, maxLines = 1) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = { Text(unit, color = Tabel.colors.secondaryText, modifier = Modifier.padding(end = 8.dp)) },
        modifier = modifier.testTag("field-$key"),
    )
}

/** «Результат»: the errors, «Заполните: …», the lines (the main ones large, in green), the warnings and «Копировать результат». */
@Composable
private fun ResultCard(
    lines: List<ru.kfh.core.model.AgroLine>, errors: List<String>, missing: List<String>, warnings: List<String>, complete: Boolean, onCopy: () -> Unit,
) {
    Card(modifier = Modifier.testTag("agroResult")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Результат", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (errors.isNotEmpty()) {
                for (e in errors) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Tabel.colors.error, modifier = Modifier.width(18.dp))
                        Text(e, color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("error"))
                    }
                }
            } else if (missing.isNotEmpty()) {
                Text("Заполните: " + missing.joinToString(", "), color = Tabel.colors.secondaryText, modifier = Modifier.testTag("missing"))
            }
            for ((i, line) in lines.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.title, style = if (line.main) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                         fontWeight = if (line.main) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text, textAlign = TextAlign.End,
                        style = if (line.main) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (line.main) FontWeight.Bold else FontWeight.Normal,
                        color = if (line.main) Tabel.colors.primary else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.testTag("line-$i"),
                    )
                }
            }
            for (w in warnings) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = Tabel.colors.accent, modifier = Modifier.width(18.dp))
                    Text(w, color = Tabel.colors.accent, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (complete) {
                TextButton(onClick = onCopy, modifier = Modifier.testTag("copyResult")) { Text("Копировать результат", color = Tabel.colors.primary) }
            }
        }
    }
}
