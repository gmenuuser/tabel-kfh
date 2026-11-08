package ru.kfh.core.timesheet

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.Employee
import ru.kfh.core.model.HoursMark
import ru.kfh.core.time.FarmTime

/**
 * The four marks of a day: 0, 4, 8 or 12 hours. An empty cell (no mark) is not the same as 0 hours.
 *
 * Every function here takes decoded marks that are not removed: a mark whose wire `hours` is `null` is deleted by
 * the sync layer (`RecordCodec.isRemovedHoursMark`) before it reaches the domain, so it can never count as 0.
 */
object HoursRules {
    val allowed: List<Int> = listOf(0, 4, 8, 12)

    fun isValid(hours: Int): Boolean = hours in allowed

    /** The button row: the selected value again removes the mark (null), another value replaces it. */
    fun tap(current: Int?, tapped: Int): Int? = if (current == tapped) null else tapped
}

sealed class HoursError(message: String) : Exception(message) {
    data object InvalidHours : HoursError("Можно отметить только 0, 4, 8 или 12 часов")
    data object OutsideEmployment :
        HoursError("В этот день сотрудник не работал в хозяйстве (до приёма или после увольнения)")
}

/** What setting one day's hours does to the store: write `put` (null = none) and delete every mark in `delete`. */
data class HoursChange(val put: HoursMark?, val delete: List<HoursMark>) {
    /** The hours of the day after the change; null = no mark. */
    val hours: Int? get() = put?.hours
}

/** Hours marks of the timesheet of hours as pure functions (port of `HoursService`). */
class HoursMarkRules(private val farm: FarmTime, private val now: () -> Instant) {
    private fun marksOf(e: Employee, marks: List<HoursMark>, day: LocalDate): List<HoursMark> =
        marks.filter { it.employeeId == e.id && farm.dayOf(it.day) == day }.sortedByDescending { it.updatedAt }

    /** The hours of (employee, day); null = no mark (which is not 0 hours). */
    fun hoursOf(e: Employee, marks: List<HoursMark>, day: LocalDate): Int? = marksOf(e, marks, day).firstOrNull()?.hours

    private fun employed(e: Employee, day: LocalDate): Boolean {
        if (day.isBefore(farm.dayOf(e.hireDate))) return false
        val dismissal = e.dismissalDate
        return dismissal == null || !day.isAfter(farm.dayOf(dismissal))
    }

    /** Sets the day's hours, replacing a previous mark; null removes it. One mark per person and day. */
    fun set(hours: Int?, e: Employee, marks: List<HoursMark>, day: LocalDate): HoursChange {
        if (hours != null && !HoursRules.isValid(hours)) throw HoursError.InvalidHours
        if (!employed(e, day)) throw HoursError.OutsideEmployment
        val existing = marksOf(e, marks, day)
        if (hours == null) return HoursChange(null, existing)
        val moment = now()
        val first = existing.firstOrNull()
        val mark = when {
            first == null -> HoursMark(
                id = StableId.hoursMark(e.id, day), employeeId = e.id, day = farm.startOf(day), hours = hours,
                createdAt = moment, updatedAt = moment,
            )
            first.hours != hours -> first.copy(hours = hours, updatedAt = moment)
            else -> first
        }
        return HoursChange(mark, existing.drop(1))
    }

    fun tap(hours: Int, e: Employee, marks: List<HoursMark>, day: LocalDate): HoursChange =
        set(HoursRules.tap(hoursOf(e, marks, day), hours), e, marks, day)

    /** «Всем неотмеченным»: `hours` for everyone in `employees` without a mark that day (and employed); new marks. */
    fun markUnmarked(employees: List<Employee>, marks: List<HoursMark>, hours: Int, day: LocalDate): List<HoursMark> {
        if (!HoursRules.isValid(hours)) throw HoursError.InvalidHours
        return employees.filter { hoursOf(it, marks, day) == null && employed(it, day) }
            .mapNotNull { set(hours, it, marks, day).put }
    }
}

/** Totals of one person or of the farm in the timesheet of hours. */
data class HoursTotals(
    val hours: Int = 0,
    /** Days with more than 0 hours. */
    val workedDays: Int = 0,
    val zero: Int = 0,
    val four: Int = 0,
    val eight: Int = 0,
    val twelve: Int = 0,
) {
    fun count(h: Int): HoursTotals = copy(
        hours = hours + h,
        workedDays = workedDays + if (h > 0) 1 else 0,
        zero = zero + if (h == 0) 1 else 0,
        four = four + if (h == 4) 1 else 0,
        eight = eight + if (h == 8) 1 else 0,
        twelve = twelve + if (h == 12) 1 else 0,
    )

    operator fun plus(o: HoursTotals) = HoursTotals(
        hours + o.hours, workedDays + o.workedDays, zero + o.zero, four + o.four, eight + o.eight, twelve + o.twelve,
    )

    val columnValues: List<Int> get() = listOf(hours, workedDays, zero, four, eight, twelve)

    /** Days marked with `h` hours. */
    fun days(h: Int): Int = when (h) {
        0 -> zero
        4 -> four
        8 -> eight
        12 -> twelve
        else -> 0
    }

    companion object {
        val columnTitles = listOf("Часов", "Раб. дней", "0 ч", "4 ч", "8 ч", "12 ч")
    }
}

/** One day's hours of one employee, reduced for the builder. */
data class HoursMarkSnapshot(val employeeId: String, val day: LocalDate, val hours: Int)

object HoursTimesheetBuilder {
    /**
     * Rows: employees whose employment overlaps the month (archived ones too), by ФИО. Days outside employment are
     * blank and not counted; a day without a mark is blank and counted nowhere.
     */
    fun build(
        month: YearMonth, employees: List<EmployeeSnapshot>, marks: List<HoursMarkSnapshot>, settings: FarmInfo,
        createdAt: Instant,
    ): TimesheetDocument {
        val days = month.lengthOfMonth()
        val byDay = HashMap<String, HashMap<Int, Int>>()
        for (m in marks) {
            if (YearMonth.from(m.day) != month) continue
            byDay.getOrPut(m.employeeId) { HashMap() }[m.day.dayOfMonth] = m.hours
        }
        var farm = HoursTotals()
        val rows = TimesheetBuilder.workingIn(month, employees).mapIndexed { index, e ->
            var totals = HoursTotals()
            val cells = (1..days).map { day ->
                val date = month.atDay(day)
                val employed = e.isEmployedOn(date)
                val hours = if (employed) byDay[e.id]?.get(day) else null
                if (hours != null) totals = totals.count(hours)
                TimesheetCell(
                    status = null, minutes = null, unfinished = false, employed = employed,
                    format = CellFormat.CODES, hours = hours,
                )
            }
            farm += totals
            TimesheetRow(index + 1, e, cells, TimesheetTotals(), totals)
        }
        return TimesheetDocument(
            month = month.monthValue, year = month.year, daysInMonth = days, version = 0, format = CellFormat.CODES,
            farmName = settings.farmName, managerName = settings.managerName,
            responsiblePerson = settings.responsiblePerson, address = settings.address, phone = settings.phone,
            extraInfo = settings.extraInfo, createdAt = createdAt, rows = rows, farmTotals = TimesheetTotals(),
            unfinished = emptyList(), farmHoursTotals = farm,
        )
    }
}
