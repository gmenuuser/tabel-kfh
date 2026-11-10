package ru.kfh.tabel.export

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Port of ExportFilesTests.swift: the names and the folder (the renderers stand in; their bytes are tested elsewhere). */
class ExportFilesTest {
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory("ExportFilesTest").toFile().resolve("Export")
    }

    @After fun tearDown() {
        directory.parentFile?.deleteRecursively()
    }

    private val pdf = ReportFile { it.write("%PDF-1.4 stand-in".toByteArray()) }
    private val xlsx = ReportFile { it.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }

    private fun write(surname: String? = null): ExportedFiles =
        ExportFiles.write(directory, "Табель_Ноябрь_2026" + (surname?.let { "_$it" } ?: ""), pdf, xlsx)

    @Test fun writesPDFAndXLSXWithExportNames() {
        val files = write()
        assertEquals("Табель_Ноябрь_2026.pdf", files.pdf.name)
        assertEquals("Табель_Ноябрь_2026.xlsx", files.xlsx.name)
        assertEquals(directory.canonicalFile, files.pdf.parentFile.canonicalFile)
        assertTrue(files.pdf.length() > 0)
        assertTrue(files.xlsx.length() > 0)
        assertEquals("%PDF", String(files.pdf.readBytes().copyOfRange(0, 4), Charsets.UTF_8))
        assertEquals("XLSX is a ZIP", listOf(0x50.toByte(), 0x4B.toByte()), files.xlsx.readBytes().take(2))
        assertEquals(listOf(files.pdf, files.xlsx), files.all)
    }

    @Test fun oneEmployeeFilesHaveTheSurname() {
        val files = write("Зубахин")
        assertEquals("Табель_Ноябрь_2026_Зубахин.pdf", files.pdf.name)
        assertEquals("Табель_Ноябрь_2026_Зубахин.xlsx", files.xlsx.name)
    }

    @Test fun writingAnotherVersionRemovesTheOlderFiles() {
        write()
        write("Зубахин")
        assertEquals(listOf("Табель_Ноябрь_2026_Зубахин.pdf", "Табель_Ноябрь_2026_Зубахин.xlsx"), directory.list()!!.sorted())
    }

    @Test fun writingAgainReplacesTheFiles() {
        write()
        val files = write()
        assertTrue(files.pdf.length() > 0)
        assertEquals(listOf("Табель_Ноябрь_2026.pdf", "Табель_Ноябрь_2026.xlsx"), directory.list()!!.sorted())
    }
}
