package ru.kfh.tabel.ui.agro

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.core.agro.AgroEngine
import ru.kfh.core.agro.AgroError
import ru.kfh.core.agro.AgroKind
import ru.kfh.core.livestock.LivestockPeriod
import ru.kfh.core.livestock.LivestockRules
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.Entities
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.time.FarmTime
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.observe
import ru.kfh.tabel.ui.common.ActionQueue
import java.time.Instant
import java.time.LocalDate

/** The inputs and the title of a calculator the owner left without saving (`UserDefaults` «agroDraft.<kind>» on the iPhone). */
data class AgroDraft(val inputs: Map<String, String>, val title: String)

/** Where the drafts live: `SharedPreferences` in the app, a map in tests. Keys are «agroDraft.<kind>». */
interface AgroDraftStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** The drafts in the app's `SharedPreferences` (file [FILE]); they survive a recreate and the process' death. */
class PrefsAgroDraftStore(context: Context) : AgroDraftStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val FILE = "agro"
    }
}

/**
 * «Агрономические калькуляторы»: the saved calculations as a flow, the drafts of the calculators, and «Сохранить» /
 * «Удалить» written through [RecordWriter]. One per process ([ru.kfh.tabel.AppGraph.agro]).
 *
 * - Every formula is [AgroEngine]'s; the screens only show its result.
 * - A draft is per device, never synced (as the iPhone's `UserDefaults` and the site's `localStorage`): one JSON object
 *   of strings under «agroDraft.<kind>», the title under «__title» (the iPhone's layout).
 * - A save runs in [scope] (the app's) one at a time; a second save of the same form id is refused
 *   («Эта запись уже сохранена»).
 * - One save = one op in one transaction; a correction queues only what changed.
 */
class AgroViewModel(
    private val dao: RecordDao,
    private val writer: RecordWriter,
    private val farmTime: StateFlow<FarmTime>,
    scope: CoroutineScope,
    private val drafts: AgroDraftStore,
    private val now: () -> Instant = { Instant.now() },
    /** The farm's name and manager (from `Me`) and the settings row: the header of the history's PDF. */
    val farmInfo: Flow<FarmInfo> = kotlinx.coroutines.flow.flowOf(FarmInfo.DEFAULT),
) {
    private val actions = ActionQueue(scope)

    val farm: FarmTime get() = farmTime.value

    fun now(): Instant = now.invoke()

    /** May the signed-in role save and delete calculations (`Role.canWrite(agroCalculation)`; a viewer only reads). */
    val canSave: Boolean get() = writer.canWrite(Entities.agroCalculation)

    /** Every saved calculation, newest first (`AgroHomeView.saved`, `AgroHistoryView.all`). */
    val calculations: Flow<List<AgroCalculation>> = dao.observe(Entities.agroCalculation)
        .map { rows -> AgroEngine.history(rows.map { RecordCodec.decode<AgroCalculation>(Entities.agroCalculation, it.id, it.json()) }) }
        .flowOn(Dispatchers.Default)

    fun calculation(id: String): Flow<AgroCalculation?> = calculations.map { list -> list.firstOrNull { it.id.equals(id, ignoreCase = true) } }

    /** The history filtered as `AgroService.history`: by calculator, a text, and the farm's day / month / year of [day]. */
    fun history(
        items: List<AgroCalculation>, kind: AgroKind? = null, search: String = "", period: LivestockPeriod? = null, day: LocalDate = farm.today(),
    ): List<AgroCalculation> {
        val range = period?.let { LivestockRules.period(it, farm.startOf(day), farm) }
        return AgroEngine.history(items, kind, search, range?.start, range?.end)
    }

    // MARK: Drafts

    fun draft(kind: AgroKind): AgroDraft? {
        val text = drafts.get(key(kind)) ?: return null
        val all = try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            return null
        }
        return AgroDraft(all.filterKeys { it != TITLE }, all[TITLE] ?: "")
    }

    fun storeDraft(kind: AgroKind, inputs: Map<String, String>, title: String) {
        drafts.put(key(kind), json.encodeToString(serializer, inputs + (TITLE to title)))
    }

    fun clearDraft(kind: AgroKind) = drafts.remove(key(kind))

    // MARK: Saving

    /** The id of a new form; a second save under it is refused. */
    fun newId(): String = RecordWriter.newId()

    /**
     * «Сохранить» (a new calculation under [id]; its draft is dropped), «Сохранить изменения» ([over] is corrected in
     * place, its id and `createdAt` kept) or «Сохранить как новый расчёт» ([over] null with [clearDraft] false).
     * Only a complete result is saved: an incomplete one fails with «Заполните: …» or the field's error.
     */
    suspend fun save(
        kind: AgroKind, inputs: Map<String, String>, title: String, id: String, over: AgroCalculation? = null, clearDraft: Boolean = over == null,
    ): Result<AgroCalculation> = actions.run {
        try {
            if (over == null) {
                val stored = dao.get(Entities.agroCalculation, id.lowercase())
                if (stored != null && !stored.deleted) throw IllegalStateException("Эта запись уже сохранена")
            }
            val c = AgroEngine.record(kind, inputs, title, id.lowercase(), now(), over)
            writer.save(Entities.agroCalculation, c.id, RecordCodec.encode(c))
            if (clearDraft) clearDraft(kind)
            Result.success(c)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(if (e is AgroError || e is IllegalStateException) e else IllegalStateException(describe(e), e))
        }
    }

    /** «Удалить расчёт»: the text for «Не получилось», or null when it went through. */
    suspend fun delete(c: AgroCalculation): String? = actions.run {
        try {
            writer.delete(Entities.agroCalculation, c.id)
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            describe(e)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is AgroError, is IllegalStateException -> e.message ?: "Не получилось"
        else -> "Не получилось: ${e.message ?: e::class.java.simpleName}"
    }

    private companion object {
        const val TITLE = "__title"
        val json = Json
        val serializer = MapSerializer(String.serializer(), String.serializer())

        fun key(kind: AgroKind) = "agroDraft.${kind.wire}"
    }
}
