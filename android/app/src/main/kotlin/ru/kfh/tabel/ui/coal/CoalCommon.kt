package ru.kfh.tabel.ui.coal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalPeriodReport
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.CoalTotals
import ru.kfh.core.coal.DriverDraft
import ru.kfh.core.coal.DriverSettlement
import ru.kfh.core.coal.VehicleType
import ru.kfh.core.coal.fullName
import ru.kfh.core.coal.isPriceAdjusted
import ru.kfh.core.coal.letter
import ru.kfh.core.coal.listTotal
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.home.longDate
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.FarmDatePickerDialog
import ru.kfh.tabel.ui.timesheet.dateText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Colours of the coal module (`CoalTheme`): hired trucks blue, the farm's own truck green. */
object CoalTheme {
    @Composable
    fun hired(): Color = if (isSystemInDarkTheme()) Color(0xFF6BA3FF) else Color(0xFF1A61CC)

    @Composable
    fun own(): Color = Tabel.colors.primary

    @Composable
    fun vehicle(v: String): Color = if (v == VehicleType.HIRED) hired() else own()
}

/** «Наёмная машина» / «Собственная машина КФХ» (`VehicleType.title`). */
fun vehicleTitle(v: String): String = if (v == VehicleType.HIRED) "Наёмная машина" else "Собственная машина КФХ"

/** For table cells: «Наёмная», «Своя» (`VehicleType.shortTitle`). */
fun vehicleShortTitle(v: String): String = if (v == VehicleType.HIRED) "Наёмная" else "Своя"

/** `CoalReportLayout.remainderNote`. */
const val REMAINDER_NOTE = "Остаток после выплат водителям — не чистая прибыль: себестоимость угля и прочие расходы не учтены."


/** A small figure with its caption, for the tops of the screens (`CoalStatTile`). */
@Composable
fun CoalStatTile(title: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground, tag: String? = null) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Tabel.colors.background.copy(alpha = 0.6f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
             modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        Text(title, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText, maxLines = 2)
    }
}

/** The figures of a set of sales: count, tons, revenue, hired and own deliveries (`CoalTotalsHeader`). */
@Composable
fun CoalTotalsHeader(totals: CoalTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoalStatTile("Продаж", "${totals.salesCount}", Modifier.weight(1f))
            CoalStatTile("Продано, т", CoalMath.tons(totals.kilograms), Modifier.weight(1f))
        }
        CoalStatTile("Общая выручка", CoalMath.rubles(totals.revenue), Modifier.fillMaxWidth(), color = Tabel.colors.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoalStatTile("Наёмными машинами", "${totals.hiredDeliveries}", Modifier.weight(1f), color = CoalTheme.hired())
            CoalStatTile("Собственной машиной", "${totals.ownDeliveries}", Modifier.weight(1f), color = CoalTheme.own())
        }
    }
}

/** One sale: time, tons, village, truck, driver, total; the colour strip tells the truck (`CoalSaleCard`). */
@Composable
fun CoalSaleCard(sale: CoalSale, zone: ZoneId, showsDate: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val color = CoalTheme.vehicle(sale.vehicleType)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tabel.colors.card)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .testTag("sale-${sale.id}")
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(6.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(3.dp)).background(color).align(Alignment.CenterVertically))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showsDate) CoalText.dateTime(sale.saleDate, zone) else CoalText.time(sale.saleDate, zone),
                     style = MaterialTheme.typography.bodyMedium, color = Tabel.colors.secondaryText, modifier = Modifier.weight(1f))
                Text(CoalMath.rubles(sale.totalAmount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.testTag("saleTotal-${sale.id}"))
            }
            Text("${CoalMath.tonsText(sale.kilograms)} · ${sale.locationNameSnapshot}", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
            Text(if (sale.vehicleType == VehicleType.HIRED) "Наёмная: ${sale.driverNameSnapshot}" else "Собственная машина КФХ",
                 style = MaterialTheme.typography.bodyMedium, color = color)
            if (sale.isPriceAdjusted) {
                Text("Цена изменена вручную, по прайсу ${CoalMath.rubles(sale.listTotal)}", style = MaterialTheme.typography.bodySmall,
                     color = Tabel.colors.accent)
            }
            if (sale.comment.isNotEmpty()) {
                Text(sale.comment, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
            }
        }
    }
}

/** A rounded tile with a title and a subtitle; filled with [color] when selected (`tile` / `choiceTile`). */
@Composable
fun ChoiceTile(
    title: String, subtitle: String, selected: Boolean, color: Color, tag: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else Tabel.colors.card)
            .border(1.dp, color.copy(alpha = if (selected) 0f else 0.35f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(tag)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        val fg = if (selected) Color.White else MaterialTheme.colorScheme.onBackground
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), maxLines = 2)
    }
}

/** A large round letter and the surname: «[М] Мунгалов», for one-handed choosing (`DriverChoiceButton`). */
@Composable
fun DriverChoiceButton(driver: Driver, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val hired = CoalTheme.hired()
    Row(
        modifier = modifier
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) hired else Tabel.colors.card)
            .border(1.dp, hired.copy(alpha = if (selected) 0f else 0.35f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = "${driver.letter} ${driver.lastName}" }
            .testTag("driver-${driver.lastName}")
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.25f) else hired.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(driver.letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                 color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground)
        }
        Text(driver.lastName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
             color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground)
    }
}

/** A rubles text field: digits with «,», the ₽ sign after it (`RublesField`). */
@Composable
fun RublesField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
        placeholder = { Text(placeholder) }, suffix = { Text("₽", color = Tabel.colors.secondaryText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
        modifier = modifier.testTag(tag),
    )
}

/** A line of the financial totals: label left, amount right (`CoalMoneyRow`). */
@Composable
fun CoalMoneyRow(label: String, amount: Long, bold: Boolean = false, color: Color = MaterialTheme.colorScheme.onBackground, tag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
             modifier = Modifier.weight(1f))
        Text(CoalMath.rubles(amount), style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
             fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, color = color,
             modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

/** A grey caption above a group of fields. */
@Composable
fun CoalCaption(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tabel.colors.secondaryText)
}

/** The lines of a refused form, in the accent colour. */
@Composable
fun ValidationErrors(errors: List<String>) {
    if (errors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in errors) Text(e, color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("error"))
    }
}

// MARK: Dialogs

/** Add or edit a driver: фамилия (required), имя, отчество, телефон, комментарий (`DriverFormView`). */
@Composable
fun DriverFormDialog(vm: CoalViewModel, driver: Driver?, onDismiss: () -> Unit, onSaved: (Driver) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(driver?.let { DriverDraft.from(it) } ?: DriverDraft()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (driver == null) "Новый водитель" else "Водитель") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                NameField("Фамилия", draft.lastName, "driverLastName") { draft = draft.copy(lastName = it) }
                NameField("Имя", draft.firstName, "driverFirstName") { draft = draft.copy(firstName = it) }
                NameField("Отчество", draft.middleName, "driverMiddleName") { draft = draft.copy(middleName = it) }
                if (!draft.isValid) {
                    Text("Фамилия обязательна: по ней строятся кнопки выбора водителя.", style = MaterialTheme.typography.bodySmall,
                         color = Tabel.colors.secondaryText)
                }
                OutlinedTextField(
                    value = draft.phone, onValueChange = { draft = draft.copy(phone = it) }, label = { Text("Телефон") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth().testTag("driverPhone"),
                )
                OutlinedTextField(
                    value = draft.comment, onValueChange = { draft = draft.copy(comment = it) }, label = { Text("Комментарий") },
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isValid && !busy && vm.canEditDrivers,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = vm.saveDriver(driver, draft)
                        busy = false
                        r.fold(onSuccess = { onSaved(it); onDismiss() }, onFailure = { message = it.message ?: "Не получилось" })
                    }
                },
                modifier = Modifier.testTag("driverSave"),
            ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NameField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** «Выплатить водителю»: amount (the whole debt by default), date, comment (`DriverPaymentFormView`). */
@Composable
fun DriverPaymentDialog(vm: CoalViewModel, driver: Driver, debt: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amountText by remember { mutableStateOf(CoalMath.rublesField(maxOf(0, debt))) }
    var date by remember { mutableStateOf(vm.now()) }
    var comment by remember { mutableStateOf("") }
    // Made once per form: a second tap on «Выплатить» is refused instead of paying twice.
    val paymentId = remember { RecordWriter.newId() }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val amount = CoalMath.parseRubles(amountText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выплата водителю") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row { Text("Водитель", modifier = Modifier.weight(1f)); Text(driver.fullName, fontWeight = FontWeight.SemiBold) }
                CoalMoneyRow("Долг сейчас", debt, bold = true, color = CoalTheme.hired())
                RublesField(amountText, { amountText = it }, "Сумма выплаты", "paymentAmount", Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { picking = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Дата выплаты", modifier = Modifier.weight(1f))
                    Text(CoalText.date(date, vm.farm.zone), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("Комментарий") }, singleLine = true,
                                  modifier = Modifier.fillMaxWidth().testTag("paymentComment"))
                if (amount != null && amount > 0 && amount <= debt) {
                    Text("Останется долг: ${CoalMath.rubles(debt - amount)}. Он перейдёт на следующие дни.",
                         style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                }
                message?.let { Text(it, color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (amount ?: 0) > 0 && !busy && vm.canPay,
                onClick = {
                    val a = amount ?: return@TextButton
                    busy = true
                    scope.launch {
                        val r = vm.pay(driver, a, date, comment, paymentId)
                        busy = false
                        if (r == null) onDismiss() else message = r
                    }
                },
                modifier = Modifier.testTag("payConfirm"),
            ) { Text("Выплатить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (picking) {
        FarmDatePickerDialog(vm.farm.dayOf(date), onDismiss = { picking = false }) { d ->
            // The picked day keeps the form's time of day.
            val t = date.atZone(vm.farm.zone).toLocalTime()
            date = d.atTime(t).atZone(vm.farm.zone).toInstant()
        }
    }
}

/** A village: name, delivery price for the buyer, the hired driver's pay (empty = the delivery price) (`LocationFormView`). */
@Composable
fun LocationFormDialog(vm: CoalViewModel, location: DeliveryLocation?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(location?.name.orEmpty()) }
    var deliveryText by remember { mutableStateOf(location?.let { CoalMath.rublesField(it.deliveryPriceKopecks) }.orEmpty()) }
    var driverText by remember { mutableStateOf(location?.driverPaymentKopecks?.let { CoalMath.rublesField(it) }.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (location == null) "Новый пункт" else "Населённый пункт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true,
                                  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                                  modifier = Modifier.fillMaxWidth().testTag("locationName"))
                Text("Доставка для покупателя", style = MaterialTheme.typography.bodyMedium)
                RublesField(deliveryText, { deliveryText = it }, "1000", "locationDelivery", Modifier.fillMaxWidth())
                Text("Оплата наёмному водителю", style = MaterialTheme.typography.bodyMedium)
                RublesField(driverText, { driverText = it }, "как доставка", "locationDriverPay", Modifier.fillMaxWidth())
                Text("Оставьте оплату водителю пустой, чтобы водитель получал всю стоимость доставки.",
                     style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                message?.let { Text(it, color = Tabel.colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && vm.canEditPrices,
                onClick = {
                    val delivery = CoalMath.parseRubles(deliveryText)
                    if (delivery == null) { message = "Укажите стоимость доставки, например 1000"; return@TextButton }
                    val trimmedDriver = driverText.trim()
                    val driverPay: Long? = if (trimmedDriver.isEmpty()) null else CoalMath.parseRubles(trimmedDriver)
                    if (trimmedDriver.isNotEmpty() && driverPay == null) { message = "Неверная оплата водителю"; return@TextButton }
                    busy = true
                    scope.launch {
                        val r = if (location == null) vm.addLocation(name, delivery, driverPay) else vm.updateLocation(location, name, delivery, driverPay)
                        busy = false
                        if (r == null) onDismiss() else message = r
                    }
                },
                modifier = Modifier.testTag("locationSave"),
            ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// MARK: Reports on screen

/** The body of a day or month report: sales table, totals, drivers, villages (month), payments (`CoalReportContent`). */
@Composable
fun CoalReportContent(report: CoalPeriodReport, zone: ZoneId, onPay: ((DriverSettlement) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Продажи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (report.rows.isEmpty()) Text("Продаж нет.", color = Tabel.colors.secondaryText) else CoalSalesTable(report, zone)
            }
        }
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Финансовые итоги", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val t = report.totals
                CoalMoneyRow("А. Общая выручка хозяйства", t.revenue, bold = true, color = Tabel.colors.primary, tag = "totalRevenue")
                CoalMoneyRow("Б. Стоимость проданного угля", t.coalAmount)
                CoalMoneyRow("В. Общая стоимость доставки", t.deliveryAmount)
                CoalMoneyRow("Г. Выплаты наёмным водителям", t.driverPayments, color = CoalTheme.hired())
                CoalMoneyRow("Д. Доход от собственной машины", t.ownVehicleDeliveryRevenue, color = CoalTheme.own())
                HorizontalDivider()
                CoalMoneyRow("Е. Остаток после выплат водителям", t.remainderAfterDrivers, bold = true)
                Text(REMAINDER_NOTE, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                Row {
                    Text("Продано угля", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${CoalMath.tonsText(t.kilograms)}, ${CoalText.sales(t.salesCount)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Взаиморасчёты с водителями", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (report.drivers.isEmpty()) Text("Начислений наёмным водителям нет.", color = Tabel.colors.secondaryText)
                for (d in report.drivers) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(CoalText.deliveries(d.deliveries), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                        }
                        if (d.debtBefore != 0L) CoalMoneyRow("Долг на начало", d.debtBefore)
                        CoalMoneyRow("Начислено", d.accrued)
                        if (d.paid != 0L) CoalMoneyRow("Выплачено", d.paid)
                        CoalMoneyRow("К выплате", d.debtAfter, bold = true, color = CoalTheme.hired(), tag = "toPay-${d.driverId}")
                        if (onPay != null && d.debtAfter > 0) {
                            OutlinedButton(onClick = { onPay(d) }, modifier = Modifier.testTag("pay-${d.driverId}")) {
                                Text("Выплатить водителю", color = CoalTheme.hired())
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (report.drivers.isNotEmpty()) {
                    CoalMoneyRow("ИТОГО К ВЫПЛАТЕ", report.totalDebtAfter, bold = true, color = CoalTheme.hired())
                    Text("Начисленное не считается выплаченным: долг уменьшают только записанные выплаты.",
                         style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                }
            }
        }
        if (report.isMonth && report.locations.isNotEmpty()) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Итоги по населённым пунктам", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    for (l in report.locations) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("${CoalText.sales(l.salesCount)}, ${CoalMath.tonsText(l.kilograms)}",
                                     style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                            }
                            Text(CoalMath.rubles(l.revenue), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        if (report.payments.isNotEmpty()) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выплаты водителям", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    for (p in report.payments) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.driver, style = MaterialTheme.typography.bodyMedium)
                                Text(CoalText.dateTime(p.date, zone) + if (p.comment.isEmpty()) "" else " · ${p.comment}",
                                     style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                            }
                            Text(CoalMath.rubles(p.amount), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

/** №, время, тонн, пункт, машина, водитель, уголь, доставка, итого — scrolls sideways on a phone (`CoalSalesTable`). */
@Composable
fun CoalSalesTable(report: CoalPeriodReport, zone: ZoneId) {
    val titles = listOf("№", if (report.isMonth) "Дата" else "Время", "Тонн", "Населённый пункт", "Машина", "Водитель", "Уголь", "Доставка", "Итого")
    val widths = listOf(28, if (report.isMonth) 112 else 48, 48, 120, 72, 120, 80, 80, 88)
    val small = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, t) in titles.withIndex()) {
                Text(t, style = small, fontWeight = FontWeight.Bold, color = Tabel.colors.secondaryText, modifier = Modifier.widthIn(min = widths[i].dp))
            }
        }
        HorizontalDivider()
        for (r in report.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.number}", style = small, modifier = Modifier.widthIn(min = widths[0].dp))
                Text(if (report.isMonth) CoalText.dateTime(r.date, zone) else CoalText.time(r.date, zone), style = small, modifier = Modifier.widthIn(min = widths[1].dp))
                Text(CoalMath.tons(r.kilograms), style = small, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[2].dp))
                Text(r.location, style = small, modifier = Modifier.widthIn(min = widths[3].dp))
                Text(vehicleShortTitle(r.vehicle), style = small, color = CoalTheme.vehicle(r.vehicle), modifier = Modifier.widthIn(min = widths[4].dp))
                Text(r.driver.ifEmpty { "—" }, style = small, modifier = Modifier.widthIn(min = widths[5].dp))
                Text(CoalMath.rublesNumber(r.coalAmount), style = small, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[6].dp))
                Text(CoalMath.rublesNumber(r.deliveryPrice), style = small, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[7].dp))
                Text(CoalMath.rublesNumber(r.totalAmount) + if (r.listTotal == null) "" else "*", style = small, fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[8].dp))
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Итого", style = small, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = widths[0].dp))
            Spacer(Modifier.widthIn(min = widths[1].dp))
            Text(CoalMath.tons(report.totals.kilograms), style = small, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[2].dp))
            Text(CoalText.sales(report.totals.salesCount), style = small, modifier = Modifier.widthIn(min = widths[3].dp))
            Spacer(Modifier.widthIn(min = widths[4].dp))
            Spacer(Modifier.widthIn(min = widths[5].dp))
            Text(CoalMath.rublesNumber(report.totals.coalAmount), style = small, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[6].dp))
            Text(CoalMath.rublesNumber(report.totals.deliveryAmount), style = small, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[7].dp))
            Text(CoalMath.rublesNumber(report.totals.revenue), style = small, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = widths[8].dp))
        }
    }
}

/** A clickable «День — dd.MM.yyyy» row that opens the farm date picker. */
@Composable
fun DayPickerRow(label: String, day: LocalDate, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { picking = true }.testTag("pickDay").padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(dateText(day), color = Tabel.colors.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("pickedDay"))
    }
    if (picking) FarmDatePickerDialog(day, onDismiss = { picking = false }, onPick = onPick)
}

/** The same instant, with the clock face of the farm. */
fun Instant.farmDay(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()
