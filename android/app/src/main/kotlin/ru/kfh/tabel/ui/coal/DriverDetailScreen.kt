package ru.kfh.tabel.ui.coal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.coal.CoalMath
import ru.kfh.core.coal.CoalText
import ru.kfh.core.coal.fullName
import ru.kfh.core.model.DriverPayment
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import ru.kfh.tabel.ui.timesheet.ErrorDialog

/** A driver (`DriverDetailView`): contacts, earned / paid / debt, «Выплатить водителю», deliveries and payments. */
@Composable
fun DriverDetailScreen(vm: CoalViewModel, driverId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val account by remember(driverId) { vm.driverAccount(driverId) }.collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var deletingPayment by remember { mutableStateOf<DriverPayment?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val zone = vm.farm.zone
    val a = account

    TitledScreen(
        a?.driver?.lastName.orEmpty(), onBack,
        actions = {
            if (vm.canEditDrivers && a != null) {
                TextButton(onClick = { editing = true }, modifier = Modifier.testTag("editDriver")) { Text("Изменить", color = Tabel.colors.primary) }
            }
        },
    ) { padding ->
        if (a == null) return@TitledScreen
        val d = a.driver
        val hired = CoalTheme.hired()
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.fullName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("driverName"))
                    if (d.phone.isNotEmpty()) {
                        Text(d.phone, color = Tabel.colors.primary, modifier = Modifier.clickable(role = Role.Button) {
                            val number = d.phone.filter { it.isDigit() || it == '+' }
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
                        })
                    }
                    if (d.comment.isNotEmpty()) Text(d.comment, color = Tabel.colors.secondaryText)
                    if (d.isArchived) Text("В архиве", color = Tabel.colors.accent)
                }
            }
            SectionHeader("Расчёты")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("Доставок", modifier = Modifier.weight(1f)); Text("${a.balance.deliveries}") }
                    CoalMoneyRow("Заработано", a.balance.earned, tag = "earned")
                    CoalMoneyRow("Выплачено", a.balance.paid, tag = "paid")
                    CoalMoneyRow("Текущий долг", a.balance.debt, bold = true, color = hired, tag = "debt")
                    if (vm.canPay) {
                        OutlinedButton(onClick = { paying = true }, enabled = a.balance.debt > 0, modifier = Modifier.fillMaxWidth().testTag("payDriver")) {
                            Text("Выплатить водителю")
                        }
                    }
                }
            }
            SectionHeader("История доставок")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.sales.isEmpty()) Text("Доставок нет", color = Tabel.colors.secondaryText)
                    for (s in a.sales) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${CoalMath.tonsText(s.kilograms)} · ${s.locationNameSnapshot}")
                                Text(CoalText.dateTime(s.saleDate, zone), style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                            }
                            Text(CoalMath.rubles(s.driverPaymentAmount))
                        }
                    }
                }
            }
            SectionHeader("Выплаты")
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.payments.isEmpty()) Text("Выплат не было", color = Tabel.colors.secondaryText)
                    for (p in a.payments) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(CoalText.dateTime(p.paymentDate, zone))
                                if (p.comment.isNotEmpty()) Text(p.comment, style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText)
                                if (vm.canPay) {
                                    Text("Удалить", color = Tabel.colors.error, style = MaterialTheme.typography.bodySmall,
                                         modifier = Modifier.clickable(role = Role.Button) { deletingPayment = p }.testTag("deletePayment-${p.id}"))
                                }
                            }
                            Text(CoalMath.rubles(p.amount))
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (vm.canEditDrivers) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.isArchived) {
                            TextButton(onClick = { scope.launch { message = vm.restoreDriver(d) } }, modifier = Modifier.testTag("restoreDriver")) { Text("Вернуть из архива") }
                        } else {
                            TextButton(onClick = { scope.launch { message = vm.archiveDriver(d) } }, modifier = Modifier.testTag("archiveDriver")) { Text("Убрать в архив") }
                        }
                        Text(
                            "Архив скрывает водителя из выбора в новой продаже; его доставки, выплаты и долг сохраняются. " +
                                "Удалить (кнопка «Удалить» в списке) можно только водителя без доставок и выплат.",
                            style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText,
                        )
                    }
                }
            }
        }
    }

    if (editing && a != null) DriverFormDialog(vm, a.driver, onDismiss = { editing = false })
    if (paying && a != null) DriverPaymentDialog(vm, a.driver, a.balance.debt) { paying = false }
    deletingPayment?.let { p ->
        ConfirmDialog(
            title = "Удалить выплату?",
            message = "${CoalMath.rubles(p.amount)} от ${CoalText.date(p.paymentDate, zone)}. Долг перед водителем увеличится.",
            confirm = "Удалить",
            onConfirm = { deletingPayment = null; scope.launch { message = vm.deletePayment(p) } },
            onDismiss = { deletingPayment = null },
        )
    }
    ErrorDialog(message) { message = null }
}

@Composable
fun SectionHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tabel.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
}
