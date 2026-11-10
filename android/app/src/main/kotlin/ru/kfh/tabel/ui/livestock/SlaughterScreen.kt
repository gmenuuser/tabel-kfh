package ru.kfh.tabel.ui.livestock

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.livestock.LivestockDraft
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockText
import ru.kfh.core.livestock.SlaughterReason
import ru.kfh.core.livestock.Species
import ru.kfh.tabel.ui.coal.TwoColumns
import ru.kfh.tabel.ui.coal.ValidationErrors
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote

private val QUICK_HEADS = listOf("1", "2", "3", "5")

/**
 * «Записать забой» (`SlaughterView`): species, category (with what is left), heads, date, reason, optional weights,
 * «СОХРАНИТЬ ЗАБОЙ» → «Будет списано: 2 головы КРС. Продолжить?». [categoryId] preselects a category.
 */
@Composable
fun SlaughterScreen(vm: LivestockViewModel, categoryId: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val herd by vm.herd.collectAsState(initial = Herd.EMPTY)
    val categories by vm.categories.collectAsState(initial = emptyList())
    var species by remember { mutableStateOf(Species.CATTLE) }
    var draft by remember { mutableStateOf(vm.newDraft(LivestockOperationKind.SLAUGHTER, categoryId)) }
    var expanded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canSlaughter
    val red = LivestockTheme.slaughter()

    // The preselected category decides the species once the categories are in.
    LaunchedEffect(categories) {
        val c = categories.firstOrNull { it.id == categoryId } ?: return@LaunchedEffect
        species = Species.fromWire(c.species)
    }

    val shown = categories.filter { Species.fromWire(it.species) == species }
    val category = categories.firstOrNull { it.id == draft.categoryId }
    val question = "Будет списано: ${LivestockText.heads(draft.heads ?: 0)} ${species.title}" + (category?.let { " (${it.name})" } ?: "") + ". Продолжить?"

    fun save() {
        if (saving) return
        saving = true
        val d = draft
        val name = category?.name.orEmpty()
        scope.launch {
            val r = vm.save(d)
            saving = false
            r.fold(
                onSuccess = { o ->
                    saved = "Записано: забой ${LivestockText.heads(o.heads)} ${species.title} ($name)"
                    draft = vm.newDraft(LivestockOperationKind.SLAUGHTER).copy(reason = d.reason)
                    showErrors = false
                },
                onFailure = { message = it.message ?: "Не получилось" },
            )
        }
    }

    TitledScreen("Записать забой", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!canEdit) ReadOnlyNote()
            LivestockCaption("1. Вид животного")
            SpeciesTiles(herd, species, enabled = canEdit) { s -> species = s; draft = draft.copy(categoryId = null) }

            LivestockCaption("2. Категория")
            TwoColumns(shown.size) { i ->
                val c = shown[i]
                val n = herd.heads(c)
                SimpleTile(c.name, selected = draft.categoryId == c.id, color = LivestockTheme.color(species), tag = "category-${c.name}",
                           modifier = Modifier.weight(1f), subtitle = "есть ${LivestockText.heads(n)}", enabled = canEdit && n > 0) {
                    draft = draft.copy(categoryId = c.id)
                }
            }

            LivestockCaption("3. Сколько голов")
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tabel.colors.card).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                HeadsField(draft.headsText, { draft = draft.copy(headsText = it) }, "slaughterHeads", Modifier.weight(1f), enabled = canEdit)
                for (h in QUICK_HEADS) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Tabel.colors.background)
                            .clickable(enabled = canEdit, role = Role.Button) { draft = draft.copy(headsText = h) }.testTag("quick-$h"),
                        contentAlignment = Alignment.Center,
                    ) { Text(h, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                }
            }

            LivestockCaption("4. Дата забоя")
            Card { DateTimeRow("Дата", draft.date, vm.farm.zone, enabled = canEdit) { draft = draft.copy(date = it) } }

            LivestockCaption("5. Причина")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in SlaughterReason.entries) {
                    SimpleTile(r.title, selected = draft.reason == r, color = red, tag = "reason-${r.wire}", modifier = Modifier.weight(1f), enabled = canEdit) {
                        draft = draft.copy(reason = r)
                    }
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.testTag("moreFields"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Вес и комментарий (необязательно)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Tabel.colors.secondaryText)
                    }
                    if (expanded) {
                        WeightField("Общий живой вес, кг", draft.liveWeightText, { draft = draft.copy(liveWeightText = it) }, "liveWeight", canEdit)
                        WeightField("Вес после забоя, кг", draft.carcassWeightText, { draft = draft.copy(carcassWeightText = it) }, "carcassWeight", canEdit)
                        WeightField("Вес мяса, кг", draft.meatWeightText, { draft = draft.copy(meatWeightText = it) }, "meatWeight", canEdit)
                        OutlinedTextField(value = draft.comment, onValueChange = { draft = draft.copy(comment = it) }, enabled = canEdit, maxLines = 3,
                                          placeholder = { Text("Комментарий") }, modifier = Modifier.fillMaxWidth().testTag("comment"))
                    }
                }
            }

            if (showErrors && !draft.isValid) ValidationErrors(draft.validationErrors)

            BigButton("СОХРАНИТЬ ЗАБОЙ", onClick = { showErrors = true; if (draft.isValid && !saving) confirming = true },
                      enabled = canEdit, busy = saving, color = red, modifier = Modifier.testTag("saveSlaughter"))
            saved?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tabel.colors.primary)
                    Text(it, color = Tabel.colors.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("savedNote"))
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(title = question, message = null, confirm = "Продолжить", onConfirm = { confirming = false; save() }, onDismiss = { confirming = false })
    }
    ErrorDialog(message) { message = null }
}
