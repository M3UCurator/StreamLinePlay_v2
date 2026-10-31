package com.streamline.play.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.streamline.play.data.settings.BufferTuning

/**
 * Grows the playback buffer reserve by 4 s for every freeze, up to 12 s above the
 * profile tuning. Continuous playback for a minute drops the extra reserve again.
 */
class AdaptiveBufferManager(
    private val tuning: BufferTuning,
    private val stepMs: Int = STEP_MS,
    private val maxExtraMs: Int = MAX_EXTRA_MS,
    val stableWindowMs: Long = STABLE_WINDOW_MS,
) {

    var extraMs: Int = 0
        private set

    val hasExtra: Boolean get() = extraMs > 0

    val minBufferMs: Int get() = tuning.minBufferMs + extraMs

    val playbackBufferMs: Int get() = tuning.playbackBufferMs + extraMs

    val maxBufferMs: Int get() = maxOf(tuning.maxBufferMs, minBufferMs + stepMs)

    val rebufferMs: Int get() = tuning.rebufferMs

    fun onFreeze(): Int {
        extraMs = minOf(extraMs + stepMs, maxExtraMs)
        return extraMs
    }

    fun onStablePlayback(): Boolean {
        val changed = extraMs > 0
        extraMs = 0
        return changed
    }

    fun reset() {
        extraMs = 0
    }

    companion object {
        const val STEP_MS = 4_000
        const val MAX_EXTRA_MS = 12_000
        const val STABLE_WINDOW_MS = 60_000L
    }
}

/**
 * LoadControl facade whose DefaultLoadControl delegate can be rebuilt in place, which
 * media3 1.9 cannot do through ExoPlayer once playback has started. The allocator is
 * shared so buffer memory survives every swap.
 */
class AdaptiveLoadControl(
    private val manager: AdaptiveBufferManager,
    private val targetBytes: Int,
) : LoadControl {

    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val preparedPlayers = mutableSetOf<PlayerId>()

    @Volatile
    private var delegate: LoadControl = build()

    private fun build(): LoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setTargetBufferBytes(targetBytes)
        .setBufferDurationsMs(
            manager.minBufferMs,
            manager.maxBufferMs,
            manager.playbackBufferMs,
            manager.rebufferMs,
        )
        .build()

    /** Registers every active player on the new delegate before it becomes visible. */
    fun refresh() {
        val next = build()
        synchronized(this) {
            preparedPlayers.forEach { playerId -> next.onPrepared(playerId) }
            delegate = next
        }
    }

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun onPrepared(playerId: PlayerId) {
        synchronized(this) { preparedPlayers += playerId }
        delegate.onPrepared(playerId)
    }

    override fun onPrepared() = delegate.onPrepared()

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onStopped() = delegate.onStopped()

    override fun onReleased(playerId: PlayerId) {
        synchronized(this) { preparedPlayers -= playerId }
        delegate.onReleased(playerId)
    }

    override fun onReleased() = delegate.onReleased()

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun getBackBufferDurationUs(): Long = delegate.getBackBufferDurationUs()

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun retainBackBufferFromKeyframe(): Boolean = delegate.retainBackBufferFromKeyframe()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
    ): Boolean = delegate.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldStartPlayback(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long,
    ): Boolean = delegate.shouldStartPlayback(
        timeline,
        mediaPeriodId,
        bufferedDurationUs,
        playbackSpeed,
        rebuffering,
        targetLiveOffsetUs,
    )

    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long,
    ): Boolean = delegate.shouldStartPlayback(
        bufferedDurationUs,
        playbackSpeed,
        rebuffering,
        targetLiveOffsetUs,
    )
}
