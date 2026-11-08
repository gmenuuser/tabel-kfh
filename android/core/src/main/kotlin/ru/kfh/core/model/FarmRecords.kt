package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.kfh.core.ids.StableId

/**
 * Only these four fields are synced; the farm's name and manager go through `/farm`.
 * One row per store: its id is [StableId.farmSettings].
 */
@Serializable
data class FarmSettings(
    @Transient val id: String = StableId.farmSettings,
    val responsiblePerson: String = "",
    val address: String = "",
    val phone: String = "",
    val extraInfo: String = "",
)
