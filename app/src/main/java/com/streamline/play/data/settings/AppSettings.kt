package com.streamline.play.data.settings

import com.streamline.play.data.model.BufferPreset
import com.streamline.play.data.model.ChannelSortMode
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.EpgRefreshMode
import com.streamline.play.data.model.ExternalPlayerApp
import com.streamline.play.data.model.NetworkProfile
import com.streamline.play.data.model.PlaybackEngineType
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.model.UserAgentPreset

/** Resolved LoadControl values after combining the buffer slider with the network profile. */
data class BufferTuning(
    val targetBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
)

data class AppSettings(
    val engine: PlaybackEngineType = PlaybackEngineType.MEDIA3,
    val externalPlayer: ExternalPlayerApp = ExternalPlayerApp.CHOOSER,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val audioPassthrough: Boolean = false,
    val resolutionCap: ResolutionCap = ResolutionCap.AUTO,
    val bitrateLimitKbps: Int = 0,
    val bufferPreset: BufferPreset = BufferPreset.SMALL,
    val networkProfile: NetworkProfile = NetworkProfile.LOW_RAM,
    val userAgentPreset: UserAgentPreset = UserAgentPreset.TIVIMATE,
    val customUserAgent: String = "",
    val referer: String = "",
    val origin: String = "",
    val authorization: String = "",
    val epgRefreshMode: EpgRefreshMode = EpgRefreshMode.AUTO_24H,
    /** App-wide XMLTV guides applied to every loaded playlist. */
    val globalEpgUrls: List<String> = emptyList(),
    val clearCacheOnExit: Boolean = false,
    val epgLastRefreshMs: Long = 0L,
    val preferredAspect: com.streamline.play.data.model.AspectMode =
        com.streamline.play.data.model.AspectMode.FIT,
    val channelSort: ChannelSortMode = ChannelSortMode.PLAYLIST,
    val parentalLockEnabled: Boolean = false,
    val parentalPinHash: String = "",
    val parentalAutoLockAdult: Boolean = true,
    val parentalLockedGroups: Set<String> = emptySet(),
    val confirmExit: Boolean = true,
) {
    val userAgent: String
        get() = when (userAgentPreset) {
            UserAgentPreset.CUSTOM -> customUserAgent.ifBlank { DEFAULT_UA }
            else -> userAgentPreset.value
        }

    val requestHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", userAgent)
            referer.trim().takeIf { it.isNotEmpty() }?.let { put("Referer", it) }
            origin.trim().takeIf { it.isNotEmpty() }?.let { put("Origin", it) }
            authorization.trim().takeIf { it.isNotEmpty() }?.let { put("Authorization", it) }
        }

    val maxVideoBitrateBps: Int
        get() = bitrateLimitKbps.coerceAtLeast(0) * 1_000

    val hasParentalPin: Boolean
        get() = parentalPinHash.isNotBlank()

    /** Profile bounds the buffer slider so old boxes stay inside their memory ceiling. */
    val bufferTuning: BufferTuning
        get() = when (networkProfile) {
            NetworkProfile.LOW_RAM -> BufferTuning(
                targetBytes = minOf(bufferPreset.targetBytes, SIXTEEN_MB),
                minBufferMs = minOf(bufferPreset.minBufferMs, 3_000),
                maxBufferMs = minOf(bufferPreset.maxBufferMs, 30_000),
                playbackBufferMs = minOf(bufferPreset.playbackBufferMs, 3_000),
                rebufferMs = minOf(bufferPreset.rebufferMs, 1_500),
            )

            NetworkProfile.ULTRA_LOW_LATENCY -> BufferTuning(
                targetBytes = minOf(bufferPreset.targetBytes, EIGHT_MB),
                minBufferMs = 1_000,
                maxBufferMs = 10_000,
                playbackBufferMs = 1_000,
                rebufferMs = 500,
            )

            NetworkProfile.AGGRESSIVE -> BufferTuning(
                targetBytes = maxOf(bufferPreset.targetBytes, 35 * 1024 * 1024)
                    .coerceAtMost(SIXTY_MB),
                minBufferMs = maxOf(bufferPreset.minBufferMs, 2_000),
                maxBufferMs = maxOf(bufferPreset.maxBufferMs, 45_000).coerceAtMost(60_000),
                playbackBufferMs = maxOf(bufferPreset.playbackBufferMs, 2_000),
                rebufferMs = maxOf(bufferPreset.rebufferMs, 1_500),
            )
        }

    companion object {
        const val DEFAULT_UA =
            "TiviMate/5.0.4 (Linux; Android 9; Android TV) ExoPlayerLib/2.18.1"

        private const val EIGHT_MB = 8 * 1024 * 1024
        private const val SIXTEEN_MB = 16 * 1024 * 1024
        private const val SIXTY_MB = 60 * 1024 * 1024
    }
}
