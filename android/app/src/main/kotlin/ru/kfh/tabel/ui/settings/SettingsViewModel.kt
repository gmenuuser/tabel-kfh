package ru.kfh.tabel.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kfh.core.model.Entities
import ru.kfh.core.model.FarmSettings
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.roles.Module
import ru.kfh.core.roles.Role
import ru.kfh.core.roles.modules
import ru.kfh.core.sync.Api
import ru.kfh.core.sync.Backup
import ru.kfh.core.sync.Conflict
import ru.kfh.core.sync.ConflictChoice
import ru.kfh.core.sync.Device
import ru.kfh.core.sync.Me
import ru.kfh.core.sync.Member
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.RefusedChanges
import ru.kfh.tabel.db.RefusedRow
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.observe
import ru.kfh.tabel.export.LocalBackup
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.session.farmInfo
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * «Настройки» (`SettingsView` and its sub-views): the farm's details, the sync and the refused changes, the owner's
 * lists on the server (helpers, devices, conflicts, server copies), the copy to a file, «Выйти».
 *
 * - The farm's name and manager go through `PATCH /farm` ([saveFarm], owner only); the other four fields are the
 *   synced `farmSettings` row written through [RecordWriter] ([saveSettings], one op).
 * - Every request and write runs in the app's [scope]: leaving the screen never cancels it.
 * - An action returns the text to show (the iPhone's note), or null when nothing is to be said.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val session: SessionManager,
    private val db: TabelDb,
    private val writer: RecordWriter,
    private val scope: CoroutineScope,
    private val photos: PhotoStore,
    private val backupDir: File = File(System.getProperty("java.io.tmpdir"), "tabel-backup"),
    private val now: () -> Instant = { Instant.now() },
    /** «1.0.7 (7)»: the version name and code of the installed app. */
    private val appVersion: String = "",
) {
    /** «Конфликты: 2 — выбрать версию» (owner) or «Конфликты: 2 — обратитесь к владельцу»; null without conflicts. */
    data class ConflictsRow(val text: String, val canResolve: Boolean)

    /** The «Синхронизация» section. */
    data class SyncRow(val status: SyncStatus, val lastSyncAt: Instant?, val conflicts: ConflictsRow?)

    /** One of «Не принятые изменения». */
    data class RefusedChange(val opId: String, val title: String, val error: String, val at: Instant)

    // MARK: Who

    val me: Flow<Me?> = session.state.map { (it as? SessionState.SignedIn)?.me }

    private val role: Role get() = session.role ?: Role.viewer

    val isOwner: Boolean get() = role == Role.owner

    /** «Название КФХ» and «ФИО руководителя» are the owner's (`PATCH /farm`). */
    val canEditFarm: Boolean get() = isOwner

    /** The four printed fields of the settings row. */
    val canEditSettings: Boolean get() = writer.canWrite(Entities.farmSettings)

    val seesCoal: Boolean get() = Module.coal in role.modules()

    /** «Версия 1.0.7 (7)». */
    val versionText: String get() = "Версия $appVersion"

    /** The farm's zone: the moments of the lists are shown in it. */
    val zone: ZoneId get() = session.farmTime.value.zone

    // MARK: Farm

    /** The synced settings row, or null before the farm has one. */
    val settings: Flow<FarmSettings?> = db.records().observe(Entities.farmSettings).map { rows ->
        rows.firstOrNull()?.let { RecordCodec.decode<FarmSettings>(Entities.farmSettings, it.id, it.json()) }
    }.flowOn(Dispatchers.Default)

    /** «Сохранить название и руководителя»: «Сохранено» or «Не сохранено: …». */
    suspend fun saveFarm(name: String, managerName: String): String = inApp {
        val error = session.updateFarm(name.trim(), managerName.trim())
        if (error == null) "Сохранено" else "Не сохранено: $error"
    }

    /** The printed fields: one op with the changed ones; the same values queue nothing. */
    suspend fun saveSettings(s: FarmSettings): String? = inApp {
        try {
            writer.save(Entities.farmSettings, s.id, RecordCodec.encode(s))
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.message ?: "Не получилось"
        }
    }

    /** The same, fired when the screen goes away in the middle of typing (its own scope is gone by then). */
    fun saveSettingsInBackground(s: FarmSettings) {
        scope.launch { saveSettings(s) }
    }

    // MARK: Sync

    val sync: Flow<SyncRow> = combine(
        session.engine.flatMapLatest { it?.status ?: flowOf<SyncStatus>(SyncStatus.Synced) },
        session.engine.flatMapLatest { it?.lastSyncAt ?: flowOf(null) },
        session.state,
    ) { status, last, state ->
        val r = (state as? SessionState.SignedIn)?.me?.role ?: Role.viewer
        SyncRow(status, last, conflictsRow(status, r))
    }.distinctUntilChanged()

    suspend fun syncNow() = inApp { session.syncNow() }

    /** The refused ops, oldest first. */
    val refused: Flow<List<RefusedChange>> = db.outbox().observeRefused().map { rows -> rows.map(::refusedChange) }

    /**
     * «Удалить» a refused change (`RefusedChanges.discard`), then the status from the store and a sync from the
     * start: the server's values come back on the phone. The text when it could not be removed.
     */
    suspend fun deleteRefused(r: RefusedChange): String? = inApp {
        try {
            RefusedChanges.discard(db, r.opId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@inApp "Не удалось удалить: ${e.message ?: e::class.java.simpleName}"
        }
        session.engine.value?.refreshStatus()
        session.resyncFromStart()
        null
    }

    // MARK: Conflicts (owner)

    suspend fun conflicts(): Result<List<Conflict>> = load { it.conflicts() }

    /** «Взять с сервера» / «Взять с устройства», then a sync (the chosen version comes to this phone with it). */
    suspend fun resolve(c: Conflict, choice: ConflictChoice): String? = inApp {
        // The sync after the answer belongs to the action: backing out of «Конфликты» must not cancel it.
        val error = act(null) { it.resolve(c.id, choice) }
        if (error == null) session.syncNow()
        error
    }

    // MARK: Helpers (owner)

    /** The helpers (`HelpersView.helpers`): the members that are not the owner. */
    suspend fun members(): Result<List<Member>> = load { api -> api.members().filter { it.role != Role.owner } }

    suspend fun addMember(login: String, password: String, role: Role): String =
        act("Помощник добавлен") { it.addMember(login.trim(), password, role) }!!

    suspend fun setRole(userId: String, role: Role): String = act("Роль изменена") { it.setRole(userId, role) }!!

    suspend fun setPassword(userId: String, password: String): String = act("Пароль изменён") { it.setPassword(userId, password) }!!

    suspend fun removeMember(userId: String): String = act("Помощник отключён") { it.removeMember(userId) }!!

    // MARK: Devices (owner)

    suspend fun devices(): Result<List<Device>> = load { it.devices() }

    suspend fun revoke(deviceId: String): String = act("Доступ отозван") { it.revoke(deviceId) }!!

    // MARK: Server copies (owner)

    suspend fun backups(): Result<List<Backup>> = load { it.backups() }

    suspend fun createBackup(): String = act("Копия на сервере создана") { it.createBackup() }!!

    suspend fun verifyBackup(id: String): String = inApp {
        try {
            if (session.request { it.verifyBackup(id) }) "Копия цела" else "Копия повреждена"
        } catch (e: Exception) {
            session.errorText(e)
        }
    }

    /** «Восстановить»: every record came back as a new version — the phone takes them all again from the start. */
    suspend fun restoreBackup(id: String): String = inApp {
        try {
            session.request { it.restoreBackup(id) }
        } catch (e: Exception) {
            return@inApp session.errorText(e)
        }
        session.resyncFromStart()
        "Данные восстановлены из копии"
    }

    // MARK: Sign out

    suspend fun unsent(): SessionManager.Unsent = session.unsent()

    /** The question before «Выйти» (`SettingsView.signOutQuestion`), with the counts of this moment. */
    suspend fun signOutQuestion(): String = signOutQuestion(unsent())

    /** As [SessionManager.signOut], in the app's scope: leaving the screen (signed out) never cancels it. */
    suspend fun signOut(force: Boolean): Int = inApp { session.signOut(force) }

    // MARK: Copy to a file

    /** «Сохранить копию в файл»: the iPhone's backup file with everything on this phone, in the cache. */
    suspend fun makeBackupFile(): File = withContext(Dispatchers.IO) {
        val me = (session.state.value as? SessionState.SignedIn)?.me
        val info = farmInfo(me, settings.first())
        val records = LocalBackup.records(db.records(), photos, info)
        LocalBackup.write(backupDir, records, now(), appVersion, session.farmTime.value.zone)
    }

    // MARK: Running

    private suspend fun <T> inApp(block: suspend () -> T): T = scope.async { block() }.await()

    /** One of the owner's lists; a failure carries the text for «Список не загружен: …». */
    private suspend fun <T> load(block: suspend (Api) -> T): Result<T> = inApp {
        try {
            Result.success(session.request(block))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(IllegalStateException(session.errorText(e), e))
        }
    }

    /** A request, then [done] (or null); the server's text when it fails. */
    private suspend fun act(done: String?, block: suspend (Api) -> Unit): String? = inApp {
        try {
            session.request(block)
            done
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            session.errorText(e)
        }
    }

    private fun refusedChange(row: RefusedRow) = RefusedChange(
        opId = row.opId,
        title = RefusedChanges.title(row),
        error = row.errorList().filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Сервер не принял изменение" },
        at = Instant.ofEpochMilli(row.at),
    )

    companion object {
        /** The conflicts line of the sync section; only the owner chooses a version. */
        fun conflictsRow(status: SyncStatus, role: Role): ConflictsRow? {
            val n = (status as? SyncStatus.Conflicts)?.count ?: return null
            return if (role == Role.owner) ConflictsRow("Конфликты: $n — выбрать версию", canResolve = true)
            else ConflictsRow("Конфликты: $n — обратитесь к владельцу", canResolve = false)
        }
    }
}
