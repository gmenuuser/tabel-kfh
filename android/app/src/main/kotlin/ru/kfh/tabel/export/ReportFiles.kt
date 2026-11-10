package ru.kfh.tabel.export

import android.content.Context
import java.io.File
import java.io.OutputStream
import ru.kfh.core.export.ReportXlsx
import ru.kfh.core.export.TimesheetXlsx
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.TimesheetDocument
import ru.kfh.core.timesheet.exportFileName

/** A report that writes itself: the PDF or the XLSX of a document. */
fun interface ReportFile {
    fun write(out: OutputStream)
}

/** The PDF and the XLSX of one report, written to a folder for sharing (`ExportedFiles`). */
data class ExportedFiles(val pdf: File, val xlsx: File) {
    /** PDF first, then XLSX. */
    val all: List<File> get() = listOf(pdf, xlsx)
}

object ExportFiles {
    /** `cache/Export/` — the timesheet's files (`tmp/Export` on the iPhone). */
    fun timesheetDirectory(context: Context): File = File(context.cacheDir, "Export")

    /** `cache/CoalExport/` — the block reports' files (`tmp/CoalExport` on the iPhone). */
    fun reportDirectory(context: Context): File = File(context.cacheDir, "CoalExport")

    /**
     * Empties [directory], then writes «[baseName].pdf» and «[baseName].xlsx» into it, so only the shown report's files
     * stay. Each file is written whole to a temporary name and renamed, never half-written under its name.
     */
    fun write(directory: File, baseName: String, pdf: ReportFile, xlsx: ReportFile): ExportedFiles {
        if (directory.exists()) directory.deleteRecursively()
        directory.mkdirs()
        val pdfFile = File(directory, "$baseName.pdf")
        val xlsxFile = File(directory, "$baseName.xlsx")
        atomically(pdfFile, pdf)
        atomically(xlsxFile, xlsx)
        return ExportedFiles(pdfFile, xlsxFile)
    }

    private fun atomically(target: File, report: ReportFile) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().buffered().use { report.write(it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** The PDF of a timesheet ([TimesheetPdf]) as a file. */
    fun timesheetPdf(d: TimesheetDocument, farm: FarmTime): ReportFile = pdf(TimesheetPdf.WIDTH.toInt(), TimesheetPdf.HEIGHT.toInt()) { TimesheetPdf(d, farm).render(it) }

    /** The PDF of a block report ([ReportPdf]) as a file. */
    fun reportPdf(d: ReportDocument): ReportFile = pdf(ReportPdf.WIDTH.toInt(), ReportPdf.HEIGHT.toInt()) { ReportPdf(d).render(it) }

    fun bytes(data: ByteArray): ReportFile = ReportFile { it.write(data) }

    private fun pdf(width: Int, height: Int, render: (PdfCanvas) -> Unit): ReportFile = ReportFile { out ->
        val canvas = AndroidPdfCanvas(width, height)
        render(canvas)
        canvas.finish(out)
    }

    /** «Табель_<Месяц>_<год>[_<Фамилия>].pdf» and «….xlsx» of [d] in [timesheetDirectory] (`ExportFiles.write`). */
    fun writeTimesheet(context: Context, d: TimesheetDocument, farm: FarmTime, surname: String?): ExportedFiles {
        val base = exportFileName(d, surname, "pdf").removeSuffix(".pdf")
        return write(timesheetDirectory(context), base, timesheetPdf(d, farm), bytes(TimesheetXlsx.make(d, farm)))
    }

    /** «<fileName>.pdf» and «<fileName>.xlsx» of [d] in [reportDirectory] (`CoalExportFiles.write`). */
    fun writeReport(context: Context, d: ReportDocument): ExportedFiles =
        write(reportDirectory(context), d.fileName, reportPdf(d), bytes(ReportXlsx.make(d)))
}
