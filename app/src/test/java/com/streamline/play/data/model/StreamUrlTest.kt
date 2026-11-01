package com.streamline.play.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {

    @Test
    fun pipeSuffixBecomesRequestHeaders() {
        val parsed = StreamUrl.parse(
            "http://cdn.example/live.m3u8|User-Agent=PipeAgent&Referer=https://portal.example/&Authorization=Bearer token123",
        )

        assertEquals("http://cdn.example/live.m3u8", parsed.url)
        assertEquals("PipeAgent", parsed.headers["User-Agent"])
        assertEquals("https://portal.example/", parsed.headers["Referer"])
        assertEquals("Bearer token123", parsed.headers["Authorization"])
    }

    @Test
    fun plainUrlIsUnchanged() {
        val parsed = StreamUrl.parse("https://cdn.example/live.m3u8?token=abc")

        assertEquals("https://cdn.example/live.m3u8?token=abc", parsed.url)
        assertTrue(parsed.headers.isEmpty())
    }

    @Test
    fun queryStringSurvivesHeaderSplit() {
        val parsed = StreamUrl.parse("http://cdn.example/x.m3u8?a=1&b=2|ua=Curl/8")

        assertEquals("http://cdn.example/x.m3u8?a=1&b=2", parsed.url)
        assertEquals("Curl/8", parsed.headers["User-Agent"])
    }

    @Test
    fun quotedAndPipeSeparatedValuesAreNormalized() {
        val parsed = StreamUrl.parse(
            "http://cdn.example/y.m3u8|referer=\"https://ref.example/\"|auth=Token 9",
        )

        assertEquals("https://ref.example/", parsed.headers["Referer"])
        assertEquals("Token 9", parsed.headers["Authorization"])
    }

    @Test
    fun customHeaderNameIsTitleCased() {
        assertEquals("X-Playback-Session", StreamUrl.canonicalHeader("x-playback_session"))
    }
}
