package com.streamline.play.player

import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

enum class EngineKind {
    MEDIA3,
    VLC,
    EXTERNAL,
}

data class TrackOption(
    val id: String,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
    /** Used to order video renditions from highest to lowest. */
    val bitrateHint: Int = 0,
)

data class EngineSnapshot(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    /** Video renditions exposed by the current adaptive manifest. */
    val videoRenditions: List<TrackOption> = emptyList(),
    /** Cap currently enforced by the engine, including automatic downgrades. */
    val activeCap: ResolutionCap = ResolutionCap.AUTO,
    val ultraSharpActive: Boolean = false,
    val error: String? = null,
    val errorCode: Int? = null,
    val fatal: Boolean = false,
    val recovering: Boolean = false,
    val ended: Boolean = false,
)

interface PlaybackEngine {
    val kind: EngineKind
    val snapshot: StateFlow<EngineSnapshot>

    fun prepare(
        item: MediaItem,
        settings: AppSettings,
        startPositionMs: Long = 0L,
        subtitleUrls: List<String> = emptyList(),
    )

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setAspect(mode: AspectMode)
    fun selectAudio(id: String)
    fun selectSubtitle(id: String)
    fun addSubtitle(url: String)

    /** Caps the rendition ladder; [ResolutionCap.AUTO] restores adaptive selection. */
    fun setQualityCap(cap: ResolutionCap) = Unit

    /** Pins one rendition, or [VIDEO_AUTO] to return to adaptive selection. */
    fun selectVideo(id: String) = Unit

    fun setUltraSharp(enabled: Boolean) = Unit

    fun refresh()
    fun release()

    companion object {
        const val VIDEO_AUTO = "video:auto"
    }
}
