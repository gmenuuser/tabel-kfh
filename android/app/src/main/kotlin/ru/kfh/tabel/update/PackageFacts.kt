package ru.kfh.tabel.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * What a downloaded `.apk` is judged by, against the installed app: the package, the `versionCode` and the SHA-256
 * fingerprints of the signing certificates. A plain value so the decision ([ApkJudge]) runs in JVM tests.
 */
data class PackageFacts(val packageName: String, val versionCode: Long, val certificates: Set<String>)

/** The facts of the installed app and of an `.apk` file; [AndroidPackageFacts] asks the package manager. */
interface PackageFactsSource {
    fun installed(): PackageFacts

    /** null when the file is not an `.apk` the package manager can read (cut short, not an archive, …). */
    fun archive(file: File): PackageFacts?
}

/** Whether a downloaded file may be offered for installation. */
sealed interface Verdict {
    data object Accepted : Verdict
    data class Rejected(val reason: String) : Verdict
}

/**
 * The file is offered only when it is our package, strictly newer than the installed app, and signed with exactly
 * the installed app's certificates: an in-place upgrade Android accepts, which keeps the data and the sign-in.
 * Anything else is rejected and the caller deletes the file.
 */
object ApkJudge {
    fun judge(archive: PackageFacts?, installed: PackageFacts): Verdict {
        if (archive == null) return Verdict.Rejected("файл не читается как APK")
        if (archive.packageName != installed.packageName) return Verdict.Rejected("другое приложение: ${archive.packageName}")
        if (archive.versionCode <= installed.versionCode) {
            return Verdict.Rejected("версия ${archive.versionCode} не новее установленной ${installed.versionCode}")
        }
        if (archive.certificates.isEmpty() || installed.certificates.isEmpty()) return Verdict.Rejected("нет подписи")
        if (archive.certificates != installed.certificates) return Verdict.Rejected("другая подпись")
        return Verdict.Accepted
    }
}

/** The package manager's answers: `GET_SIGNING_CERTIFICATES` from API 28, `GET_SIGNATURES` below. */
class AndroidPackageFacts(context: Context) : PackageFactsSource {
    private val app = context.applicationContext
    private val pm: PackageManager get() = app.packageManager

    @Suppress("DEPRECATION")
    override fun installed(): PackageFacts = facts(pm.getPackageInfo(app.packageName, signingFlag()))

    @Suppress("DEPRECATION")
    override fun archive(file: File): PackageFacts? {
        if (!file.isFile) return null
        val path = file.absolutePath
        val info = try {
            pm.getPackageArchiveInfo(path, signingFlag())
        } catch (e: Exception) {
            null
        } ?: return null
        var f = facts(info)
        // Some phones (API 28) give the archive without `signingInfo`: the old flag still fills `signatures`.
        if (f.certificates.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.let { f = facts(it) }
            } catch (e: Exception) {
                // keep the empty set: the judge rejects it
            }
        }
        return f
    }

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): PackageFacts {
        val signers: Array<Signature>? =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null) ?: info.signatures
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return PackageFacts(info.packageName ?: "", code, signers.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
