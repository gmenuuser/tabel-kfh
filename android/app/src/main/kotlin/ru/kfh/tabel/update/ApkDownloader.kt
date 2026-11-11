package ru.kfh.tabel.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Downloads an `.apk` (≈11 MB) into [download]'s `target`, resume-safe: the bytes go to `target.part`, a later try
 * continues it with a `Range` request, and the file becomes `target` only when every byte announced by the server
 * is there. A server that does not resume (200 instead of 206) starts the part over; a stale part (416) is dropped
 * and the download starts over at once. Nothing here checks the address: the caller does ([UpdateManager]).
 */
class ApkDownloader(private val client: OkHttpClient) {
    /** false when the download did not complete (connection lost, server trouble): the part stays for next time. */
    suspend fun download(url: String, target: File, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            val part = partOf(target)
            target.parentFile?.mkdirs()
            try {
                var attempt = fetch(url, part, onProgress)
                if (attempt == Attempt.StalePart) {
                    part.delete()
                    attempt = fetch(url, part, onProgress)
                }
                if (attempt != Attempt.Complete) return@withContext false
                target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                true
            } catch (e: IOException) {
                false
            }
        }

    private enum class Attempt { Complete, Short, StalePart, Failed }

    private fun fetch(url: String, part: File, onProgress: (Long, Long) -> Unit): Attempt {
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url).header("Accept", "application/vnd.android.package-archive, */*")
        if (have > 0) request.header("Range", "bytes=$have-")
        client.newCall(request.build()).execute().use { r ->
            when {
                r.code == 416 -> return Attempt.StalePart
                r.code == 206 && have > 0 -> Unit
                r.code == 200 -> Unit
                else -> return Attempt.Failed
            }
            val resumed = r.code == 206
            val body = r.body ?: return Attempt.Failed
            val remaining = body.contentLength()
            val total = if (remaining < 0) -1L else (if (resumed) have else 0L) + remaining
            var written = if (resumed) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)
            // The server named the size: anything less is a cut connection, and the part waits for the next try.
            return if (total >= 0 && written < total) Attempt.Short else Attempt.Complete
        }
    }

    companion object {
        fun partOf(target: File): File = File(target.path + ".part")
    }
}
