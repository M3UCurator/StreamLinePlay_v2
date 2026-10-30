package com.streamline.play.ui

import com.streamline.play.data.epg.EpgMatcher
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import java.util.Locale

fun Map<String, List<EpgProgram>>.scheduleFor(item: MediaItem): List<EpgProgram> =
    EpgMatcher.scheduleFor(this, item)

fun Map<String, List<EpgProgram>>.currentProgramFor(
    item: MediaItem,
    now: Long = System.currentTimeMillis(),
): EpgProgram? = scheduleFor(item).firstOrNull { it.isLiveAt(now) }

fun Map<String, List<EpgProgram>>.nextProgramFor(
    item: MediaItem,
    now: Long = System.currentTimeMillis(),
): EpgProgram? = scheduleFor(item).firstOrNull { it.startMs > now }

fun Map<String, List<EpgProgram>>.hasSchedule(item: MediaItem): Boolean =
    scheduleFor(item).isNotEmpty()

fun Int?.channelBadge(): String = this?.toString()?.padStart(3, '0') ?: "—"

fun MediaItem.qualityLabel(): String = when {
    name.contains("4K", true) || name.contains("UHD", true) -> "4K"
    name.contains("FHD", true) || name.contains("1080", true) -> "FHD"
    name.contains("HD", true) || name.contains("720", true) -> "HD"
    else -> "SD"
}

fun String.normalizedKey(): String = trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
