package ru.kfh.tabel.export

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import ru.kfh.core.agro.AgroReportLayout
import ru.kfh.core.livestock.LivestockBooks
import ru.kfh.core.livestock.LivestockOperationKind
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.livestock.LivestockReport
import ru.kfh.core.livestock.LivestockRules
import ru.kfh.core.livestock.Species
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AgroLine
import ru.kfh.core.model.Employee
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.time.DEFAULT_FARM_ZONE
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.timesheet.TimesheetBuilder
import ru.kfh.core.timesheet.TimesheetDocument

/** The documents of the review samples (`PDFRendererTests.sampleDocument` and the coal / livestock / agro spec data). */
object SampleDocuments {
    val farm = FarmTime(DEFAULT_FARM_ZONE)

    val info = FarmInfo(
        farmName = "КФХ Зубахин", managerName = "Зубахин Иван Петрович", responsiblePerson = "Кривошеева Мария Алексеевна",
        address = "Ростовская обл., Сальский р-н, х. Зубахин, ул. Степная, 12", phone = "+7 (928) 123-45-67", extraInfo = "",
    )

    private val surnames = listOf(
        "Константинопольский", "Зубахин", "Преображенский", "Ковальчук", "Иванов", "Вознесенский", "Шереметьев", "Ли",
        "Сидоренко", "Кривошеев", "Новиков", "Белоусов", "Ястржембский", "Орлов", "Петров",
    )
    private val givenNames = listOf(
        "Александр Владиславович", "Иван Петрович", "Вячеслав Константинович", "Пётр Ильич", "Максимилиан Святославович",
        "Олег Юрьевич", "Дмитрий Александрович", "Ян",
    )

    /** 31.12.2026 18:00 at the farm. */
    val createdAt: Instant = farm.startOf(LocalDate.of(2026, 12, 31)).plusSeconds(18 * 3600)

    private fun employees(count: Int): List<Employee> = (0 until count).map { i ->
        val kind = i % 12
        val given = givenNames[(i + i / surnames.size * 3) % givenNames.size].split(" ")
        Employee(
            id = "e$i", lastName = surnames[i % surnames.size], firstName = given[0], patronymic = given.getOrElse(1) { "" }, position = "Рабочий",
            hireDate = farm.startOf(if (kind == 7) LocalDate.of(2026, 12, 10) else LocalDate.of(2020, 1, 1)),
            dismissalDate = if (kind == 6) farm.startOf(LocalDate.of(2026, 12, 15)) else null,
        )
    }

    /**
     * December 2026 of hours (what the iPhone and the app export; the hours counterpart of `sampleDocument` of
     * PDFRendererTests.swift): 8 on weekdays with 4 / 12 / 0 sprinkled in, weekends blank, one dismissed on the 15th,
     * one hired on the 10th, blanks at the end.
     */
    fun hours(count: Int): TimesheetDocument {
        val employees = employees(count)
        val marks = mutableListOf<HoursMark>()
        for ((i, e) in employees.withIndex()) {
            val kind = i % 12
            for (day in 1..31) {
                val date = LocalDate.of(2026, 12, day)
                val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                if (weekend && kind != 4) continue
                if (kind == 9 && day >= 30) continue
                val hours = when ((i * 13 + day * 7) % 9) {
                    0 -> 4
                    1 -> 12
                    2 -> if (kind == 5) 0 else 8
                    else -> 8
                }
                marks += HoursMark(id = "m-${e.id}-$day", employeeId = e.id, day = farm.startOf(date), hours = hours, updatedAt = createdAt)
            }
        }
        return TimesheetBuilder.build(YearMonth.of(2026, 12), employees, marks, emptyList(), null, info, createdAt = createdAt, farm = farm)
    }

    // ---- Livestock ----

    private val livestockNow: Instant = farm.startOf(LocalDate.of(2026, 11, 28)).plusSeconds(10 * 3600)

    fun livestockBooks(): LivestockBooks {
        val categories = listOf(
            LivestockCategory("c1", "cattle", "Коровы", 0), LivestockCategory("c2", "cattle", "Телята", 1),
            LivestockCategory("c3", "goats", "Козы", 0), LivestockCategory("c4", "goats", "Козлята", 1),
            LivestockCategory("c5", "pigs", "Свиноматки", 0),
        )
        fun at(day: Int, hour: Int = 9) = farm.startOf(LocalDate.of(2026, 11, day)).plusSeconds(hour * 3600L)
        val operations = listOf(
            LivestockOperation("o1", LivestockOperationKind.INITIAL.wire, at(1), "c1", heads = 20),
            LivestockOperation("o2", LivestockOperationKind.INITIAL.wire, at(1), "c2", heads = 6),
            LivestockOperation("o3", LivestockOperationKind.INITIAL.wire, at(1), "c3", heads = 15),
            LivestockOperation("o4", LivestockOperationKind.BIRTH.wire, at(5), "c4", heads = 3),
            LivestockOperation("o5", LivestockOperationKind.PURCHASE.wire, at(8), "c5", heads = 4),
            LivestockOperation("o6", LivestockOperationKind.SLAUGHTER.wire, at(12), "c1", heads = 2, comment = "на мясо"),
            LivestockOperation("o7", LivestockOperationKind.SLAUGHTER.wire, at(20), "c3", heads = 1),
            LivestockOperation("o8", LivestockOperationKind.DEATH.wire, at(22), "c4", heads = 1),
            LivestockOperation("o9", LivestockOperationKind.SALE.wire, at(25), "c3", heads = 2),
        )
        val slaughters = listOf(
            SlaughterRecord("s1", "o6", "meat", liveWeightGrams = 900_000, carcassWeightGrams = 500_000, meatWeightGrams = 420_000),
            SlaughterRecord("s2", "o7", "forced", liveWeightGrams = 45_000, carcassWeightGrams = null, meatWeightGrams = 20_000),
        )
        return LivestockBooks(categories, operations, slaughters, farm)
    }

    fun herd(): ReportDocument {
        val books = livestockBooks()
        val balances = LivestockRules.balances(books)
        val lines = LivestockRules.categories(books).map { LivestockReport.HerdLine(Species.fromWire(it.species), it.name, balances[it.id] ?: 0) }
        return LivestockReport.herd(lines, farm.dayOf(livestockNow), info, livestockNow, farm)
    }

    fun slaughter(): ReportDocument {
        val books = livestockBooks()
        val p = LivestockRules.period(LivestockPeriod.MONTH, livestockNow, farm)
        val byId = books.categories.associateBy { it.id }
        val lines = books.operations.filter { it.kind == LivestockOperationKind.SLAUGHTER.wire }.map { o ->
            val s = books.slaughterOf(o.id)
            LivestockReport.SlaughterLine(
                o.date, Species.fromWire(byId[o.categoryId]!!.species), byId[o.categoryId]!!.name, o.heads,
                if (s?.reason == "meat") "На мясо" else "Вынужденный забой", s?.liveWeightGrams, s?.meatWeightGrams, o.comment,
            )
        }
        return LivestockReport.slaughter(LivestockRules.slaughterSummary(books, p.start, p.end), lines, LivestockPeriod.MONTH, p.start, info, livestockNow, farm)
    }

    fun movement(): ReportDocument {
        val books = livestockBooks()
        val p = LivestockRules.period(LivestockPeriod.MONTH, livestockNow, farm)
        return LivestockReport.movement(LivestockRules.movement(books, p.start, p.end), LivestockPeriod.MONTH, p.start, info, livestockNow, farm)
    }

    // ---- Agro ----

    fun agro(): ReportDocument {
        val items = listOf(
            AgroCalculation(
                "a1", "seeding", "Поле у реки", mapOf("area" to "10", "rate" to "200"),
                listOf(AgroLine("Нужно семян", "2 000 кг", true), AgroLine("В центнерах", "20 ц"), AgroLine("В тоннах", "2 т")),
                createdAt = livestockNow, updatedAt = livestockNow,
            ),
            AgroCalculation(
                "a2", "fuel", "", mapOf("area" to "25", "rate" to "12,5"),
                listOf(AgroLine("Нужно топлива", "312,5 л", true)),
                createdAt = livestockNow.plusSeconds(3600), updatedAt = livestockNow.plusSeconds(3600),
            ),
        )
        return AgroReportLayout.history(items, info, livestockNow.plusSeconds(7200), farm)
    }
}
