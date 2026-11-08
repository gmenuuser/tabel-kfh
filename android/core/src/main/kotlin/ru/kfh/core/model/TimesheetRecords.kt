package ru.kfh.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

/** `workSchedule`: fiveTwo, sixOne, twoTwo, free. `hireDate`/`dismissalDate` are the instants of farm midnights. */
@Serializable
data class Employee(
    @Transient val id: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val patronymic: String = "",
    val position: String = "",
    @Serializable(InstantSerializer::class) val hireDate: Instant = now(),
    @Serializable(InstantSerializer::class) val dismissalDate: Instant? = null,
    val isArchived: Boolean = false,
    val workSchedule: String = "fiveTwo",
    val standardWorkHours: Double = 8.0,
    val defaultBreakMinutes: Int = 60,
)

/**
 * `day` is the instant of the farm day's midnight (what the site's `dayStartISO` writes), not `YYYY-MM-DD`.
 * `hours` is 0, 4, 8 or 12; a removed mark is `hours: null` on the wire (see `RecordCodec.removedHoursMark`).
 */
@Serializable
data class HoursMark(
    @Transient val id: String = "",
    val employeeId: String = "",
    @Serializable(InstantSerializer::class) val day: Instant = now(),
    val hours: Int = 0,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)

/** `status` is the raw value of the iPhone's DayStatus: Я, В, Б, О, ОТ, ПР, К. */
@Serializable
data class AttendanceRecord(
    @Transient val id: String = "",
    val employeeId: String = "",
    @Serializable(InstantSerializer::class) val day: Instant = now(),
    @Serializable(InstantSerializer::class) val arrivalTime: Instant? = null,
    @Serializable(InstantSerializer::class) val departureTime: Instant? = null,
    val breakMinutes: Int = 0,
    val workedMinutes: Int? = null,
    val manualWorkedMinutes: Int? = null,
    val status: String = "Я",
    val comment: String = "",
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(InstantSerializer::class) val updatedAt: Instant = now(),
)

/** `documentData` and `employeeData` are base64 strings (the site does not read them). */
@Serializable
data class TimesheetSnapshot(
    @Transient val id: String = "",
    val month: Int = 0,
    val year: Int = 0,
    val version: Int = 0,
    val employeeId: String? = null,
    @Serializable(InstantSerializer::class) val createdAt: Instant = now(),
    val documentData: String = "",
    val employeeData: String = "",
)
