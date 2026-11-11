package ru.kfh.tabel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A downloaded `.apk` is offered only as an in-place upgrade of this very app: same package, newer, same key. */
class ApkJudgeTest {
    private val installed = PackageFacts("ru.kfh.tabel", 1029, setOf("cert-kfh"))

    private fun reason(v: Verdict) = (v as Verdict.Rejected).reason

    @Test fun newerSameKeyAccepted() {
        assertEquals(Verdict.Accepted, ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1030, setOf("cert-kfh")), installed))
    }

    @Test fun sameVersionRejected() {
        val v = ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1029, setOf("cert-kfh")), installed)
        assertTrue(reason(v), reason(v).contains("не новее"))
    }

    @Test fun olderVersionRejected() {
        val v = ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1001, setOf("cert-kfh")), installed)
        assertTrue(reason(v), reason(v).contains("не новее"))
    }

    @Test fun otherPackageRejected() {
        val v = ApkJudge.judge(PackageFacts("ru.kfh.other", 1030, setOf("cert-kfh")), installed)
        assertTrue(reason(v), reason(v).contains("другое приложение"))
    }

    @Test fun otherSignatureRejected() {
        assertEquals("другая подпись", reason(ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1030, setOf("cert-debug")), installed)))
        // One more signer than the installed app is another signature too.
        assertEquals("другая подпись", reason(ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1030, setOf("cert-kfh", "cert-x")), installed)))
    }

    @Test fun missingSignatureRejected() {
        assertEquals("нет подписи", reason(ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1030, emptySet()), installed)))
        assertEquals("нет подписи", reason(ApkJudge.judge(PackageFacts("ru.kfh.tabel", 1030, setOf("cert-kfh")), installed.copy(certificates = emptySet()))))
    }

    @Test fun unreadableFileRejected() {
        assertTrue(reason(ApkJudge.judge(null, installed)).contains("не читается"))
    }

    @Test fun fileNames() {
        assertEquals("tabel-kfh-1030.apk", UpdateManager.fileName(1030))
        assertEquals(1030L, UpdateManager.codeOf("tabel-kfh-1030.apk"))
        assertEquals(1030L, UpdateManager.codeOf("tabel-kfh-1030.apk.part"))
        assertEquals(null, UpdateManager.codeOf("tabel-kfh.apk"))
        assertEquals(null, UpdateManager.codeOf("other-1030.apk"))
    }
}
