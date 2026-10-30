package com.streamline.play.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.MediaItem
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.components.Artwork
import com.streamline.play.ui.components.EmptyState
import com.streamline.play.ui.components.EpisodeRow
import com.streamline.play.ui.components.IconAction
import com.streamline.play.ui.components.RatingBadge
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.nav.DetailKey
import com.streamline.play.ui.theme.Cinema
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(
    vm: AppViewModel,
    key: DetailKey,
    onPlay: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val item = remember(catalog.items, key.itemId) { catalog.items.firstOrNull { it.id == key.itemId } }
    val playFocus = remember { FocusRequester() }

    if (item == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyState(
                title = if (catalog.loading) "Loading details…" else "Title unavailable",
                message = "This item is no longer part of your catalog. Go back and pick another title.",
                action = { TvButton(text = "Back", onClick = onBack) },
            )
            IconAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "Back",
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(28.dp),
            )
        }
        return
    }

    val episodes = remember(catalog.episodes, item.seriesName) {
        if (item.type == ContentType.SERIES) {
            catalog.episodes.filter { it.seriesName == item.seriesName }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        } else {
            emptyList()
        }
    }
    val playTarget = episodes.firstOrNull() ?: item
    val seasons = remember(episodes) { episodes.groupBy { it.season ?: 1 }.toSortedMap() }

    LaunchedEffect(item.id) {
        delay(160)
        runCatching { playFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item(key = "hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    Artwork(
                        title = item.seriesName ?: item.name,
                        imageUrl = item.logo,
                        modifier = Modifier.fillMaxSize(),
                        corner = 0.dp,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Cinema.Ink.copy(alpha = 0.35f),
                                        Cinema.Ink.copy(alpha = 0.6f),
                                        Cinema.Background,
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 56.dp, vertical = 24.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tag(text = item.displayGroup, accent = true)
                            Spacer(Modifier.width(8.dp))
                            item.year?.let { Tag(text = it.toString()) }
                            Spacer(Modifier.width(8.dp))
                            item.rating?.let { RatingBadge(it) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = item.seriesName ?: item.name,
                            style = MaterialTheme.typography.displayMedium,
                            color = Cinema.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = item.description ?: "No synopsis provided by the playlist.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cinema.TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.72f),
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TvButton(
                                text = if (item.type == ContentType.SERIES) "Play first episode" else "Play movie",
                                onClick = { onPlay(playTarget) },
                                icon = Icons.Rounded.PlayArrow,
                                primary = true,
                                focusRequester = playFocus,
                            )
                            if (item.type == ContentType.SERIES && episodes.isNotEmpty()) {
                                TvButton(
                                    text = "${episodes.size} episodes · ${seasons.size} seasons",
                                    onClick = { onPlay(playTarget) },
                                    primary = false,
                                )
                            }
                        }
                    }
                }
            }

            if (item.type == ContentType.SERIES) {
                seasons.forEach { (season, seasonEpisodes) ->
                    item(key = "season-$season") {
                        Text(
                            text = "Season $season",
                            style = MaterialTheme.typography.titleLarge,
                            color = Cinema.TextPrimary,
                            modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 12.dp),
                        )
                    }
                    items(seasonEpisodes, key = { it.id }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            onClick = { onPlay(episode) },
                            modifier = Modifier.padding(horizontal = 56.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                item(key = "about") {
                    Column(modifier = Modifier.padding(horizontal = 56.dp, vertical = 8.dp)) {
                        Text(
                            text = "About this movie",
                            style = MaterialTheme.typography.titleLarge,
                            color = Cinema.TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = item.description ?: "No synopsis provided by the playlist.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cinema.TextSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Tag(text = "Source · ${item.sourceName.ifBlank { "Playlist" }}")
                            item.year?.let { Tag(text = "Year · $it") }
                            Tag(text = "Format · ${item.url.substringAfterLast('.', "stream").uppercase()}")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "Back",
                onClick = onBack,
            )
        }
    }
}
