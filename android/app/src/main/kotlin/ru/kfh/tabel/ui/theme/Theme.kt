package ru.kfh.tabel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours of `TabelKFH/App/Theme.swift`: dark green primary, orange accent, light-grey cards on the background;
 * module colours of `LivestockTheme.module`, `CoalTheme.hired` and `systemGray`.
 */
@Immutable
data class TabelColors(
    val primary: Color,
    val accent: Color,
    val card: Color,
    val background: Color,
    val livestock: Color,
    val agro: Color,
    val reports: Color,
    val error: Color,
    val secondaryText: Color,
)

private val Light = TabelColors(
    primary = Color(0xFF1F5D3A),
    accent = Color(0xFFE8892B),
    card = Color(0xFFF2F2F7),
    background = Color(0xFFFFFFFF),
    livestock = Color(0xFF855433),
    agro = Color(0xFF1A61CC),
    reports = Color(0xFF8E8E93),
    error = Color(0xFFFF3B30),
    secondaryText = Color(0x993C3C43),
)

private val Dark = TabelColors(
    primary = Color(0xFF4FA36F),
    accent = Color(0xFFE8892B),
    card = Color(0xFF1C1C1E),
    background = Color(0xFF000000),
    livestock = Color(0xFFB3805C),
    agro = Color(0xFF6BA3FF),
    reports = Color(0xFF8E8E93),
    error = Color(0xFFFF453A),
    secondaryText = Color(0x99EBEBF5),
)

val LocalTabelColors = staticCompositionLocalOf { Light }

/** The app's colours: `Tabel.colors.primary` etc. */
object Tabel {
    val colors: TabelColors
        @Composable get() = LocalTabelColors.current
}

@Composable
fun TabelTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = Color.White, secondary = c.accent, background = c.background,
            surface = c.background, surfaceVariant = c.card, error = c.error,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = Color.White, secondary = c.accent, background = c.background,
            surface = c.background, surfaceVariant = c.card, error = c.error,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTabelColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
