package com.streamline.play.data.epg

import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.ui.currentProgramFor
import com.streamline.play.ui.nextProgramFor
import com.streamline.play.ui.scheduleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    private val zaGuide = listOf(
        "SABC.1.HD.za" to "SABC 1 HD",
        "SABC.2.HD.za" to "SABC 2 HD",
        "SABC.3.HD.za" to "SABC 3 HD",
        "SABC.News.za" to "SABC News",
        "M-Net.HD.za" to "M-Net HD",
        "M.Movies.1.za" to "M Movies 1",
        "E!.Entertainment.HD.za" to "E! Entertainment HD",
        "KykNET.&.Kie.HD.za" to "KykNET & Kie HD",
        "Discovery.Channel.HD.za" to "Discovery Channel HD",
        "SWITCH’D.ON.Channel.109.(HD).za" to "SWITCH’D ON Channel 109 (HD)",
    )

    private fun buildIndex(
        channels: List<Pair<String, String>>,
        startMs: Long = 1_000_000L,
        durationMs: Long = 60 * 60 * 1000L,
    ): Map<String, List<EpgProgram>> {
        val index = mutableMapOf<String, MutableList<EpgProgram>>()
        channels.forEachIndexed { position, (id, name) ->
            val program = EpgProgram(
                channelId = id,
                title = "Programme $position",
                description = "",
                startMs = startMs + position,
                endMs = startMs + position + durationMs,
            )
            EpgMatcher.variantKeys(id, name).forEach { key ->
                index.getOrPut(key) { mutableListOf() }.add(program)
            }
        }
        return index
    }

    private fun channel(name: String, tvgId: String? = null, tvgName: String? = null) = MediaItem(
        id = "test:$name",
        name = name,
        url = "http://example.com/$name.m3u8",
        type = ContentType.LIVE,
        tvgId = tvgId,
        tvgName = tvgName,
    )

    @Test
    fun strictIdMatchWinsOverNameMatch() {
        val index = buildIndex(
            listOf(
                "Alpha.HD.za" to "Alpha HD",
                "Beta.za" to "Beta",
            ),
        )
        // Name points at Alpha, tvg-id points at Beta: the strict id strategy wins.
        val item = channel(name = "Alpha HD", tvgId = "Beta.za")
        assertEquals("Beta.za", index.scheduleFor(item).single().channelId)
    }

    @Test
    fun tvgNameMatchesGuideDisplayName() {
        val index = buildIndex(zaGuide.take(4))
        // Unusable tvg-id, but the tvg-name alias equals the guide display name.
        val item = channel(name = "SABC One Banner", tvgId = "not-a-guide-id", tvgName = "SABC 1ᴴᴰ 🇿🇦 (720).s")
        assertEquals("SABC.1.HD.za", index.scheduleFor(item).single().channelId)
    }

    @Test
    fun zaPlaylistNamesResolveToGuideIds() {
        val index = buildIndex(zaGuide)
        val cases = mapOf(
            "SABC 1ᴴᴰ 🇿🇦 (720).s" to "SABC.1.HD.za",
            "SABC 2ᴴᴰ 🇿🇦 (720).s" to "SABC.2.HD.za",
            "SABC 3 🇿🇦 (HD).s" to "SABC.3.HD.za",
            "SABC Newsᴴᴰ 🇿🇦 (720).s" to "SABC.News.za",
            "M-Net HD" to "M-Net.HD.za",
            "ZA: M-Net" to "M-Net.HD.za",
            "DSTV: M-Net" to "M-Net.HD.za",
            "M Movies 1 FHD" to "M.Movies.1.za",
            "E! Entertainment" to "E!.Entertainment.HD.za",
            "KykNET & Kie HD" to "KykNET.&.Kie.HD.za",
            "SWITCH’D ON Channel 109" to "SWITCH’D.ON.Channel.109.(HD).za",
        )
        cases.forEach { (name, expectedId) ->
            val item = channel(name)
            assertEquals("Failed for $name", expectedId, index.scheduleFor(item).single().channelId)
        }
    }

    @Test
    fun everyProvidedZaChannelMatchesItsTvgId() {
        val channels = listOf(
            Triple("SABC 1ᴴᴰ 🇿🇦 (720).s", "SABC.1.HD.za", "SABC 1 HD"),
            Triple("SABC 2ᴴᴰ 🇿🇦 (720).s", "SABC.2.HD.za", "SABC 2 HD"),
            Triple("SABC 3 🇿🇦 (HD).s", "SABC.3.HD.za", "SABC 3 HD"),
            Triple("SABC Newsᴴᴰ 🇿🇦 (720).s", "SABC.News.za", "SABC News"),
        )
        val index = buildIndex(zaGuide)
        channels.forEach { (name, id, display) ->
            val item = channel(name, tvgId = id)
            assertEquals(id, index.scheduleFor(item).single().channelId)
            // Name-only fallback must resolve to the same channel even without tvg-id.
            val byName = channel(name, tvgName = display)
            assertEquals(id, index.scheduleFor(byName).single().channelId)
        }
    }

    @Test
    fun unrelatedChannelDoesNotMatch() {
        val index = buildIndex(zaGuide)
        assertTrue(index.scheduleFor(channel("SuperSport 1")).isEmpty())
        assertTrue(index.scheduleFor(channel("Random Movie Channel", tvgId = "bogus.id")).isEmpty())
    }

    @Test
    fun timelineProjectsCurrentNextAndProgress() {
        val start = XmltvDate.parse("20261029041500 +0700")!!
        val stop = XmltvDate.parse("20261029051500 +0700")!!
        assertEquals(XmltvDate.parse("20261028211500 +0000"), start)
        assertEquals(60L * 60L * 1000L, stop - start)

        val program = EpgProgram("SABC.1.HD.za", "Morning Show", "", start, stop)
        val index = EpgMatcher.variantKeys("SABC.1.HD.za", "SABC 1 HD")
            .associateWith { listOf(program) }
        val item = channel("SABC 1ᴴᴰ 🇿🇦 (720).s", tvgId = "SABC.1.HD.za")

        assertEquals("Morning Show", index.currentProgramFor(item, start + 30 * 60 * 1000L)?.title)
        assertEquals("Morning Show", index.nextProgramFor(item, start - 1)?.title)
        assertNull(index.currentProgramFor(item, stop + 1))
        assertEquals(0.25f, program.progressAt(start + 15 * 60 * 1000L), 0.0001f)
        assertEquals(1f, program.progressAt(stop + 1), 0.0001f)
    }
}
