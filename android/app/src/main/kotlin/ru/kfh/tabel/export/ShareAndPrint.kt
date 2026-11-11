package ru.kfh.tabel.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** «Поделиться» (FileProvider + ACTION_SEND) and «Распечатать» (PrintManager) of the exported files. */
object ShareAndPrint {
    const val PDF = "application/pdf"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun mime(file: File): String = if (file.name.endsWith(".pdf")) PDF else XLSX

    /** The content URI other apps read [file] through (`<applicationId>.files`). */
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** The share sheet with [files] (the PDF and the XLSX together, as the iPhone's `ShareLink`). */
    fun share(context: Context, files: List<File>) {
        val uris = ArrayList(files.map { uri(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // No FLAG_ACTIVITY_NEW_TASK: [context] is the activity's, and the flag only pushed the chooser into a task of
        // its own on some launchers.
        context.startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    /** The system print dialog with the PDF [pdf], A4 [landscape] or portrait, the job named after the file. */
    fun print(context: Context, pdf: File, jobName: String, landscape: Boolean) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val size = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait()
        val attributes = PrintAttributes.Builder().setMediaSize(size).build()
        manager.print(jobName, PdfPrintAdapter(pdf, jobName), attributes)
    }
}

/** Hands a ready PDF file to the print service as it is. */
class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
