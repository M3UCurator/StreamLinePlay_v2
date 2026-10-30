package com.streamline.play.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.MediaItem
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.FocusRestore
import com.streamline.play.ui.components.ChannelCard
import com.streamline.play.ui.components.HeroPanel
import com.streamline.play.ui.components.PillChip
import com.streamline.play.ui.components.PosterCard
import com.streamline.play.ui.components.SectionHeader
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.rememberNow
import com.streamline.play.ui.components.requestFocusReliably
import com.streamline.play.ui.nav.GuideKey
import com.streamline.play.ui.nav.LiveKey
import com.streamline.play.ui.nav.MoviesKey
import com.streamline.play.ui.nav.SeriesKey
import com.streamline.play.ui.nav.SettingsKey
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.ui.toSeriesGroups
import com.streamline.play.util.asClock
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPlay: (MediaItem) -> Unit,
    onDetails: (MediaItem) -> Unit,
    onOpenTab: (NavKey) -> Unit,
    restore: FocusRestore? = null,
    onItemFocused: (String) -> Unit = {},
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val epg by vm.epgPrograms.collectAsStateWithLifecycle()
    val epgUpdated by vm.epgLastRefreshMs.collectAsStateWithLifecycle()
    val now = rememberNow(30_000L)

    val hero = remember(catalog.items) {
        catalog.movies.maxByOrNull { it.rating ?: 0f }
            ?: catalog.movies.firstOrNull()
            ?: catalog.items.firstOrNull()
    }
    val seriesGroups = remember(catalog.episodes) { catalog.episodes.toSeriesGroups() }
    val liveGroups = remember(catalog.liveChannels) {
        catalog.liveChannels.map { it.displayGroup }.distinct()
    }
    var liveGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(liveGroups) {
        if (liveGroup != null && liveGroup !in liveGroups) liveGroup = null
    }
    val liveRow = remember(catalog.liveChannels, liveGroup) {
        val base = if (liveGroup == null) {
            catalog.liveChannels
        } else {
            catalog.liveChannels.filter { it.displayGroup == liveGroup }
        }
        base.take(14)
    }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val heroFocus = remember { FocusRequester() }
    val emptyFocus = rememberInitialFocus(enabled = hero == null)
    LaunchedEffect(hero?.id) {
        if (hero != null && restore == null) {
            delay(160)
            heroFocus.requestFocusReliably()
        }
    }
    LaunchedEffect(restore?.token, catalog.items) {
        val target = restore?.itemId ?: return@LaunchedEffect
        delay(220)
        val requester = requesters[target] ?: heroFocus
        requester.requestFocusReliably()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "hero") {
            if (hero != null) {
                HeroPanel(
                    item = hero,
                    onPlay = { onPlay(hero) },
                    onDetails = { onDetails(hero) },
                    playFocusRequester = heroFocus,
                    onPlayFocus = { onItemFocused(hero.id) },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (catalog.loading) "Loading your cinema…" else "Your catalog is empty",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Cinema.TextPrimary,
                    )
                    Text(
                        text = if (catalog.loading) {
                            "Reading playlists, channels and EPG data"
                        } else {
                            "Add an M3U playlist, Xtream account or portal in Settings → Sources."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Cinema.TextMuted,
                    )
                    Spacer(Modifier.height(22.dp))
                    TvButton(
                        text = "Open settings",
                        onClick = { onOpenTab(SettingsKey) },
                        focusRequester = emptyFocus,
                    )
                }
            }
        }

        if (catalog.errors.isNotEmpty()) {
            item(key = "errors") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    catalog.errors.forEach { message ->
                        Text(
                            text = "ⓘ $message",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.Live,
                        )
                    }
                }
            }
        }

        item(key = "summary") {
            Spacer(Modifier.height(20.dp))
            RowSummary(catalog.liveChannels.size, catalog.movies.size, catalog.episodes.size, now)
        }

        if (catalog.liveChannels.isNotEmpty()) {
            item(key = "live") {
                HomeRow(
                    title = "Live now",
                    subtitle = liveGroup?.let { "$it · pick a channel to play" }
                        ?: "Jump straight into a channel",
                    trailing = {
                        TvButton(
                            text = "All channels",
                            onClick = { onOpenTab(LiveKey) },
                            primary = false,
                        )
                    },
                ) {
                    Column {
                        if (liveGroups.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                item {
                                    PillChip(
                                        text = "All live",
                                        selected = liveGroup == null,
                                        onClick = { liveGroup = null },
                                    )
                                }
                                items(liveGroups, key = { "group:$it" }) { candidate ->
                                    PillChip(
                                        text = candidate,
                                        selected = liveGroup == candidate,
                                        onClick = { liveGroup = candidate },
                                    )
                                }
                            }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(liveRow, key = { it.id }) { channel ->
                                ChannelCard(
                                    item = channel,
                                    epg = epg,
                                    now = now,
                                    onClick = { onPlay(channel) },
                                    modifier = Modifier.width(340.dp),
                                    focusRequester = requesters.getOrPut(channel.id) { FocusRequester() },
                                    onFocus = { onItemFocused(channel.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (catalog.movies.isNotEmpty()) {
            item(key = "movies") {
                HomeRow(
                    title = "Movies",
                    subtitle = "Blockbusters and hidden gems",
                    trailing = {
                        TvButton(
                            text = "Browse",
                            onClick = { onOpenTab(MoviesKey) },
                            primary = false,
                        )
                    },
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(catalog.movies, key = { it.id }) { movie ->
                            PosterCard(
                                title = movie.name,
                                imageUrl = movie.logo,
                                onClick = { onDetails(movie) },
                                badge = movie.rating?.let { String.format(java.util.Locale.US, "★ %.1f", it) },
                                subtitle = movie.year?.toString() ?: movie.displayGroup,
                                focusRequester = requesters.getOrPut(movie.id) { FocusRequester() },
                                onFocus = { onItemFocused(movie.id) },
                            )
                        }
                    }
                }
            }
        }

        if (catalog.episodes.isNotEmpty()) {
            item(key = "series") {
                HomeRow(
                    title = "Series",
                    subtitle = "Binge your next obsession",
                    trailing = {
                        TvButton(
                            text = "Browse",
                            onClick = { onOpenTab(SeriesKey) },
                            primary = false,
                        )
                    },
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(seriesGroups, key = { it.name }) { group ->
                            val requester = requesters.getOrPut(group.poster.id) { FocusRequester() }
                            group.episodes.forEach { requesters[it.id] = requester }
                            PosterCard(
                                title = group.name,
                                imageUrl = group.poster.logo,
                                onClick = { onDetails(group.poster) },
                                badge = "${group.seasonCount}S",
                                subtitle = "${group.episodeCount} episodes",
                                focusRequester = requester,
                                onFocus = { onItemFocused(group.poster.id) },
                            )
                        }
                    }
                }
            }
        }

        item(key = "footer") {
            Spacer(Modifier.height(28.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    TvButton(
                        text = "Open TV Guide",
                        onClick = { onOpenTab(GuideKey) },
                        primary = false,
                    )
                }
                item {
                    Tag(
                        text = "EPG updated ${if (epgUpdated > 0) epgUpdated.asClock() else "—"}",
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RowSummary(channels: Int, movies: Int, episodes: Int, now: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tag(text = "$channels channels", accent = true)
        Tag(text = "$movies movies")
        Tag(text = "$episodes episodes")
        Spacer(Modifier.weight(1f))
        Text(
            text = now.asClock(),
            style = MaterialTheme.typography.titleMedium,
            color = Cinema.TextMuted,
        )
    }
}

@Composable
private fun HomeRow(
    title: String,
    subtitle: String?,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            subtitle = subtitle,
            trailing = trailing,
        )
        content()
    }
}
