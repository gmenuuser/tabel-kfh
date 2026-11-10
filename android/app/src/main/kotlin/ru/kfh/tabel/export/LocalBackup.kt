package ru.kfh.tabel.export

import android.content.Context
import ru.kfh.core.export.BackupCodec
import ru.kfh.core.export.BackupMeta
import ru.kfh.core.export.BackupRecords
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AttendanceRecord
import ru.kfh.core.model.CoalSale
import ru.kfh.core.model.CoalSettings
import ru.kfh.core.model.CoalWorkDay
import ru.kfh.core.model.DeliveryLocation
import ru.kfh.core.model.Driver
import ru.kfh.core.model.DriverPayment
import ru.kfh.core.model.Employee
import ru.kfh.core.model.Entities
import ru.kfh.core.model.HoursMark
import ru.kfh.core.model.LivestockCategory
import ru.kfh.core.model.LivestockOperation
import ru.kfh.core.model.LivestockSettings
import ru.kfh.core.model.RecordCodec
import ru.kfh.core.model.SlaughterRecord
import ru.kfh.core.model.TimesheetSnapshot
import ru.kfh.core.timesheet.FarmInfo
import ru.kfh.tabel.db.PhotoStore
import ru.kfh.tabel.db.RecordDao
import ru.kfh.tabel.db.RecordRow
import ru.kfh.tabel.db.isRemovedHoursMark
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * «Сохранить копию в файл» (`BackupService.makeBackup`): everything on the phone — the records of every module and
 * the employee photos — as the iPhone's backup file ([BackupCodec]). The file is written to `cache/Backup/` (as the
 * reports to `cache/Export/`) and then copied where the person chose (`ACTION_CREATE_DOCUMENT`).
 */
object LocalBackup {
    /** The phone's data as the codec takes it. */
    suspend fun records(dao: RecordDao, photos: PhotoStore, settings: FarmInfo): BackupRecords {
        val rows = dao.all().filter { !it.deleted }.groupBy { it.entity }
        fun <T : Any> of(entity: String): List<T> =
            rows[entity].orEmpty().map { RecordCodec.decode<T>(entity, it.id, it.json()) }
        val employees = of<Employee>(Entities.employee)
        val photoBytes = LinkedHashMap<String, ByteArray>()
        for (e in employees) photos.load(e.id)?.let { photoBytes[e.id.lowercase(Locale.ROOT)] = it }
        return BackupRecords(
            settings = settings,
            employees = employees,
            photos = photoBytes,
            attendance = of(Entities.attendanceRecord),
            snapshots = of(Entities.timesheetSnapshot),
            hours = rows[Entities.hoursMark].orEmpty().filterNot(RecordRow::isRemovedHoursMark)
                .map { RecordCodec.decode<HoursMark>(Entities.hoursMark, it.id, it.json()) },
            coalSettings = of<CoalSettings>(Entities.coalSettings).minByOrNull { it.createdAt },
            locations = of<DeliveryLocation>(Entities.deliveryLocation),
            drivers = of<Driver>(Entities.driver),
            sales = of<CoalSale>(Entities.coalSale),
            payments = of<DriverPayment>(Entities.driverPayment),
            days = of<CoalWorkDay>(Entities.coalWorkDay),
            livestockSettings = of<LivestockSettings>(Entities.livestockSettings).firstOrNull(),
            categories = of<LivestockCategory>(Entities.livestockCategory),
            operations = of<LivestockOperation>(Entities.livestockOperation),
            slaughters = of<SlaughterRecord>(Entities.slaughterRecord),
            agro = of<AgroCalculation>(Entities.agroCalculation),
        )
    }

    /** The file in [dir] (emptied first, so two copies never sit side by side), written whole before it gets its name. */
    fun write(dir: File, records: BackupRecords, createdAt: Instant, appVersion: String, zone: ZoneId): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val bytes = BackupCodec.encode(records, BackupMeta(createdAt, appVersion, zone))
        val target = File(dir, BackupCodec.fileName(createdAt.atZone(zone).toLocalDate()))
        val part = File(dir, target.name + ".part")
        part.writeBytes(bytes)
        check(part.renameTo(target)) { "не удалось записать файл копии" }
        return target
    }

    /** `cache/Backup` of the app. */
    fun dir(context: Context): File = File(context.cacheDir, "Backup")

    const val JSON = "application/json"
}
