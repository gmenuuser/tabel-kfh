package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.kfh.core.ids.StableId
import java.time.Instant

/** `species`: cattle, goats, pigs. */
@Serializable
data class LivestockCategory(
    @Transient val id: String = "",
    val species: String = "cattle",
    val name: String = "",
    val sortOrder: Int = 0,
    val isBuiltIn: Boolean = false,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)

/** `kind`: initial, purchase, birth, transfer, slaughter, sale, death, other. */
@Serializable
data class LivestockOperation(
    @Transient val id: String = "",
    val kind: String = "other",
    @Serializable(InstantSerializer::class) val date: Instant = now(),
    val categoryId: String = "",
    val targetCategoryId: String? = null,
    val heads: Long = 0,
    val comment: String = "",
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)

/** `reason`: meat, forced, other. Weights are grams. */
@Serializable
data class SlaughterRecord(
    @Transient val id: String = "",
    val operationId: String = "",
    val reason: String = "other",
    val liveWeightGrams: Long? = null,
    val carcassWeightGrams: Long? = null,
    val meatWeightGrams: Long? = null,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)

@Serializable
data class LivestockSettings(
    @Transient val id: String = StableId.livestockSettings,
    val defaultsCreated: Boolean = true,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)
