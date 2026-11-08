package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.model.FarmSettings
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.TimesheetSnapshot
import ru.kfh.core.time.FarmTime

/** What a day cell shows: only the status code, or the code and the hours of a Я day. */
enum class CellFormat(val title: String) {
    CODES("Обозначения"),
    CODES_AND_HOURS("Обозначения и часы"),
}

/** An employee as they were when the timesheet was generated. Days are farm days. */
data class EmployeeSnapshot(
    val id: String,
    val fullName: String,
    val position: String,
    val hireDate: LocalDate,
    val dismissalDate: LocalDate?,
) {
    fun isEmployedOn(day: LocalDate): Boolean =
        !day.isBefore(hireDate) && (dismissalDate == null || !day.isAfter(dismissalDate))
}

/** One employee's mark for one day, reduced to what the timesheet needs. */
data class RecordSnapshot(
    val employeeId: String,
    val day: LocalDate,
    val status: DayStatus,
    /** Worked minutes of a finished Я day (manual total wins); null otherwise. */
    val minutes: Int?,
    /** Я with an arrival and no departure: shown «Я?», 0 hours. */
    val unfinished: Boolean,
)

/** Farm details printed on the timesheet. */
data class FarmInfo(
    val farmName: String,
    val managerName: String,
    val responsiblePerson: String,
    val address: String,
    val phone: String,
    val extraInfo: String,
) {
    companion object {
        val DEFAULT = FarmInfo("КФХ Зубахин", "", "", "", "", "")

        /** The synced settings row over the defaults (the site does not store a farm or manager name). */
        fun from(s: FarmSettings): FarmInfo = DEFAULT.copy(
            responsiblePerson = s.responsiblePerson, address = s.address, phone = s.phone, extraInfo = s.extraInfo,
        )
    }
}

data class TimesheetCell(
    /** null = no mark that day. */
    val status: DayStatus?,
    /** Worked minutes of a finished Я day; null otherwise. */
    val minutes: Int?,
    val unfinished: Boolean,
    /** false before the hire date and after the dismissal date: blank, not counted. */
    val employed: Boolean,
    val format: CellFormat,
    /** The hours of the day (0, 4, 8, 12) in the timesheet of hours; null = no mark (or a timesheet of codes). */
    val hours: Int? = null,
) {
    /** "" | "Я" | "В" | "Б" ... | the hours: only the code of the day, the timesheet keeps no times. */
    val text: String
        get() {
            if (!employed) return ""
            if (hours != null) return hours.toString()
            return status?.code ?: ""
        }
}

data class TimesheetTotals(
    /** Я days, unfinished ones included. */
    val presentDays: Int = 0,
    val minutes: Int = 0,
    val dayOff: Int = 0,
    val sick: Int = 0,
    val vacation: Int = 0,
    val timeOff: Int = 0,
    val absent: Int = 0,
    val businessTrip: Int = 0,
) {
    operator fun plus(o: TimesheetTotals) = TimesheetTotals(
        presentDays + o.presentDays, minutes + o.minutes, dayOff + o.dayOff, sick + o.sick, vacation + o.vacation,
        timeOff + o.timeOff, absent + o.absent, businessTrip + o.businessTrip,
    )

    fun counted(cell: TimesheetCell): TimesheetTotals {
        val status = cell.status
        if (!cell.employed || status == null) return this
        return when (status) {
            DayStatus.PRESENT -> copy(
                presentDays = presentDays + 1, minutes = minutes + if (cell.unfinished) 0 else (cell.minutes ?: 0),
            )
            DayStatus.DAY_OFF -> copy(dayOff = dayOff + 1)
            DayStatus.SICK -> copy(sick = sick + 1)
            DayStatus.VACATION -> copy(vacation = vacation + 1)
            DayStatus.TIME_OFF -> copy(timeOff = timeOff + 1)
            DayStatus.ABSENT -> copy(absent = absent + 1)
            DayStatus.BUSINESS_TRIP -> copy(businessTrip = businessTrip + 1)
        }
    }

    /** Days in `columnTitles` order. */
    val columnValues: List<Int> get() = listOf(presentDays, dayOff, sick, vacation, timeOff, absent, businessTrip)

    val columnTexts: List<String> get() = columnValues.map { it.toString() }

    /** Days of one status. */
    fun days(status: DayStatus): Int = when (status) {
        DayStatus.PRESENT -> presentDays
        DayStatus.DAY_OFF -> dayOff
        DayStatus.SICK -> sick
        DayStatus.VACATION -> vacation
        DayStatus.TIME_OFF -> timeOff
        DayStatus.ABSENT -> absent
        DayStatus.BUSINESS_TRIP -> businessTrip
    }

    companion object {
        /** Titles of the totals columns of the screen, the PDF and the XLSX: days of each status. */
        val columnTitles = listOf("Я", "В", "Б", "О", "ОТ", "ПР", "К")

        /** The statuses of `columnTitles`, in order. */
        val columnStatuses = listOf(
            DayStatus.PRESENT, DayStatus.DAY_OFF, DayStatus.SICK, DayStatus.VACATION, DayStatus.TIME_OFF,
            DayStatus.ABSENT, DayStatus.BUSINESS_TRIP,
        )
    }
}

data class TimesheetRow(
    /** 1-based row number. */
    val number: Int,
    val employee: EmployeeSnapshot,
    /** One cell per day of the month, cells[0] is day 1. */
    val cells: List<TimesheetCell>,
    val totals: TimesheetTotals,
    /** The totals of the timesheet of hours; null in a timesheet of codes. */
    val hoursTotals: HoursTotals? = null,
)

/** A generated timesheet. Immutable; a stored version is never recomputed from live data. */
data class TimesheetDocument(
    val month: Int,
    val year: Int,
    val daysInMonth: Int,
    val version: Int,
    val format: CellFormat,
    val farmName: String,
    val managerName: String,
    val responsiblePerson: String,
    val address: String,
    val phone: String,
    val extraInfo: String,
    val createdAt: Instant,
    val rows: List<TimesheetRow>,
    val farmTotals: TimesheetTotals,
    /** «Зубахин И. П. — 5 ноября», in row order, then by day. */
    val unfinished: List<String>,
    /** The farm's totals of the timesheet of hours; null in a timesheet of codes. */
    val farmHoursTotals: HoursTotals? = null,
) {
    /** The timesheet of hours (since the farm switched from status codes). */
    val isHours: Boolean get() = farmHoursTotals != null

    val totalsTitles: List<String> get() = if (isHours) HoursTotals.columnTitles else TimesheetTotals.columnTitles

    fun totalsValues(row: TimesheetRow): List<Int> =
        if (isHours) (row.hoursTotals ?: HoursTotals()).columnValues else row.totals.columnValues

    val farmTotalsValues: List<Int>
        get() = if (isHours) (farmHoursTotals ?: HoursTotals()).columnValues else farmTotals.columnValues

    /** The legend under the table. */
    val legendText: String
        get() = if (isHours) {
            "Обозначения: число — отработанные часы за день (0, 4, 8 или 12); пустая клетка — отметки нет " +
                "(это не 0 часов). «Раб. дней» — дни с ненулевыми часами."
        } else {
            "Обозначения: $LEGEND"
        }

    /** «06.11.2026», the day at the farm. */
    fun createdDateText(farm: FarmTime): String {
        val d = farm.dayOf(createdAt)
        return "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)
    }

    companion object {
        /** Codes and their meaning, for the legend under the table. */
        val LEGEND: String = DayStatus.entries.joinToString(", ") { "${it.code} — ${it.title.lowercase()}" }
    }
}

/** «Табель_Ноябрь_2026.pdf» for the whole farm; «Табель_Ноябрь_2026_Зубахин.pdf» for one employee's timesheet. */
fun exportFileName(d: TimesheetDocument, surname: String? = null, ext: String): String {
    val person = surname?.let { "_" + it.replace("/", "-").replace(":", "-") } ?: ""
    return "Табель_${RuMonths.name(d.month)}_${d.year}$person.$ext"
}

/** Hours as a decimal with a comma, at most two decimals: 480 -> «8», 450 -> «7,5», 465 -> «7,75», 470 -> «7,83». */
fun decimalHoursText(minutes: Int): String {
    val sign = if (minutes < 0) "-" else ""
    val hundredths = (Math.abs(minutes) * 100 + 30) / 60
    val whole = hundredths / 100
    val fraction = hundredths % 100
    if (fraction == 0) return "$sign$whole"
    if (fraction % 10 == 0) return "$sign$whole,${fraction / 10}"
    return "$sign$whole," + (if (fraction < 10) "0$fraction" else "$fraction")
}

/** Which timesheet to build: the timesheet of hours (current) or the older one of status codes. */
enum class TimesheetKind { HOURS, CODES }

sealed class TimesheetError(message: String) : Exception(message) {
    data object InvalidMonth : TimesheetError("Неверный месяц")
}

object TimesheetBuilder {
    /**
     * The timesheet of `month` for the whole farm, or for `onlyEmployeeId` alone.
     *
     * `kind` HOURS (the default) is built from `marks` only: old status records are never turned into hours.
     * CODES is built from `attendance`. `marks` must be decoded marks that are not removed (a removed mark, wire
     * `hours: null`, is never 0 hours). When a day has several rows the latest `updatedAt` wins.
     */
    fun build(
        month: YearMonth,
        employees: List<Employee>,
        marks: List<HoursMark>,
        attendance: List<AttendanceRecord>,
        onlyEmployeeId: String?,
        settings: FarmInfo = FarmInfo.DEFAULT,
        format: CellFormat = CellFormat.CODES,
        version: Int = 0,
        createdAt: Instant = Instant.now(),
        farm: FarmTime,
        kind: TimesheetKind = TimesheetKind.HOURS,
    ): TimesheetDocument {
        val people = employees.filter { onlyEmployeeId == null || it.id == onlyEmployeeId }
        val snapshots = people.map { snapshot(it, farm) }
        val ids = snapshots.map { it.id }.toSet()
        return when (kind) {
            TimesheetKind.HOURS -> HoursTimesheetBuilder.build(
                month, snapshots,
                marks.filter { it.employeeId in ids }.sortedBy { it.updatedAt }
                    .map { HoursMarkSnapshot(it.employeeId, farm.dayOf(it.day), it.hours) },
                settings, createdAt,
            )
            TimesheetKind.CODES -> buildFromSnapshots(
                month, snapshots,
                attendance.filter { it.employeeId in ids }.sortedBy { it.updatedAt }.map { snapshot(it, farm) },
                settings, format, version, createdAt, farm,
            )
        }
    }

    fun snapshot(e: Employee, farm: FarmTime) = EmployeeSnapshot(
        e.id, e.fullName, e.position, farm.dayOf(e.hireDate), e.dismissalDate?.let { farm.dayOf(it) },
    )

    fun snapshot(r: AttendanceRecord, farm: FarmTime): RecordSnapshot {
        val status = r.dayStatus()
        val unfinished = status == DayStatus.PRESENT && r.arrivalTime != null && r.departureTime == null &&
            r.manualWorkedMinutes == null
        val minutes = if (status == DayStatus.PRESENT && !unfinished) WorkTimeCalculator.effectiveMinutes(r) else null
        return RecordSnapshot(r.employeeId, farm.dayOf(r.day), status, minutes, unfinished)
    }

    /** Employees whose employment overlaps `month`, by ФИО (equal names by id). */
    internal fun workingIn(month: YearMonth, employees: List<EmployeeSnapshot>): List<EmployeeSnapshot> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        return employees
            .filter { e -> !e.hireDate.isAfter(last) && (e.dismissalDate == null || !e.dismissalDate.isBefore(first)) }
            .sortedWith { a, b ->
                val byName = NameOrder.compare(a.fullName, b.fullName)
                if (byName != 0) byName else a.id.lowercase().compareTo(b.id.lowercase())
            }
    }

    /**
     * Rows: employees whose employment overlaps the month, by ФИО. Days outside employment are blank and not
     * counted; a day without a record is blank (never ПР); farm totals are the sum of the rows. When a day has several
     * records the last one in `records` wins.
     */
    fun buildFromSnapshots(
        month: YearMonth,
        employees: List<EmployeeSnapshot>,
        records: List<RecordSnapshot>,
        settings: FarmInfo,
        format: CellFormat,
        version: Int,
        createdAt: Instant,
        farm: FarmTime,
    ): TimesheetDocument {
        val days = month.lengthOfMonth()
        val marks = HashMap<String, HashMap<Int, RecordSnapshot>>()
        for (r in records) {
            if (YearMonth.from(r.day) != month) continue
            marks.getOrPut(r.employeeId) { HashMap() }[r.day.dayOfMonth] = r
        }
        var farmTotals = TimesheetTotals()
        val unfinished = mutableListOf<String>()
        val rows = workingIn(month, employees).mapIndexed { index, e ->
            var totals = TimesheetTotals()
            val cells = (1..days).map { day ->
                val employed = e.isEmployedOn(month.atDay(day))
                val r = if (employed) marks[e.id]?.get(day) else null
                val isPresent = r?.status == DayStatus.PRESENT
                val isUnfinished = isPresent && r?.unfinished == true
                val cell = TimesheetCell(
                    status = r?.status, minutes = if (isPresent && !isUnfinished) r?.minutes else null,
                    unfinished = isUnfinished, employed = employed, format = format,
                )
                totals = totals.counted(cell)
                if (isUnfinished) unfinished += "${shortName(e.fullName)} — $day ${RuMonths.genitive(month.monthValue)}"
                cell
            }
            farmTotals += totals
            TimesheetRow(index + 1, e, cells, totals)
        }
        return TimesheetDocument(
            month = month.monthValue, year = month.year, daysInMonth = days, version = version, format = format,
            farmName = settings.farmName, managerName = settings.managerName,
            responsiblePerson = settings.responsiblePerson, address = settings.address, phone = settings.phone,
            extraInfo = settings.extraInfo, createdAt = createdAt, rows = rows, farmTotals = farmTotals,
            unfinished = unfinished,
        )
    }

    /** «Зубахин Иван Петрович» -> «Зубахин И. П.»; a single word stays as it is. */
    fun shortName(fullName: String): String {
        val parts = fullName.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lastName = parts.firstOrNull() ?: return ""
        val initials = parts.drop(1).map { it.first().uppercase() + "." }
        return (listOf(lastName) + initials).joinToString(" ")
    }
}

/** Versions of generated timesheets: numbered per (month, year, employee); `employeeId == null` = всё хозяйство. */
object TimesheetVersions {
    fun checkMonth(month: Int, year: Int): YearMonth {
        if (month !in 1..12 || year <= 0) throw TimesheetError.InvalidMonth
        return YearMonth.of(year, month)
    }

    /** Versions of (month, year, employee), newest first. */
    fun versions(all: List<TimesheetSnapshot>, month: Int, year: Int, employeeId: String?): List<TimesheetSnapshot> =
        all.filter { it.month == month && it.year == year && it.employeeId == employeeId }
            .sortedByDescending { it.version }

    /** The last version + 1. */
    fun next(all: List<TimesheetSnapshot>, month: Int, year: Int, employeeId: String?): Int =
        (versions(all, month, year, employeeId).firstOrNull()?.version ?: 0) + 1
}
