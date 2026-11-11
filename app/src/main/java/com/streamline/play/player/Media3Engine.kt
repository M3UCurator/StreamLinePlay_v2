package com.streamline.play.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.effect.LanczosResample
import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.settings.AppSettings
import com.streamline.play.player.proxy.StreamProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class Media3Engine(
    private val context: Context,
    private val settings: AppSettings,
    private val decoderMode: DecoderMode,
    private val proxy: StreamProxyServer? = null,
) : PlaybackEngine {

    override val kind: EngineKind = EngineKind.MEDIA3

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    val player: ExoPlayer
    private val audioLookup = mutableMapOf<String, TrackRef>()
    private val subtitleLookup = mutableMapOf<String, TrackRef>()
    private val videoLookup = mutableMapOf<String, TrackRef>()
    private var userCap: ResolutionCap = settings.resolutionCap
    private var autoCap: ResolutionCap = ResolutionCap.AUTO
    private var ultraSharpEnabled: Boolean = settings.ultraSharpUpscaling
    private var effectsActive = false
    private var effectsFailed = false
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recoveryJob: Job? = null
    private var probeJob: Job? = null
    private val probedFormats = HashMap<String, String?>()
    private val adaptiveBuffer = AdaptiveBufferManager(settings.bufferTuning)
    private val adaptiveLoadControl =
        AdaptiveLoadControl(adaptiveBuffer, settings.bufferTuning.targetBytes)
    private val locks = PlaybackLocks(context)
    private var appliedExtraBufferMs = 0
    private var playedOnce = false
    private var playingSinceMs = 0L
    private var liveRetries = 0

    private var currentItem: MediaItem? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentSubtitles: List<String> = emptyList()
    private var currentStartPositionMs = 0L
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var headerProfiles: List<HeaderProfile> = emptyList()
    private var headerIndex = 0
    private var stallRetries = 0
    private var lastStallCheckMs = 0L
    private var lastStallPositionMs = 0L
    private var recovering = false

    private data class HeaderProfile(val label: String, val headers: Map<String, String>)
    private data class TrackRef(val group: TrackGroup, val index: Int)

    init {
        val renderers = DefaultRenderersFactory(context).apply {
            setEnableAudioFloatOutput(!settings.audioPassthrough)
            when (decoderMode) {
                DecoderMode.AUTO -> {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    setEnableDecoderFallback(true)
                }
                DecoderMode.HW -> {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    setEnableDecoderFallback(false)
                    setMediaCodecSelector(hardwareOnly)
                }
                DecoderMode.HW_PLUS -> {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    setEnableDecoderFallback(true)
                    setMediaCodecSelector(hardwareFirst)
                }
                DecoderMode.SW -> {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    setEnableDecoderFallback(false)
                    setMediaCodecSelector(softwareOnly)
                }
            }
        }

        val trackParameters = TrackSelectionParameters.Builder()
            .setMaxVideoSize(settings.resolutionCap.maxWidth, settings.resolutionCap.maxHeight)
            .apply {
                if (settings.maxVideoBitrateBps > 0) {
                    setMaxVideoBitrate(settings.maxVideoBitrateBps)
                }
            }
            .build()

        val trackSelector = DefaultTrackSelector(context, trackParameters)

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(adaptiveLoadControl)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playedOnce = true
                if (playbackState == Player.STATE_BUFFERING) escalateBuffer()
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    stallRetries = 0
                    liveRetries = 0
                    recovering = false
                    playingSinceMs = SystemClock.elapsedRealtime()
                    _snapshot.update { it.copy(recovering = false, fatal = false, error = null) }
                } else {
                    playingSinceMs = 0L
                }
                publish()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
            override fun onVideoSizeChanged(videoSize: VideoSize) = publishVideo(videoSize)
            override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)
            override fun onPlayerError(error: PlaybackException) = handlePlaybackError(error)
        })
        player.playWhenReady = true
        applyVideoEffects()
    }

    override fun prepare(
        item: MediaItem,
        settings: AppSettings,
        startPositionMs: Long,
        subtitleUrls: List<String>,
    ) {
        recoveryJob?.cancel()
        probeJob?.cancel()
        currentItem = item
        currentSubtitles = subtitleUrls
        currentHeaders = settings.requestHeaders + item.headers
        currentStartPositionMs = if (item.isLive) 0L else startPositionMs.coerceAtLeast(0L)
        candidates = streamCandidates(item)
        candidateIndex = 0
        headerProfiles = buildHeaderProfiles(settings, item)
        headerIndex = 0
        stallRetries = 0
        liveRetries = 0
        recovering = false
        lastStallCheckMs = 0L
        lastStallPositionMs = 0L
        if (item.isLive) locks.acquire() else locks.release()
        playedOnce = false
        playingSinceMs = 0L
        appliedExtraBufferMs = 0
        autoCap = ResolutionCap.AUTO
        adaptiveBuffer.reset()
        adaptiveLoadControl.refresh()
        applyVideoCap(userCap)
        _snapshot.value = EngineSnapshot(buffering = true, activeCap = userCap, ultraSharpActive = effectsActive)
        val startUrl = candidates.firstOrNull() ?: item.url
        if (streamMime(startUrl) == null && !probedFormats.containsKey(startUrl)) {
            probeFormatThenStart(startUrl, currentStartPositionMs)
        } else {
            startCandidate(startUrl, currentStartPositionMs)
        }
    }

    /**
     * Extension-less links (shorteners, redirect endpoints) are sniffed once so the matching
     * HLS/DASH source is used instead of a progressive source that cannot parse a manifest.
     */
    private fun probeFormatThenStart(url: String, positionMs: Long) {
        probeJob = recoveryScope.launch {
            val result = withContext(Dispatchers.IO) {
                StreamFormatProbe.probe(url, currentHeaders)
            }
            if (currentItem == null) return@launch
            if (result.reachable) {
                if (probedFormats.size >= MAX_PROBED_FORMATS) probedFormats.clear()
                probedFormats[url] = result.mimeType
            }
            startCandidate(url, positionMs)
        }
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun setAspect(mode: AspectMode) = Unit

    override fun selectAudio(id: String) {
        val ref = audioLookup[id] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(ref.group, listOf(ref.index)))
            .build()
    }

    override fun selectSubtitle(id: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == SUBTITLE_OFF) {
            player.trackSelectionParameters = builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val ref = subtitleLookup[id] ?: return
        player.trackSelectionParameters = builder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(ref.group, listOf(ref.index)))
            .build()
    }

    override fun addSubtitle(url: String) {
        val item = currentItem ?: return
        currentSubtitles = currentSubtitles + url
        prepare(item, settings, player.currentPosition, currentSubtitles)
    }

    override fun setQualityCap(cap: ResolutionCap) {
        userCap = cap
        autoCap = ResolutionCap.AUTO
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        applyCapTo(builder, cap)
        runCatching { player.trackSelectionParameters = builder.build() }
        _snapshot.update { it.copy(activeCap = cap) }
    }

    override fun selectVideo(id: String) {
        if (id == PlaybackEngine.VIDEO_AUTO) {
            autoCap = ResolutionCap.AUTO
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            applyCapTo(builder, userCap)
            runCatching { player.trackSelectionParameters = builder.build() }
            _snapshot.update { it.copy(activeCap = userCap) }
            return
        }
        val ref = videoLookup[id] ?: return
        val builder = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setOverrideForType(TrackSelectionOverride(ref.group, listOf(ref.index)))
        runCatching { player.trackSelectionParameters = builder.build() }
    }

    override fun setUltraSharp(enabled: Boolean) {
        ultraSharpEnabled = enabled
        effectsFailed = false
        applyVideoEffects()
    }

    private fun applyCapTo(
        builder: TrackSelectionParameters.Builder,
        cap: ResolutionCap,
    ) {
        builder.setMaxVideoSize(cap.maxWidth, cap.maxHeight)
        if (settings.maxVideoBitrateBps > 0) builder.setMaxVideoBitrate(settings.maxVideoBitrateBps)
    }

    private fun applyVideoCap(cap: ResolutionCap) {
        val builder = player.trackSelectionParameters.buildUpon()
        applyCapTo(builder, cap)
        runCatching { player.trackSelectionParameters = builder.build() }
        _snapshot.update { it.copy(activeCap = cap) }
    }

    /** GPU Lanczos upscale to the panel size plus an unsharp pass; disabled if GL setup fails. */
    private fun applyVideoEffects() {
        if (!ultraSharpEnabled || effectsFailed) {
            runCatching { player.setVideoEffects(emptyList()) }
            effectsActive = false
            _snapshot.update { it.copy(ultraSharpActive = false) }
            return
        }
        val (width, height) = panelSize()
        val effects: List<Effect> = listOf(
            LanczosResample.scaleToFit(width, height),
            UltraSharpEffect(),
        )
        val applied = runCatching { player.setVideoEffects(effects) }.isSuccess
        if (!applied) effectsFailed = true
        effectsActive = applied
        _snapshot.update { it.copy(ultraSharpActive = applied) }
    }

    private fun panelSize(): Pair<Int, Int> {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val rawWidth = mode?.physicalWidth?.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val rawHeight = mode?.physicalHeight?.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        val scale = minOf(1f, MAX_UPSCALE_WIDTH.toFloat() / rawWidth, MAX_UPSCALE_HEIGHT.toFloat() / rawHeight)
        return (rawWidth * scale).toInt().coerceAtLeast(2) to (rawHeight * scale).toInt().coerceAtLeast(2)
    }

    /** Weak-network guard: step the adaptive ladder down before reconnecting. */
    private fun autoDownscale(): Boolean {
        if (userCap != ResolutionCap.AUTO || currentItem == null) return false
        val height = player.videoSize.height
        val current = when {
            autoCap != ResolutionCap.AUTO -> autoCap
            height >= 2000 -> ResolutionCap.UHD_4K
            height >= 1000 -> ResolutionCap.FHD_1080
            height >= 700 -> ResolutionCap.HD_720
            height >= 450 -> ResolutionCap.SD_480
            height > 0 -> ResolutionCap.SD_360
            else -> ResolutionCap.FHD_1080
        }
        val next = when (current) {
            ResolutionCap.AUTO, ResolutionCap.UHD_4K -> ResolutionCap.FHD_1080
            ResolutionCap.FHD_1080 -> ResolutionCap.HD_720
            ResolutionCap.HD_720 -> ResolutionCap.SD_480
            ResolutionCap.SD_480 -> ResolutionCap.SD_360
            ResolutionCap.SD_360 -> return false
        }
        autoCap = next
        applyVideoCap(next)
        return true
    }

    override fun refresh() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val size = player.videoSize
        val format = player.videoFormat
        _snapshot.update {
            it.copy(
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                ended = player.playbackState == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                videoWidth = size.width,
                videoHeight = size.height,
                frameRate = format?.frameRate?.takeIf { fps -> fps > 0f } ?: it.frameRate,
                bitrate = format?.bitrate?.takeIf { bitrate -> bitrate > 0 } ?: it.bitrate,
            )
        }
        watchForStall()
        releaseReserveWhenStable()
    }

    override fun release() {
        recoveryJob?.cancel()
        probeJob?.cancel()
        recoveryScope.cancel()
        locks.release()
        proxy?.clearAllowedHosts()
        proxy?.updateSessionHeaders(emptyMap())
        proxy?.abandonConnections()
        runCatching { player.release() }
    }

    /** Drops the extra reserve after a minute of uninterrupted playback. */
    private fun releaseReserveWhenStable() {
        if (!adaptiveBuffer.hasExtra || !player.isPlaying) return
        val now = SystemClock.elapsedRealtime()
        if (playingSinceMs == 0L) {
            playingSinceMs = now
            return
        }
        if (now - playingSinceMs < adaptiveBuffer.stableWindowMs) return
        adaptiveBuffer.onStablePlayback()
        appliedExtraBufferMs = 0
        adaptiveLoadControl.refresh()
        playingSinceMs = now
    }

    private fun escalateBuffer() {
        if (!playedOnce || recovering) return
        val extra = adaptiveBuffer.onFreeze()
        if (extra == appliedExtraBufferMs) return
        appliedExtraBufferMs = extra
        adaptiveLoadControl.refresh()
    }

    private fun shouldProxy(url: String): Boolean =
        proxy != null && settings.streamProxyEnabled &&
            (isHlsUrl(url) || probedFormats[url] == MimeTypes.APPLICATION_M3U8)

    private fun startCandidate(url: String, positionMs: Long) {
        val item = currentItem ?: return
        val activeUrl = candidates.getOrNull(candidateIndex) ?: url
        val profile = headerProfiles.getOrNull(headerIndex)
        val headers = profile?.headers ?: currentHeaders
        val userAgent = headers.headerValue("User-Agent") ?: settings.userAgent

        val proxied = shouldProxy(activeUrl)
        val playbackUrl = if (proxied) {
            proxy?.updateSessionHeaders(headers)
            proxy?.allowHosts(candidates)
            proxy?.urlFor(activeUrl) ?: activeUrl
        } else {
            activeUrl
        }

        val http = PlayerDataSources.httpFactory(userAgent, headers)
        val dataSource: DataSource.Factory = when {
            proxied || item.isLive -> DefaultDataSource.Factory(context, http)
            else -> StreamNetworking.cachedFactory(context, http)
                ?: DefaultDataSource.Factory(context, http)
        }
        val sourceFactory = DefaultMediaSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(StreamRetryPolicy(MAX_SILENT_RETRIES))

        val builder = ExoItem.Builder()
            .setUri(playbackUrl)
            .setMediaId(item.id)
        (streamMime(activeUrl) ?: probedFormats[activeUrl])?.let(builder::setMimeType)
        if (currentSubtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(currentSubtitles.map(::subtitleConfiguration))
        }
        player.setMediaSource(sourceFactory.createMediaSource(builder.build()), positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val failure = error.streamFailure()
        val item = currentItem
        // A GPU effect that cannot run must not take the stream down: retry plainly once.
        if (effectsActive && error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) {
            effectsFailed = true
            effectsActive = false
            runCatching { player.setVideoEffects(emptyList()) }
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    ultraSharpActive = false,
                    error = "Ultra Sharp unavailable on this GPU · continuing without it",
                )
            }
            val target = candidates.getOrNull(candidateIndex) ?: item?.url ?: return
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(target, 0L)
            }
            return
        }
        // A live channel that loses its socket reconnects the same source first;
        // switching to a swapped-protocol backup would only produce a 404.
        if (item != null && item.isLive && error.isTransientIoError() &&
            liveRetries < MAX_SILENT_RETRIES
        ) {
            liveRetries++
            recovering = true
            _snapshot.update {
                it.copy(
                    playing = false,
                    buffering = true,
                    recovering = true,
                    fatal = false,
                    errorCode = failure.code,
                    error = "Connection dropped · reconnecting live (${liveRetries}/${MAX_SILENT_RETRIES})",
                )
            }
            scheduleRecovery(RECONNECT_DELAY_MS * liveRetries) {
                startCandidate(candidates.getOrElse(candidateIndex) { item.url }, 0L)
            }
            return
        }
        if (item != null && candidateIndex + 1 < candidates.size) {
            candidateIndex++
            recovering = true
            _snapshot.update {
                it.copy(
                    playing = false,
                    buffering = true,
                    recovering = true,
                    fatal = false,
                    errorCode = failure.code,
                    error = "Stream dropped · switching to backup ${candidateIndex + 1}/${candidates.size}",
                )
            }
            val resume = if (item.isLive) 0L else player.currentPosition.coerceAtLeast(0L)
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(candidates.getOrElse(candidateIndex) { item.url }, resume)
            }
            return
        }
        if (item != null && headerIndex + 1 < headerProfiles.size && canBypassWithHeaders(failure)) {
            headerIndex++
            candidateIndex = 0
            recovering = true
            val next = headerProfiles[headerIndex]
            _snapshot.update {
                it.copy(
                    playing = false,
                    buffering = true,
                    recovering = true,
                    fatal = false,
                    errorCode = failure.code,
                    error = "Provider rejected the request · retrying as ${next.label}",
                )
            }
            val resume = if (item.isLive) 0L else player.currentPosition.coerceAtLeast(0L)
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(candidates.firstOrNull() ?: item.url, resume)
            }
            return
        }
        _snapshot.update {
            it.copy(
                playing = false,
                buffering = false,
                recovering = false,
                fatal = failure.fatal,
                errorCode = failure.code,
                error = failure.message,
            )
        }
    }

    /** A newer prepare/error supersedes any retry still waiting out its backoff. */
    private fun scheduleRecovery(delayMs: Long, block: suspend () -> Unit) {
        recoveryJob?.cancel()
        recoveryJob = recoveryScope.launch {
            delay(delayMs)
            block()
        }
    }

    /** A different signature can unlock links rejected with 401/403/404 or a 5xx edge error. */
    private fun canBypassWithHeaders(failure: StreamFailure): Boolean {
        val code = failure.code ?: return failure.message.startsWith("Network")
        return code == 400 || code == 401 || code == 403 || code == 404 ||
            code == 406 || code == 429 ||
            code in 500..599
    }

    /** Detects a hung segment request and forces a reconnect instead of spinning forever. */
    private fun watchForStall() {
        val now = SystemClock.elapsedRealtime()
        val buffering = player.playbackState == Player.STATE_BUFFERING && !recovering
        val position = player.currentPosition
        if (!buffering) {
            lastStallCheckMs = 0L
            lastStallPositionMs = position
            return
        }
        if (position != lastStallPositionMs || lastStallCheckMs == 0L) {
            lastStallPositionMs = position
            lastStallCheckMs = now
            return
        }
        if (now - lastStallCheckMs < STALL_TIMEOUT_MS) return

        lastStallCheckMs = now
        val item = currentItem ?: return
        val downscaled = autoDownscale()
        val qualityNote = if (downscaled) " · lowering to ${autoCap.shortLabel}" else ""
        if (stallRetries < MAX_SILENT_RETRIES) {
            stallRetries++
            recovering = true
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    error = "Stream stalled · reconnecting (${stallRetries}/${MAX_SILENT_RETRIES})$qualityNote",
                )
            }
            val resume = if (item.isLive) 0L else position.coerceAtLeast(0L)
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(candidates.getOrElse(candidateIndex) { item.url }, resume)
            }
            return
        }
        if (candidateIndex + 1 < candidates.size) {
            candidateIndex++
            stallRetries = 0
            recovering = true
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    error = "Switching to backup stream ${candidateIndex + 1}/${candidates.size}$qualityNote",
                )
            }
            val resume = if (item.isLive) 0L else position.coerceAtLeast(0L)
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(candidates.getOrElse(candidateIndex) { item.url }, resume)
            }
            return
        }
        if (headerIndex + 1 < headerProfiles.size) {
            headerIndex++
            candidateIndex = 0
            stallRetries = 0
            recovering = true
            val next = headerProfiles[headerIndex]
            _snapshot.update {
                it.copy(
                    buffering = true,
                    recovering = true,
                    error = "Rebuffering · retrying as ${next.label}$qualityNote",
                )
            }
            val resume = if (item.isLive) 0L else position.coerceAtLeast(0L)
            scheduleRecovery(RECONNECT_DELAY_MS) {
                startCandidate(candidates.firstOrNull() ?: item.url, resume)
            }
            return
        }
        recovering = false
        _snapshot.update {
            it.copy(
                playing = false,
                buffering = false,
                recovering = false,
                fatal = true,
                error = "Stream timed out. The provider did not deliver video data.",
            )
        }
    }

    /**
     * Signature ladder used when a provider rejects or throttles the configured headers.
     * The authorization token is preserved so account-bound streams keep working.
     */
    private fun buildHeaderProfiles(settings: AppSettings, item: MediaItem): List<HeaderProfile> {
        val base = settings.requestHeaders + item.headers
        val referer = base.headerValue("Referer")
        val origin = base.headerValue("Origin")
        val auth = base.headerValue("Authorization")

        fun profile(
            label: String,
            ua: String,
            refererValue: String?,
            originValue: String?,
        ): HeaderProfile = HeaderProfile(
            label,
            buildMap {
                put("User-Agent", ua)
                put("Accept", "*/*")
                refererValue?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                originValue?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
                auth?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
            },
        )

        return listOf(
            HeaderProfile("configured signature", base),
            profile("VLC signature", VLC_UA, referer, origin),
            profile("TiviMate signature", AppSettings.DEFAULT_UA, referer, origin),
            profile("browser signature", BROWSER_UA, referer, origin),
            profile("clean signature", DEFAULT_EXO_UA, null, null),
        ).distinctBy { it.headers }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun publish() {
        _snapshot.update {
            it.copy(
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                ended = player.playbackState == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0),
            )
        }
    }

    private fun publishVideo(videoSize: VideoSize) {
        _snapshot.update {
            it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
        }
    }

    private fun publishTracks(tracks: Tracks) {
        audioLookup.clear()
        subtitleLookup.clear()
        videoLookup.clear()
        val audio = mutableListOf<TrackOption>()
        val subtitles = mutableListOf<TrackOption>()
        val renditions = mutableListOf<TrackOption>()

        tracks.groups.forEachIndexed { groupIndex, group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = "vid:$groupIndex:$trackIndex"
                        videoLookup[id] = TrackRef(group.mediaTrackGroup, trackIndex)
                        val label = videoRenditionLabel(format)
                        val selected = group.isTrackSelected(trackIndex)
                        val existing = renditions.indexOfFirst { it.label == label }
                        if (existing < 0) {
                            renditions += TrackOption(
                                id = id,
                                label = label,
                                selected = selected,
                                bitrateHint = format.bitrate,
                            )
                        } else if (selected) {
                            renditions[existing] = renditions[existing].copy(selected = true)
                        }
                    }
                }

                C.TRACK_TYPE_AUDIO -> {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = "aud:$groupIndex:$trackIndex"
                        audioLookup[id] = TrackRef(group.mediaTrackGroup, trackIndex)
                        audio += TrackOption(
                            id = id,
                            label = formatLabel(format, audio.size + 1, "Audio"),
                            language = format.language,
                            selected = group.isTrackSelected(trackIndex),
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = "sub:$groupIndex:$trackIndex"
                        subtitleLookup[id] = TrackRef(group.mediaTrackGroup, trackIndex)
                        subtitles += TrackOption(
                            id = id,
                            label = formatLabel(format, subtitles.size + 1, "Subtitle"),
                            language = format.language,
                            selected = group.isTrackSelected(trackIndex),
                        )
                    }
                }
            }
        }
        _snapshot.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subtitles,
                videoRenditions = renditions.sortedByDescending { rendition -> rendition.bitrateHint },
            )
        }
    }

    private fun videoRenditionLabel(format: Format): String {
        val height = format.height
        val name = when {
            height >= 2000 -> "2160p"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height >= 450 -> "480p"
            height > 0 -> "${height}p"
            else -> "Source"
        }
        return if (format.bitrate > 0) {
            String.format(Locale.US, "%s · %.1f Mbps", name, format.bitrate / 1_000_000f)
        } else {
            name
        }
    }

    private fun formatLabel(format: Format, index: Int, prefix: String): String {
        val name = format.label?.takeIf { it.isNotBlank() }
            ?: format.language?.uppercase()
            ?: "$prefix $index"
        val codecs = format.codecs?.takeIf { it.isNotBlank() && !name.contains(it, true) }
        return if (codecs != null) "$name · $codecs" else name
    }

    private fun subtitleConfiguration(url: String): ExoItem.SubtitleConfiguration {
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "Subtitle" }
        return ExoItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(mimeForSubtitle(url))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLabel(name)
            .build()
    }

    companion object {
        const val SUBTITLE_OFF = "off"

        private const val MAX_SILENT_RETRIES = 3
        private const val RECONNECT_DELAY_MS = 900L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val MAX_PROBED_FORMATS = 64
        private const val MAX_UPSCALE_WIDTH = 3840
        private const val MAX_UPSCALE_HEIGHT = 2160
        private const val VLC_UA = "VLC/3.0.20 LibVLC/3.0.20"
        private const val DEFAULT_EXO_UA = "ExoPlayerLib/2.18.1"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val hardwareOnly = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .filter { it.hardwareAccelerated }
        }

        private val hardwareFirst = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .sortedByDescending { it.hardwareAccelerated }
        }

        private val softwareOnly = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .filter { !it.hardwareAccelerated }
        }

        private fun isHlsUrl(url: String): Boolean =
            url.contains(".m3u8", true) || url.contains("type=m3u", true)

        private fun streamMime(url: String): String? = when {
            isHlsUrl(url) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        private fun mimeForSubtitle(url: String): String = when {
            url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            url.endsWith(".ssa", true) || url.endsWith(".ass", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
