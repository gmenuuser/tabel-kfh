package ru.kfh.core.timesheet

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the domain part of EmployeeServiceTests.swift (the form, order, who works when, month totals). */
class EmployeeRulesTest {
    private val farm = farmIn()

    private fun draft(
        lastName: String = "Зубахин", firstName: String = "Иван", hire: LocalDate = day(1, 3, 2025),
        dismissal: LocalDate? = null,
    ) = EmployeeDraft(lastName = lastName, firstName = firstName, hireDate = hire, dismissalDate = dismissal)

    @Test fun addStoresTrimmedFieldsInNameOrder() {
        val d = draft("  Зубахин ", " Иван").copy(
            patronymic = "Петрович  ", position = " Тракторист ", workSchedule = WorkSchedule.TWO_TWO,
            standardWorkHours = 11.0, defaultBreakMinutes = 45,
        )
        val e = EmployeeRules.create(d, "id-1", farm)
        assertEquals("Зубахин", e.lastName)
        assertEquals("Иван", e.firstName)
        assertEquals("Петрович", e.patronymic)
        assertEquals("Тракторист", e.position)
        assertEquals("Зубахин Иван Петрович", e.fullName)
        assertEquals(farm.startOf(day(1, 3, 2025)), e.hireDate)
        assertNull(e.dismissalDate)
        assertFalse(e.isArchived)
        assertEquals("twoTwo", e.workSchedule)
        assertEquals(11.0, e.standardWorkHours, 0.0)
        assertEquals(45, e.defaultBreakMinutes)
    }

    @Test fun emptyLastNameIsRejected() {
        val d = draft("   ")
        assertEquals(listOf("Укажи фамилию"), d.validationErrors)
        val error = assertThrows(EmployeeError::class.java) { EmployeeRules.create(d, "id", farm) }
        assertEquals(EmployeeError.Invalid(listOf("Укажи фамилию")), error)
        assertEquals("Укажи фамилию", error.message)
    }

    @Test fun emptyFirstNameIsRejected() = assertEquals(listOf("Укажи имя"), draft(firstName = "").validationErrors)

    @Test fun dismissalBeforeHireIsRejected() {
        val d = draft(hire = day(10, 3, 2025), dismissal = day(9, 3, 2025))
        assertEquals(listOf("Дата увольнения раньше даты приёма"), d.validationErrors)
        assertThrows(EmployeeError::class.java) { EmployeeRules.create(d, "id", farm) }
        assertTrue(draft(hire = day(10, 3, 2025), dismissal = day(10, 3, 2025)).validationErrors.isEmpty())
    }

    @Test fun normAndBreakLimits() {
        var d = draft().copy(standardWorkHours = 0.5, defaultBreakMinutes = 241)
        assertEquals(listOf("Норма — от 1 до 24 часов в день", "Перерыв — от 0 до 240 минут"), d.validationErrors)
        d = d.copy(standardWorkHours = 24.0, defaultBreakMinutes = 0)
        assertTrue(d.validationErrors.isEmpty())
        assertTrue(d.isValid)
        d = d.copy(standardWorkHours = 24.5, defaultBreakMinutes = -5)
        assertEquals(2, d.validationErrors.size)
    }

    @Test fun updateChangesPositionAndKeepsRecordBreaks() {
        val e = EmployeeRules.create(draft().copy(position = "Тракторист", defaultBreakMinutes = 60), "id", farm)
        val r = farm.record(e, day(5), arrival = farm.at(5, 8), departure = farm.at(5, 17), breakMinutes = 60, worked = 480)
        var edit = EmployeeDraft.from(e, farm)
        assertEquals("Тракторист", edit.position)
        assertEquals(60, edit.defaultBreakMinutes)
        edit = edit.copy(position = " Механик ", defaultBreakMinutes = 30)
        val updated = EmployeeRules.update(e, edit, farm)
        assertEquals("Механик", updated.position)
        assertEquals(30, updated.defaultBreakMinutes)
        assertEquals(e.id, updated.id)
        assertEquals(60, r.breakMinutes)
        assertEquals(480, r.workedMinutes)
    }

    @Test fun invalidUpdateChangesNothing() {
        val e = EmployeeRules.create(draft(), "id", farm)
        val edit = EmployeeDraft.from(e, farm).copy(lastName = "", position = "Механик")
        assertThrows(EmployeeError::class.java) { EmployeeRules.update(e, edit, farm) }
        assertEquals("Зубахин", e.lastName)
        assertEquals("", e.position)
    }

    @Test fun archiveRemovesFromActiveAndRestoreReturns() {
        val e = EmployeeRules.create(draft(), "id", farm)
        val today = day(6)
        assertEquals(listOf(e.id), EmployeeRules.active(listOf(e), today, farm).map { it.id })
        val archived = EmployeeRules.archive(e)
        assertTrue(archived.isArchived)
        assertTrue(EmployeeRules.active(listOf(archived), today, farm).isEmpty())
        assertEquals(listOf(e.id), EmployeeRules.archived(listOf(archived)).map { it.id })
        val back = EmployeeRules.restore(archived)
        assertFalse(back.isArchived)
        assertEquals(listOf(e.id), EmployeeRules.active(listOf(back), today, farm).map { it.id })
        assertTrue(EmployeeRules.archived(listOf(back)).isEmpty())
    }

    @Test fun activeExcludesNotYetHiredAndDismissedAndSortsByName() {
        val today = day(6)
        val all = listOf(
            farm.employee("Яковлев"), farm.employee("борисов"), farm.employee("Алексеев", hire = today),
            farm.employee("Ёлкин", dismissal = today), farm.employee("Будущий", hire = day(7)),
            farm.employee("Уволенный", dismissal = day(5)),
        )
        assertEquals(
            listOf("Алексеев", "борисов", "Ёлкин", "Яковлев"),
            EmployeeRules.active(all, today, farm).map { it.lastName },
        )
    }

    @Test fun employedIncludesArchivedAndRangeOverlap() {
        val all = listOf(
            farm.employee("Иванов"), farm.employee("Архипов", dismissal = day(10), archived = true),
            farm.employee("Старов", dismissal = day(31, 10)), farm.employee("Будущев", hire = day(1, 12)),
        )
        assertEquals(
            listOf("Архипов", "Иванов"),
            EmployeeRules.employed(all, day(1), day(30), farm).map { it.lastName },
        )
        assertEquals(listOf("Архипов"), EmployeeRules.employed(all, day(5), farm).map { it.lastName }.take(1))
    }

    /** The history's month totals count, like the timesheet, only days within employment. */
    @Test fun monthTotalsCountOnlyDaysWithinEmployment() {
        val e = farm.employee("Зубахин", hire = day(3), dismissal = day(20))
        fun rec(d: Int, status: String, minutes: Int? = null, open: Boolean = false) = farm.record(
            e, day(d), status = status,
            arrival = if (status == "Я") farm.at(d, 8) else null,
            departure = if (status == "Я" && !open) farm.at(d, 17) else null,
            worked = minutes,
        )
        val records = listOf(
            rec(2, "Я", 480), rec(5, "Я", 480), rec(6, "Я", open = true), rec(7, "Б"), rec(21, "Я", 300),
        )
        val totals = EmployeeRules.monthTotals(records, e, farm)
        assertEquals(2, totals.presentDays)
        assertEquals(480, totals.minutes)
    }

    @Test fun draftFromEmployeeCopiesAllFields() {
        val d = draft(dismissal = day(1, 12)).copy(
            patronymic = "Петрович", position = "Агроном", workSchedule = WorkSchedule.SIX_ONE,
            standardWorkHours = 7.5, defaultBreakMinutes = 30,
        )
        assertEquals(d, EmployeeDraft.from(EmployeeRules.create(d, "id", farm), farm))
    }
}
