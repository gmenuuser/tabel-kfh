package ru.kfh.tabel.ui.coal

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog
import ru.kfh.tabel.ui.timesheet.ReadOnlyNote

/** «Цены и населённые пункты» (`CoalPricesView`): the coal price per ton and the villages with their delivery prices and drivers' pay. */
@Composable
fun CoalSettingsScreen(vm: CoalViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val price by vm.pricePerTon.collectAsState(initial = null)
    val locations by vm.activeLocations.collectAsState(initial = emptyList())
    var priceText by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DeliveryLocation?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DeliveryLocation?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val canEdit = vm.canEditPrices

    LaunchedEffect(vm) { message = vm.ensureDefaults() }
    LaunchedEffect(price) { if (priceText == null) price?.let { priceText = CoalMath.rublesField(it) } }

    TitledScreen("Цены и населённые пункты", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!canEdit) ReadOnlyNote()
            SectionHeader("Уголь")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Цена угля за 1 тонну", modifier = Modifier.weight(1f))
                        RublesField(priceText.orEmpty(), { priceText = it; saved = false }, "6500", "priceField", Modifier.width(160.dp), enabled = canEdit)
                    }
                    val parsed = CoalMath.parseRubles(priceText.orEmpty())
                    if (canEdit) {
                        OutlinedButton(
                            onClick = {
                                val k = parsed ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    val r = vm.setPricePerTon(k)
                                    busy = false
                                    if (r == null) saved = true else message = r
                                }
                            },
                            enabled = parsed != null && parsed != price && !busy,
                            modifier = Modifier.fillMaxWidth().testTag("savePrice"),
                        ) { Text("Сохранить цену") }
                    }
                    if (saved) Text("Цена сохранена", color = Tabel.colors.primary, modifier = Modifier.testTag("priceSaved"))
                    Text("Новая цена действует для новых продаж. Сохранённые продажи и отчёты остаются по своим ценам.",
                         style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                }
            }
            SectionHeader("Доставка по населённым пунктам")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in locations) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = canEdit, role = Role.Button) { editing = l }.testTag("locationRow-${l.name}").padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(l.name)
                                l.driverPaymentKopecks?.let {
                                    Text("водителю ${CoalMath.rubles(it)}", style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                                }
                                if (canEdit) {
                                    Text("Удалить", color = Tabel.colors.error, style = MaterialTheme.typography.bodySmall,
                                         modifier = Modifier.clickable(role = Role.Button) { removing = l }.testTag("removeLocation-${l.name}"))
                                }
                            }
                            Text(CoalMath.rubles(l.deliveryPriceKopecks))
                        }
                        HorizontalDivider()
                    }
                    if (canEdit) {
                        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().testTag("addLocation")) { Text("Добавить населённый пункт") }
                    }
                    Text(
                        "Нажмите на пункт, чтобы изменить цену доставки или оплату водителю; «Удалить» убирает его из списка. " +
                            "По умолчанию наёмный водитель получает всю стоимость доставки.",
                        style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText,
                    )
                }
            }
        }
    }

    editing?.let { l -> LocationFormDialog(vm, l) { editing = null } }
    if (adding) LocationFormDialog(vm, null) { adding = false }
    removing?.let { l ->
        ConfirmDialog(
            title = "Удалить населённый пункт?",
            message = "«${l.name}» исчезнет из новой продажи. Прошлые продажи сохранят название и цены.",
            confirm = "Удалить",
            onConfirm = { removing = null; scope.launch { message = vm.removeLocation(l) } },
            onDismiss = { removing = null },
        )
    }
    ErrorDialog(message) { message = null }
}
