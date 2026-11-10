package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalRules
import ru.kfh.core.coal.CoalSaleDraft
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.model.CoalSale
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.FarmDatePickerDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote
import java.time.LocalTime

private val QUICK_TONS = listOf("0,5", "1", "1,5", "2", "2,5", "3")

/**
 * A saved sale («Продажа», `CoalSaleFormView`): tons, village, truck, driver, the amounts recomputed on every
 * change, a hand correction of the price, comment, date and time, «СОХРАНИТЬ ПРОДАЖУ», «Отменить продажу».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoalSaleFormScreen(vm: CoalViewModel, saleId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sale by remember(saleId) { vm.sales.map { list -> list.firstOrNull { it.id.equals(saleId, ignoreCase = true) } } }.collectAsState(initial = null)
    val locations by vm.activeLocations.collectAsState(initial = emptyList())
    val allDrivers by vm.drivers.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<CoalSaleDraft?>(null) }
    var loaded by remember { mutableStateOf<CoalSale?>(null) }
    var search by remember { mutableStateOf("") }
    var addingDriver by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canSell

    LaunchedEffect(sale) {
        val s = sale
        if (draft == null && s != null) {
            loaded = s
            draft = CoalSaleDraft.from(s)
        }
    }

    fun save() {
        val d = draft ?: return
        val s = loaded ?: return
        showErrors = true
        if (!d.isValid || saving) return
        saving = true
        scope.launch {
            val r = vm.updateSale(s, d)
            saving = false
            if (r == null) onDone() else message = r
        }
    }

    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Продажа", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onDone, modifier = Modifier.testTag("cancel")) { Text("Отмена", color = Tabel.colors.primary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
    ) { padding ->
        val d = draft ?: return@Scaffold
        val zone = vm.farm.zone
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!canEdit) ReadOnlyNote()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CoalCaption("Количество угля, тонн")
                OutlinedTextField(
                    value = d.tonsText, onValueChange = { draft = d.copy(tonsText = it) }, singleLine = true, enabled = canEdit,
                    placeholder = { Text("Например, 1,5") }, suffix = { Text("т", color = Tabel.colors.secondaryText) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("tonsField"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (t in QUICK_TONS) {
                        val selected = d.kilograms == CoalMath.parseTons(t)
                        OutlinedButton(onClick = { draft = d.copy(tonsText = t) }, enabled = canEdit) {
                            Text("$t т", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CoalCaption("Населённый пункт")
                if (locations.isEmpty() && d.locationName.isEmpty()) {
                    Text("Добавьте населённые пункты на вкладке «Цены».", color = Tabel.colors.secondaryText)
                }
                // A saved sale's village that was removed from the list stays choosable as it was.
                val gone = d.locationName.isNotEmpty() && locations.none { it.name == d.locationName }
                val count = locations.size + if (gone) 1 else 0
                TwoColumns(count) { i ->
                    if (gone && i == 0) {
                        ChoiceTile(d.locationName, "доставка " + CoalMath.rubles(d.listDeliveryPrice), selected = true, color = Tabel.colors.primary,
                                   tag = "location-${d.locationName}", modifier = Modifier.weight(1f), enabled = canEdit) {}
                    } else {
                        val l = locations[i - if (gone) 1 else 0]
                        ChoiceTile(l.name, "доставка " + CoalMath.rubles(l.deliveryPriceKopecks), selected = d.locationName == l.name,
                                   color = Tabel.colors.primary, tag = "location-${l.name}", modifier = Modifier.weight(1f), enabled = canEdit) {
                            draft = d.chooseLocation(l)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CoalCaption("Машина")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceTile("Наёмная", "водителю начисляется доставка", selected = d.vehicle == VehicleType.HIRED, color = CoalTheme.hired(),
                               tag = "vehicle-hired", modifier = Modifier.weight(1f), enabled = canEdit) { draft = d.copy(vehicle = VehicleType.HIRED) }
                    ChoiceTile("Собственная КФХ", "доставка — доход хозяйства", selected = d.vehicle == VehicleType.OWN, color = CoalTheme.own(),
                               tag = "vehicle-own", modifier = Modifier.weight(1f), enabled = canEdit) { draft = d.copy(vehicle = VehicleType.OWN) }
                }
            }

            if (d.vehicle == VehicleType.HIRED) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoalCaption("Водитель")
                    val active = allDrivers.filter { !it.isArchived }
                    if (active.size > 6) {
                        OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                                          placeholder = { Text("Поиск по фамилии") }, modifier = Modifier.fillMaxWidth().testTag("driverSearch"))
                    }
                    val shown = CoalRules.activeDrivers(allDrivers, search)
                    TwoColumns(shown.size) { i ->
                        val driver = shown[i]
                        DriverChoiceButton(driver, selected = d.driverId == driver.id, modifier = Modifier.weight(1f), enabled = canEdit) {
                            draft = d.chooseDriver(driver)
                        }
                    }
                    val chosen = d.driverId
                    if (chosen != null && active.none { it.id == chosen }) {
                        Text("Выбран: ${d.driverName} (в архиве)", style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText)
                    }
                    OutlinedButton(onClick = { addingDriver = true }, enabled = canEdit, modifier = Modifier.fillMaxWidth().testTag("addDriver")) {
                        Text("Добавить водителя")
                    }
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Оплата водителю за доставку", modifier = Modifier.weight(1f))
                            RublesField(d.driverPaymentText, { draft = d.copy(driverPaymentText = it) }, "1000", "driverPayment",
                                        Modifier.width(150.dp), enabled = canEdit)
                        }
                    }
                }
            }

            SummaryCard(d, canEdit) { draft = it }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CoalCaption("Комментарий")
                OutlinedTextField(value = d.comment, onValueChange = { draft = d.copy(comment = it) }, enabled = canEdit, maxLines = 4,
                                  placeholder = { Text("Например, покупатель или адрес") }, modifier = Modifier.fillMaxWidth().testTag("comment"))
            }

            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = canEdit, role = Role.Button) { pickingDate = true }.testTag("saleDate"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Дата и время", modifier = Modifier.weight(1f))
                    Text(CoalText.dateTime(d.saleDate, zone), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }

            if (showErrors && !d.isValid) ValidationErrors(d.validationErrors)

            if (canEdit) {
                BigButton("СОХРАНИТЬ ПРОДАЖУ", onClick = ::save, busy = saving, modifier = Modifier.testTag("saveSale"))
                Text("Отменить продажу", color = Tabel.colors.error, style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmingDelete = true }.testTag("deleteSale").padding(vertical = 8.dp),
                     textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }

    if (addingDriver) {
        DriverFormDialog(vm, null, onDismiss = { addingDriver = false }) { d -> draft = draft?.chooseDriver(d) }
    }
    if (pickingDate) {
        val d = draft
        if (d != null) {
            FarmDatePickerDialog(vm.farm.dayOf(d.saleDate), onDismiss = { pickingDate = false }) { day ->
                // The picked day keeps the sale's time of day (the iPhone's wheel changes both; Android's picker only the day).
                val time: LocalTime = d.saleDate.atZone(vm.farm.zone).toLocalTime()
                draft = d.copy(saleDate = day.atTime(time).atZone(vm.farm.zone).toInstant())
            }
        }
    }
    if (confirmingDelete) {
        ConfirmDialog(
            title = "Отменить продажу?",
            message = "Продажа исчезнет из итогов дня и из расчётов с водителем.",
            confirm = "Отменить продажу",
            onConfirm = {
                confirmingDelete = false
                val s = loaded ?: return@ConfirmDialog
                scope.launch {
                    val r = vm.deleteSale(s)
                    if (r == null) onDone() else message = r
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    ErrorDialog(message) { message = null }
}

/** The amounts, «ИТОГО К ОПЛАТЕ», the truck line and «Изменить цену вручную» (`summaryCard`). */
@Composable
private fun SummaryCard(v: CoalSaleDraft, enabled: Boolean, onChange: (CoalSaleDraft) -> Unit) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text("Уголь", modifier = Modifier.weight(1f))
                Text("${CoalMath.tonsText(v.kilograms ?: 0)} × ${CoalMath.rubles(v.pricePerTon)}", style = MaterialTheme.typography.bodyMedium,
                     color = Tabel.colors.secondaryText)
            }
            CoalMoneyRow("Стоимость угля", v.coalAmount)
            CoalMoneyRow("Стоимость доставки", v.deliveryPrice)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ИТОГО К ОПЛАТЕ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(CoalMath.rubles(v.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Tabel.colors.primary,
                     modifier = Modifier.testTag("saleTotal"))
            }
            if (v.isManual && v.isAdjusted) {
                Text("По прайсу: ${CoalMath.rubles(v.listTotal)}", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.accent)
            }
            val color = CoalTheme.vehicle(v.vehicle)
            if (v.vehicle == VehicleType.HIRED) {
                Row {
                    Text(if (v.driverId == null) "Водитель не выбран" else "Водитель: ${v.driverName}", style = MaterialTheme.typography.bodyMedium,
                         color = color, modifier = Modifier.weight(1f))
                    Text("к выплате ${CoalMath.rubles(v.driverPayment)}", style = MaterialTheme.typography.bodyMedium, color = color)
                }
            } else {
                Text("Собственная машина КФХ — выплат водителю нет", style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Изменить цену вручную", modifier = Modifier.weight(1f))
                Switch(checked = v.isManual, enabled = enabled, onCheckedChange = { on -> onChange(if (on) v.startManual() else v.copy(isManual = false)) },
                       modifier = Modifier.testTag("manualPrice"))
            }
            if (v.isManual) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Уголь, факт", modifier = Modifier.weight(1f))
                    RublesField(v.manualCoalText, { onChange(v.copy(manualCoalText = it)) }, "0", "manualCoal", Modifier.width(160.dp), enabled = enabled)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Доставка, факт", modifier = Modifier.weight(1f))
                    RublesField(v.manualDeliveryText, { onChange(v.copy(manualDeliveryText = it)) }, "0", "manualDelivery", Modifier.width(160.dp), enabled = enabled)
                }
            }
        }
    }
}
