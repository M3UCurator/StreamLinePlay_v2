package com.streamline.play.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamline.play.data.m3u.M3uParser
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.groupedChannels
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.FocusRestore
import com.streamline.play.ui.components.EmptyState
import com.streamline.play.ui.components.FocusableSurface
import com.streamline.play.ui.components.LogoBadge
import com.streamline.play.ui.components.ProgressLine
import com.streamline.play.ui.components.SectionHeader
import com.streamline.play.ui.components.SortChip
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.rememberNow
import com.streamline.play.ui.components.requestFocusReliably
import com.streamline.play.ui.scheduleFor
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.asClock
import com.streamline.play.util.asDayDate
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private const val SLOT_MS = 30L * 60L * 1000L
private val CHANNEL_COLUMN = 224.dp

@Composable
fun GuideScreen(
    vm: AppViewModel,
    onPlay: (MediaItem, EpgProgram?) -> Unit,
    restore: FocusRestore? = null,
    onItemFocused: (String) -> Unit = {},
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val epg by vm.epgPrograms.collectAsStateWithLifecycle()
    val refreshing by vm.epgRefreshing.collectAsStateWithLifecycle()
    val updated by vm.epgLastRefreshMs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)

    val windowStart = remember(now / SLOT_MS) { (now / SLOT_MS) * SLOT_MS - SLOT_MS }
    val totalMs = 4L * 60L * 60L * 1000L
    val windowEnd = windowStart + totalMs
    val nowFraction = ((now - windowStart).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val sort = settings.channelSort
    val channels = remember(catalog.lastUpdatedMs, catalog.items.size, sort) {
        catalog.liveChannels.groupedChannels(sort)
    }
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val emptyFocus = rememberInitialFocus(enabled = channels.isEmpty())

    LaunchedEffect(restore?.token, sort, channels.size) {
        requesters.keys.retainAll(channels.mapTo(HashSet()) { it.id })
        val index = restore?.itemId?.let { id -> channels.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            listState.scrollToItem(index)
            delay(140)
            requesters[channels[index].id]?.requestFocusReliably()
        } else if (channels.isNotEmpty()) {
            delay(160)
            requesters[channels.first().id]?.requestFocusReliably()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 22.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    title = "TV Guide",
                    subtitle = "${now.asDayDate()} · ${sort.label}",
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortChip(
                    mode = sort,
                    onCycle = {
                        vm.updateSettings { it.copy(channelSort = it.channelSort.next()) }
                    },
                )
                Tag(text = if (updated > 0) "Updated ${updated.asClock()}" else "Never updated")
                TvButton(
                    text = if (refreshing) "Refreshing…" else "Refresh EPG",
                    onClick = { vm.refreshEpg() },
                    primary = true,
                    enabled = !refreshing,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (channels.isEmpty()) {
            EmptyState(
                title = "No channels to schedule",
                message = "Load a playlist with live channels to see the EPG timeline.",
                action = {
                    TvButton(
                        text = "Reload playlists",
                        onClick = { vm.reloadPlaylists() },
                        focusRequester = emptyFocus,
                    )
                },
            )
            return@Column
        }

        TimeRuler(windowStart = windowStart)

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { _, channel ->
                    GuideRow(
                        channel = channel,
                        programs = epg.scheduleFor(channel),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        onPlay = onPlay,
                        channelFocus = requesters.getOrPut(channel.id) { FocusRequester() },
                        onFocus = { onItemFocused(channel.id) },
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val available = maxWidth - CHANNEL_COLUMN
                val lineX = CHANNEL_COLUMN + available * nowFraction
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = lineX.toPx()
                    drawLine(
                        brush = Brush.verticalGradient(
                            listOf(Cinema.SteelBright.copy(alpha = 0.9f), Cinema.SteelBlue.copy(alpha = 0.25f)),
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.5f,
                    )
                    drawCircle(
                        color = Cinema.SteelBright,
                        radius = 5f,
                        center = Offset(x, 2f),
                    )
                }
                Box(modifier = Modifier.offset(x = lineX - 24.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                            .background(Cinema.SteelBlue)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "NOW",
                            style = MaterialTheme.typography.labelMedium,
                            color = Cinema.Ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp)
            .height(34.dp),
    ) {
        Spacer(Modifier.width(CHANNEL_COLUMN))
        Row(modifier = Modifier.weight(1f)) {
            repeat(8) { index ->
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        text = (windowStart + index * SLOT_MS).asClock(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index == 0) Cinema.SteelBright else Cinema.TextMuted,
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 2.dp, top = 26.dp)
                            .width(1.dp)
                            .height(8.dp)
                            .background(Cinema.Border),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: MediaItem,
    programs: List<EpgProgram>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    onPlay: (MediaItem, EpgProgram?) -> Unit,
    channelFocus: FocusRequester?,
    onFocus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .padding(horizontal = 36.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusableSurface(
            onClick = { onPlay(channel, null) },
            modifier = Modifier
                .width(CHANNEL_COLUMN - 12.dp)
                .fillMaxHeight(),
            restingColor = Cinema.Surface,
            focusedColor = Cinema.SurfaceHigh,
            restingBorder = Cinema.Border,
            focusedBorder = Cinema.SteelBlue,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            focusRequester = channelFocus,
            onFocusChanged = { if (it) onFocus() },
        ) { focused ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LogoBadge(title = channel.name, imageUrl = channel.logo, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (focused) Cinema.SteelBright else Cinema.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = channel.channelNumber?.toString()?.padStart(3, '0') ?: channel.displayGroup,
                        style = MaterialTheme.typography.labelMedium,
                        color = Cinema.TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (programs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Cinema.Surface.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "No Information",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cinema.TextMuted,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val visible = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }
                var cursor = windowStart
                visible.forEach { program ->
                    val start = max(program.startMs, windowStart)
                    val end = min(program.endMs, windowEnd)
                    if (start > cursor) {
                        Spacer(Modifier.weight((start - cursor) / 60_000f))
                    }
                    val minutes = ((end - start) / 60_000f).coerceAtLeast(1f)
                    ProgramChip(
                        channel = channel,
                        program = program,
                        now = now,
                        live = now in program.startMs until program.endMs,
                        endMs = end,
                        onPlay = onPlay,
                        modifier = Modifier.weight(minutes),
                    )
                    cursor = max(cursor, end)
                }
                if (cursor < windowEnd) {
                    Spacer(Modifier.weight((windowEnd - cursor) / 60_000f))
                }
            }
        }
    }
}

@Composable
private fun ProgramChip(
    channel: MediaItem,
    program: EpgProgram,
    now: Long,
    live: Boolean,
    endMs: Long,
    onPlay: (MediaItem, EpgProgram?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = {
            val archive = if (program.endMs <= now && channel.canCatchup) {
                M3uParser.archiveUrl(channel, program)
            } else {
                null
            }
            if (archive != null) {
                onPlay(channel.copy(url = archive), program)
            } else {
                onPlay(channel, null)
            }
        },
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp),
        shape = RoundedCornerShape(8.dp),
        restingColor = if (live) Cinema.SteelDeep.copy(alpha = 0.28f) else Cinema.Surface,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = if (live) Cinema.SteelDeep.copy(alpha = 0.7f) else Cinema.Border,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { focused ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Cinema.SteelBright else Cinema.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${program.startMs.asClock()} – ${endMs.asClock()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                )
                if (channel.canCatchup && program.endMs <= now) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Cinema.Gold),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Catch-up",
                        style = MaterialTheme.typography.labelMedium,
                        color = Cinema.Gold,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (live) {
                ProgressLine(
                    progress = program.progressAt(now),
                    modifier = Modifier.fillMaxWidth(),
                    height = 3.dp,
                )
            }
        }
    }
}
