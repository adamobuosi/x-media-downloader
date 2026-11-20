package com.xmediadownloader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the file and saves it to:
 *  - Android 10+ (Q): MediaStore/Download/XMediaDownloader (no runtime permission);
 *  - Android 5 to 9: public folder Downloads/XMediaDownloader
 *    (requires WRITE_EXTERNAL_STORAGE granted beforehand).
 *
 * Progress is reported in bytes (received, total); total = -1 when unknown.
 * The download is cancellable: cancelling the coroutine stops the copy and removes
 * the partial file from MediaStore.
 *
 * Only HTTPS URLs on Twitter/X media hosts are fetched (the URLs come from third-party
 * API responses, so they are not trusted). Redirects are not followed.
 */
object MediaDownloader {

    class MediaDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    const val SUBFOLDER = "XMediaDownloader"

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER_SIZE = 64 * 1024

    /** Allowed host suffix for media: `pbs.twimg.com`, `video.twimg.com`, etc. */
    private const val ALLOWED_HOST_SUFFIX = ".twimg.com"

    /**
     * Downloads [url] and returns the saved file name.
     * Throws [MediaDownloadException] if the URL is not an allowed media URL.
     */
    suspend fun download(
        context: Context,
        url: String,
        tweetId: String,
        mediaIndex: Int,
        mime: String,
        onProgress: (received: Long, total: Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!isAllowedMediaUrl(url)) {
            throw MediaDownloadException("Refused to download from an untrusted address.")
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false // a redirect could point to a host we did not validate
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw MediaDownloadException("Server returned HTTP ${conn.responseCode}")
            }

            val total = conn.contentLengthLong // may be -1 (not reported)
            val fileName = buildFileName(url, tweetId, mediaIndex, mime)

            onProgress(0L, total)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, conn, fileName, mime, total, onProgress)
            } else {
                saveWithLegacyFile(context, conn, fileName, mime, total, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** True only for `https://` URLs without credentials whose host is a Twitter/X media host. */
    internal fun isAllowedMediaUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        return parsed.protocol == "https" &&
            parsed.userInfo == null &&
            (parsed.port == -1 || parsed.port == 443) &&
            host.endsWith(ALLOWED_HOST_SUFFIX) &&
            host.length > ALLOWED_HOST_SUFFIX.length
    }

    // ------------------------------------------------------------------
    // Android 10+ — MediaStore (no permission needed)
    // ------------------------------------------------------------------
    private suspend fun saveWithMediaStore(
        context: Context,
        conn: HttpURLConnection,
        fileName: String,
        mime: String,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw MediaDownloadException("Could not create the file in storage.")
        try {
            copy(conn.inputStream, resolver.openOutputStream(uri)) { received ->
                onProgress(received, total)
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return fileName
        } catch (e: Throwable) {
            resolver.delete(uri, null, null) // never leaves a pending/invalid file behind
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Android <= 9 — public folder (needs the permission granted beforehand)
    // ------------------------------------------------------------------
    private suspend fun saveWithLegacyFile(
        context: Context,
        conn: HttpURLConnection,
        fileName: String,
        mime: String,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw MediaDownloadException("Storage unavailable.")
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, SUBFOLDER).apply { mkdirs() }
        val target = uniqueFileIn(File(folder, fileName))
        copy(conn.inputStream, FileOutputStream(target)) { received ->
            onProgress(received, total)
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return target.name
    }

    /** Ensures a non-existent file name by appending " (1)", " (2)", ... */
    private fun uniqueFileIn(candidate: File): File {
        if (!candidate.exists()) return candidate
        val name = candidate.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val attempt = File(candidate.parentFile, "$base ($i)$ext")
            if (!attempt.exists()) return attempt
            i++
        }
    }

    // ------------------------------------------------------------------
    // streaming copy with progress (cancellable)
    // ------------------------------------------------------------------
    private suspend fun copy(
        input: InputStream,
        output: OutputStream?,
        onProgress: (Long) -> Unit,
    ): Long {
        val dst = output ?: throw MediaDownloadException("Could not open the file for writing.")
        var written = 0L
        input.use { src ->
            dst.use {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive() // stops as soon as the coroutine is cancelled
                    val n = src.read(buffer)
                    if (n == -1) break
                    dst.write(buffer, 0, n)
                    written += n
                    onProgress(written)
                }
                dst.flush()
            }
        }
        return written
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    internal fun buildFileName(url: String, tweetId: String, mediaIndex: Int, mime: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val originalName = path.substringAfterLast('/')
        val cleanBase = originalName
            .substringBeforeLast('.')
            .removeSuffix(".mp4")
            .ifBlank { tweetId }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(50)
        val ext = when {
            mime.startsWith("video/") -> "mp4"
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            else -> "jpg"
        }
        return "tweet-${tweetId}_${mediaIndex + 1}_$cleanBase.$ext"
    }
}
