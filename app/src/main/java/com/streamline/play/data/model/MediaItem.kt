package com.streamline.play.data.model

import java.util.Locale

enum class ContentType {
    LIVE,
    MOVIE,
    SERIES,
}

data class MediaItem(
    val id: String,
    val name: String,
    val url: String,
    val type: ContentType,
    val sourceId: String = "",
    val sourceName: String = "",
    val group: String = "General",
    val logo: String? = null,
    val channelNumber: Int? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val catchup: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val fallbackUrls: List<String> = emptyList(),
) {
    val isLive: Boolean get() = type == ContentType.LIVE

    val epgKey: String get() = tvgId?.takeIf { it.isNotBlank() } ?: id

    val canCatchup: Boolean get() = !catchup.isNullOrBlank() && catchupDays > 0

    val numberLabel: String
        get() = channelNumber?.toString()?.padStart(3, '0') ?: ""

    val displayGroup: String
        get() = group.ifBlank { "General" }

    fun seriesLabel(): String {
        val s = season ?: return seriesName.orEmpty()
        val e = episode ?: 0
        return String.format(Locale.US, "%s  S%02dE%02d", seriesName.orEmpty(), s, e).trim()
    }
}

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val endMs: Long,
    val icon: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    fun progressAt(now: Long): Float = when {
        now <= startMs -> 0f
        now >= endMs -> 1f
        else -> ((now - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isLiveAt(now: Long): Boolean = now in startMs until endMs
}

data class PlaylistResult(
    val items: List<MediaItem>,
    val epgUrls: List<String> = emptyList(),
    val sourceErrors: List<String> = emptyList(),
)

fun List<MediaItem>.liveChannels(): List<MediaItem> = filter { it.type == ContentType.LIVE }

fun List<MediaItem>.movies(): List<MediaItem> = filter { it.type == ContentType.MOVIE }

fun List<MediaItem>.seriesEpisodes(): List<MediaItem> = filter { it.type == ContentType.SERIES }
