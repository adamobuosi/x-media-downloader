package com.xmediadownloader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests of the parsing functions (pure functions over JSON) for the two response
 * shapes: FxTwitter (`width/height`, `duration` in seconds) and API v1.1
 * (`video_info`, `sizes.*`).
 */
class TweetApiParsingTest {

    // ------------------------------------------------------------------
    // Photos — FxTwitter format
    // ------------------------------------------------------------------
    @Test
    fun `fxtwitter photo generates 4 sizes and dims in Original label`() {
        val jo = JSONObject(
            """
            {"type":"photo","id":"1",
             "url":"https://pbs.twimg.com/media/AAA12.jpg?name=orig",
             "width":858,"height":678}
            """.trimIndent()
        )
        val media = TweetApi.parsePhotoFromAny(0, jo)!!
        assertEquals(MediaType.PHOTO, media.type)
        assertEquals(4, media.options.size)
        assertEquals(listOf("Original (858×678)", "Large", "Medium", "Small"), media.options.map { it.label })
        assertEquals("https://pbs.twimg.com/media/AAA12.jpg?format=jpg&name=orig", media.options[0].url)
        assertEquals("image/jpeg", media.options[0].mime)
    }

    // ------------------------------------------------------------------
    // Photos — API v1.1 format (syndication/guest)
    // ------------------------------------------------------------------
    @Test
    fun `v11 photo uses sizes for dims and ext via format`() {
        val jo = JSONObject(
            """
            {"type":"photo",
             "media_url_https":"https://pbs.twimg.com/media/AbC.png?format=png&name=900x900",
             "sizes":{
               "large":{"w":1000,"h":500,"resize":"fit"},
               "small":{"w":250,"h":125,"resize":"fit"}
             }}
            """.trimIndent()
        )
        val media = TweetApi.parsePhotoFromAny(0, jo)!!
        assertEquals(4, media.options.size)
        assertEquals("Original (1000×500)", media.options[0].label)
        assertEquals("https://pbs.twimg.com/media/AbC.png?format=png&name=orig", media.options[0].url)
        assertEquals("image/png", media.options[0].mime)
        // large uses dims of large; small uses dims of small
        assertEquals("Large (1000×500)", media.options[1].label)
        assertEquals("Small (250×125)", media.options[3].label)
        // medium was not in the response → no dims will exist, but the named option does
        assertEquals("Medium", media.options[2].label)
    }

    @Test
    fun `photo without recognized extension falls back to jpg`() {
        val jo = JSONObject("""{"type":"photo","url":"https://pbs.twimg.com/media/SemExt","width":10,"height":10}""")
        val media = TweetApi.parsePhotoFromAny(0, jo)!!
        assertEquals("https://pbs.twimg.com/media/SemExt?format=jpg&name=orig", media.options[0].url)
    }

    @Test
    fun `item without photo url returns null`() {
        assertNull(TweetApi.parsePhotoFromAny(0, JSONObject("""{"type":"photo","width":10,"height":10}""")))
    }

    @Test
    fun `photoExtension reads format query and extension`() {
        assertEquals("png", TweetApi.photoExtension("https://pbs.twimg.com/media/X.png?format=png&name=900x900"))
        assertEquals("webp", TweetApi.photoExtension("https://pbs.twimg.com/media/X?format=webp"))
        assertEquals("jpg", TweetApi.photoExtension("https://pbs.twimg.com/media/X.jpg:large"))
        assertEquals("jpg", TweetApi.photoExtension("https://pbs.twimg.com/media/X"))
    }

    // ------------------------------------------------------------------
    // Videos — FxTwitter format
    // ------------------------------------------------------------------
    @Test
    fun `fxtwitter video filters m3u8 and sorts by highest bitrate`() {
        val jo = JSONObject(
            """
            {"type":"video",
             "url":"https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/aaa.mp4?tag=12",
             "duration":12.5,
             "variants":[
               {"type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/1/pu/pl/m3u8.m3u8?tag=12"},
               {"type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/240x480/bbb.mp4?tag=12","bitrate":320000},
               {"type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/aaa.mp4?tag=12","bitrate":1850000}
             ]}
            """.trimIndent()
        )
        val media = TweetApi.parseVideoFromAny(0, jo, MediaType.VIDEO)!!
        assertEquals(MediaType.VIDEO, media.type)
        assertEquals(2, media.options.size) // .m3u8 filtered out
        // highest bitrate first
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/aaa.mp4?tag=12", media.options[0].url)
        // label uses URL dims + bitrate + estimated size (bitrate * 12.5 s)
        val label = media.options[0].label
        assert(label.contains("1280×720"))
        assert(label.contains("1850 kbps")) // 1850000/1000
        assert(label.contains("~2.9 MB")) // 2890625 B = 2.89 MB
    }

    @Test
    fun `byte estimate from bitrate and duration in seconds`() {
        val jo = JSONObject(
            """
            {"type":"video","duration":8.0,
             "variants":[{"type":"video/mp4","bitrate":1000000,
                          "url":"https://video.twimg.com/x/vid/1280x720/v.mp4"}]}
            """.trimIndent()
        )
        val media = TweetApi.parseVideoFromAny(0, jo, MediaType.VIDEO)!!
        assertEquals(1_000_000L, media.options[0].estimatedBytes) // 1000000*8000/8000
    }

    // ------------------------------------------------------------------
    // Videos — API v1.1 format (video_info / duration_millis)
    // ------------------------------------------------------------------
    @Test
    fun `v11 video with video_info and duration_millis`() {
        val jo = JSONObject(
            """
            {"type":"video",
             "media_url_https":"https://pbs.twimg.com/media/xyz.jpg",
             "video_info":{
               "duration_millis":4500,
               "variants":[
                 {"bitrate":832000,"content_type":"video/mp4","url":"https://video.twimg.com/x/vid/560x960/xyz.mp4"},
                 {"bitrate":0,"content_type":"application/x-mpegURL","url":"https://video.twimg.com/x/pl/xyz.m3u8"}
               ]}}
            """.trimIndent()
        )
        val media = TweetApi.parseVideoFromAny(0, jo, MediaType.VIDEO)!!
        assertEquals(1, media.options.size) // .m3u8 removed
        assertEquals("video/mp4", media.options[0].mime)
        assertEquals(468_000L, media.options[0].estimatedBytes) // 832000*4500/8000
    }

    @Test
    fun `fxtwitter gif uses main url when there is no mp4 variant`() {
        val jo = JSONObject(
            """
            {"type":"gif","url":"https://video.twimg.com/tweet_video/gif/abc.mp4","duration":3.2,
             "variants":[{"type":"application/x-mpegURL","url":"https://video.twimg.com/pl/x.m3u8"}]}
            """.trimIndent()
        )
        val media = TweetApi.parseVideoFromAny(0, jo, MediaType.GIF)!!
        assertEquals(MediaType.GIF, media.type)
        assertEquals("https://video.twimg.com/tweet_video/gif/abc.mp4", media.options[0].url)
        assertEquals("video/mp4", media.options[0].mime)
    }

    @Test
    fun `duplicate variants are deduplicated`() {
        val jo = JSONObject(
            """
            {"type":"video",
             "variants":[
               {"type":"video/mp4","bitrate":100,"url":"https://v.twimg.com/a.mp4"},
               {"type":"video/mp4","bitrate":100,"url":"https://v.twimg.com/a.mp4"}
             ]}
            """.trimIndent()
        )
        val media = TweetApi.parseVideoFromAny(0, jo, MediaType.VIDEO)!!
        assertEquals(1, media.options.size)
    }

    @Test
    fun `video without any mp4 variant returns null`() {
        val jo = JSONObject(
            """
            {"type":"video","variants":[{"type":"application/x-mpegURL","url":"https://v.twimg.com/x.m3u8"}]}
            """.trimIndent()
        )
        assertNull(TweetApi.parseVideoFromAny(0, jo, MediaType.VIDEO))
    }

    @Test
    fun `videoLabel builds dims kbps and estimated size`() {
        val label = TweetApi.videoLabel("https://video.twimg.com/x/vid/1280x720/a.mp4", 2_000_000L, 5_000L)
        assert(label.startsWith("1280×720")) // known resolution
        assert(label.contains("2000 kbps"))
        assert(label.contains("~1.3 MB")) // 1.25 MB, HALF_UP → 1.3
        val noDims = TweetApi.videoLabel("https://video.twimg.com/a.mp4", 0L, 0L)
        assertEquals("Original quality", noDims)
    }
}
