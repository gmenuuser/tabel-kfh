package ru.kfh.tabel.clear

import kotlinx.serialization.json.JsonNull
import ru.kfh.tabel.db.RecordRow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * «Очистка периода»: какие записи раздела уходят за выбранный день или месяц. Таблица повторяет сайт
 * (`web/src/lib/clear.ts`) — телефон и сайт чистят одни и те же строки. Справочники (сотрудники, водители,
 * категории, настройки) не трогаются никогда.
 */
enum class ClearSection(val title: String) {
    Timesheet("табель"),
    Coal("уголь"),
    Livestock("скот"),
    Agro("агро"),
}

/** Как тип записи участвует в очистке. */
enum class ClearMode {
    /** Операция удаления: запись исчезает и на сервере, и на остальных устройствах. */
    Delete,

    /** Отметка табеля не удаляется, а обнуляется (`hours: null`) — так приложение понимает «отметки нет». */
    UnsetHours,
}

data class ClearTarget(val entity: String, val dateField: String, val mode: ClearMode)

val CLEAR_TARGETS: Map<ClearSection, List<ClearTarget>> = mapOf(
    ClearSection.Timesheet to listOf(ClearTarget("hoursMark", "day", ClearMode.UnsetHours)),
    ClearSection.Coal to listOf(
        ClearTarget("coalSale", "saleDate", ClearMode.Delete),
        ClearTarget("coalWorkDay", "day", ClearMode.Delete),
        ClearTarget("driverPayment", "paymentDate", ClearMode.Delete),
    ),
    ClearSection.Livestock to listOf(ClearTarget("livestockOperation", "date", ClearMode.Delete)),
    ClearSection.Agro to listOf(ClearTarget("agroCalculation", "createdAt", ClearMode.Delete)),
)

/** День или месяц — так же, как на сайте. */
enum class PeriodMode(val title: String) {
    Day("День"),
    Month("Месяц"),
}

/** Границы очистки: один день или весь месяц, обе границы включительно. */
fun bounds(mode: PeriodMode, day: LocalDate, month: YearMonth): Pair<LocalDate, LocalDate> = when (mode) {
    PeriodMode.Day -> day to day
    PeriodMode.Month -> month.atDay(1) to month.atEndOfMonth()
}

/** День хозяйства для даты записи: `2026-11-17`, ISO-момент в любом смещении или мусор (тогда null). */
fun dayOf(raw: String, zone: ZoneId): LocalDate? {
    if (raw.isBlank()) return null
    return runCatching { LocalDate.parse(raw) }.getOrNull()
        ?: runCatching { Instant.parse(raw).atZone(zone).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
}

/** Записи [target], у которых деловая дата попадает в [from]..[to]. Без даты — не попадает никогда. */
fun rowsInPeriod(
    rows: List<RecordRow>,
    target: ClearTarget,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<RecordRow> = rows.filter { row ->
    if (row.deleted) return@filter false
    val json = row.json()
    if (target.mode == ClearMode.UnsetHours) {
        val hours = json["hours"]
        if (hours == null || hours is JsonNull) return@filter false
    }
    val raw = json[target.dateField]?.toString()?.trim('"') ?: return@filter false
    val day = dayOf(raw, zone) ?: return@filter false
    !day.isBefore(from) && !day.isAfter(to)
}

/** Причины забоя привязаны к операции и своей даты не имеют: уходят вместе с ней. */
fun slaughterOfClearedOps(
    rows: List<RecordRow>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<RecordRow> {
    val ops = rowsInPeriod(rows, CLEAR_TARGETS.getValue(ClearSection.Livestock).first(), from, to, zone)
    if (ops.isEmpty()) return emptyList()
    val ids = ops.mapTo(HashSet()) { it.id }
    return rows.filter { row ->
        if (row.deleted || row.entity != "slaughterRecord") return@filter false
        row.json()["operationId"]?.toString()?.trim('"') in ids
    }
}

/** Сколько записей раздела уйдёт за период — для предупреждения до нажатия. */
fun countInPeriod(
    rows: List<RecordRow>,
    section: ClearSection,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): Int {
    var n = CLEAR_TARGETS.getValue(section).sumOf { rowsInPeriod(rows, it, from, to, zone).size }
    if (section == ClearSection.Livestock) n += slaughterOfClearedOps(rows, from, to, zone).size
    return n
}
