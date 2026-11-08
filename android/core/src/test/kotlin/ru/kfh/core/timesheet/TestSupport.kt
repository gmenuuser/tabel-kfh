package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

fun farmIn(zone: ZoneId = DEFAULT_FARM_ZONE) = FarmTime(zone)

fun day(d: Int, m: Int = 11, y: Int = 2026): LocalDate = LocalDate.of(y, m, d)

/** A moment on a farm clock face. */
fun FarmTime.at(d: Int, hour: Int, minute: Int = 0, month: Int = 11, year: Int = 2026): Instant =
    ZonedDateTime.of(year, month, d, hour, minute, 0, 0, zone).toInstant()

fun FarmTime.employee(
    lastName: String,
    id: String = "e-$lastName",
    hire: LocalDate = day(1, 1, 2025),
    dismissal: LocalDate? = null,
    archived: Boolean = false,
    breakMinutes: Int = 60,
    firstName: String = "Иван",
    patronymic: String = "",
    position: String = "",
) = Employee(
    id = id, lastName = lastName, firstName = firstName, patronymic = patronymic, position = position,
    hireDate = startOf(hire), dismissalDate = dismissal?.let { startOf(it) }, isArchived = archived,
    defaultBreakMinutes = breakMinutes,
)

fun FarmTime.record(
    e: Employee,
    d: LocalDate,
    status: String = "Я",
    arrival: Instant? = null,
    departure: Instant? = null,
    breakMinutes: Int = 60,
    worked: Int? = null,
    manual: Int? = null,
    id: String = "r-${e.id}-$d",
) = AttendanceRecord(
    id = id, employeeId = e.id, day = startOf(d), arrivalTime = arrival, departureTime = departure,
    breakMinutes = breakMinutes, workedMinutes = worked, manualWorkedMinutes = manual, status = status,
)

/** The timesheet of status codes built from attendance records, the way the old live document was. */
fun FarmTime.codesDoc(
    month: java.time.YearMonth,
    employees: List<Employee>,
    attendance: List<AttendanceRecord>,
    onlyEmployeeId: String? = null,
    format: CellFormat = CellFormat.CODES,
    version: Int = 0,
    createdAt: Instant = Instant.EPOCH,
    settings: FarmInfo = FarmInfo.DEFAULT,
) = TimesheetBuilder.build(
    month, employees, emptyList(), attendance, onlyEmployeeId, settings, format, version, createdAt, this,
    TimesheetKind.CODES,
)
