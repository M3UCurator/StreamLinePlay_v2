package com.streamline.play.data.model

import java.util.Locale

enum class ChannelSortMode(val label: String, val shortLabel: String) {
    PLAYLIST("Playlist order", "Playlist"),
    NAME_ASC("Name A–Z", "A–Z"),
    NAME_DESC("Name Z–A", "Z–A"),
    CHANNEL_NUMBER("Channel number", "Number");

    fun next(): ChannelSortMode = entries[(ordinal + 1) % entries.size]
}

fun List<MediaItem>.sortedChannels(mode: ChannelSortMode): List<MediaItem> = when (mode) {
    ChannelSortMode.PLAYLIST -> this
    ChannelSortMode.NAME_ASC -> sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )
    ChannelSortMode.NAME_DESC -> sortedWith(
        compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name },
    )
    ChannelSortMode.CHANNEL_NUMBER -> sortedWith(
        compareBy({ it.channelNumber == null }, { it.channelNumber ?: Int.MAX_VALUE }),
    )
}

fun List<MediaItem>.channelGroupOrder(mode: ChannelSortMode): List<String> {
    val groups = map { it.displayGroup }.distinct()
    return when (mode) {
        ChannelSortMode.PLAYLIST -> groups
        ChannelSortMode.NAME_ASC -> groups.sortedWith(String.CASE_INSENSITIVE_ORDER)
        ChannelSortMode.NAME_DESC -> groups.sortedWith(String.CASE_INSENSITIVE_ORDER.reversed())
        ChannelSortMode.CHANNEL_NUMBER -> groups
            .withIndex()
            .sortedWith(
                compareBy(
                    { entry ->
                        this.filter { it.displayGroup == entry.value }
                            .mapNotNull { it.channelNumber }
                            .minOrNull() ?: Int.MAX_VALUE
                    },
                    { it.index },
                ),
            )
            .map { it.value }
    }
}

fun String.nameKey(): String = trim().lowercase(Locale.US)
