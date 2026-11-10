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
import ru.kfh.core.roles.Module
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.AppGraph
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.home.HomeScreen
import ru.kfh.tabel.ui.home.LoadingScreen
import ru.kfh.tabel.ui.home.moduleTitle
import ru.kfh.tabel.ui.settings.SettingsScreen
import ru.kfh.tabel.ui.signin.SignInScreen

/** Navigation routes inside a sign-in. Signed out / loading are not routes: [AppRoot] switches on the session. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MODULE = "module/{name}"

    fun module(m: Module) = "module/${m.name}"
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
            // The modules come with Tasks 10–15: until then only the title and «Назад».
            TitledScreen(m?.let(::moduleTitle).orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
