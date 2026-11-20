package com.xmediadownloader

import com.xmediadownloader.data.MediaType
import com.xmediadownloader.data.TweetData
import java.util.Locale

/** Fetch state of a tweet. */
sealed interface FetchUi {
    data object Idle : FetchUi
    data object Loading : FetchUi
    data class Loaded(val tweet: TweetData) : FetchUi
    data class Error(val message: String) : FetchUi
}

/** Download state of a specific option (key = option URL). */
sealed interface DownloadUi {
    data object Idle : DownloadUi
    data class Running(val received: Long, val total: Long, val speedBps: Long) : DownloadUi
    data class Done(val fileName: String) : DownloadUi
    data class Failed(val message: String) : DownloadUi
}

/** Friendly label for the media type. */
fun mediaTypeLabel(type: MediaType): String = when (type) {
    MediaType.PHOTO -> "📷 Photo"
    MediaType.VIDEO -> "🎥 Video"
    MediaType.GIF -> "🎞️ GIF"
}

/** Locale used for numeric displays (dot as decimal separator, locale-independent). */
private val LOCALE: Locale = Locale.US

/** Formats bytes: "1.2 MB", "340 KB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    return when {
        bytes >= 1_000_000L -> String.format(LOCALE, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(LOCALE, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

/** Formats speed: "2.4 MB/s". */
fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/s"
