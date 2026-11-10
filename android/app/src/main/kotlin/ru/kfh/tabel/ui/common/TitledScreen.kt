package ru.kfh.tabel.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ru.kfh.tabel.ui.theme.Tabel

/** A screen opened from the main screen: [title] on top and «Назад» back to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitledScreen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit = {}) {
    Scaffold(
        containerColor = Tabel.colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("screenTitle")) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Tabel.colors.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tabel.colors.background),
            )
        },
        content = content,
    )
}
