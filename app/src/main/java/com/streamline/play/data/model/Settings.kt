package com.streamline.play.data.model

import java.util.Locale

enum class PlaybackEngineType(val label: String, val description: String) {
    MEDIA3("Media3 ExoPlayer", "Adaptive HLS & DASH playback with hardware decoding"),
    VLC("VLC Engine", "Embedded libVLC profile tuned for high packet-loss streams"),
    MPV("MPV Direct Engine", "Hands the stream to an installed MPV player"),
    EXTERNAL("External Player", "Uses your chosen external player application"),
}

enum class ExternalPlayerApp(val label: String, val packageName: String?, val description: String) {
    CHOOSER(
        "All installed players",
        null,
        "Native chooser listing every app on this device that can open video",
    ),
    VLC("VLC for Android", "org.videolan.vlc", "Open directly in VLC for Android"),
    MX("MX Player", "com.mxtech.videoplayer.ad", "Open directly in MX Player"),
    JUST("Just Player", "com.brouken.player", "Open directly in Just Player"),
    MPV("MPV", "is.xyz.mpv", "Open directly in MPV"),
}

enum class DecoderMode(val label: String, val detail: String) {
    AUTO("Auto", "Let the engine pick the best decoder"),
    HW("Hardware (HW)", "Standard MediaCodec hardware decoding"),
    HW_PLUS("Hardware+ (HW+)", "Prefer hardware with aggressive fallback"),
    SW("Software (SW)", "Software decoding only"),
}

enum class ResolutionCap(val label: String, val shortLabel: String, val maxWidth: Int, val maxHeight: Int) {
    AUTO("Auto (best available)", "Auto", Int.MAX_VALUE, Int.MAX_VALUE),
    UHD_4K("4K Ultra HD (2160p)", "4K", 3840, 2160),
    FHD_1080("1080p Full HD", "1080p", 1920, 1080),
    HD_720("720p HD", "720p", 1280, 720),
    SD_480("SD (480p)", "480p", 854, 480),
    SD_360("360p", "360p", 640, 360);

    val isCapped: Boolean get() = this != AUTO
}

object BitrateLimit {
    const val AUTO = 0
    const val MIN_KBPS = 800
    const val MAX_KBPS = 8_000
    const val STEP_KBPS = 100

    fun label(kbps: Int): String =
        if (kbps <= AUTO) "Auto (no limit)" else String.format(Locale.US, "%,d Kbps", kbps)
}

data class QualityPreset(
    val name: String,
    val target: String,
    val recommendedKbps: Int,
    val safeKbps: Int,
    val resolution: ResolutionCap,
    val summary: String,
)

object QualityPresets {
    val all: List<QualityPreset> = listOf(
        QualityPreset(
            name = "SD",
            target = "360p / 480p",
            recommendedKbps = 800,
            safeKbps = 1_500,
            resolution = ResolutionCap.SD_480,
            summary = "800–1,500 Kbps · safe margin 1,500 Kbps",
        ),
        QualityPreset(
            name = "HD",
            target = "720p",
            recommendedKbps = 1_500,
            safeKbps = 3_000,
            resolution = ResolutionCap.HD_720,
            summary = "1,500–3,000 Kbps · safe margin 3,000 Kbps",
        ),
        QualityPreset(
            name = "Full HD",
            target = "1080p",
            recommendedKbps = 3_000,
            safeKbps = 6_000,
            resolution = ResolutionCap.FHD_1080,
            summary = "3,000–6,000 Kbps · safe margin 6,000 Kbps",
        ),
        QualityPreset(
            name = "4K",
            target = "2160p",
            recommendedKbps = 6_000,
            safeKbps = 8_000,
            resolution = ResolutionCap.UHD_4K,
            summary = "6,000–8,000 Kbps · safe margin 8,000 Kbps",
        ),
    )
}

enum class BufferPreset(
    val label: String,
    val targetBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
) {
    SMALL("Fast start · 30 s lookahead", 20 * 1024 * 1024, 2_000, 30_000, 2_000, 1_000),
    MEDIUM("Medium · 30 s lookahead", 35 * 1024 * 1024, 2_000, 30_000, 2_000, 1_500),
    HIGH("High · 60 s lookahead", 60 * 1024 * 1024, 2_500, 60_000, 2_500, 2_000),
}

enum class NetworkProfile(val label: String, val summary: String) {
    LOW_RAM(
        "Low RAM (Android 4+)",
        "2 s fast start with a 16 MB / 30 s forward buffer ceiling",
    ),
    ULTRA_LOW_LATENCY(
        "Ultra low latency live",
        "Near real-time live playback tuned for sports",
    ),
    AGGRESSIVE(
        "Aggressive buffer",
        "30–60 s lookahead that survives network dropouts",
    ),
}

enum class UserAgentPreset(val label: String, val value: String) {
    TIVIMATE(
        "TiviMate 5.0.4 (Android TV)",
        "TiviMate/5.0.4 (Linux; Android 9; Android TV) ExoPlayerLib/2.18.1",
    ),
    OTT_NAVIGATOR(
        "OTT Navigator 1.6.8.5 (Android TV)",
        "OTT Navigator/1.6.8.5 (Linux; Android 9; Android TV) ExoPlayerLib/2.18.1",
    ),
    IPTV_SMARTERS(
        "IPTV Smarters Pro 3.1.5",
        "IPTVSmartersPro/3.1.5 (Linux; Android 9) ExoPlayerLib/2.18.1",
    ),
    WINK("Wink 1.31.1", "Wink/1.31.1 (Linux; Android 9; Android TV)"),
    LIBVLC("LibVLC 3.5.4", "LibVLC/3.5.4"),
    CUSTOM("User defined", ""),
}

enum class EpgRefreshMode(val label: String) {
    AUTO_24H("Automatic, every 24 hours"),
    MANUAL("Manual refresh only"),
}

enum class AspectMode(val label: String, val detail: String) {
    ORIGINAL("Original", "Native source size, centred on screen"),
    FIT("Fit", "Scale to the screen and keep the source shape"),
    RATIO_16_9("16:9", "Force the picture into a 16:9 frame"),
    RATIO_18_9("18:9", "Force the picture into a wide 18:9 frame"),
    STRETCH("Stretch", "Fill the screen and ignore the source shape"),
    CROP("Crop", "Fill the screen and crop the edges");

    val isForcedRatio: Boolean get() = this == RATIO_16_9 || this == RATIO_18_9
}
