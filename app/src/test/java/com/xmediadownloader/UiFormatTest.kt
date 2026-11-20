package com.xmediadownloader

import com.xmediadownloader.data.MediaType.PHOTO
import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {

    @Test
    fun `formatBytes in bytes`() {
        assertEquals("999 B", formatBytes(999L))
        assertEquals("0 B", formatBytes(0L))
    }

    @Test
    fun `formatBytes in KB`() {
        assertEquals("1 KB", formatBytes(1_000L))
        assertEquals("123 KB", formatBytes(123_000L))
    }

    @Test
    fun `formatBytes in MB`() {
        formatBytes(1_500_000L).let { assertEquals("1.5 MB", it) }
        formatBytes(1_999_999L).let { assertEquals("2.0 MB", it) }
    }

    @Test
    fun `formatBytes negative returns question mark`() {
        assertEquals("?", formatBytes(-1L))
    }

    @Test
    fun `formatSpeed empty for zero and with suffix for positive`() {
        assertEquals("", formatSpeed(0L))
        assertEquals("2.5 MB/s", formatSpeed(2_500_000L))
    }

    @Test
    fun `media type labels`() {
        assertEquals("📷 Photo", mediaTypeLabel(PHOTO))
        assertEquals("🎥 Video", mediaTypeLabel(com.xmediadownloader.data.MediaType.VIDEO))
        assertEquals("🎞️ GIF", mediaTypeLabel(com.xmediadownloader.data.MediaType.GIF))
    }
}
