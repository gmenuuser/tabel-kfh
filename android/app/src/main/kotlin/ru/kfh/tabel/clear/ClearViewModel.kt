package ru.kfh.tabel.clear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.TabelDb
import java.time.LocalDate
import java.time.YearMonth

/**
 * «Очистка данных» (owner): раздел × день/месяц. Месяц требует набрать слово [WORD] — отменить очистку нельзя,
 * а промах датой на телефоне стоит дорого. Сама очистка — обычные операции записи, поэтому уезжает на сервер
 * и на остальные телефоны той же синхронизацией.
 */
class ClearViewModel(
    private val db: TabelDb,
    private val writer: RecordWriter,
    /** The farm's clock and zone: the phone's own zone is not the farm's (`session.farmTime`). */
    private val time: StateFlow<FarmTime>,
) : ViewModel() {

    data class State(
        val section: ClearSection,
        val mode: PeriodMode,
        val day: LocalDate,
        val month: YearMonth,
        val typed: String = "",
        val count: Int = 0,
        val busy: Boolean = false,
        val note: String? = null,
    ) {
        /** Месяц без набранного слова не очищается; день — по обычному подтверждению. */
        val ready: Boolean
            get() = count > 0 && !busy && (mode == PeriodMode.Day || typed.trim().lowercase() == WORD)
    }

    private val _state = MutableStateFlow(
        State(section = ClearSection.Timesheet, mode = PeriodMode.Month, day = time.value.today(),
              month = YearMonth.from(time.value.today())),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun chooseSection(section: ClearSection) {
        _state.update { it.copy(section = section, typed = "", note = null) }
        refresh()
    }

    fun chooseMode(mode: PeriodMode) {
        _state.update { it.copy(mode = mode, typed = "", note = null) }
        refresh()
    }

    fun chooseDay(day: LocalDate) {
        _state.update { it.copy(day = day, note = null) }
        refresh()
    }

    fun chooseMonth(month: YearMonth) {
        _state.update { it.copy(month = month, typed = "", note = null) }
        refresh()
    }

    fun type(text: String) = _state.update { it.copy(typed = text) }

    /** Сколько записей уйдёт за текущий период — считается по тому, что лежит на телефоне. */
    private fun refresh() {
        viewModelScope.launch {
            val s = _state.value
            val (from, to) = bounds(s.mode, s.day, s.month)
            val rows = withContext(Dispatchers.IO) { db.records().all() }
            _state.update { it.copy(count = countInPeriod(rows, s.section, from, to, time.value.zone)) }
        }
    }

    fun clear() {
        val s = _state.value
        if (!s.ready) return
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val note = try {
                val (from, to) = bounds(s.mode, s.day, s.month)
                val rows = withContext(Dispatchers.IO) { db.records().all() }
                var touched = 0
                // Забои ищутся до удаления операций: после него связь уже не восстановить.
                val slaughters = if (s.section == ClearSection.Livestock) {
                    slaughterOfClearedOps(rows, from, to, time.value.zone)
                } else {
                    emptyList()
                }
                for (target in CLEAR_TARGETS.getValue(s.section)) {
                    for (row in rowsInPeriod(rows, target, from, to, time.value.zone)) {
                        writer.delete(target.entity, row.id)
                        touched++
                    }
                }
                for (row in slaughters) {
                    writer.delete("slaughterRecord", row.id)
                    touched++
                }
                "Удалено записей: $touched. Остальные телефоны получат это при синхронизации."
            } catch (e: Exception) {
                "Не удалось очистить: ${e.message ?: "ошибка записи"}"
            }
            val rows = withContext(Dispatchers.IO) { db.records().all() }
            val (from, to) = bounds(s.mode, s.day, s.month)
            _state.update {
                it.copy(busy = false, typed = "", note = note, count = countInPeriod(rows, s.section, from, to, time.value.zone))
            }
        }
    }

    companion object {
        /** Слово-подтверждение месяца: набрано без ошибок — можно чистить. */
        const val WORD = "очистить"
    }
}
