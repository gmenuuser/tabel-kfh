package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalSaleDraft
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.LiveFarmToday
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote
import java.time.LocalDate

private val QUICK_TONS = listOf("0,5", "1", "1,5", "2", "2,5", "3")

/**
 * «Продажа угля» (`CoalSaleScreen`): one screen for the whole day. The price and today's figures on top, the new
 * sale form (tons, village, truck), the calculation, «СОХРАНИТЬ ПРОДАЖУ», today's sales, «ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ».
 */
@Composable
fun CoalSaleScreen(
    vm: CoalViewModel,
    onBack: () -> Unit,
    openSummary: (LocalDate) -> Unit,
    openHistory: () -> Unit,
    openDrivers: () -> Unit,
    openPrices: () -> Unit,
    openSale: (String) -> Unit,
    closeDay: (LocalDate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by vm.todayState.collectAsState(initial = null)
    val locations by vm.activeLocations.collectAsState(initial = emptyList())
    val drivers by vm.activeDrivers.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<CoalSaleDraft?>(null) }
    var showErrors by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var addingDriver by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<String?>(null) }
    var confirmingReopen by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canSell = vm.canSell

    LiveFarmToday(vm, vm::refreshToday, vm::millisUntilNextFarmDay)
    // The settings row and the owner's villages are made on the first opening (`CoalSettingsService.current()`).
    LaunchedEffect(vm) { message = vm.ensureDefaults() }
    val s = state
    // A new day at the farm (or the first state): a clean form. A new price set in «Цены» applies to the form being typed.
    LaunchedEffect(s?.day) { if (s != null) draft = vm.newDraft() }
    LaunchedEffect(s?.pricePerTon) { s?.let { st -> draft = draft?.copy(pricePerTon = st.pricePerTon) } }

    fun save() {
        val d = draft ?: return
        showErrors = true
        if (!d.isValid || saving) return
        saving = true
        scope.launch {
            val r = vm.saveSale(d)
            saving = false
            r.fold(
                onSuccess = { sale ->
                    draft = vm.newDraft()
                    showErrors = false
                    savedNote = "Сохранено: ${CoalMath.tonsText(sale.kilograms)}, ${sale.locationNameSnapshot}, ${CoalMath.rubles(sale.totalAmount)}"
                },
                onFailure = { message = it.message ?: "Не получилось" },
            )
        }
    }

    TitledScreen(
        "Продажа угля", onBack,
        actions = {
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = Tabel.colors.primary)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Итоги дня и выплаты") }, onClick = { more = false; openSummary(vm.todayFlow.value) },
                                     modifier = Modifier.testTag("openSummary"))
                    DropdownMenuItem(text = { Text("История продаж") }, onClick = { more = false; openHistory() }, modifier = Modifier.testTag("openHistory"))
                    DropdownMenuItem(text = { Text("Водители") }, onClick = { more = false; openDrivers() }, modifier = Modifier.testTag("openDrivers"))
                    DropdownMenuItem(text = { Text("Цены и населённые пункты") }, onClick = { more = false; openPrices() }, modifier = Modifier.testTag("openPrices"))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (s == null) return@TitledScreen
            Header(s)
            if (!canSell) ReadOnlyNote()
            val d = draft
            if (s.isClosed) {
                ClosedBanner(s, vm.farm.zone, vm.canCloseDay) { confirmingReopen = true }
            } else if (d != null) {
                SaleForm(
                    d = d, locations = locations, drivers = drivers, enabled = canSell, showErrors = showErrors, saving = saving,
                    savedNote = savedNote, onChange = { draft = it }, addDriver = { addingDriver = true }, save = ::save,
                )
            }
            SalesList(s, vm, canSell, onOpen = { id ->
                if (s.isClosed) message = ru.kfh.core.coal.CoalError.DayClosed(s.day).message else openSale(id)
            }, onCancel = { cancelling = it })
            if (!s.isClosed && vm.canCloseDay) {
                BigButton("ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ", onClick = { closeDay(s.day) }, color = Tabel.colors.accent,
                          modifier = Modifier.padding(top = 4.dp).testTag("closeDay"))
            }
        }
    }

    if (addingDriver) {
        DriverFormDialog(vm, null, onDismiss = { addingDriver = false }) { d -> draft = draft?.chooseDriver(d) }
    }
    cancelling?.let { id ->
        val sale = s?.sales?.firstOrNull { it.id == id }
        if (sale == null) {
            cancelling = null
        } else {
            ConfirmDialog(
                title = "Отменить продажу?",
                message = "${CoalMath.tonsText(sale.kilograms)}, ${sale.locationNameSnapshot}, ${CoalMath.rubles(sale.totalAmount)}",
                confirm = "Отменить продажу",
                onConfirm = { cancelling = null; scope.launch { message = vm.deleteSale(sale) } },
                onDismiss = { cancelling = null },
            )
        }
    }
    if (confirmingReopen) {
        ReopenDialog(
            title = "Открыть день для исправлений?",
            message = "Продажи дня снова можно будет менять. После исправлений завершите день ещё раз.",
            onConfirm = { confirmingReopen = false; s?.let { st -> scope.launch { message = vm.reopenDay(st.day) } } },
            onDismiss = { confirmingReopen = false },
        )
    }
    ErrorDialog(message) { message = null }
}

/** «Открыть день …?» with «Открыть день» and «Отмена». */
@Composable
fun ReopenDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm, modifier = Modifier.testTag("reopenConfirm")) { Text("Открыть день", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Header(s: CoalViewModel.DayState) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Цена угля", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${CoalMath.rubles(s.pricePerTon)} за тонну", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.testTag("pricePerTon"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoalStatTile("Продаж сегодня", "${s.totals.salesCount}", Modifier.weight(1f), tag = "salesCount")
                CoalStatTile("Выручка за день", CoalMath.rubles(s.totals.revenue), Modifier.weight(1f), color = Tabel.colors.primary, tag = "dayRevenue")
            }
        }
    }
}

@Composable
private fun ClosedBanner(s: CoalViewModel.DayState, zone: java.time.ZoneId, canReopen: Boolean, reopen: () -> Unit) {
    Card(modifier = Modifier.testTag("closedBanner")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tabel.colors.primary)
                Text("Рабочий день завершён", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tabel.colors.primary)
            }
            s.closedAt?.let {
                Text("Закрыт в ${CoalText.time(it, zone)}. Новые продажи и исправления — после открытия дня.",
                     style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
            }
            if (canReopen) {
                BigButton("Открыть день для исправлений", onClick = reopen, color = Tabel.colors.accent, modifier = Modifier.testTag("reopenDay"))
            }
        }
    }
}

/** The «Новая продажа» form: 1. Сколько тонн, 2. Куда везти, 3. Чья машина, the calculation, «СОХРАНИТЬ ПРОДАЖУ». */
@Composable
private fun SaleForm(
    d: CoalSaleDraft,
    locations: List<DeliveryLocation>,
    drivers: List<Driver>,
    enabled: Boolean,
    showErrors: Boolean,
    saving: Boolean,
    savedNote: String?,
    onChange: (CoalSaleDraft) -> Unit,
    addDriver: () -> Unit,
    save: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Новая продажа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoalCaption("1. Сколько тонн")
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tabel.colors.card).padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = d.tonsText, onValueChange = { onChange(d.copy(tonsText = it)) }, singleLine = true, enabled = enabled,
                    placeholder = { Text("0", fontSize = 36.sp, fontWeight = FontWeight.Bold) },
                    textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).testTag("tonsField"),
                )
                Text("т", style = MaterialTheme.typography.headlineMedium, color = Tabel.colors.secondaryText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (t in QUICK_TONS) {
                    val selected = d.kilograms == CoalMath.parseTons(t)
                    Box(
                        modifier = Modifier
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Tabel.colors.primary else Tabel.colors.card)
                            .clickable(enabled = enabled, role = Role.Button) { onChange(d.copy(tonsText = t)) }
                            .semantics { this.selected = selected }
                            .testTag("tons-$t")
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$t т", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                             color = if (selected) Color.White else Tabel.colors.primary)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoalCaption("2. Куда везти")
            if (locations.isEmpty()) {
                Text("Добавьте населённые пункты: «Ещё» → «Цены и населённые пункты».", color = Tabel.colors.secondaryText)
            }
            TwoColumns(locations.size) { i ->
                val l = locations[i]
                ChoiceTile(l.name, CoalMath.rubles(l.deliveryPriceKopecks), selected = d.locationName == l.name, color = Tabel.colors.primary,
                           tag = "location-${l.name}", modifier = Modifier.weight(1f), enabled = enabled) { onChange(d.chooseLocation(l)) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoalCaption("3. Чья машина")
            val own = CoalTheme.own()
            TwoColumns(drivers.size + 2) { i ->
                when (i) {
                    0 -> ChoiceTile("СВОЯ МАШИНА", "без оплаты водителю", selected = d.vehicle == VehicleType.OWN, color = own, tag = "vehicle-own",
                                    modifier = Modifier.weight(1f), enabled = enabled) {
                        onChange(d.copy(vehicle = VehicleType.OWN, driverId = null, driverName = ""))
                    }
                    drivers.size + 1 -> Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Tabel.colors.card)
                            .clickable(enabled = enabled, role = Role.Button, onClick = addDriver)
                            .semantics(mergeDescendants = true) { }
                            .testTag("addDriver"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Добавить водителя", tint = Tabel.colors.primary)
                        Text("Водитель", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tabel.colors.primary)
                    }
                    else -> {
                        val driver = drivers[i - 1]
                        DriverChoiceButton(driver, selected = d.vehicle == VehicleType.HIRED && d.driverId == driver.id,
                                           modifier = Modifier.weight(1f), enabled = enabled) { onChange(d.chooseDriver(driver)) }
                    }
                }
            }
        }

        Calculation(d)

        if (showErrors && !d.isValid) ValidationErrors(screenErrors(d))

        BigButton("СОХРАНИТЬ ПРОДАЖУ", onClick = save, enabled = enabled, busy = saving, modifier = Modifier.testTag("saveSale"))
        savedNote?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tabel.colors.primary)
                Text(it, color = Tabel.colors.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("savedNote"))
            }
        }
    }
}

/** [count] items in rows of two, each [item] sized with `Modifier.weight(1f)` inside the row. */
@Composable
fun TwoColumns(count: Int, item: @Composable androidx.compose.foundation.layout.RowScope.(Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var i = 0
        while (i < count) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(i)
                if (i + 1 < count) item(i + 1) else androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            i += 2
        }
    }
}

@Composable
private fun Calculation(v: CoalSaleDraft) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text("Уголь: ${CoalMath.tonsText(v.kilograms ?: 0)} × ${CoalMath.rubles(v.pricePerTon)}", modifier = Modifier.weight(1f))
                Text(CoalMath.rubles(v.coalAmount))
            }
            Row {
                Text(if (v.locationName.isEmpty()) "Доставка" else "Доставка: ${v.locationName}", modifier = Modifier.weight(1f))
                Text(CoalMath.rubles(v.deliveryPrice))
            }
            if (v.vehicle == VehicleType.HIRED && v.driverId != null) {
                Row {
                    Text("Заработок водителя ${v.driverName}", style = MaterialTheme.typography.bodyMedium, color = CoalTheme.hired(), modifier = Modifier.weight(1f))
                    Text(CoalMath.rubles(v.driverPayment), style = MaterialTheme.typography.bodyMedium, color = CoalTheme.hired())
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ИТОГО", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(CoalMath.rubles(v.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                     color = Tabel.colors.primary, modifier = Modifier.testTag("saleTotal"))
            }
        }
    }
}

/** The draft's messages in the words of this screen (the truck is chosen with one row of buttons). */
private fun screenErrors(d: CoalSaleDraft): List<String> =
    d.validationErrors.map {
        when (it) {
            "Выберите водителя наёмной машины" -> "Выберите машину: свою или водителя"
            "Укажите оплату водителю, например 1000" -> "Выберите, куда везти"
            else -> it
        }
    }.distinct()

@Composable
private fun SalesList(s: CoalViewModel.DayState, vm: CoalViewModel, canSell: Boolean, onOpen: (String) -> Unit, onCancel: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Продажи за сегодня", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (s.sales.isEmpty()) Text("Сегодня продаж пока нет.", color = Tabel.colors.secondaryText, modifier = Modifier.testTag("noSales"))
        for (sale in s.sales) {
            // A viewer opens the card too: the form shows the sale read-only (`ReadOnlyNote`), as the site does.
            CoalSaleCard(sale, vm.farm.zone, onClick = { onOpen(sale.id) })
            if (!s.isClosed && canSell) {
                Text("Отменить продажу", color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.clickable(role = Role.Button) { onCancel(sale.id) }.testTag("cancel-${sale.id}").padding(horizontal = 14.dp, vertical = 2.dp))
            }
        }
        if (s.sales.isNotEmpty() && !s.isClosed && canSell) {
            Text("Нажмите на продажу, чтобы исправить или отменить её.", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
        }
    }
}
