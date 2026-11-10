package ru.kfh.tabel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.kfh.core.roles.Module
import ru.kfh.core.roles.Role
import ru.kfh.core.roles.modules
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.ui.common.Card
import ru.kfh.tabel.ui.common.ModuleButton
import ru.kfh.tabel.ui.common.SyncStatusLine
import ru.kfh.tabel.ui.theme.Tabel
import java.time.LocalDate
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

/** The big buttons of the main screen for [role], in the iPhone's order (`Roles.destinations`). Never settings. */
fun homeButtons(role: Role): List<Module> {
    val shown = role.modules()
    return listOf(Module.timesheet, Module.coal, Module.livestock, Module.agro, Module.reports).filter { it in shown }
}

/** «Табель, продажи угля, выплаты водителям, скот и забой — PDF и Excel», only what [role] sees (`reportsSubtitle`). */
fun reportsSubtitle(role: Role): String {
    val m = role.modules()
    val parts = buildList {
        if (Module.timesheet in m) add("Табель")
        if (Module.coal in m) add("продажи угля, выплаты водителям")
        if (Module.livestock in m) add("скот и забой")
    }
    return (parts.joinToString(", ") + " — PDF и Excel").capitalizedFirst()
}

/** The title of a module's screen (the iPhone's `navigationTitle`). */
fun moduleTitle(m: Module): String = when (m) {
    Module.timesheet -> "Табель работников"
    Module.coal -> "Продажа угля"
    Module.livestock -> "Скот и забой"
    Module.agro -> "Агрономические калькуляторы"
    Module.reports -> "Отчёты"
    Module.settings -> "Настройки"
}

/** «Воскресенье, 8 ноября 2026» (`CoalText.longDate(...).capitalizedFirst`). */
fun longDate(d: LocalDate): String {
    val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                        "октября", "ноября", "декабря")
    return "${weekdays[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}".capitalizedFirst()
}

private fun String.capitalizedFirst(): String = if (isEmpty()) this else substring(0, 1).uppercase(RU) + substring(1)

/**
 * Главный экран (`MainScreen`): the farm, today, the role (not for the owner), the sync line and the big buttons of
 * the modules the role sees; the gear opens «Настройки» for every role.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    me: Me,
    today: LocalDate,
    status: SyncStatus,
    onSync: () -> Unit,
    open: (Module) -> Unit,
) {
    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Главная", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { open(Module.settings) }, modifier = Modifier.testTag("openSettings")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = Tabel.colors.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(me.farmName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                         color = Tabel.colors.primary, maxLines = 2, modifier = Modifier.testTag("farmName"))
                    Text(longDate(today), style = MaterialTheme.typography.titleMedium)
                    if (me.role != Role.owner) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Tabel.colors.primary,
                                 modifier = Modifier.size(18.dp))
                            Text("Ваша роль: ${me.role.title.lowercase(RU)}",
                                 style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                 color = Tabel.colors.primary)
                        }
                    }
                }
            }
            SyncStatusLine(status, me.role, onSync)
            val shown = homeButtons(me.role)
            if (shown.isEmpty()) {
                Card {
                    Text("Для вашей роли в хозяйстве нет доступных разделов. Обратитесь к владельцу.",
                         color = Tabel.colors.secondaryText)
                }
            }
            for (m in shown) {
                val look = look(m)
                ModuleButton(
                    title = buttonTitle(m),
                    subtitle = if (m == Module.reports) reportsSubtitle(me.role) else subtitle(m),
                    icon = look.first,
                    color = look.second,
                    tag = "open" + m.name.replaceFirstChar { it.uppercase() },
                    onClick = { open(m) },
                )
            }
        }
    }
}

private fun buttonTitle(m: Module): String = when (m) {
    Module.timesheet -> "ТАБЕЛЬ РАБОТНИКОВ"
    Module.coal -> "ПРОДАЖА УГЛЯ"
    Module.livestock -> "СКОТ И ЗАБОЙ"
    Module.agro -> "АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ"
    Module.reports -> "ОТЧЁТЫ"
    Module.settings -> "НАСТРОЙКИ"
}

private fun subtitle(m: Module): String = when (m) {
    Module.timesheet -> "Отметки за день, табель за месяц, сотрудники"
    Module.coal -> "Новая продажа, продажи за сегодня, итоги дня"
    Module.livestock -> "Поголовье, забой, приплод, история"
    Module.agro -> "Посев, удобрения, опрыскивание, урожай и другие"
    Module.reports, Module.settings -> ""
}

@Suppress("DEPRECATION") // Icons.Filled.List: the auto-mirrored one is the same picture.
@Composable
private fun look(m: Module): Pair<ImageVector, Color> = when (m) {
    Module.timesheet -> Icons.Filled.Person to Tabel.colors.primary
    Module.coal -> Icons.Filled.ShoppingCart to Tabel.colors.accent
    Module.livestock -> Icons.Filled.Favorite to Tabel.colors.livestock
    Module.agro -> Icons.Filled.Build to Tabel.colors.agro
    Module.reports, Module.settings -> Icons.Filled.List to Tabel.colors.reports
}
