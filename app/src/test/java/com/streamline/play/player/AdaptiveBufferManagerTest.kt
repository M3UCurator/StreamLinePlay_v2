package com.streamline.play.player

import com.streamline.play.data.settings.BufferTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferManagerTest {

    private fun tuning(maxBufferMs: Int = 30_000) = BufferTuning(
        targetBytes = 16 * 1024 * 1024,
        minBufferMs = 3_000,
        maxBufferMs = maxBufferMs,
        playbackBufferMs = 3_000,
        rebufferMs = 1_500,
    )

    @Test
    fun freezesRaiseReserveFourSecondsUntilCap() {
        val manager = AdaptiveBufferManager(tuning())

        assertEquals(3_000, manager.minBufferMs)
        assertEquals(4_000, manager.onFreeze())
        assertEquals(7_000, manager.minBufferMs)
        assertEquals(7_000, manager.playbackBufferMs)

        manager.onFreeze()
        manager.onFreeze()
        assertEquals(12_000, manager.extraMs)
        assertEquals(15_000, manager.minBufferMs)

        assertEquals(12_000, manager.onFreeze())
        assertEquals(12_000, manager.extraMs)
    }

    @Test
    fun stablePlaybackDropsReserve() {
        val manager = AdaptiveBufferManager(tuning())
        manager.onFreeze()
        assertTrue(manager.hasExtra)

        assertTrue(manager.onStablePlayback())
        assertFalse(manager.hasExtra)
        assertEquals(3_000, manager.minBufferMs)
        assertFalse(manager.onStablePlayback())
    }

    @Test
    fun maxBufferAlwaysCoversTheRaisedReserve() {
        val manager = AdaptiveBufferManager(tuning(maxBufferMs = 10_000))
        repeat(3) { manager.onFreeze() }

        assertEquals(15_000, manager.minBufferMs)
        assertEquals(19_000, manager.maxBufferMs)
    }
}
