package com.xmediadownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TweetUrlParserTest {

    @Test
    fun `extracts id from xcom URL`() {
        assertEquals("123456789", TweetUrlParser.extractTweetId("https://x.com/usuario/status/123456789"))
        assertEquals("123456789", TweetUrlParser.extractTweetId("x.com/usuario/status/123456789"))
    }

    @Test
    fun `extracts id from twitter com URL`() {
        assertEquals("42", TweetUrlParser.extractTweetId("https://twitter.com/usuario/status/42"))
    }

    @Test
    fun `extracts id from domain and path variants`() {
        assertEquals("999", TweetUrlParser.extractTweetId("https://mobile.twitter.com/user/status/999"))
        assertEquals("999", TweetUrlParser.extractTweetId("https://www.x.com/user/statuses/999"))
        assertEquals("999", TweetUrlParser.extractTweetId("https://vx.twitter.com/user/web/status/999"))
        assertEquals("999", TweetUrlParser.extractTweetId("https://twitter.com/user/status/photo/999"))
        assertEquals("999", TweetUrlParser.extractTweetId("https://twitter.com/user/status/video/999"))
        assertEquals("999", TweetUrlParser.extractTweetId("https://twitter.com/i/status/999"))
    }

    @Test
    fun `extracts id from shared text with URL in the middle`() {
        val shared = "Confira isso! \"https://x.com/foo/status/777888\" via @app"
        assertEquals("777888", TweetUrlParser.extractTweetId(shared))
    }

    @Test
    fun `returns null for text without tweet`() {
        assertNull(TweetUrlParser.extractTweetId("olha só que legal"))
        assertNull(TweetUrlParser.extractTweetId("https://example.com/foo/1"))
        assertNull(TweetUrlParser.extractTweetId("https://x.com/usuario"))
        assertNull(TweetUrlParser.extractTweetId(""))
    }

    @Test
    fun `ignores incomplete or non-numeric id`() {
        assertNull(TweetUrlParser.extractTweetId("https://x.com/user/status/abc"))
        assertNull(TweetUrlParser.extractTweetId("https://x.com/user/status/"))
    }

    @Test
    fun `extractFirstUrl returns the first URL from text`() {
        assertEquals("https://x.com/a/status/1", TweetUrlParser.extractFirstUrl("veja https://x.com/a/status/1 e depois https://x.com/b/status/2"))
        assertEquals("https://example.com", TweetUrlParser.extractFirstUrl("texto https://example.com, fim"))
        assertNull(TweetUrlParser.extractFirstUrl("sem url nenhuma"))
        assertNull(TweetUrlParser.extractFirstUrl(""))
    }

    @Test
    fun `extractFirstUrl removes trailing punctuation`() {
        assertEquals("https://x.com/a/status/9", TweetUrlParser.extractFirstUrl("link: https://x.com/a/status/9."))
    }
}
