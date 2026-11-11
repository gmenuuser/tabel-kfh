package ru.kfh.tabel.ui.timesheet

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kfh.core.timesheet.RuMonths
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import java.time.YearMonth

/**
 * «Старые отметки (архив)» (`OldMarksView`): the status codes (Я, В, Б…) kept from before the timesheet of hours,
 * by month, read only. Nothing here is ever changed or deleted: the records are history, the iPhone and the site
 * still read them, and they are not hours.
 */
@Composable
fun OldMarksScreen(vm: TimesheetViewModel, onBack: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(vm.today())) }
    var pickingMonth by remember { mutableStateOf(false) }
    val marks by remember(month) { vm.oldMarks(month) }.collectAsState(initial = null)

    TitledScreen("Старые отметки", onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pickingMonth = true }
                            .testTag("pickMonth").padding(vertical = 6.dp),
                    ) {
                        Text("Месяц", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(RuMonths.title(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                             color = Tabel.colors.primary, modifier = Modifier.testTag("oldMarksMonth"))
                    }
                    Text(
                        "Здесь видны отметки старого формата (Я, В, Б…). Они сохранены как история и в табель часов не входят.",
                        style = MaterialTheme.typography.bodySmall, color = Tabel.colors.secondaryText,
                    )
                }
            }
            val m = marks
            if (m != null) {
                Text("ОТМЕТКИ (${m.total})", style = MaterialTheme.typography.labelMedium, color = Tabel.colors.secondaryText,
                     modifier = Modifier.padding(start = 4.dp).testTag("oldMarksTotal"))
                Card {
                    Column {
                        if (m.lines.isEmpty()) {
                            Text("В этом месяце старых отметок нет", color = Tabel.colors.secondaryText)
                        }
                        for ((index, line) in m.lines.withIndex()) {
                            if (index > 0) HorizontalDivider(color = separatorColor())
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(line.days, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                                     color = Tabel.colors.secondaryText)
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingMonth) {
        MonthYearDialog(month, vm.today().year, onDismiss = { pickingMonth = false }) {
            month = it
            pickingMonth = false
        }
    }
}
