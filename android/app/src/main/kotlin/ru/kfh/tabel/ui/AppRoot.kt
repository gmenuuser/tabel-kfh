package ru.kfh.tabel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.kfh.core.agro.AgroKind
import ru.kfh.core.roles.Module
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.AppGraph
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.ui.agro.AgroCalculatorScreen
import ru.kfh.tabel.ui.agro.AgroHistoryScreen
import ru.kfh.tabel.ui.agro.AgroHomeScreen
import ru.kfh.tabel.ui.coal.CloseDayScreen
import ru.kfh.tabel.ui.coal.CoalDaySummaryScreen
import ru.kfh.tabel.ui.coal.CoalHistoryScreen
import ru.kfh.tabel.ui.coal.CoalSaleFormScreen
import ru.kfh.tabel.ui.coal.CoalSaleScreen
import ru.kfh.tabel.ui.coal.CoalSettingsScreen
import ru.kfh.tabel.ui.coal.DriverDetailScreen
import ru.kfh.tabel.ui.coal.DriversScreen
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.home.HomeScreen
import ru.kfh.tabel.ui.home.LoadingScreen
import ru.kfh.tabel.ui.home.moduleTitle
import ru.kfh.tabel.ui.livestock.HerdScreen
import ru.kfh.tabel.ui.livestock.LivestockHistoryScreen
import ru.kfh.tabel.ui.livestock.LivestockHomeScreen
import ru.kfh.tabel.ui.livestock.OperationFormScreen
import ru.kfh.tabel.ui.livestock.SlaughterScreen
import ru.kfh.tabel.ui.settings.SettingsScreen
import ru.kfh.tabel.ui.signin.SignInScreen
import ru.kfh.tabel.ui.timesheet.DayScreen
import ru.kfh.tabel.ui.timesheet.EmployeeFormScreen
import ru.kfh.tabel.ui.timesheet.EmployeeHistoryScreen
import ru.kfh.tabel.ui.timesheet.EmployeesScreen
import ru.kfh.tabel.ui.timesheet.MonthGridScreen
import java.time.LocalDate

/** Navigation routes inside a sign-in. Signed out / loading are not routes: [AppRoot] switches on the session. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MODULE = "module/{name}"
    const val TIMESHEET_DAY = "timesheet/day"
    const val TIMESHEET_MONTH = "timesheet/month"
    const val EMPLOYEES = "employees"
    const val EMPLOYEE = "employee/{id}"
    const val EMPLOYEE_FORM = "employeeForm?id={id}"
    const val COAL = "coal"
    const val COAL_CLOSE = "coal/close/{day}"
    const val COAL_SUMMARY = "coal/summary/{day}"
    const val COAL_SALE = "coal/sale/{id}"
    const val COAL_DRIVERS = "coal/drivers"
    const val COAL_DRIVER = "coal/driver/{id}"
    const val COAL_SETTINGS = "coal/settings"
    const val COAL_HISTORY = "coal/history"
    const val LIVESTOCK = "livestock"
    const val LIVESTOCK_HERD = "livestock/herd"
    const val LIVESTOCK_OPERATION = "livestock/operation?id={id}&category={category}&adding={adding}"
    const val LIVESTOCK_SLAUGHTER = "livestock/slaughter?category={category}"
    const val LIVESTOCK_HISTORY = "livestock/history?category={category}"
    const val LIVESTOCK_REPORTS = "livestock/reports"
    const val AGRO = "agro"
    const val AGRO_HISTORY = "agro/history"
    const val AGRO_CALCULATOR = "agro/{kind}?id={id}"

    fun module(m: Module) = when (m) {
        Module.timesheet -> TIMESHEET_DAY
        Module.coal -> COAL
        Module.livestock -> LIVESTOCK
        Module.agro -> AGRO
        else -> "module/${m.name}"
    }

    /** The calculator [kind], empty (its draft) or over the saved calculation [id]. */
    fun agroCalculator(kind: AgroKind, id: String? = null) = if (id == null) "agro/${kind.wire}" else "agro/${kind.wire}?id=$id"

    /** The form of the saved operation [id], or of a new one for [category]: «Добавить» ([adding]) or «Уменьшить». */
    fun livestockOperation(id: String?, category: String? = null, adding: Boolean = false) =
        if (id != null) "livestock/operation?id=$id" else "livestock/operation?category=$category&adding=$adding"

    fun livestockSlaughter(category: String? = null) = if (category == null) "livestock/slaughter" else "livestock/slaughter?category=$category"

    fun livestockHistory(category: String? = null) = if (category == null) "livestock/history" else "livestock/history?category=$category"

    fun coalClose(day: LocalDate) = "coal/close/$day"

    fun coalSummary(day: LocalDate) = "coal/summary/$day"

    fun coalSale(id: String) = "coal/sale/$id"

    fun coalDriver(id: String) = "coal/driver/$id"

    fun employee(id: String) = "employee/$id"

    /** The form of [id], or of a new employee without one. */
    fun employeeForm(id: String?) = if (id == null) "employeeForm" else "employeeForm?id=$id"
}

/** Signed out: the sign-in screen; loading: «Загрузка данных…»; signed in: the farm (`AppRootView`). */
@Composable
fun AppRoot(graph: AppGraph) {
    val state by graph.session.state.collectAsState()
    when (val s = state) {
        SessionState.SignedOut -> {
            val notice by graph.session.signOutNotice.collectAsState()
            // The sign-in runs in the app's scope: as soon as it reaches «Загрузка данных…» this screen is gone,
            // and a sign-in in the screen's own scope would be cancelled in the middle of the first download.
            SignInScreen(notice) { login, password ->
                graph.scope.async { graph.session.signIn(login, password) }.await()
            }
        }
        SessionState.Loading -> LoadingScreen()
        is SessionState.SignedIn -> FarmRoot(graph, s.me)
    }
}

/** One main screen; every part opens on top of it and «Назад» returns to it. */
@Composable
private fun FarmRoot(graph: AppGraph, me: Me) {
    val nav = rememberNavController()
    val engine by graph.session.engine.collectAsState()
    val farmTime by graph.session.farmTime.collectAsState()
    // Between sign-ins there is no engine for a moment: the line then says «synced» until the next one reports.
    val statusFlow = remember(engine) { engine?.status ?: MutableStateFlow<SyncStatus>(SyncStatus.Synced) }
    val status by statusFlow.collectAsState()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                me = me,
                today = farmTime.today(),
                status = status,
                onSync = graph.scheduler::syncNow,
                open = { m -> nav.navigate(if (m == Module.settings) Routes.SETTINGS else Routes.module(m)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                me = me,
                unsent = graph.session::unsent,
                signOut = { graph.scope.launch { graph.session.signOut(force = true) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MODULE, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
            val m = entry.arguments?.getString("name")?.let { n -> Module.entries.firstOrNull { it.name == n } }
            // The reports module comes with Task 15: until then only the title and «Назад».
            TitledScreen(m?.let(::moduleTitle).orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.TIMESHEET_DAY) {
            DayScreen(
                vm = graph.timesheet,
                onBack = { nav.popBackStack() },
                openMonth = { nav.navigate(Routes.TIMESHEET_MONTH) },
                openEmployees = { nav.navigate(Routes.EMPLOYEES) },
                addEmployee = { nav.navigate(Routes.employeeForm(null)) },
            )
        }
        composable(Routes.TIMESHEET_MONTH) {
            MonthGridScreen(graph.timesheet, onBack = { nav.popBackStack() })
        }
        composable(Routes.EMPLOYEES) {
            EmployeesScreen(
                graph.timesheet,
                onBack = { nav.popBackStack() },
                open = { id -> nav.navigate(Routes.employee(id)) },
                add = { nav.navigate(Routes.employeeForm(null)) },
            )
        }
        composable(Routes.EMPLOYEE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            EmployeeHistoryScreen(
                graph.timesheet, id,
                onBack = { nav.popBackStack() },
                edit = { nav.navigate(Routes.employeeForm(id)) },
            )
        }
        composable(
            Routes.EMPLOYEE_FORM,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            EmployeeFormScreen(graph.timesheet, entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(Routes.COAL) {
            CoalSaleScreen(
                vm = graph.coal,
                onBack = { nav.popBackStack() },
                openSummary = { day -> nav.navigate(Routes.coalSummary(day)) },
                openHistory = { nav.navigate(Routes.COAL_HISTORY) },
                openDrivers = { nav.navigate(Routes.COAL_DRIVERS) },
                openPrices = { nav.navigate(Routes.COAL_SETTINGS) },
                openSale = { id -> nav.navigate(Routes.coalSale(id)) },
                closeDay = { day -> nav.navigate(Routes.coalClose(day)) },
            )
        }
        composable(Routes.COAL_CLOSE, arguments = listOf(navArgument("day") { type = NavType.StringType })) { entry ->
            CloseDayScreen(graph.coal, LocalDate.parse(entry.arguments?.getString("day")), onDone = { nav.popBackStack() })
        }
        composable(Routes.COAL_SUMMARY, arguments = listOf(navArgument("day") { type = NavType.StringType })) { entry ->
            CoalDaySummaryScreen(
                graph.coal, LocalDate.parse(entry.arguments?.getString("day")),
                onBack = { nav.popBackStack() },
                closeDay = { day -> nav.navigate(Routes.coalClose(day)) },
            )
        }
        composable(Routes.COAL_SALE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            CoalSaleFormScreen(graph.coal, entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
        }
        composable(Routes.COAL_DRIVERS) {
            DriversScreen(graph.coal, onBack = { nav.popBackStack() }, open = { id -> nav.navigate(Routes.coalDriver(id)) })
        }
        composable(Routes.COAL_DRIVER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            DriverDetailScreen(graph.coal, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.COAL_SETTINGS) {
            CoalSettingsScreen(graph.coal, onBack = { nav.popBackStack() })
        }
        composable(Routes.COAL_HISTORY) {
            CoalHistoryScreen(
                graph.coal,
                onBack = { nav.popBackStack() },
                openSummary = { day -> nav.navigate(Routes.coalSummary(day)) },
                openSale = { id -> nav.navigate(Routes.coalSale(id)) },
            )
        }
        composable(Routes.LIVESTOCK) {
            LivestockHomeScreen(
                vm = graph.livestock,
                onBack = { nav.popBackStack() },
                openHerd = { nav.navigate(Routes.LIVESTOCK_HERD) },
                openSlaughter = { nav.navigate(Routes.livestockSlaughter()) },
                openHistory = { nav.navigate(Routes.livestockHistory()) },
                openReports = { nav.navigate(Routes.LIVESTOCK_REPORTS) },
            )
        }
        composable(Routes.LIVESTOCK_HERD) {
            HerdScreen(
                graph.livestock,
                onBack = { nav.popBackStack() },
                openForm = { category, adding -> nav.navigate(Routes.livestockOperation(null, category, adding)) },
                openHistory = { category -> nav.navigate(Routes.livestockHistory(category)) },
            )
        }
        composable(
            Routes.LIVESTOCK_OPERATION,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("adding") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            OperationFormScreen(
                graph.livestock, args?.getString("id"), args?.getString("category"), args?.getBoolean("adding") ?: false,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.LIVESTOCK_SLAUGHTER,
            arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            SlaughterScreen(graph.livestock, entry.arguments?.getString("category"), onBack = { nav.popBackStack() })
        }
        composable(
            Routes.LIVESTOCK_HISTORY,
            arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            LivestockHistoryScreen(
                graph.livestock, entry.arguments?.getString("category"),
                onBack = { nav.popBackStack() },
                openOperation = { id -> nav.navigate(Routes.livestockOperation(id)) },
            )
        }
        composable(Routes.LIVESTOCK_REPORTS) {
            // The livestock reports (PDF / Excel) come with the reports module (Task 15): until then only the title and «Назад».
            TitledScreen("Отчёты по скоту", onBack = { nav.popBackStack() })
        }
        composable(Routes.AGRO) {
            AgroHomeScreen(
                graph.agro,
                onBack = { nav.popBackStack() },
                openCalculator = { kind -> nav.navigate(Routes.agroCalculator(kind)) },
                openHistory = { nav.navigate(Routes.AGRO_HISTORY) },
            )
        }
        composable(Routes.AGRO_HISTORY) {
            AgroHistoryScreen(graph.agro, onBack = { nav.popBackStack() }) { c -> nav.navigate(Routes.agroCalculator(AgroKind.fromWire(c.kind), c.id)) }
        }
        composable(
            Routes.AGRO_CALCULATOR,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val kind = AgroKind.fromWire(entry.arguments?.getString("kind").orEmpty())
            AgroCalculatorScreen(graph.agro, kind, entry.arguments?.getString("id"), onBack = { nav.popBackStack() })
        }
    }
}
