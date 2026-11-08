package ru.kfh.core.timesheet

import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.time.FarmTime

/** One square of the Monday-first month grid: a leading blank or a day of the month. */
data class CalendarCell(
    /** Position in the grid. */
    val id: Int,
    /** null for a leading blank. */
    val day: LocalDate?,
    val number: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
    /** All employees: how many marks of each status the day has. Empty with an employee filter. */
    val statusCounts: Map<DayStatus, Int>,
    /** With an employee filter: that employee's status of the day, null = no mark. */
    val status: DayStatus?,
    /** With an employee filter: the day's total for Я with a finished or corrected total. */
    val minutes: Int?,
    /** With an employee filter: false before hire and after dismissal. Always true for all employees. */
    val isEmployed: Boolean,
) {
    val isBlank: Boolean get() = day == null

    companion object {
        fun blank(index: Int) = CalendarCell(index, null, 0, false, false, emptyMap(), null, null, false)
    }
}

/** An employee employed on the day and their mark, if any. */
data class DayEntry(val employee: Employee, val record: AttendanceRecord?) {
    val id: String get() = employee.id
}

/** Календарь, domain part: one month of marks for the whole farm or one employee, and the people of a day. */
object CalendarGrid {
    fun title(month: YearMonth): String = RuMonths.title(month)

    fun next(month: YearMonth): YearMonth = month.plusMonths(1)

    fun previous(month: YearMonth): YearMonth = month.minusMonths(1)

    /** Leading blanks so that the 1st falls under its weekday (Пн first), then one cell per day. */
    fun cells(
        month: YearMonth, today: LocalDate, records: List<AttendanceRecord>, employeeFilter: Employee?, farm: FarmTime,
    ): List<CalendarCell> {
        val leading = month.atDay(1).dayOfWeek.value - 1
        val byDay = records
            .filter { YearMonth.from(farm.dayOf(it.day)) == month && (employeeFilter == null || it.employeeId == employeeFilter.id) }
            .groupBy { farm.dayOf(it.day) }
        val hire = employeeFilter?.let { farm.dayOf(it.hireDate) }
        val dismissal = employeeFilter?.dismissalDate?.let { farm.dayOf(it) }

        val cells = (0 until leading).mapTo(ArrayList()) { CalendarCell.blank(it) }
        for (number in 1..month.lengthOfMonth()) {
            val day = month.atDay(number)
            val dayRecords = byDay[day].orEmpty()
            var counts = emptyMap<DayStatus, Int>()
            var status: DayStatus? = null
            var minutes: Int? = null
            var employed = true
            if (employeeFilter != null) {
                val r = dayRecords.firstOrNull()
                status = r?.dayStatus()
                if (r != null && r.dayStatus() == DayStatus.PRESENT &&
                    (r.workedMinutes != null || r.manualWorkedMinutes != null)
                ) {
                    minutes = WorkTimeCalculator.effectiveMinutes(r)
                }
                if (hire != null && day.isBefore(hire)) employed = false
                if (dismissal != null && day.isAfter(dismissal)) employed = false
            } else {
                counts = dayRecords.groupingBy { it.dayStatus() }.eachCount()
            }
            cells += CalendarCell(
                id = leading + number - 1, day = day, number = number, isToday = day == today,
                isFuture = day.isAfter(today), statusCounts = counts, status = status, minutes = minutes,
                isEmployed = employed,
            )
        }
        return cells
    }

    /** Employees employed on `day` (archived ones too) with their mark of that day, by name. */
    fun entries(day: LocalDate, employees: List<Employee>, records: List<AttendanceRecord>, farm: FarmTime): List<DayEntry> =
        EmployeeRules.employed(employees, day, farm).map { e ->
            DayEntry(e, records.firstOrNull { it.employeeId == e.id && farm.dayOf(it.day) == day })
        }

    /** Choices of the employee menu: everyone employed during the shown month, archived ones too. */
    fun employees(month: YearMonth, employees: List<Employee>, farm: FarmTime): List<Employee> =
        EmployeeRules.employed(employees, month.atDay(1), month.atEndOfMonth(), farm)
}
