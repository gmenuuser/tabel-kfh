package ru.kfh.tabel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.kfh.core.sync.Me
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.ui.common.BigButton
import ru.kfh.tabel.ui.common.ConfirmDialog
import ru.kfh.tabel.ui.common.TitledScreen
import ru.kfh.tabel.ui.theme.Tabel
import java.util.Locale

/** 1 изменение, 2 изменения, 5 изменений. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** The question before «Выйти» (`SettingsView.signOutQuestion`). */
fun signOutQuestion(unsent: SessionManager.Unsent): String {
    val n = unsent.pending
    val r = unsent.refused
    val parts = buildList {
        if (n > 0) add("$n ${plural(n, "изменение", "изменения", "изменений")} ещё не " +
                       plural(n, "отправлено", "отправлены", "отправлены") + ".")
        if (r > 0) add("$r ${plural(r, "изменение", "изменения", "изменений")} сервер не принял — " +
                       "при выходе они удалятся.")
    }
    return if (parts.isEmpty()) "Выйти из хозяйства?" else (parts + "Выйти всё равно?").joinToString(" ")
}

/**
 * «Настройки», reached by the gear for every role. This task has only «Выйти» (with its question); what each role
 * sees here besides is Task 15.
 */
@Composable
fun SettingsScreen(
    me: Me,
    unsent: suspend () -> SessionManager.Unsent,
    /** Runs in the app's scope: leaving this screen (signed out) must not cancel the sign-out. */
    signOut: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    TitledScreen("Настройки", onBack) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigButton(
                "Выйти",
                onClick = { scope.launch { question = signOutQuestion(unsent()) } },
                busy = busy,
                color = Tabel.colors.error,
                modifier = Modifier.testTag("signOut"),
            )
            Text(
                "Вы вошли в «${me.farmName}»: ${me.role.title.lowercase(Locale.forLanguageTag("ru"))}. " +
                    "При выходе данные хозяйства удаляются с этого телефона; на сервере они остаются.",
                style = MaterialTheme.typography.bodySmall,
                color = Tabel.colors.secondaryText,
            )
        }
    }

    question?.let { q ->
        ConfirmDialog(
            title = q,
            message = "Данные хозяйства будут удалены с этого телефона. На сервере они сохранятся; войти можно снова.",
            confirm = "Выйти",
            onConfirm = {
                question = null
                busy = true
                // The question named what would be lost: the answer is the force.
                signOut()
            },
            onDismiss = { question = null },
        )
    }
}
