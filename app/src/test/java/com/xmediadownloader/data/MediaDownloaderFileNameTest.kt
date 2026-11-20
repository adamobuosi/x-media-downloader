package com.xmediadownloader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDownloaderFileNameTest {

    @Test
    fun `default name with video mime`() {
        val name = MediaDownloader.buildFileName(
            "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/AbC.mp4?tag=12",
            tweetId = "123",
            mediaIndex = 0,
            mime = "video/mp4",
        )
        assertEquals("tweet-123_1_AbC.mp4", name)
    }

    @Test
    fun `default name with photo mime by extension`() {
        fun nameFor(mime: String, url: String) = MediaDownloader.buildFileName(url, "55", 1, mime)
        assertEquals("tweet-55_2_img.png", nameFor("image/png", "https://pbs.twimg.com/media/img.png?name=orig"))
        assertEquals("tweet-55_2_img.webp", nameFor("image/webp", "https://pbs.twimg.com/media/img?format=webp&name=orig"))
        assertEquals("tweet-55_2_img.jpg", nameFor("image/jpeg", "https://pbs.twimg.com/media/img.jpg"))
        // video is always mp4, even when the URL is .m4s
        assertEquals("tweet-55_2_v.mp4", nameFor("video/mp4", "https://v.twimg.com/v.m4s"))
    }

    @Test
    fun `invalid characters are replaced`() {
        val name = MediaDownloader.buildFileName(
            "https://v.twimg.com/ab ç!@.mp4",
            tweetId = "9",
            mediaIndex = 0,
            mime = "video/mp4",
        )
        assertEquals("tweet-9_1_ab____.mp4", name)
    }

    @Test
    fun `url without useful name falls back to tweet id`() {
        val name = MediaDownloader.buildFileName(
            "https://v.twimg.com/?tag=1",
            tweetId = "77",
            mediaIndex = 0,
            mime = "video/mp4",
        )
        assertEquals("tweet-77_1_77.mp4", name)
    }

    @Test
    fun `very long base is truncated to 50`() {
        val longBase = "a".repeat(120)
        val name = MediaDownloader.buildFileName(
            "https://v.twimg.com/$longBase.mp4",
            tweetId = "5",
            mediaIndex = 0,
            mime = "video/mp4",
        )
        val expectedBase = "a".repeat(50)
        assertEquals("tweet-5_1_$expectedBase.mp4", name)
    }

    @Test
    fun `mediaIndex enters name starting at 1`() {
        assertEquals(
            "tweet-1_3_X.jpg",
            MediaDownloader.buildFileName("https://pbs.twimg.com/media/X.jpg", "1", 2, "image/jpeg"),
        )
    }
}
