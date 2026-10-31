package com.streamline.play.ui.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.ExternalPlayerApp
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.PlaybackEngineType
import com.streamline.play.data.settings.AppSettings
import com.streamline.play.player.EngineKind
import com.streamline.play.player.ExternalPlayerLauncher
import com.streamline.play.player.Media3Engine
import com.streamline.play.player.PlaybackEngine
import com.streamline.play.player.TrackOption
import com.streamline.play.player.VlcEngine
import com.streamline.play.streamLineContainer
import com.streamline.play.ui.nav.PlayerKey
import com.streamline.play.util.asMediaClock
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlayerStatus {
    PREPARING,
    READY,
    PAUSED,
    BUFFERING,
    ENDED,
    ERROR,
    EXTERNAL,
}

data class PlayerUiState(
    val itemId: String? = null,
    val status: PlayerStatus = PlayerStatus.PREPARING,
    val engineKind: EngineKind? = null,
    val engineSerial: Int = 0,
    val engineLabel: String = "Media3 ExoPlayer",
    val decoderLabel: String = "Auto",
    val error: String? = null,
    val errorCode: Int? = null,
    val fatal: Boolean = false,
    val recovering: Boolean = false,
    val external: Boolean = false,
    val externalMessage: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val aspect: AspectMode = AspectMode.FIT,
    val audioBoostSteps: Int = 0,
    val catchup: Boolean = false,
    val message: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.streamLineContainer
    private val appContext: Context = application.applicationContext
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(
        PlayerUiState(aspect = container.settingsRepository.current.value.preferredAspect),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var ticker: Job? = null
    private var playerKey: PlayerKey? = null
    private var baseSettings: AppSettings = container.settingsRepository.current.value
    private var decoderOverride: DecoderMode? = null
    private val subtitleUrls = mutableListOf<String>()

    /** Kept across an external handoff so playback can continue where it stopped. */
    private var savedPositionMs = 0L
    private var handoffPositionMs = 0L
    private var handoffAtMs = 0L
    private var externalReturnPositionMs = 0L

    val media3Player: ExoPlayer? get() = (engine as? Media3Engine)?.player

    fun start(key: PlayerKey, items: List<MediaItem>) {
        if (playerKey == key && (engine != null || _state.value.external)) return
        val catalogItem = items.firstOrNull { it.id == key.itemId } ?: return
        playerKey = key
        subtitleUrls.clear()
        decoderOverride = null
        savedPositionMs = 0L
        handoffPositionMs = 0L
        handoffAtMs = 0L
        externalReturnPositionMs = 0L
        baseSettings = container.settingsRepository.current.value
        val item = when {
            !key.streamUrl.isNullOrBlank() && key.streamUrl != catalogItem.url ->
                catalogItem.copy(url = key.streamUrl)
            else -> catalogItem
        }
        val derivedQueue = when (item.type) {
            ContentType.LIVE -> items.filter { it.type == ContentType.LIVE }
            ContentType.SERIES -> items.filter {
                it.type == ContentType.SERIES && it.seriesName == item.seriesName
            }
            ContentType.MOVIE -> items.filter { it.type == ContentType.MOVIE }
        }.ifEmpty { listOf(item) }

        _queue.value = derivedQueue
        _current.value = item
        _state.update {
            it.copy(
                itemId = item.id,
                status = PlayerStatus.PREPARING,
                catchup = key.catchupStartMs != null,
                aspect = baseSettings.preferredAspect,
                error = null,
                errorCode = null,
                fatal = false,
                recovering = false,
                external = false,
                externalMessage = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                audioBoostSteps = 0,
            )
        }
        beginPlayback(item, restartEngine = true, resumeMs = 0L)
    }

    fun play() {
        val active = engine ?: return
        if (active.snapshot.value.ended) active.seekTo(0)
        active.play()
    }

    fun pause() {
        engine?.pause()
    }

    fun togglePlay() {
        val active = engine ?: return
        if (active.snapshot.value.playing) active.pause() else play()
    }

    fun playNext() = stepQueue(1)

    fun playPrevious() = stepQueue(-1)

    fun switchTo(item: MediaItem) {
        _current.value = item
        _state.update {
            it.copy(
                itemId = item.id,
                status = PlayerStatus.PREPARING,
                error = null,
                errorCode = null,
                fatal = false,
                recovering = false,
                catchup = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
            )
        }
        beginPlayback(item, restartEngine = false, resumeMs = 0L)
    }

    fun selectAudio(id: String) {
        engine?.selectAudio(id)
    }

    fun selectSubtitle(id: String) {
        engine?.selectSubtitle(id)
    }

    fun loadSubtitle(url: String) {
        if (engine == null) return
        subtitleUrls += url
        runCatching { engine?.addSubtitle(url) }
        _state.update { it.copy(message = "Subtitle loaded") }
    }

    fun setAspect(mode: AspectMode) {
        _state.update { it.copy(aspect = mode) }
        engine?.setAspect(mode)
        container.settingsRepository.update { it.copy(preferredAspect = mode) }
    }

    fun toggleAudioBoost() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI,
        )
        val step = _state.value.audioBoostSteps + 1
        _state.update {
            it.copy(audioBoostSteps = step, message = "Volume boosted · +$step")
        }
    }

    fun reload() {
        val item = _current.value ?: return
        val resume = if (item.isLive) 0L else internalPosition(item)
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume)
        _state.update { it.copy(message = "Stream refreshed") }
    }

    fun cycleDecoder() {
        val order = listOf(DecoderMode.AUTO, DecoderMode.HW, DecoderMode.HW_PLUS, DecoderMode.SW)
        val active = decoderOverride ?: baseSettings.decoderMode
        val next = order[(order.indexOf(active) + 1) % order.size]
        decoderOverride = next
        val item = _current.value ?: return
        val resume = if (item.isLive) 0L else internalPosition(item)
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume)
        _state.update { it.copy(message = "Decoder: ${next.label}") }
    }

    fun switchEngine() {
        val item = _current.value ?: return
        val target = if (_state.value.engineKind == EngineKind.MEDIA3) {
            PlaybackEngineType.VLC
        } else {
            PlaybackEngineType.MEDIA3
        }
        val resume = if (item.isLive) 0L else internalPosition(item)
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume, forcedKind = target)
        _state.update { it.copy(message = "Switched to ${target.label}") }
    }

    fun openExternal() {
        val item = _current.value ?: return
        val target = baseSettings.externalPlayer
        val position = internalPosition(item)
        engine?.pause()
        val launched = ExternalPlayerLauncher.launch(
            appContext,
            item,
            baseSettings,
            target,
            startPositionMs = position,
        )
        if (launched) {
            savedPositionMs = position
            handoffPositionMs = position
            handoffAtMs = SystemClock.elapsedRealtime()
            externalReturnPositionMs = position
            // One-instance playback: the embedded engine is torn down so no audio
            // or decoder holds the stream while the external app plays it.
            releaseEngine()
            _state.update {
                it.copy(
                    external = true,
                    externalMessage = handoffMessage(target, position, item.isLive),
                    status = PlayerStatus.EXTERNAL,
                    engineKind = null,
                    positionMs = position,
                    fatal = false,
                    error = null,
                    errorCode = null,
                    recovering = false,
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList(),
                )
            }
        } else {
            _state.update { it.copy(message = "No compatible external player found") }
        }
    }

    /** Called when the app returns to the foreground after an external handoff. */
    fun onExternalReturn() {
        if (!_state.value.external) return
        val item = _current.value ?: return
        val elapsed = (SystemClock.elapsedRealtime() - handoffAtMs).coerceAtLeast(0L)
        val projected = if (item.isLive) 0L else handoffPositionMs + elapsed
        externalReturnPositionMs = projected
        savedPositionMs = projected
        _state.update {
            it.copy(
                positionMs = projected,
                externalMessage = if (item.isLive) {
                    "External player finished · resume the live stream here"
                } else {
                    "External player finished · continue at ${projected.asMediaClock()}"
                },
            )
        }
    }

    fun resumeHere() {
        val item = _current.value ?: return
        if (engine != null) {
            _state.update { it.copy(external = false, externalMessage = null) }
            play()
            return
        }
        val resume = externalResumePosition(item)
        _state.update {
            it.copy(
                external = false,
                externalMessage = null,
                positionMs = resume,
            )
        }
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume)
        _state.update {
            it.copy(
                message = if (item.isLive) "Resumed live playback" else "Resumed at ${resume.asMediaClock()}",
            )
        }
    }

    fun handoffAgain() {
        _state.update { it.copy(external = false) }
        openExternal()
    }

    fun useBuiltInPlayer() {
        val item = _current.value ?: return
        val resume = externalResumePosition(item)
        _state.update {
            it.copy(
                external = false,
                externalMessage = null,
                positionMs = resume,
            )
        }
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume, forcedKind = PlaybackEngineType.MEDIA3)
        _state.update {
            it.copy(
                message = if (item.isLive) {
                    "Using built-in Media3 player"
                } else {
                    "Media3 resumed at ${resume.asMediaClock()}"
                },
            )
        }
    }

    fun retry() {
        val item = _current.value ?: return
        val resume = if (item.isLive) 0L else internalPosition(item)
        launchBuiltIn(item, baseSettings, restartEngine = true, resumeMs = resume)
        _state.update { it.copy(message = "Reconnecting…") }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun createVlcLayout(context: Context): View =
        (engine as? VlcEngine)?.createView(context) ?: View(context)

    fun release() {
        releaseEngine()
        _queue.value = emptyList()
        _current.value = null
        playerKey = null
        savedPositionMs = 0L
        handoffPositionMs = 0L
        handoffAtMs = 0L
        externalReturnPositionMs = 0L
        _state.update { it.copy(external = false, externalMessage = null, status = PlayerStatus.PREPARING) }
    }

    override fun onCleared() {
        releaseEngine()
        super.onCleared()
    }

    private fun stepQueue(delta: Int) {
        val items = _queue.value
        if (items.isEmpty()) return
        val currentId = _current.value?.id
        val index = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val next = items[(index + delta + items.size) % items.size]
        switchTo(next)
    }

    private fun beginPlayback(item: MediaItem, restartEngine: Boolean, resumeMs: Long) {
        when (baseSettings.engine) {
            PlaybackEngineType.MPV -> handoff(item, ExternalPlayerApp.MPV, resumeMs)
            PlaybackEngineType.EXTERNAL -> handoff(item, baseSettings.externalPlayer, resumeMs)
            else -> launchBuiltIn(item, baseSettings, restartEngine, resumeMs)
        }
    }

    private fun handoff(item: MediaItem, target: ExternalPlayerApp, startPositionMs: Long) {
        val position = if (item.isLive) 0L else startPositionMs.coerceAtLeast(0L)
        val launched = ExternalPlayerLauncher.launch(
            appContext,
            item,
            baseSettings,
            target,
            startPositionMs = position,
        )
        if (launched) {
            savedPositionMs = position
            handoffPositionMs = position
            handoffAtMs = SystemClock.elapsedRealtime()
            externalReturnPositionMs = position
            releaseEngine()
            _state.update {
                it.copy(
                    status = PlayerStatus.EXTERNAL,
                    external = true,
                    externalMessage = handoffMessage(target, position, item.isLive),
                    engineLabel = target.label,
                    engineKind = null,
                    positionMs = position,
                    error = null,
                    errorCode = null,
                    fatal = false,
                )
            }
        } else {
            _state.update { it.copy(message = "${target.label} is not installed · using Media3") }
            launchBuiltIn(item, baseSettings, true, position, forcedKind = PlaybackEngineType.MEDIA3)
        }
    }

    /** Current stop point for a handoff, falling back to the last known position. */
    private fun internalPosition(item: MediaItem): Long {
        if (item.isLive) return 0L
        val live = engine?.snapshot?.value?.positionMs ?: 0L
        return if (live > 0L) live else savedPositionMs
    }

    private fun externalResumePosition(item: MediaItem): Long {
        if (item.isLive) return 0L
        return externalReturnPositionMs.coerceAtLeast(savedPositionMs).coerceAtLeast(0L)
    }

    private fun launchBuiltIn(
        item: MediaItem,
        settings: AppSettings,
        restartEngine: Boolean,
        resumeMs: Long,
        forcedKind: PlaybackEngineType? = null,
    ) {
        if (restartEngine || engine == null) {
            releaseEngine()
            val target = forcedKind ?: settings.engine
            val mode = decoderOverride ?: settings.decoderMode
            var fallbackMessage: String? = null
            val created = runCatching {
                if (target == PlaybackEngineType.VLC) {
                    VlcEngine(appContext, settings, mode)
                } else {
                    Media3Engine(appContext, settings, mode, container.streamProxy)
                }
            }.getOrElse {
                fallbackMessage = "VLC engine unavailable · using Media3"
                Media3Engine(appContext, settings, mode, container.streamProxy)
            }
            engine = created
            _state.update {
                it.copy(
                    engineKind = created.kind,
                    engineSerial = it.engineSerial + 1,
                    engineLabel = if (created.kind == EngineKind.VLC) "VLC Engine" else "Media3 ExoPlayer",
                    decoderLabel = mode.label,
                    status = PlayerStatus.PREPARING,
                    error = null,
                    message = fallbackMessage ?: it.message,
                )
            }
            startTicker()
        }
        val active = engine ?: return
        _state.update { it.copy(status = PlayerStatus.PREPARING, error = null) }
        runCatching {
            active.prepare(item, settings, resumeMs, subtitleUrls.toList())
            active.setAspect(_state.value.aspect)
        }.onFailure { error ->
            _state.update {
                it.copy(
                    status = PlayerStatus.ERROR,
                    error = error.message ?: "Unable to start this stream",
                )
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val active = engine ?: break
                runCatching { active.refresh() }
                applySnapshot(active)
                delay(500)
            }
        }
    }

    private fun applySnapshot(active: PlaybackEngine) {
        val snap = active.snapshot.value
        _state.update { state ->
            val status = when {
                snap.error != null && !snap.recovering -> PlayerStatus.ERROR
                snap.ended -> PlayerStatus.ENDED
                state.external -> PlayerStatus.EXTERNAL
                snap.buffering || snap.recovering -> PlayerStatus.BUFFERING
                snap.playing -> PlayerStatus.READY
                state.status == PlayerStatus.PREPARING -> PlayerStatus.PREPARING
                else -> PlayerStatus.PAUSED
            }
            state.copy(
                status = status,
                error = snap.error,
                errorCode = snap.errorCode,
                fatal = snap.fatal,
                recovering = snap.recovering,
                positionMs = snap.positionMs,
                durationMs = snap.durationMs,
                videoWidth = snap.videoWidth,
                videoHeight = snap.videoHeight,
                frameRate = snap.frameRate,
                bitrate = snap.bitrate,
                audioTracks = snap.audioTracks,
                subtitleTracks = snap.subtitleTracks,
            )
        }
        if (snap.fatal && !_state.value.external) {
            // Drop the failed instance immediately; the screen returns focus to the
            // originating list item while this engine is already torn down.
            savedPositionMs = snap.positionMs.coerceAtLeast(savedPositionMs)
            releaseEngine()
            _state.update { it.copy(engineKind = null, engineLabel = "Stopped") }
        }
    }

    private fun releaseEngine() {
        ticker?.cancel()
        ticker = null
        runCatching { engine?.release() }
        engine = null
    }

    private fun handoffMessage(target: ExternalPlayerApp, positionMs: Long, isLive: Boolean): String {
        val at = if (!isLive && positionMs > 0L) " at ${positionMs.asMediaClock()}" else ""
        return if (target.packageName == null) {
            "Pick any installed player · continues$at"
        } else {
            "Stream handed to ${target.label}$at"
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                PlayerViewModel(application)
            }
        }
    }
}
