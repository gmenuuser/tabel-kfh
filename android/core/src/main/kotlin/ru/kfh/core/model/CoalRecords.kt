package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.kfh.core.ids.StableId
import java.time.Instant

/** The owner's starting price: 6 500 ₽ a ton. */
const val DEFAULT_PRICE_PER_TON_KOPECKS: Long = 650_000

@Serializable
data class CoalSettings(
    @Transient val id: String = StableId.coalSettings,
    val pricePerTonKopecks: Long = DEFAULT_PRICE_PER_TON_KOPECKS,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)

/** `driverPaymentKopecks` is null when the driver is paid the delivery price. */
@Serializable
data class DeliveryLocation(
    @Transient val id: String = "",
    val name: String = "",
    val deliveryPriceKopecks: Long = 0,
    val driverPaymentKopecks: Long? = null,
    val isActive: Boolean = true,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)

@Serializable
data class Driver(
    @Transient val id: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val phone: String = "",
    val comment: String = "",
    val isArchived: Boolean = false,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)

/** `vehicleType` is `hired` or `own`; `kilograms` is a whole number; all amounts are kopecks. */
@Serializable
data class CoalSale(
    @Transient val id: String = "",
    @Serializable(InstantSerializer::class) val saleDate: Instant = now(),
    val kilograms: Long = 0,
    val locationNameSnapshot: String = "",
    val coalPricePerTonSnapshot: Long = 0,
    val deliveryPriceSnapshot: Long = 0,
    val coalAmount: Long = 0,
    val deliveryPrice: Long = 0,
    val totalAmount: Long = 0,
    val vehicleType: String = "own",
    val driverId: String? = null,
    val driverNameSnapshot: String = "",
    val driverPaymentAmount: Long = 0,
    val comment: String = "",
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)

@Serializable
data class DriverPayment(
    @Transient val id: String = "",
    val driverId: String = "",
    val driverNameSnapshot: String = "",
    @Serializable(InstantSerializer::class) val paymentDate: Instant = now(),
    val amount: Long = 0,
    val comment: String = "",
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)

/** `date` is the instant of the farm day's midnight. */
@Serializable
data class CoalWorkDay(
    @Transient val id: String = "",
    @Serializable(InstantSerializer::class) val date: Instant = now(),
    val isClosed: Boolean = false,
    @Serializable(InstantSerializer::class) val closedAt: Instant? = null,
    val totalRevenue: Long = 0,
    val totalKilograms: Long = 0,
    val totalDriverPayments: Long = 0,
    val ownVehicleDeliveryRevenue: Long = 0,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
)
