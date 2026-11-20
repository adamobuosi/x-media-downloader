package com.xmediadownloader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloaderUrlPolicyTest {

    @Test
    fun `twitter and x media hosts over https are allowed`() {
        assertTrue(MediaDownloader.isAllowedMediaUrl("https://pbs.twimg.com/media/AAA12.jpg?name=orig"))
        assertTrue(MediaDownloader.isAllowedMediaUrl("https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/x.mp4"))
        assertTrue(MediaDownloader.isAllowedMediaUrl("https://PBS.TWIMG.COM/media/x.jpg"))
    }

    @Test
    fun `other hosts are refused`() {
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://example.com/x.mp4"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://twimg.com.evil.example/x.mp4"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://eviltwimg.com/x.mp4"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://.twimg.com/x.mp4"))
    }

    @Test
    fun `plain http, other schemes and credentials are refused`() {
        assertFalse(MediaDownloader.isAllowedMediaUrl("http://pbs.twimg.com/media/x.jpg"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("file:///sdcard/x.jpg"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://user:pass@pbs.twimg.com/x.jpg"))
        assertFalse(MediaDownloader.isAllowedMediaUrl("https://pbs.twimg.com:8443/x.jpg"))
    }

    @Test
    fun `malformed urls are refused`() {
        assertFalse(MediaDownloader.isAllowedMediaUrl(""))
        assertFalse(MediaDownloader.isAllowedMediaUrl("not a url"))
    }
}
