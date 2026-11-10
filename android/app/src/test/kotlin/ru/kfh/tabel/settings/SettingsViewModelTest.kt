package ru.kfh.tabel.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.kfh.core.ids.StableId
import ru.kfh.core.model.Entities
import ru.kfh.core.model.FarmSettings
import ru.kfh.core.roles.Role
import ru.kfh.core.sync.ConflictChoice
import ru.kfh.core.sync.SyncStatus
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordWriter
import ru.kfh.tabel.db.RoomLocalStore
import ru.kfh.tabel.db.TabelDb
import ru.kfh.tabel.db.obj
import ru.kfh.tabel.db.openTestDb
import ru.kfh.tabel.session.FarmServer
import ru.kfh.tabel.session.MemoryTokenStore
import ru.kfh.tabel.session.SessionManager
import ru.kfh.tabel.session.SessionState
import ru.kfh.tabel.ui.settings.SettingsViewModel
import java.time.Instant

/** «Настройки» over a real session, store and fake farm server: what the owner and a helper see and do. */
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: FarmServer
    private lateinit var db: TabelDb
    private lateinit var photos: PhotoStore
    private lateinit var store: RoomLocalStore
    private lateinit var session: SessionManager
    private lateinit var writer: RecordWriter
    private lateinit var scope: CoroutineScope
    private lateinit var vm: SettingsViewModel

    private val driverA = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a01"
    private val driverB = "0b6f3a52-7c1e-4d7a-9a51-3f2c8e7d1a02"
    private val moment: Instant = Instant.parse("2026-11-06T03:04:05Z")

    @Before
    fun setUp() {
        server = FarmServer()
        db = openTestDb(tmp.newFile("s.db").also { it.delete() })
        photos = PhotoStore(tmp.newFolder("photos"))
        store = RoomLocalStore(db, photos)
        session = SessionManager(store, MemoryTokenStore(), server.url, OkHttpClient(), "Google Pixel 9")
        runBlocking { session.restore() }
        writer = RecordWriter(db, { session.role ?: Role.viewer })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        vm = SettingsViewModel(session, db, writer, scope, photos, now = { moment }, appVersion = "1.0.7 (7)")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        db.close()
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { body() } }

    private suspend fun signedIn(role: String = "owner") {
        server.role = role
        assertNull(session.signIn("zubakhin", "secret"))
        assertTrue(session.state.value is SessionState.SignedIn)
    }

    private fun conflict(id: String) = buildJsonObject {
        put("id", id); put("entity", "driver"); put("recordId", driverA); put("field", "lastName")
        put("serverValue", "Мунгалов"); put("clientValue", "Мунгалов-Петров"); put("createdAt", "2026-11-06T03:00:00.000Z")
    }

    // MARK: Conflicts

    @Test
    fun helperSeesConflictCountOnly() = test {
        server.conflicts = listOf(conflict("c1"), conflict("c2"))
        signedIn("coal")
        assertEquals(SyncStatus.Conflicts(2), session.engine.value!!.status.value)

        val row = vm.sync.first().conflicts
        assertNotNull(row)
        assertEquals("Конфликты: 2 — обратитесь к владельцу", row!!.text)
        // No «выбрать версию»: the helper never gets the list or the buttons.
        assertFalse(row.canResolve)
        assertFalse(vm.isOwner)
        assertTrue(server.requests("/conflicts").none { it.path.endsWith("/resolve") })
    }

    @Test
    fun ownerResolvesConflict() = test {
        server.conflicts = listOf(conflict("c1"))
        signedIn("owner")
        assertTrue(vm.isOwner)
        val row = vm.sync.first().conflicts!!
        assertEquals("Конфликты: 1 — выбрать версию", row.text)
        assertTrue(row.canResolve)

        val list = vm.conflicts().getOrThrow()
        assertEquals(listOf("c1"), list.map { it.id })
        val pulls = server.requests("/sync/pull").size
        assertNull(vm.resolve(list.single(), ConflictChoice.server))

        val resolve = server.seen.single { it.path == "/conflicts/c1/resolve" }
        assertEquals("POST", resolve.method)
        assertEquals("server", resolve.json["choice"]!!.jsonPrimitive.content)
        // The chosen version comes with the sync that follows; the count on the main screen goes down.
        assertTrue(server.requests("/sync/pull").size > pulls)
        assertEquals(SyncStatus.Synced, session.engine.value!!.status.value)
        assertEquals(null, vm.sync.first().conflicts)
    }

    // MARK: Sign out

    @Test
    fun signOutWarnsAboutPending() = test {
        signedIn()
        writer.save(Entities.driver, driverA, obj("lastName" to "Мунгалов"))

        assertEquals("1 изменение ещё не отправлено. Выйти всё равно?", vm.signOutQuestion())
        // «Выйти» in the question: signOut(false) names the count again — the sign-out goes only with the force.
        assertEquals(1, vm.signOut(force = false))
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals(1, db.outbox().count())

        assertEquals(0, vm.signOut(force = true))
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(0, db.outbox().count())
    }

    @Test
    fun signOutQuestionNamesRefusedChangesToo() = test {
        signedIn()
        assertEquals("Выйти из хозяйства?", vm.signOutQuestion())
        writer.save(Entities.driver, driverB, obj("lastName" to "Бянкин"))
        server.rejectPush = true
        session.syncNow()
        assertEquals(1, db.outbox().refusedCount())
        writer.save(Entities.driver, driverA, obj("lastName" to "Мунгалов"))
        writer.save(Entities.driver, driverA, obj("lastName" to "Мунгалов С."))
        assertEquals(
            "2 изменения ещё не отправлены. 1 изменение сервер не принял — при выходе они удалятся. Выйти всё равно?",
            vm.signOutQuestion(),
        )
    }

    // MARK: Refused changes

    @Test
    fun deleteRefusedOp() = test {
        server.records = listOf(FarmServer.driver(driverA))
        signedIn()
        // A new driver the server refuses: the phone keeps a record the server never had.
        writer.save(Entities.driver, driverB, obj("lastName" to "Бянкин", "firstName" to "Пётр"))
        server.rejectPush = true
        session.syncNow()
        server.rejectPush = false
        val refused = vm.refused.first()
        assertEquals(1, refused.size)
        assertEquals("Водитель", refused.single().title)
        assertEquals("Нет прав на изменение", refused.single().error)
        assertNotNull(db.records().get(Entities.driver, driverB))
        assertEquals(SyncStatus.Failed("Не принятые изменения: 1"), session.engine.value!!.status.value)

        val pulls = server.requests("/sync/pull").size
        assertNull(vm.deleteRefused(refused.single()))

        assertEquals(0, db.outbox().refusedCount())
        assertTrue(vm.refused.first().isEmpty())
        // The ghost is gone, the server's records were taken again from the start, the line is clean.
        assertNull(db.records().get(Entities.driver, driverB))
        assertEquals("Мунгалов", db.records().get(Entities.driver, driverA)!!.json()["lastName"]!!.jsonPrimitive.content)
        assertTrue(server.requests("/sync/pull").size > pulls)
        assertEquals("0", server.requests("/sync/pull").last().query("since"))
        assertEquals(SyncStatus.Synced, session.engine.value!!.status.value)
    }

    @Test
    fun deleteRefusedEditRestoresTheServerValue() = test {
        server.records = listOf(FarmServer.driver(driverA))
        signedIn()
        writer.save(Entities.driver, driverA, obj("lastName" to "Бянкин"))
        server.rejectPush = true
        // The push is refused and the pull that follows is held back: the phone still shows the refused value.
        server.pullFails = true
        session.syncNow()
        server.rejectPush = false
        server.pullFails = false
        assertEquals("Бянкин", db.records().get(Entities.driver, driverA)!!.json()["lastName"]!!.jsonPrimitive.content)
        val refused = vm.refused.first().single()
        assertEquals("Водитель", refused.title)

        assertNull(vm.deleteRefused(refused))
        // The server's value is back on the phone; the record itself stays (the server has it).
        assertEquals("Мунгалов", db.records().get(Entities.driver, driverA)!!.json()["lastName"]!!.jsonPrimitive.content)
        assertEquals(0, db.outbox().refusedCount())
    }

    // MARK: Farm

    @Test
    fun ownerSavesFarmNameAndManagerThroughPatchFarm() = test {
        signedIn()
        server.farmName = "КФХ Зубахин"
        assertEquals("Сохранено", vm.saveFarm("КФХ Старова", "Старов П. И."))
        val patch = server.seen.single { it.path == "/farm" }
        assertEquals("PATCH", patch.method)
        assertEquals("КФХ Старова", patch.json["name"]!!.jsonPrimitive.content)
        assertEquals("Старов П. И.", patch.json["managerName"]!!.jsonPrimitive.content)
        // Not a synced field: nothing queued; `Me` carries the names at once.
        assertEquals(0, db.outbox().count())
        val me = (session.state.value as SessionState.SignedIn).me
        assertEquals("КФХ Старова", me.farmName)
        assertEquals("Старов П. И.", me.managerName)
    }

    @Test
    fun farmSettingsRowIsWrittenThroughTheWriter() = test {
        signedIn()
        assertNull(vm.saveSettings(FarmSettings(responsiblePerson = "Иванова А. А.", address = "х. Зубахин", phone = "+7 928", extraInfo = "ИНН 1")))
        val op = db.outbox().all().single()
        assertEquals(Entities.farmSettings, op.entity)
        assertEquals(StableId.farmSettings, op.recordId)
        assertEquals("Иванова А. А.", op.fieldsJson()["responsiblePerson"]!!.jsonPrimitive.content)
        assertEquals("Иванова А. А.", vm.settings.first()!!.responsiblePerson)
        // The same values again: nothing more is queued.
        assertNull(vm.saveSettings(vm.settings.first()!!))
        assertEquals(1, db.outbox().all().size)
    }

    @Test
    fun helperCannotChangeTheFarm() = test {
        signedIn("coal")
        assertFalse(vm.canEditFarm)
        assertFalse(vm.canEditSettings)
        assertFalse(vm.isOwner)
    }

    // MARK: Owner's lists

    @Test
    fun ownerListsActOnMembersDevicesAndBackups() = test {
        signedIn()
        assertEquals(listOf("anna"), vm.members().getOrThrow().map { it.login })
        assertEquals("Помощник добавлен", vm.addMember("petya", "password8", Role.coal))
        val add = server.seen.single { it.path == "/members" && it.method == "POST" }
        assertEquals("coal", add.json["role"]!!.jsonPrimitive.content)
        assertEquals("Роль изменена", vm.setRole("m1", Role.livestock))
        assertEquals("Пароль изменён", vm.setPassword("m1", "newpass88"))
        assertEquals("Помощник отключён", vm.removeMember("m1"))
        assertEquals(listOf("PATCH", "POST", "DELETE"), server.seen.filter { it.path.startsWith("/members/m1") }.map { it.method })

        assertEquals(listOf("d1", "d2"), vm.devices().getOrThrow().map { it.id })
        assertEquals("Доступ отозван", vm.revoke("d2"))
        assertEquals("POST", server.seen.single { it.path == "/devices/d2/revoke" }.method)

        assertEquals(listOf("b1"), vm.backups().getOrThrow().map { it.id })
        assertEquals("Копия на сервере создана", vm.createBackup())
        assertEquals("Копия цела", vm.verifyBackup("b1"))
        val pulls = server.requests("/sync/pull").size
        assertEquals("Данные восстановлены из копии", vm.restoreBackup("b1"))
        assertEquals("true", server.seen.single { it.path == "/backups/b1/restore" }.json["confirm"]!!.jsonPrimitive.content)
        // Every record came back as a new version: the phone takes them all again from the start.
        assertTrue(server.requests("/sync/pull").size > pulls)
        assertEquals("0", server.requests("/sync/pull").last().query("since"))
    }

    // MARK: Local copy

    @Test
    fun localBackupIsTheIphonesFileWithThePhonesData() = test {
        server.records = listOf(FarmServer.driver(driverA))
        signedIn()
        vm.saveSettings(FarmSettings(responsiblePerson = "Иванова А. А."))
        val file = vm.makeBackupFile()
        assertEquals("Табель-КФХ-копия-2026-11-06.json", file.name)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("tabel-kfh-backup", root["format"]!!.jsonPrimitive.content)
        assertEquals("1.0.7 (7)", root["appVersion"]!!.jsonPrimitive.content)
        val payload = root["payload"]!!.jsonObject
        assertEquals("КФХ Зубахин", payload["settings"]!!.jsonObject["farmName"]!!.jsonPrimitive.content)
        assertEquals("Зубахин А. В.", payload["settings"]!!.jsonObject["managerName"]!!.jsonPrimitive.content)
        assertEquals("Иванова А. А.", payload["settings"]!!.jsonObject["responsiblePerson"]!!.jsonPrimitive.content)
        assertEquals("Asia/Chita", payload["timeZone"]!!.jsonPrimitive.content)
        val drivers = payload["coal"]!!.jsonObject["drivers"]!!.jsonArray
        assertEquals("Мунгалов", drivers.single().jsonObject["lastName"]!!.jsonPrimitive.content)
        assertEquals(driverA.uppercase(), drivers.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun versionLine() {
        assertEquals("Версия 1.0.7 (7)", vm.versionText)
    }
}
