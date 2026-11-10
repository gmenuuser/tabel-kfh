package ru.kfh.tabel.ui.livestock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.livestock.LivestockRules
import ru.kfh.core.livestock.Species
import ru.kfh.tabel.ui.coal.DayPickerRow
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote

private val PERIODS: List<LivestockPeriod?> = listOf(null, LivestockPeriod.DAY, LivestockPeriod.MONTH, LivestockPeriod.YEAR)

private fun periodTitle(p: LivestockPeriod?): String = when (p) {
    null -> "Всё время"
    LivestockPeriod.DAY -> "День"
    LivestockPeriod.MONTH -> "Месяц"
    LivestockPeriod.YEAR -> "Год"
}

/**
 * «История операций» (`LivestockHistoryView`): every movement with filters by species, category, type and period;
 * a tap opens it for correction or deletion. Opened from a category's «История» ([categoryId]): only that category at first.
 */
@Composable
fun LivestockHistoryScreen(vm: LivestockViewModel, categoryId: String?, onBack: () -> Unit, openOperation: (String) -> Unit) {
    val books by vm.books.collectAsState(initial = null)
    var species by remember { mutableStateOf<Species?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<LivestockOperationKind?>(null) }
    var period by remember { mutableStateOf<LivestockPeriod?>(null) }
    var day by remember { mutableStateOf(vm.farm.dayOf(vm.now())) }
    var loaded by remember { mutableStateOf(categoryId == null) }
    val zone = vm.farm.zone
    val canEdit = vm.canEditOperations

    LaunchedEffect(books) {
        if (loaded) return@LaunchedEffect
        val c = books?.categories?.firstOrNull { it.id == categoryId } ?: return@LaunchedEffect
        species = Species.fromWire(c.species)
        category = c.id
        loaded = true
    }

    TitledScreen("История операций", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val b = books ?: return@TitledScreen
            val byId = b.categories.associateBy { it.id }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Отбор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    LivestockCaption("Вид")
                    ChipRow(listOf<Species?>(null) + Species.entries, species, { it?.title ?: "Все" }, { "filterSpecies-${it?.wire ?: "all"}" }) { s ->
                        species = s
                        if (category != null && byId[category]?.let { Species.fromWire(it.species) } != s) category = null
                    }
                    species?.let { s ->
                        LivestockCaption("Категория")
                        val cats = b.categories.filter { Species.fromWire(it.species) == s }
                        ChipRow(listOf<String?>(null) + cats.map { it.id }, category, { id -> id?.let { byId[it]?.name } ?: "Все" },
                                { id -> "filterCategory-${id?.let { byId[it]?.name } ?: "all"}" }) { category = it }
                    }
                    LivestockCaption("Операция")
                    ChipRow(listOf<LivestockOperationKind?>(null) + LivestockOperationKind.entries, kind, { it?.title ?: "Все" },
                            { "filterKind-${it?.wire ?: "all"}" }) { kind = it }
                    LivestockCaption("Период")
                    ChipRow(PERIODS, period, ::periodTitle, { "filterPeriod-${it?.name?.lowercase() ?: "all"}" }) { period = it }
                    if (period != null) DayPickerRow("Дата", day) { day = it }
                }
            }
            val range = period?.let { LivestockRules.period(it, vm.farm.startOf(day), vm.farm) }
            val filtered = b.operations.filter { o ->
                (category == null || o.categoryId == category || o.targetCategoryId == category) &&
                    (species == null || byId[o.categoryId]?.let { Species.fromWire(it.species) } == species) &&
                    (kind == null || o.kind == kind?.wire) &&
                    (range == null || (o.date >= range.start && o.date < range.end))
            }
            Text("Операции (${filtered.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.testTag("operationsCount"))
            if (!canEdit) ReadOnlyNote()
            if (filtered.isEmpty()) Text("Операций нет", color = Tabel.colors.secondaryText, modifier = Modifier.testTag("noOperations"))
            for (o in filtered) {
                OperationRow(o, byId[o.categoryId], o.targetCategoryId?.let { byId[it] }, b.slaughterOf(o.id), zone, enabled = canEdit) { openOperation(o.id) }
            }
        }
    }
}
