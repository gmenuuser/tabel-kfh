package ru.kfh.tabel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.kfh.tabel.ui.AppRoot
import ru.kfh.tabel.ui.theme.TabelTheme

/** The one activity: sign-in, «Загрузка данных…» or the farm ([AppRoot]) over the process's [AppGraph]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as TabelApp).graph
        setContent {
            TabelTheme {
                AppRoot(graph)
            }
        }
    }
}
