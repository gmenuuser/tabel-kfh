package ru.kfh.core.timesheet

import java.time.LocalDate
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.Employee
import ru.kfh.core.time.FarmTime

/** Russian alphabetical order, ignoring case; ё sorts with е (after it when the words are otherwise equal). */
object NameOrder {
    fun compare(a: String, b: String): Int {
        val primary = key(a).compareTo(key(b))
        if (primary != 0) return primary
        return a.lowercase().compareTo(b.lowercase())
    }

    private fun key(s: String) = s.lowercase().replace('ё', 'е')
}

/** All fields of the employee form. Text fields are trimmed when saved. */
data class EmployeeDraft(
    val lastName: String = "",
    val firstName: String = "",
    val patronymic: String = "",
    val position: String = "",
    val hireDate: LocalDate,
    val dismissalDate: LocalDate? = null,
    val workSchedule: WorkSchedule = WorkSchedule.FIVE_TWO,
    /** Norm of working hours per day. */
    val standardWorkHours: Double = 8.0,
    val defaultBreakMinutes: Int = 60,
) {
    /** Russian messages, empty when the draft can be saved. */
    val validationErrors: List<String>
        get() {
            val errors = mutableListOf<String>()
            if (lastName.trim().isEmpty()) errors += "Укажи фамилию"
            if (firstName.trim().isEmpty()) errors += "Укажи имя"
            if (dismissalDate != null && dismissalDate.isBefore(hireDate)) errors += "Дата увольнения раньше даты приёма"
            if (standardWorkHours !in HOURS_RANGE) errors += "Норма — от 1 до 24 часов в день"
            if (defaultBreakMinutes !in BREAK_RANGE) errors += "Перерыв — от 0 до 240 минут"
            return errors
        }

    val isValid: Boolean get() = validationErrors.isEmpty()

    companion object {
        val HOURS_RANGE = 1.0..24.0
        val BREAK_RANGE = 0..240

        /** The form filled from an existing employee. */
        fun from(e: Employee, farm: FarmTime) = EmployeeDraft(
            lastName = e.lastName, firstName = e.firstName, patronymic = e.patronymic, position = e.position,
            hireDate = farm.dayOf(e.hireDate), dismissalDate = e.dismissalDate?.let { farm.dayOf(it) },
            workSchedule = WorkSchedule.fromRaw(e.workSchedule), standardWorkHours = e.standardWorkHours,
            defaultBreakMinutes = e.defaultBreakMinutes,
        )
    }
}

sealed class EmployeeError(message: String) : Exception(message) {
    data class Invalid(val messages: List<String>) : EmployeeError(messages.joinToString("\n"))
}

/** Total of a month's Я days and worked minutes. */
data class MonthTotals(val presentDays: Int, val minutes: Int)

/**
 * Employees as pure functions: validation of the form, who works on a day, the order of names. Archiving only
 * hides an employee; their records and timesheets stay. Port of `EmployeeService`.
 */
object EmployeeRules {
    private fun validate(draft: EmployeeDraft) {
        val errors = draft.validationErrors
        if (errors.isNotEmpty()) throw EmployeeError.Invalid(errors)
    }

    private fun apply(d: EmployeeDraft, e: Employee, farm: FarmTime) = e.copy(
        lastName = d.lastName.trim(), firstName = d.firstName.trim(), patronymic = d.patronymic.trim(),
        position = d.position.trim(), hireDate = farm.startOf(d.hireDate),
        dismissalDate = d.dismissalDate?.let { farm.startOf(it) }, workSchedule = d.workSchedule.raw,
        standardWorkHours = d.standardWorkHours, defaultBreakMinutes = d.defaultBreakMinutes,
    )

    /** A new employee from the form. */
    fun create(draft: EmployeeDraft, id: String, farm: FarmTime): Employee {
        validate(draft)
        return apply(draft, Employee(id = id, hireDate = farm.startOf(draft.hireDate)), farm)
    }

    /** Changes only the employee; existing records keep their own break and minutes. */
    fun update(e: Employee, draft: EmployeeDraft, farm: FarmTime): Employee {
        validate(draft)
        return apply(draft, e, farm)
    }

    fun archive(e: Employee): Employee = e.copy(isArchived = true)

    fun restore(e: Employee): Employee = e.copy(isArchived = false)

    /** Not archived and employed on `day` (hired on or before it, not dismissed before it), by name. */
    fun active(employees: List<Employee>, day: LocalDate, farm: FarmTime): List<Employee> =
        employed(employees, day, farm).filter { !it.isArchived }

    /** Employed on `day`, archived or not, by name: who can have a mark that day. */
    fun employed(employees: List<Employee>, day: LocalDate, farm: FarmTime): List<Employee> =
        employed(employees, day, day, farm)

    /** Employed on at least one day of `start..end`, archived or not, by name. */
    fun employed(employees: List<Employee>, start: LocalDate, end: LocalDate, farm: FarmTime): List<Employee> =
        sortedByName(employees.filter { e ->
            if (farm.dayOf(e.hireDate).isAfter(end)) return@filter false
            val dismissal = e.dismissalDate
            dismissal == null || !farm.dayOf(dismissal).isBefore(start)
        })

    fun archived(employees: List<Employee>): List<Employee> = sortedByName(employees.filter { it.isArchived })

    /**
     * Я days and worked minutes of `records`, counting, like the timesheet, only days within `e`'s employment; an
     * unfinished day counts as a day with 0 minutes.
     */
    fun monthTotals(records: List<AttendanceRecord>, e: Employee, farm: FarmTime): MonthTotals {
        val hire = farm.dayOf(e.hireDate)
        val dismissal = e.dismissalDate?.let { farm.dayOf(it) }
        val counted = records.filter { r ->
            val day = farm.dayOf(r.day)
            r.dayStatus() == DayStatus.PRESENT && !day.isBefore(hire) && (dismissal == null || !day.isAfter(dismissal))
        }
        val minutes = counted.sumOf { r ->
            val unfinished = r.arrivalTime != null && r.departureTime == null && r.manualWorkedMinutes == null
            if (unfinished) 0 else WorkTimeCalculator.effectiveMinutes(r)
        }
        return MonthTotals(counted.size, minutes)
    }

    /** By ФИО in Russian alphabetical order, ignoring case. */
    fun sortedByName(employees: List<Employee>): List<Employee> =
        employees.sortedWith { a, b -> NameOrder.compare(a.fullName, b.fullName) }
}
