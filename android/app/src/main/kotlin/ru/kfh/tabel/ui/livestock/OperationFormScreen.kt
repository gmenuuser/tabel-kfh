package ru.kfh.tabel.ui.livestock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.kfh.core.livestock.LivestockDraft
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockText
import ru.kfh.core.livestock.SlaughterReason
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.LivestockOperation
import ru.kfh.tabel.ui.coal.ValidationErrors
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote

/**
 * One operation (`OperationFormView`): add (поступление, приплод, начальное поголовье), decrease (забой, продажа,
 * падёж, перевод, другое) or correct a saved one. The herd is checked before saving; a saved one can be deleted.
 *
 * [operationId] null = a new operation for [categoryId]: «Добавить» ([adding]) or «Уменьшить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationFormScreen(vm: LivestockViewModel, operationId: String?, categoryId: String?, adding: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val categories by vm.categories.collectAsState(initial = emptyList())
    val herd by vm.herd.collectAsState(initial = Herd.EMPTY)
    var draft by remember { mutableStateOf<LivestockDraft?>(null) }
    var loaded by remember { mutableStateOf<LivestockOperation?>(null) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmingSlaughter by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // A new decrease starts without a reason: the owner must choose one.
    var reasonChosen by remember { mutableStateOf(operationId != null || adding) }
    val canEdit = vm.canEditOperations
    val needsReason = operationId == null && !adding

    LaunchedEffect(operationId) {
        if (draft != null) return@LaunchedEffect
        if (operationId == null) {
            draft = vm.newDraft(if (adding) LivestockOperationKind.PURCHASE else LivestockOperationKind.SALE, categoryId)
        } else {
            val o = vm.operation(operationId).first()
            val d = vm.draftFor(operationId)
            if (o == null || d == null) { onDone(); return@LaunchedEffect }
            loaded = o
            draft = d
        }
    }

    val d = draft
    val category = categories.firstOrNull { it.id == d?.categoryId }
    val species = category?.let { Species.fromWire(it.species) } ?: Species.CATTLE
    val kinds = if (operationId == null) (if (adding) LivestockOperationKind.additions else LivestockOperationKind.decreases) else LivestockOperationKind.entries

    fun errors(d: LivestockDraft): List<String> = (if (needsReason && !reasonChosen) listOf("Выберите причину уменьшения") else emptyList()) + d.validationErrors

    fun save() {
        val d = draft ?: return
        if (saving) return
        saving = true
        scope.launch {
            val o = loaded
            val r = if (o != null) vm.update(o, d) else vm.save(d).exceptionOrNull()?.let { it.message ?: "Не получилось" }
            saving = false
            if (r == null) onDone() else message = r
        }
    }

    fun trySave() {
        val d = draft ?: return
        showErrors = true
        if (errors(d).isNotEmpty() || saving) return
        if (d.kind == LivestockOperationKind.SLAUGHTER) confirmingSlaughter = true else save()
    }

    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (operationId == null) (if (adding) "Добавить" else "Уменьшить") else "Операция", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onDone, modifier = Modifier.testTag("cancel")) { Text("Отмена", color = Tabel.colors.primary) } },
                actions = {
                    if (canEdit) TextButton(onClick = ::trySave, enabled = !saving, modifier = Modifier.testTag("saveTop")) { Text("Сохранить", color = Tabel.colors.primary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!canEdit) ReadOnlyNote()
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (category != null) {
                        Row { Text(species.title, modifier = Modifier.weight(1f)); Text(category.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("formCategory")) }
                        Row { Text("Сейчас голов", modifier = Modifier.weight(1f)); Text("${herd.heads(category)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("formHeadsNow")) }
                    }
                    LivestockCaption(if (needsReason) "Причина" else if (adding && operationId == null) "Как поступили" else "Тип")
                    ChipRow(kinds, if (reasonChosen) d.kind else null, { it.title }, { "kind-${it.wire}" }, enabled = canEdit) { k ->
                        draft = d.copy(kind = k)
                        reasonChosen = true
                    }
                    if (operationId != null) {
                        LivestockCaption("Категория")
                        val same = categories.filter { Species.fromWire(it.species) == species }
                        ChipRow(same, category, { it.name }, { "category-${it.name}" }, enabled = canEdit) { draft = d.copy(categoryId = it.id) }
                    }
                    if (d.kind == LivestockOperationKind.TRANSFER) {
                        LivestockCaption("Перевести в")
                        val targets = categories.filter { Species.fromWire(it.species) == species && it.id != d.categoryId }
                        ChipRow(targets, targets.firstOrNull { it.id == d.targetCategoryId }, { it.name }, { "target-${it.name}" }, enabled = canEdit) {
                            draft = d.copy(targetCategoryId = it.id)
                        }
                    }
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Голов", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        HeadsField(d.headsText, { draft = d.copy(headsText = it) }, "headsField", Modifier.width(160.dp), enabled = canEdit)
                    }
                    DateTimeRow("Дата", d.date, vm.farm, enabled = canEdit) { draft = d.copy(date = it) }
                    OutlinedTextField(value = d.comment, onValueChange = { draft = d.copy(comment = it) }, enabled = canEdit, maxLines = 3,
                                      placeholder = { Text("Комментарий") }, modifier = Modifier.fillMaxWidth().testTag("comment"))
                }
            }
            if (d.kind == LivestockOperationKind.SLAUGHTER && !(needsReason && !reasonChosen)) {
                SlaughterFields(d, canEdit) { draft = it }
            }
            if (showErrors) ValidationErrors(errors(d))
            if (canEdit) {
                BigButton(
                    if (operationId == null) "СОХРАНИТЬ" else "СОХРАНИТЬ ИЗМЕНЕНИЯ", onClick = ::trySave, busy = saving,
                    color = if (d.kind == LivestockOperationKind.SLAUGHTER) LivestockTheme.slaughter() else Tabel.colors.primary,
                    modifier = Modifier.testTag("saveOperation"),
                )
                if (operationId != null) {
                    Text("Удалить операцию", color = Tabel.colors.error, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmingDelete = true }.testTag("deleteOperation").padding(vertical = 8.dp))
                }
            }
        }
    }

    if (confirmingSlaughter && d != null) {
        ConfirmDialog(
            title = "Будет списано: ${LivestockText.heads(d.heads ?: 0)} ${species.title}" + (category?.let { " (${it.name})" } ?: "") + ". Продолжить?",
            message = null, confirm = "Продолжить",
            onConfirm = { confirmingSlaughter = false; save() },
            onDismiss = { confirmingSlaughter = false },
        )
    }
    if (confirmingDelete) {
        ConfirmDialog(
            title = "Удалить операцию?",
            message = "Поголовье пересчитается так, как будто этой операции не было.",
            confirm = "Удалить",
            onConfirm = {
                confirmingDelete = false
                val o = loaded ?: return@ConfirmDialog
                scope.launch {
                    val r = vm.delete(o)
                    if (r == null) onDone() else message = r
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    ErrorDialog(message) { message = null }
}

/** Reason and the optional weights of a slaughter (`SlaughterFields`). */
@Composable
fun SlaughterFields(d: LivestockDraft, enabled: Boolean, onChange: (LivestockDraft) -> Unit) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LivestockCaption("Забой")
            Text("Причина забоя", style = MaterialTheme.typography.bodyLarge)
            ChipRow(SlaughterReason.entries, d.reason, { it.title }, { "reason-${it.wire}" }, enabled = enabled) { onChange(d.copy(reason = it)) }
            WeightField("Живой вес, кг", d.liveWeightText, { onChange(d.copy(liveWeightText = it)) }, "liveWeight", enabled)
            WeightField("Вес после забоя, кг", d.carcassWeightText, { onChange(d.copy(carcassWeightText = it)) }, "carcassWeight", enabled)
            WeightField("Вес мяса, кг", d.meatWeightText, { onChange(d.copy(meatWeightText = it)) }, "meatWeight", enabled)
            Text("Веса можно не заполнять.", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        }
    }
}
