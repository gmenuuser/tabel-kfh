package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

/** One result line; a line the site wrote without `main` is not the main one. */
@Serializable
data class AgroLine(val title: String = "", val text: String = "", val main: Boolean = false)

/** `kind`: seeding, fertilizer, spraying, yield, moisture, fuel, area, transport. `inputs` are the typed strings. */
@Serializable
data class AgroCalculation(
    @Transient val id: String = "",
    val kind: String = "seeding",
    val title: String = "",
    val inputs: Map<String, String> = emptyMap(),
    val lines: List<AgroLine> = emptyList(),
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)
