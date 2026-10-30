package com.streamline.play.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamline.play.data.model.ChannelSortMode
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.channelGroupOrder
import com.streamline.play.data.model.sortedChannels
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.FocusRestore
import com.streamline.play.ui.components.ChannelCard
import com.streamline.play.ui.components.EmptyState
import com.streamline.play.ui.components.PillChip
import com.streamline.play.ui.components.SectionHeader
import com.streamline.play.ui.components.SortChip
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.rememberNow
import com.streamline.play.ui.components.requestFocusReliably
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    vm: AppViewModel,
    onPlay: (MediaItem) -> Unit,
    restore: FocusRestore? = null,
    onItemFocused: (String) -> Unit = {},
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val epg by vm.epgPrograms.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow(30_000L)
    var group by remember { mutableStateOf<String?>(null) }
    val sort = settings.channelSort

    val ordered = remember(catalog.liveChannels, sort) { catalog.liveChannels.sortedChannels(sort) }
    val groups = remember(ordered, sort) { ordered.channelGroupOrder(sort) }
    val channels = remember(ordered, group) {
        if (group == null) ordered else ordered.filter { it.displayGroup == group }
    }
    val gridState = rememberLazyGridState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val emptyFocus = rememberInitialFocus(enabled = channels.isEmpty())
    var handledRestoreToken by remember { mutableIntStateOf(-1) }

    LaunchedEffect(restore?.token) {
        val token = restore?.token ?: return@LaunchedEffect
        if (token == handledRestoreToken) return@LaunchedEffect
        handledRestoreToken = token
        val wanted = restore?.itemId ?: return@LaunchedEffect
        var target = channels
        if (target.none { it.id == wanted } && ordered.any { it.id == wanted }) {
            group = null
            target = ordered
        }
        val index = target.indexOfFirst { it.id == wanted }
        if (index >= 0) {
            gridState.scrollToItem(index)
            delay(140)
            requesters[target[index].id]?.requestFocusReliably()
        }
    }

    LaunchedEffect(channels.isEmpty(), restore?.itemId) {
        if (channels.isEmpty()) return@LaunchedEffect
        if (restore?.itemId != null) return@LaunchedEffect
        delay(180)
        requesters[channels.first().id]?.requestFocusReliably()
    }

    LaunchedEffect(groups) {
        if (group != null && group !in groups) group = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 24.dp)) {
            SectionHeader(
                title = "Live TV",
                subtitle = "${catalog.liveChannels.size} channels · ${catalog.groups.size} groups · ${sort.label}",
                trailing = {
                    Tag(text = "EPG ${if (epg.isNotEmpty()) "ready" else "pending"}", accent = true)
                    SortChip(
                        mode = sort,
                        onCycle = {
                            vm.updateSettings { it.copy(channelSort = it.channelSort.next()) }
                        },
                    )
                    TvButton(
                        text = "Reload",
                        onClick = { vm.reloadPlaylists() },
                        primary = false,
                    )
                },
            )
            Spacer(Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
            ) {
                item {
                    PillChip(
                        text = "All channels",
                        selected = group == null,
                        onClick = { group = null },
                    )
                }
                items(groups, key = { it }) { candidate ->
                    PillChip(
                        text = candidate,
                        selected = group == candidate,
                        onClick = { group = candidate },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (channels.isEmpty()) {
            EmptyState(
                title = if (catalog.loading) "Loading channels…" else "No channels in this source",
                message = if (catalog.loading) {
                    "Parsing your playlist sources"
                } else {
                    "Add or update a source in Settings → Sources, then reload your playlists."
                },
                action = {
                    TvButton(
                        text = "Reload playlists",
                        onClick = { vm.reloadPlaylists() },
                        primary = true,
                        focusRequester = emptyFocus,
                    )
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(channels, key = { _, item -> item.id }) { _, channel ->
                    ChannelCard(
                        item = channel,
                        epg = epg,
                        now = now,
                        onClick = { onPlay(channel) },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = requesters.getOrPut(channel.id) { FocusRequester() },
                        onFocus = { onItemFocused(channel.id) },
                    )
                }
            }
        }
    }
}
