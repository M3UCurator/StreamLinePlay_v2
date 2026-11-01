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
        ChannelSortMode.CHANNEL_NUMBER -> {
            val firstNumber = HashMap<String, Int>()
            for (item in this) {
                val number = item.channelNumber ?: continue
                firstNumber.merge(item.displayGroup, number, ::minOf)
            }
            groups
                .withIndex()
                .sortedWith(
                    compareBy(
                        { entry -> firstNumber[entry.value] ?: Int.MAX_VALUE },
                        { it.index },
                    ),
                )
                .map { it.value }
        }
    }
}

/** Channels ordered group by group, sorted inside each group: one pass over the list. */
fun List<MediaItem>.groupedChannels(mode: ChannelSortMode): List<MediaItem> {
    if (isEmpty()) return this
    val order = channelGroupOrder(mode)
    val buckets = sortedChannels(mode).groupBy { it.displayGroup }
    val out = ArrayList<MediaItem>(size)
    order.forEach { group -> buckets[group]?.let(out::addAll) }
    if (buckets.size > order.size) {
        val known = order.toHashSet()
        buckets.forEach { (group, items) -> if (group !in known) out.addAll(items) }
    }
    return out
}

fun String.nameKey(): String = trim().lowercase(Locale.US)
