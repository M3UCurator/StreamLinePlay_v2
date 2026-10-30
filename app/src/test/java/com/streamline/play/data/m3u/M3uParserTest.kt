package com.streamline.play.data.m3u

import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val parser = M3uParser()
    private val source = SourceConfig(name = "test")

    @Test
    fun extractsAllEpgHeaderVariants() {
        val playlist = """
            #EXTM3U url-tvg="http://a/guide.xml.gz" x-tvg-url="http://b/guide.xml" tvg-url="http://c/guide.xml"
            #EXTINF:-1 tvg-id="one" group-title="News",One
            http://example.com/one.m3u8
        """.trimIndent()

        val result = parser.parse(playlist, source)

        assertEquals(
            listOf("http://a/guide.xml.gz", "http://b/guide.xml", "http://c/guide.xml"),
            result.epgUrls,
        )
        assertEquals(1, result.items.size)
    }

    @Test
    fun splitsCommaSeparatedEpgHeaders() {
        val playlist = """
            #EXTM3U url-tvg="http://a/guide.xml, http://b/guide.xml"
            #EXTINF:-1,One
            http://example.com/one.m3u8
        """.trimIndent()

        assertEquals(
            listOf("http://a/guide.xml", "http://b/guide.xml"),
            parser.parse(playlist, source).epgUrls,
        )
    }

    @Test
    fun ignoresNonHttpEpgHeaders() {
        val playlist = """
            #EXTM3U url-tvg="file:///local/guide.xml"
            #EXTINF:-1,One
            http://example.com/one.m3u8
        """.trimIndent()

        assertTrue(parser.parse(playlist, source).epgUrls.isEmpty())
    }

    @Test
    fun parsesZaChannelMetadataWithFlagsAndQualitySuffixes() {
        val playlist = """
            #EXTM3U x-tvg-url="https://epgshare01.online/epgshare01/epg_ripper_ZA1.xml.gz"
            #EXTINF:-1 tvg-chno="001" tvg-id="SABC.1.HD.za" tvg-logo="https://i.imgur.com/G8OXWe4.png" group-title="ZATV", SABC 1ᴴᴰ 🇿🇦 (720).s
            https://sabconeta.cdn.mangomolo.com/sabc1/smil:sabc1.stream.smil/chunklist_b1600000_t64NzIwcA==.m3u8
        """.trimIndent()

        val result = parser.parse(playlist, source)

        assertEquals(
            listOf("https://epgshare01.online/epgshare01/epg_ripper_ZA1.xml.gz"),
            result.epgUrls,
        )
        val item = result.items.single()
        assertEquals("SABC 1ᴴᴰ 🇿🇦 (720).s", item.name)
        assertEquals("SABC.1.HD.za", item.tvgId)
        assertEquals(1, item.channelNumber)
        assertEquals(ContentType.LIVE, item.type)
    }

    @Test
    fun keepsTvgNameWhenDisplayNameDiffers() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="SABC.1.HD.za" tvg-name="SABC 1 HD" group-title="ZATV",SABC One
            http://example.com/one.m3u8
        """.trimIndent()

        val item = parser.parse(playlist, source).items.single()

        assertEquals("SABC One", item.name)
        assertEquals("SABC 1 HD", item.tvgName)
    }
}
