package com.streamline.play.player

import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.MediaItem
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
    fun refresh()
    fun release()
}
