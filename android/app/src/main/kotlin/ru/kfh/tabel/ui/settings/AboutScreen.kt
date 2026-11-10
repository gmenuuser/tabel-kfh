package ru.kfh.tabel.ui.settings

import androidx.compose.runtime.Composable

/** «О приложении»: «Табель КФХ — Версия 1.0.7 (7)» (`SettingsForm.versionText`). */
@Composable
fun AboutSection(versionText: String) {
    SettingsSection(header = "О приложении") {
        LabeledRow("Табель КФХ", versionText, tag = "version")
    }
}
