package ru.kfh.tabel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.export.ReportXlsx
import ru.kfh.core.export.TimesheetXlsx
import ru.kfh.core.report.ReportDocument
import ru.kfh.tabel.export.CoalSpecDay
import ru.kfh.tabel.export.ExportFiles
import ru.kfh.tabel.export.ReportPdf
import ru.kfh.tabel.export.SampleDocuments
import ru.kfh.tabel.export.TimesheetPdf

/**
 * Renders every report with the real `PdfDocument` and writes the PDFs, the XLSXs and a PNG per PDF page into
 * `<external files>/review-samples/` of the app (pull them with `adb pull`; CI uploads them as «review-samples» from
 * Task 17). Checks what PDFKit checked on the iPhone: «%PDF», A4 landscape 842×595 / portrait 595×842, the page
 * counts; the texts are checked on the JVM over the recording canvas.
 */
@RunWith(AndroidJUnit4::class)
class ReportSamplesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // One instance per test method: never emptied here, each test only writes its own files over the old ones.
    private val folder: File = File(context.getExternalFilesDir(null), "review-samples").apply { mkdirs() }

    private class Pdf(val file: File, val pageCount: Int, val width: Int, val height: Int)

    /** Writes [name].pdf and a PNG per page at [scale] × points. */
    private fun pdf(name: String, scale: Int, write: (java.io.OutputStream) -> Unit): Pdf {
        val file = File(folder, "$name.pdf")
        file.outputStream().buffered().use(write)
        val bytes = file.readBytes()
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4), Charsets.UTF_8))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                var width = 0
                var height = 0
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        width = page.width
                        height = page.height
                        val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(folder, "$name-p${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                return Pdf(file, renderer.pageCount, width, height)
            }
        }
    }

    private fun xlsx(name: String, data: ByteArray) {
        File(folder, "$name.xlsx").writeBytes(data)
        assertEquals("XLSX is a ZIP", listOf(0x50.toByte(), 0x4B.toByte()), data.take(2))
    }

    private fun report(name: String, d: ReportDocument): Pdf {
        xlsx(name, ReportXlsx.make(d))
        val pdf = pdf(name, 2) { ExportFiles.reportPdf(d).write(it) }
        assertEquals("$name is A4 portrait", 595, pdf.width)
        assertEquals("$name is A4 portrait", 842, pdf.height)
        return pdf
    }

    @Test fun timesheetSamplesAreA4Landscape() {
        val farm = SampleDocuments.farm
        val hours = SampleDocuments.hours(12)
        xlsx("timesheet", TimesheetXlsx.make(hours, farm))
        xlsx("timesheet-codes", TimesheetXlsx.make(SampleDocuments.codes(12), farm))
        val small = pdf("timesheet", 2) { ExportFiles.timesheetPdf(hours, farm).write(it) }
        assertEquals(1, small.pageCount)
        assertEquals(842, small.width)
        assertEquals(595, small.height)
        assertEquals(1, pdf("timesheet-codes", 2) { ExportFiles.timesheetPdf(SampleDocuments.codes(12), farm).write(it) }.pageCount)
        val long = pdf("timesheet-60", 2) { ExportFiles.timesheetPdf(SampleDocuments.hours(60), farm).write(it) }
        assertTrue("60 employees need more than one page", long.pageCount > 1)
        assertTrue(File(folder, "timesheet-p1.png").exists())
    }

    @Test fun coalSamplesAreA4Portrait() {
        val s = CoalSpecDay()
        s.close()
        val day = CoalReportLayout.document(s.dayReport(), s.farm)
        assertEquals(1, report("coal-day", day).pageCount)
        report("coal-month", CoalReportLayout.document(s.monthReport(), s.farm))
        report("payments", CoalReportLayout.paymentsDocument(s.dayReport(), s.farm))
        val long = CoalSpecDay(extra = 70)
        assertTrue(report("coal-day-long", CoalReportLayout.document(long.dayReport(), long.farm)).pageCount > 1)
    }

    @Test fun livestockAndAgroSamples() {
        report("herd", SampleDocuments.herd())
        report("slaughter", SampleDocuments.slaughter())
        report("movement", SampleDocuments.movement())
        report("agro", SampleDocuments.agro())
    }

    @Test fun exportFilesLandInTheCacheFolders() {
        val files = ExportFiles.writeTimesheet(context, SampleDocuments.hours(3), SampleDocuments.farm, surname = "Зубахин")
        assertEquals("Табель_Декабрь_2026_Зубахин.pdf", files.pdf.name)
        assertEquals("Export", files.pdf.parentFile!!.name)
        assertEquals(context.cacheDir.canonicalFile, files.pdf.parentFile!!.parentFile!!.canonicalFile)
        assertTrue(files.pdf.length() > 0 && files.xlsx.length() > 0)
        val s = CoalSpecDay()
        val coal = ExportFiles.writeReport(context, CoalReportLayout.document(s.dayReport(), s.farm))
        assertEquals("Уголь_день_2026-11-06.pdf", coal.pdf.name)
        assertEquals("CoalExport", coal.pdf.parentFile!!.name)
        assertEquals(TimesheetPdf.WIDTH.toInt(), 842)
        assertEquals(ReportPdf.WIDTH.toInt(), 595)
    }
}
