package com.xmediadownloader.data

import android.net.Uri

/** Type of media present in a tweet. */
enum class MediaType { PHOTO, VIDEO, GIF }

/** A downloadable variation of a media item (image size or video quality). */
data class MediaVariant(
    val url: String,
    val mime: String,
    val label: String,          // "Original (1280×720)", "1080p (~14 MB)", etc.
    val estimatedBytes: Long = -1L,
)

/** One individual media item inside the tweet (1 photo / 1 video / 1 gif). */
data class TweetMedia(
    val index: Int,
    val type: MediaType,
    val options: List<MediaVariant>, // sorted from largest/best to smallest
)

/** Fetch result for a tweet. */
data class TweetData(
    val tweetId: String,
    val authorScreenName: String,
    val authorName: String,
    val text: String,
    val createdAt: String?,
    val media: List<TweetMedia>,
) {
    val shortText: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: ""
}

/** Exception with a user-friendly message for the UI. */
class TweetFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)
