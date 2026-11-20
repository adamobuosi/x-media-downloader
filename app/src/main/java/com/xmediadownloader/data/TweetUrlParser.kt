package com.xmediadownloader.data

/**
 * Extracts a tweet ID from a text/URL.
 * Supported formats:
 *  - https://twitter.com/user/status/1234567890
 *  - https://x.com/user/status/1234567890
 *  - https://mobile.twitter.com/user/status/1234567890
 *  - vxtwitter/fxtwitter (nitter no longer exists; /video/ /photo/ variants)
 */
object TweetUrlParser {

    private val tweetRegex = Regex(
        pattern = "(?:https?://)?(?:(?:www|mobile|vx|fx)\\.)?(?:twitter\\.com|x\\.com)/" +
            "[A-Za-z0-9_]{1,25}/(?:status|statuses|web/status|watch)/(?:photo/|video/|media/)?(\\d+)",
        option = RegexOption.IGNORE_CASE
    )

    private val anyUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /** Tries to find the tweet ID in any pasted/shared text.
     *  The regex accepts URLs with or without the protocol, so one search is enough. */
    fun extractTweetId(text: String): String? =
        tweetRegex.find(text.trim())?.groupValues?.get(1)

    /** First http(s) URL present in the shared text, used to pre-fill the field. */
    fun extractFirstUrl(text: String): String? =
        anyUrlRegex.find(text)?.value?.trimEnd(',', '.', ')', ']')
}
