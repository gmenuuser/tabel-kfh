package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A field of the farm (the site's `web/src/pages/fields.tsx`): the area is whole square metres, as money is kopecks
 * and mass is kilograms; the name is 1..60 characters, the culture and the note are optional.
 */
@Serializable
data class Field(
    @Transient val id: String = "",
    val name: String = "",
    val areaSqM: Long = 0,
    val culture: String? = null,
    val note: String? = null,
)
