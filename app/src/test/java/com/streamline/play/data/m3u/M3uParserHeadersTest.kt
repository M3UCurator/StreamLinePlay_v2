package com.streamline.play.data.m3u

import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserHeadersTest {

    private val source = SourceConfig(id = "test", name = "Test")

    private fun items(content: String): List<MediaItem> = M3uParser().parse(content, source).items

    @Test
    fun vlcDirectivesBecomeRequestHeaders() {
        val item = items(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Live",VLC Channel
            #EXTVLCOPT:http-user-agent=VLC/3.0.20 LibVLC/3.0.20
            #EXTVLCOPT:http-referrer=https://portal.example/watch
            http://cdn.example/one.m3u8
            """.trimIndent(),
        ).single()

        assertEquals("http://cdn.example/one.m3u8", item.url)
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", item.headers["User-Agent"])
        assertEquals("https://portal.example/watch", item.headers["Referer"])
    }

    @Test
    fun pipeHeadersAreStrippedAndOverrideVlcDirectives() {
        val item = items(
            """
            #EXTM3U
            #EXTINF:-1,Token Channel
            #EXTVLCOPT:http-user-agent=VLC/3.0.20
            http://cdn.example/two.m3u8|User-Agent=PipeAgent&Referer=https://ref.example/&Authorization=Bearer token123
            """.trimIndent(),
        ).single()

        assertEquals("http://cdn.example/two.m3u8", item.url)
        assertEquals("PipeAgent", item.headers["User-Agent"])
        assertEquals("https://ref.example/", item.headers["Referer"])
        assertEquals("Bearer token123", item.headers["Authorization"])
    }

    @Test
    fun plainPlaylistEntriesCarryNoHeaders() {
        val item = items(
            """
            #EXTM3U
            #EXTINF:-1,Plain
            http://cdn.example/three.m3u8?token=abc
            """.trimIndent(),
        ).single()

        assertEquals("http://cdn.example/three.m3u8?token=abc", item.url)
        assertEquals(emptyMap<String, String>(), item.headers)
    }
}
