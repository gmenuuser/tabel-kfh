package ru.kfh.tabel.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.kfh.tabel.ui.theme.Tabel

/** The first download after signing in (`LoadingDataView`). */
@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Tabel.colors.background).testTag("loading"),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Tabel.colors.primary, modifier = Modifier.size(44.dp))
        Text("Загрузка данных…", style = MaterialTheme.typography.titleLarge)
    }
}
