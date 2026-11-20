package com.xmediadownloader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches tweet data using public mechanisms (no login).
 *
 * Source chain (in order):
 *  1. Public FxTwitter API (api.fxtwitter.com) — open source service
 *     maintained by the community; returns photos (with URL and dimensions)
 *     and videos (MP4 variants with bitrate and duration). Proven to work today.
 *  2. X's own syndication endpoint (cdn.syndication.twimg.com),
 *     used by the embed widget — kept as a fallback in case it works again.
 *  3. API v1.1 with a temporary guest token (the same bearer the website uses).
 */
object TweetApi {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    // Public bearer used by the X website itself (for guests / logged-out visitors).
    private const val PUBLIC_BEARER =
        "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16mH4GMoVMyQ7AAAAAAAA%3AAA"

    suspend fun fetchTweet(rawInput: String): TweetData = withContext(Dispatchers.IO) {
        val tweetId = TweetUrlParser.extractTweetId(rawInput)
            ?: throw TweetFetchException(
                "No tweet ID found in the URL. Accepted example: x.com/user/status/123456789"
            )

        val errors = StringBuilder()
        var parsed: TweetData? = null

        // 1) FxTwitter (public open-source API)
        runCatching { fetchFxTwitter(tweetId) }
            .onSuccess { parsed = it }
            .onFailure { errors.append("fxtwitter: ${it.message ?: it.javaClass.simpleName}") }

        // 2) X syndication
        if (parsed == null) {
            runCatching { fetchSyndication(tweetId) }
                .onSuccess { json -> parseTweet(tweetId, json)?.let { parsed = it } }
                .onFailure { errors.append(" | syndication: ${it.message ?: it.javaClass.simpleName}") }
        }

        // 3) API v1.1 with guest token
        if (parsed == null) {
            runCatching { fetchGuestApi(tweetId) }
                .onSuccess { json -> parseTweet(tweetId, json)?.let { parsed = it } }
                .onFailure { errors.append(" | guest: ${it.message ?: it.javaClass.simpleName}") }
        }

        parsed ?: throw TweetFetchException(
            "Could not fetch the tweet (it may be protected, deleted, or the public services " +
                "may be down). $errors"
        )
    }

    // ---------------------------------------------------------------------
    // 1) FxTwitter
    // ---------------------------------------------------------------------
    private fun fetchFxTwitter(tweetId: String): TweetData {
        val conn = open("https://api.fxtwitter.com/status/$tweetId")
        try {
            val body = readAll(conn)
            val json = JSONObject(body)
            if (json.optInt("code", 0) != 200) {
                throw IOException(json.optString("message", "HTTP error ${conn.responseCode}"))
            }
            val tweet = json.optJSONObject("tweet")
                ?: throw IOException("response without the tweet object")

            val mediaObj = tweet.optJSONObject("media")
            val items = ArrayList<JSONObject>()
            // Proposed Array: photos / videos / all (most complete)
            val all = mediaObj?.optJSONArray("all")
            if (all != null && all.length() > 0) {
                for (i in 0 until all.length()) items += all.optJSONObject(i) ?: continue
            } else {
                for (key in listOf("photos", "videos")) {
                    val arr = mediaObj?.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) items += arr.optJSONObject(i) ?: continue
                }
            }

            val mediaList = ArrayList<TweetMedia>(items.size)
            for ((i, item) in items.withIndex()) {
                val media = when (item.optString("type")) {
                    "photo" -> parsePhotoFromAny(i, item)
                    "video" -> parseVideoFromAny(i, item, MediaType.VIDEO)
                    "gif" -> parseVideoFromAny(i, item, MediaType.GIF)
                    else -> null
                } ?: continue
                if (media.options.isNotEmpty()) mediaList.add(media)
            }
            if (mediaList.isEmpty()) {
                throw IOException("the tweet has no downloadable photos or videos")
            }

            val author = tweet.optJSONObject("author")
            return TweetData(
                tweetId = tweetId,
                authorScreenName = author?.optString("screen_name") ?: "",
                authorName = author?.optString("name") ?: "",
                text = tweet.optString("text", ""),
                createdAt = tweet.optNonBlankString("created_at"),
                media = mediaList,
            )
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------------
    // 2) Syndication
    // ---------------------------------------------------------------------
    private fun fetchSyndication(tweetId: String): JSONObject {
        val url = "https://cdn.syndication.twimg.com/tweet-result?" +
            "id=${URLEncoder.encode(tweetId, "UTF-8")}&token=x&lang=en"
        return readJson(url)
    }

    // ---------------------------------------------------------------------
    // 3) Guest token + API v1.1
    // ---------------------------------------------------------------------
    private fun fetchGuestApi(tweetId: String): JSONObject {
        val guestToken = fetchGuestToken()
        val url = "https://api.twitter.com/1.1/statuses/show/$tweetId.json?tweet_mode=extended"
        val conn = open(url)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", PUBLIC_BEARER)
            conn.setRequestProperty("x-guest-token", guestToken)
            val body = readAll(conn)
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchGuestToken(): String {
        val conn = open("https://api.twitter.com/1.1/guest/activate.json")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", PUBLIC_BEARER)
            val body = readAll(conn)
            val json = JSONObject(body)
            return json.optString("guest_token")
                .takeIf { it.isNotEmpty() }
                ?: throw IOException("guest token missing from response")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json, text/plain, */*")
    }

    private fun readJson(url: String): JSONObject {
        val conn = open(url)
        return try {
            val body = readAll(conn)
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads the response body as text.
     * On HTTP >= 400 throws [IOException] including the error body (when the
     * server sends one), so the message shown in the UI is useful.
     */
    private fun readAll(conn: HttpURLConnection): String {
        val isError = conn.responseCode >= 400
        val stream = (if (isError) conn.errorStream else conn.inputStream)
            ?: throw IOException("no response from the server (HTTP ${conn.responseCode})")
        val text = stream.bufferedReader().use(BufferedReader::readText)
        if (isError) {
            val detail = text.take(200).ifBlank { "no details" }
            throw IOException("HTTP ${conn.responseCode}: $detail")
        }
        return text
    }

    // ---------------------------------------------------------------------
    // Parsing: v1.1 shape (syndication / guest)
    // ---------------------------------------------------------------------
    /** Normalizes both shapes: syndication (mediaDetails) and API v1.1 (entities). */
    private fun mediaArray(json: JSONObject): JSONArray? {
        json.optJSONArray("mediaDetails")?.takeIf { it.length() > 0 }?.let { return it }
        json.optJSONObject("extended_entities")?.optJSONArray("media")?.takeIf { it.length() > 0 }?.let { return it }
        json.optJSONObject("entities")?.optJSONArray("media")?.takeIf { it.length() > 0 }?.let { return it }
        return null
    }

    private fun parseTweet(tweetId: String, json: JSONObject): TweetData? {
        val mediaJson = mediaArray(json) ?: return null
        if (mediaJson.length() == 0) return null

        val user = json.optJSONObject("user")
        val mediaList = ArrayList<TweetMedia>(mediaJson.length())
        for (i in 0 until mediaJson.length()) {
            val m = mediaJson.optJSONObject(i) ?: continue
            val media = parseMediaItem(i, m) ?: continue
            if (media.options.isEmpty()) continue
            mediaList.add(media)
        }
        if (mediaList.isEmpty()) return null

        val text = json.optNonBlankString("full_text")
            ?: json.optNonBlankString("text")
            ?: ""

        return TweetData(
            tweetId = tweetId,
            authorScreenName = user?.optString("screen_name") ?: "",
            authorName = user?.optString("name") ?: "",
            text = text,
            createdAt = json.optNonBlankString("created_at"),
            media = mediaList,
        )
    }

    private fun parseMediaItem(index: Int, m: JSONObject): TweetMedia? {
        val type = when (m.optString("type")) {
            "photo" -> MediaType.PHOTO
            "animated_gif" -> MediaType.GIF
            "video" -> MediaType.VIDEO
            else -> return null
        }
        return when (type) {
            MediaType.PHOTO -> parsePhotoFromAny(index, m)
            else -> parseVideoFromAny(index, m, type)
        }
    }

    // ---------------------------------------------------------------------
    // Photos (works for both v1.1 and FxTwitter)
    // ---------------------------------------------------------------------
    /**
     * Photos: builds size options (?name=orig|large|small). Dimensions come
     * from width/height (FxTwitter) or from sizes.{large,...} (v1.1).
     */
    internal fun parsePhotoFromAny(index: Int, m: JSONObject): TweetMedia? {
        val rawUrl = m.optString("url").takeIf { it.isNotEmpty() }
            ?: m.optString("media_url_https").takeIf { it.isNotEmpty() }
            ?: return null

        // Preserves "https://" and cuts the '?' (query) and ':' (v1.1 suffix "…jpg:large")
        val base = UrlMedia.extractCdnBase(rawUrl)
        val ext = photoExtension(rawUrl)
        val sizes = m.optJSONObject("sizes")

        fun dims(key: String): Triple<Int, Int, Int>? {
            val o = sizes?.optJSONObject(key) ?: return null
            val w = o.optInt("w"); val h = o.optInt("h")
            if (w <= 0 || h <= 0) return null
            return Triple(w, h, w * h)
        }

        // FxTwitter gives width/height directly (equivalent to the original)
        var origW = m.optInt("width", 0)
        var origH = m.optInt("height", 0)
        if (origW <= 0 || origH <= 0) {
            val large = dims("large")
            origW = large?.first ?: 0
            origH = large?.second ?: 0
        }

        val options = ArrayList<MediaVariant>(4)
        options += MediaVariant(
            url = "$base?format=$ext&name=orig",
            mime = mimeFor(ext),
            label = if (origW > 0) "Original ($origW×$origH)" else "Original",
        )
        for (key in listOf("large" to "Large", "medium" to "Medium", "small" to "Small")) {
            val d = dims(key.first)
            val dimsSuffix = d?.let { " (${it.first}×${it.second})" } ?: ""
            options += MediaVariant(
                url = "$base?format=$ext&name=${key.first}",
                mime = mimeFor(ext),
                label = "${key.second}$dimsSuffix",
            )
        }

        return TweetMedia(index = index, type = MediaType.PHOTO, options = options)
    }

    // ---------------------------------------------------------------------
    // Videos / GIFs.
    //  - v1.1: video_info.variants [{bitrate, content_type, url}] (bitrate bps)
    //  - FxTwitter: variants [{type, url, bitrate}] and duration in seconds
    // ---------------------------------------------------------------------
    internal fun parseVideoFromAny(index: Int, m: JSONObject, type: MediaType): TweetMedia? {
        val videoInfo = m.optJSONObject("video_info") ?: m.optJSONObject("videoInfo")
        val variants: JSONArray? = videoInfo?.optJSONArray("variants") ?: m.optJSONArray("variants")
        val durationMs = runCatching {
            val v11Millis = videoInfo?.optLong("duration_millis", 0L) ?: 0L
            if (v11Millis > 0) {
                v11Millis
            } else {
                val secs = m.optDouble("duration", Double.NaN) // fxtwitter: seconds
                if (secs.isNaN()) 0L else (secs * 1000).toLong()
            }
        }.getOrDefault(0L)

        val data = ArrayList<MediaVariant>()
        val seenUrls = HashSet<String>()

        fun addVariant(url: String, bitrate: Long, contentType: String) {
            if (url.isEmpty() || !seenUrls.add(url)) return
            if (contentType.contains("mpegurl", ignoreCase = true) || url.contains(".m3u8")) return
            val bytes = if (bitrate > 0 && durationMs > 0) bitrate * durationMs / 8000 else -1L
            data += MediaVariant(
                url = url,
                mime = if (contentType.startsWith("video/")) contentType else "video/mp4",
                label = videoLabel(url, bitrate, durationMs),
                estimatedBytes = bytes,
            )
        }

        if (variants != null) {
            for (i in 0 until variants.length()) {
                val v = variants.optJSONObject(i) ?: continue
                addVariant(
                    url = v.optString("url"),
                    bitrate = v.optLong("bitrate", 0L),
                    contentType = v.optString("content_type").takeIf { it.isNotEmpty() }
                        ?: v.optString("type"),
                )
            }
        }
        // The item's "main" URL is usually the best variant already (FxTwitter)
        if (type == MediaType.GIF || variants == null || data.isEmpty()) {
            addVariant(m.optString("url").takeIf { it.isNotEmpty() } ?: "", 0L, "video/mp4")
        }

        data.sortByDescending { it.estimatedBytes.coerceAtLeast(0) }
        if (data.isEmpty()) return null

        return TweetMedia(index = index, type = type, options = data)
    }

    internal fun videoLabel(url: String, bitrate: Long, durationMs: Long): String {
        // video.twimg.com URLs usually contain "/vid/WIDTHxHEIGHT/"
        val dims = Regex("(\\d{2,4})x(\\d{2,4})").find(url.substringBefore('?'))
            ?.let { "${it.groupValues[1]}×${it.groupValues[2]}" }

        val kbps = bitrate / 1000
        // bytes = bitrate(bps) * duration(ms) / 8000; MB = /1e6
        val sizeMb = if (durationMs > 0) (bitrate * durationMs) / 8_000_000_000.0 else Double.NaN
        val sizeStr = if (!sizeMb.isNaN() && sizeMb > 0.05) {
            " · ~" + String.format(java.util.Locale.US, "%.1f", sizeMb) + " MB"
        } else ""
        return when {
            dims != null && bitrate > 0 -> "$dims · $kbps kbps$sizeStr"
            dims != null -> "$dims$sizeStr"
            bitrate > 0 -> "$kbps kbps$sizeStr"
            else -> "Original quality"
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------
    /** Returns the string at [name], or null when it is missing, null or blank. */
    private fun JSONObject.optNonBlankString(name: String): String? =
        optString(name, "").takeIf { it.isNotBlank() }

    internal fun photoExtension(raw: String): String {
        Regex("format=([a-zA-Z0-9]+)").find(raw)?.let { return it.groupValues[1].lowercase() }
        // Base already comes without the "?" query and without the ":large" suffix (see UrlMedia)
        val name = UrlMedia.extractCdnBase(raw).substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        val ok = ext.matches(Regex("[a-zA-Z0-9]{1,5}"))
        return if (ok) ext.lowercase() else "jpg"
    }
    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }
}

/** Extracts the "scheme://host/path" base of an X media URL (no query, no ":large" suffix). */
internal object UrlMedia {
    private val CdnBase = Regex("(https?://[^:?]+)")

    fun extractCdnBase(rawUrl: String): String =
        CdnBase.find(rawUrl)?.value ?: rawUrl.substringBefore('?')
}
