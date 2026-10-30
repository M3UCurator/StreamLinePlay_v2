package com.streamline.play.ui

import com.streamline.play.data.model.MediaItem

data class SeriesGroup(
    val name: String,
    val episodes: List<MediaItem>,
) {
    val poster: MediaItem get() = episodes.first()
    val seasonCount: Int get() = episodes.mapNotNull { it.season }.distinct().size.coerceAtLeast(1)
    val episodeCount: Int get() = episodes.size
}

fun List<MediaItem>.toSeriesGroups(): List<SeriesGroup> =
    filter { it.seriesName != null }
        .groupBy { it.seriesName.orEmpty() }
        .map { (name, episodes) ->
            SeriesGroup(
                name = name,
                episodes = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
            )
        }
        .sortedBy { it.name.lowercase() }
