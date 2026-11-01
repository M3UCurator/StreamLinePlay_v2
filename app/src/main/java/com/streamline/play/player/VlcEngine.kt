package com.streamline.play.player

import android.content.Context
import android.net.Uri
import androidx.core.view.doOnAttach
import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.NetworkProfile
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

class VlcEngine(
    private val context: Context,
    private val settings: AppSettings,
    private val decoderMode: DecoderMode,
) : PlaybackEngine {

    override val kind: EngineKind = EngineKind.VLC

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    private val libVlc = LibVLC(context, buildArguments(settings))
    private val mediaPlayer = MediaPlayer(libVlc)
    private val locks = PlaybackLocks(context)
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recoveryJob: Job? = null
    private var aspect: AspectMode = AspectMode.FIT
    private var durationMs: Long = 0L
    private var currentItem: MediaItem? = null
    private var currentSubtitles: List<String> = emptyList()
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var retries = 0
    private var recovering = false

    @Volatile
    private var released = false

    init {
        mediaPlayer.setEventListener { event -> handleEvent(event) }
    }

    fun createView(context: Context): VLCVideoLayout {
        val layout = VLCVideoLayout(context)
        layout.doOnAttach {
            runCatching { mediaPlayer.attachViews(layout, null, false, false) }
            runCatching { mediaPlayer.updateVideoSurfaces() }
        }
        return layout
    }

    override fun prepare(
        item: MediaItem,
        settings: AppSettings,
        startPositionMs: Long,
        subtitleUrls: List<String>,
    ) {
        recoveryJob?.cancel()
        currentItem = item
        currentSubtitles = subtitleUrls
        candidates = streamCandidates(item)
        candidateIndex = 0
        retries = 0
        recovering = false
        if (item.isLive) locks.acquire() else locks.release()
        startStream(candidates.firstOrNull() ?: item.url, if (item.isLive) 0L else startPositionMs)
    }

    private fun startStream(url: String, startPositionMs: Long) {
        durationMs = 0L
        _snapshot.value = EngineSnapshot(buffering = true, recovering = recovering)
        val item = currentItem
        val headers = settings.requestHeaders + item?.headers.orEmpty()
        val cache = cacheMs(settings)
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(decoderMode != DecoderMode.SW, decoderMode == DecoderMode.HW_PLUS)
            addOption(":network-caching=$cache")
            addOption(":live-caching=$cache")
            addOption(":http-reconnect")
            addOption(":http-continuous")
            addOption(":network-timeout=${NETWORK_TIMEOUT_MS}")
            headers["User-Agent"]?.let { addOption(":http-user-agent=$it") }
            headers["Referer"]?.let { addOption(":http-referrer=$it") }
            addQualityOptions(this)
            currentSubtitles.forEach { addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, it)) }
        }
        mediaPlayer.setMedia(media)
        media.release()
        mediaPlayer.play()
        if (startPositionMs > 0) mediaPlayer.setTime(startPositionMs)
        applyAspect()
        refresh()
    }

    override fun play() {
        if (mediaPlayer.isPlaying) return
        if (_snapshot.value.ended) {
            runCatching { mediaPlayer.setTime(0) }
            _snapshot.update { it.copy(ended = false, positionMs = 0L) }
        }
        mediaPlayer.play()
    }

    override fun pause() {
        runCatching { mediaPlayer.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { mediaPlayer.setTime(positionMs.coerceAtLeast(0)) }
    }

    override fun setAspect(mode: AspectMode) {
        aspect = mode
        applyAspect()
    }

    override fun selectAudio(id: String) {
        val trackId = id.removePrefix("aud:").toIntOrNull() ?: return
        runCatching { mediaPlayer.setAudioTrack(trackId) }
        publishTracks()
    }

    override fun selectSubtitle(id: String) {
        val trackId = if (id == SUBTITLE_OFF) -1 else id.removePrefix("sub:").toIntOrNull() ?: return
        runCatching { mediaPlayer.setSpuTrack(trackId) }
        publishTracks()
    }

    override fun addSubtitle(url: String) {
        runCatching {
            mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
        }
    }

    override fun refresh() {
        val length = runCatching { mediaPlayer.getLength() }.getOrDefault(0L)
        if (length > 0) durationMs = length
        _snapshot.update {
            it.copy(
                playing = runCatching { mediaPlayer.isPlaying }.getOrDefault(false),
                positionMs = runCatching { mediaPlayer.getTime() }.getOrDefault(0L).coerceAtLeast(0L),
                durationMs = durationMs,
            )
        }
    }

    override fun release() {
        released = true
        recoveryJob?.cancel()
        recoveryScope.cancel()
        runCatching { mediaPlayer.setEventListener(null) }
        locks.release()
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun recover() {
        val item = currentItem ?: return
        val resume = if (item.isLive) 0L else runCatching { mediaPlayer.getTime() }.getOrDefault(0L)
        if (candidateIndex + 1 < candidates.size) {
            candidateIndex++
            retries = 0
            recovering = true
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    error = "Stream dropped · switching to backup ${candidateIndex + 1}/${candidates.size}",
                )
            }
            recoveryJob?.cancel()
            recoveryJob = recoveryScope.launch {
                delay(RECONNECT_DELAY_MS)
                startStream(candidates.getOrElse(candidateIndex) { item.url }, resume)
            }
            return
        }
        if (retries < MAX_SILENT_RETRIES) {
            retries++
            recovering = true
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    error = "Reconnecting (${retries}/${MAX_SILENT_RETRIES})…",
                )
            }
            recoveryJob?.cancel()
            recoveryJob = recoveryScope.launch {
                delay(RECONNECT_DELAY_MS * retries)
                startStream(candidates.getOrElse(candidateIndex) { item.url }, resume)
            }
            return
        }
        recovering = false
        _snapshot.update {
            it.copy(
                playing = false,
                buffering = false,
                recovering = false,
                error = "VLC could not play this stream. Try another decoder or engine.",
            )
        }
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Opening -> _snapshot.update { it.copy(buffering = true, error = null) }
            MediaPlayer.Event.Buffering -> _snapshot.update { it.copy(buffering = true) }
            MediaPlayer.Event.Playing -> {
                retries = 0
                recovering = false
                _snapshot.update { it.copy(playing = true, buffering = false, error = null, recovering = false) }
                applyAspect()
                publishTracks()
                publishVideo()
            }
            MediaPlayer.Event.Paused -> _snapshot.update { it.copy(playing = false, buffering = false) }
            MediaPlayer.Event.Stopped -> _snapshot.update { it.copy(playing = false, buffering = false) }
            MediaPlayer.Event.EndReached -> _snapshot.update {
                it.copy(playing = false, buffering = false, ended = true, positionMs = durationMs)
            }
            MediaPlayer.Event.EncounteredError -> recover()
            MediaPlayer.Event.TimeChanged -> _snapshot.update {
                it.copy(positionMs = event.timeChanged.coerceAtLeast(0L))
            }
            MediaPlayer.Event.LengthChanged -> {
                if (event.lengthChanged > 0) durationMs = event.lengthChanged
                _snapshot.update { it.copy(durationMs = durationMs) }
            }
            MediaPlayer.Event.Vout -> {
                publishVideo()
                publishTracks()
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> publishTracks()
        }
    }

    private fun publishTracks() {
        val audio = runCatching {
            val currentId = mediaPlayer.getAudioTrack()
            mediaPlayer.getAudioTracks()
                ?.filter { it.id >= 0 }
                ?.map { TrackOption("aud:${it.id}", cleanName(it.name), selected = it.id == currentId) }
                .orEmpty()
        }.getOrDefault(emptyList())
        val subtitles = runCatching {
            val currentId = mediaPlayer.getSpuTrack()
            mediaPlayer.getSpuTracks()
                ?.map { TrackOption("sub:${it.id}", cleanName(it.name), selected = it.id == currentId) }
                .orEmpty()
        }.getOrDefault(emptyList())
        _snapshot.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }
    }

    private fun publishVideo() {
        val track = runCatching { mediaPlayer.getCurrentVideoTrack() }.getOrNull() ?: return
        val fps = if (track.frameRateDen > 0) {
            track.frameRateNum.toFloat() / track.frameRateDen.toFloat()
        } else {
            0f
        }
        _snapshot.update {
            it.copy(
                videoWidth = track.width,
                videoHeight = track.height,
                frameRate = fps,
                bitrate = track.bitrate,
            )
        }
    }

    private fun applyAspect() {
        runCatching {
            mediaPlayer.setVideoScale(
                when (aspect) {
                    AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                    AspectMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FILL
                    AspectMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
                    AspectMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
                    AspectMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
                },
            )
        }
    }

    private fun cleanName(name: String?): String = name?.replace("Disable", "Off") ?: "Track"

    /** Forces VLC's HLS/adaptive demuxer into the requested quality tier. */
    private fun addQualityOptions(media: Media) {
        val cap = settings.resolutionCap
        if (cap != ResolutionCap.AUTO) {
            media.addOption(":adaptive-maxwidth=${cap.maxWidth}")
            media.addOption(":adaptive-maxheight=${cap.maxHeight}")
        }
        if (settings.bitrateLimitKbps > 0) {
            media.addOption(":adaptive-bw=${settings.bitrateLimitKbps}")
        }
        settings.requestHeaders["Cookie"]?.let { media.addOption(":http-cookie=$it") }
    }

    private fun cacheMs(settings: AppSettings): Int {
        val tuning = settings.bufferTuning
        val base = tuning.playbackBufferMs + tuning.rebufferMs
        return when (settings.networkProfile) {
            NetworkProfile.LOW_RAM -> base.coerceIn(800, 1_500)
            NetworkProfile.ULTRA_LOW_LATENCY -> base.coerceIn(300, 600)
            NetworkProfile.AGGRESSIVE -> base.coerceIn(3_000, 6_000)
        }
    }

    companion object {
        const val SUBTITLE_OFF = "off"

        private const val MAX_SILENT_RETRIES = 3
        private const val RECONNECT_DELAY_MS = 900L
        private const val NETWORK_TIMEOUT_MS = 15_000

        private fun buildArguments(settings: AppSettings): List<String> {
            val tuning = settings.bufferTuning
            val cache = when (settings.networkProfile) {
                NetworkProfile.LOW_RAM -> (tuning.playbackBufferMs + tuning.rebufferMs).coerceIn(800, 1_500)
                NetworkProfile.ULTRA_LOW_LATENCY -> 450
                NetworkProfile.AGGRESSIVE -> 4_500
            }
            return listOf(
                "--network-caching=$cache",
                "--live-caching=$cache",
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--avcodec-hw=any",
            )
        }
    }
}
