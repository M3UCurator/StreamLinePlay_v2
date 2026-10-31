package com.streamline.play.player.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProxyCoreTest {

    @Test
    fun signatureRoundTripRejectsTampering() {
        val signer = ProxySigner("streamline-secret".toByteArray())
        val url = "https://cdn.example/live/index.m3u8?token=abc"

        val signature = signer.sign(url)
        assertTrue(signer.verify(url, signature))
        assertFalse(signer.verify("$url&extra=1", signature))
        assertFalse(signer.verify(url, signature.dropLast(1)))
        assertFalse(signer.verify(url, "not-hex"))
        assertFalse(ProxySigner("other-secret".toByteArray()).verify(url, signature))
    }

    @Test
    fun guardBlocksUnsafeTargets() {
        assertNull(ProxyGuard.parseTarget("file:///data/local/tmp/x"))
        assertNull(ProxyGuard.parseTarget("http://127.0.0.1:8000/x"))
        assertNull(ProxyGuard.parseTarget("http://localhost/x"))
        assertNull(ProxyGuard.parseTarget("http://[::1]/x"))
        assertNull(ProxyGuard.parseTarget("not a url"))
        assertNull(ProxyGuard.parseTarget(null))
        assertNotNull(ProxyGuard.parseTarget("http://example.com/live.m3u8"))
        assertNotNull(ProxyGuard.parseTarget("https://cdn.example/live.m3u8"))
        assertEquals("example.com", ProxyGuard.hostOf("https://EXAMPLE.com:8080/x"))
    }

    @Test
    fun manifestRewritesChildPlaylistsKeysAndMediaUris() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/index.m3u8"
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            chunk/720/index.m3u8
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="https://subs.example/s.vtt"
            https://other.example/abs.m3u8
        """.trimIndent() + "\n"

        val rewritten = ManifestRewriter.rewrite(manifest, "https://cdn.example/live/master.m3u8?x=1") {
            "P($it)"
        }

        assertTrue(rewritten.contains("URI=\"P(https://cdn.example/live/keys/key.bin)\""))
        assertTrue(rewritten.contains("URI=\"P(https://cdn.example/live/audio/index.m3u8)\""))
        assertTrue(rewritten.contains("URI=\"P(https://cdn.example/live/init.mp4)\""))
        assertTrue(rewritten.contains("P(https://cdn.example/live/chunk/720/index.m3u8)"))
        assertTrue(rewritten.contains("P(https://subs.example/s.vtt)"))
        assertTrue(rewritten.contains("P(https://other.example/abs.m3u8)"))
        assertFalse(rewritten.contains("URI=\"keys/key.bin\""))
        assertTrue(rewritten.endsWith("\n"))
    }

    @Test
    fun manifestLeavesInlineDataUrisUntouched() {
        val manifest = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\n"

        val rewritten = ManifestRewriter.rewrite(manifest, "https://cdn.example/a.m3u8") { "P($it)" }

        assertTrue(rewritten.contains("data:text/plain;base64,AAAA"))
        assertFalse(rewritten.contains("P(data:"))
    }
}
