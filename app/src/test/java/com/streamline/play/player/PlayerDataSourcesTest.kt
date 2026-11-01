package com.streamline.play.player

import com.streamline.play.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerDataSourcesTest {

    @Test
    fun blankUserAgentFallsBackToTheAppDefault() {
        assertEquals(AppSettings.DEFAULT_UA, PlayerDataSources.resolveUserAgent("", emptyMap()))
        assertEquals(AppSettings.DEFAULT_UA, PlayerDataSources.resolveUserAgent("   ", emptyMap()))
    }

    @Test
    fun injectedUserAgentWinsOverSettings() {
        assertEquals(
            "PipeAgent",
            PlayerDataSources.resolveUserAgent("SettingsAgent", mapOf("User-Agent" to "PipeAgent")),
        )
    }

    @Test
    fun requestHeadersCarryRefererAuthorizationAndCookies() {
        val headers = PlayerDataSources.headersFor(
            mapOf(
                "User-Agent" to "PipeAgent",
                "Referer" to "https://ref.example/",
                "Authorization" to "Bearer token123",
                "Cookie" to "session=abc",
                "X-Token" to "1",
                "Empty" to "  ",
            ),
        )

        assertEquals("*/*", headers["Accept"])
        assertEquals("keep-alive", headers["Connection"])
        assertEquals("https://ref.example/", headers["Referer"])
        assertEquals("Bearer token123", headers["Authorization"])
        assertEquals("session=abc", headers["Cookie"])
        assertEquals("1", headers["X-Token"])
        assertNull(headers["User-Agent"])
        assertFalse(headers.containsKey("Empty"))
    }

    @Test
    fun readTimeoutIsDisabledForLiveStreams() {
        assertEquals(0, PlayerDataSources.READ_TIMEOUT_MS)
        assertEquals(20_000, PlayerDataSources.CONNECT_TIMEOUT_MS)
    }
}
