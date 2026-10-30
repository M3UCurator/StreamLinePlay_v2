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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamline.play.data.model.MediaItem
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.FocusRestore
import com.streamline.play.ui.components.EmptyState
import com.streamline.play.ui.components.PillChip
import com.streamline.play.ui.components.PosterCard
import com.streamline.play.ui.components.SectionHeader
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.requestFocusReliably
import com.streamline.play.ui.toSeriesGroups
import kotlinx.coroutines.delay

@Composable
fun MoviesScreen(
    vm: AppViewModel,
    onDetails: (MediaItem) -> Unit,
    restore: FocusRestore? = null,
    onItemFocused: (String) -> Unit = {},
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var group by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val requesters = remember { HashMap<String, FocusRequester>() }

    val movies = catalog.movies.filter { group == null || it.displayGroup == group }
    val groups = remember(catalog.movies) { catalog.movies.map { it.displayGroup }.distinct().sorted() }
    val emptyFocus = rememberInitialFocus(enabled = movies.isEmpty())
    var handledRestoreToken by remember { mutableIntStateOf(-1) }

    LaunchedEffect(restore?.token) {
        val token = restore?.token ?: return@LaunchedEffect
        if (token == handledRestoreToken) return@LaunchedEffect
        handledRestoreToken = token
        val wanted = restore?.itemId ?: return@LaunchedEffect
        var target = movies
        if (target.none { it.id == wanted } && catalog.movies.any { it.id == wanted }) {
            group = null
            target = catalog.movies
        }
        val index = target.indexOfFirst { it.id == wanted }
        if (index >= 0) {
            gridState.scrollToItem(index)
            delay(140)
            requesters[target[index].id]?.requestFocusReliably()
        }
    }

    LaunchedEffect(movies.isEmpty(), restore?.itemId) {
        if (movies.isEmpty() || restore?.itemId != null) return@LaunchedEffect
        delay(160)
        requesters[movies.first().id]?.requestFocusReliably()
    }

    LaunchedEffect(groups) {
        if (group != null && group !in groups) group = null
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        Column(modifier = Modifier.padding(horizontal = 36.dp)) {
            SectionHeader(
                title = "Movies",
                subtitle = "${catalog.movies.size} titles in your library",
                trailing = {
                    TvButton(
                        text = "Reload",
                        onClick = { vm.reloadPlaylists() },
                        primary = false,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            if (groups.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                ) {
                    item {
                        PillChip(
                            text = "All movies",
                            selected = group == null,
                            onClick = { group = null },
                        )
                    }
                    items(groups.size) { index ->
                        val candidate = groups[index]
                        PillChip(
                            text = candidate,
                            selected = group == candidate,
                            onClick = { group = candidate },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (movies.isEmpty()) {
            EmptyState(
                title = if (catalog.loading) "Loading movies…" else "No movies found",
                message = "Movies from your M3U or Xtream sources appear here. Try reloading or adding another source.",
                action = {
                    TvButton(
                        text = "Reload playlists",
                        onClick = { vm.reloadPlaylists() },
                        focusRequester = emptyFocus,
                    )
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 186.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(movies, key = { _, movie -> movie.id }) { _, movie ->
                    PosterCard(
                        title = movie.name,
                        imageUrl = movie.logo,
                        onClick = { onDetails(movie) },
                        modifier = Modifier.fillMaxWidth(),
                        width = Dp.Unspecified,
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

@Composable
fun SeriesScreen(
    vm: AppViewModel,
    onDetails: (MediaItem) -> Unit,
    restore: FocusRestore? = null,
    onItemFocused: (String) -> Unit = {},
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val groups = remember(catalog.episodes) { catalog.episodes.toSeriesGroups() }
    val gridState = rememberLazyGridState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val emptyFocus = rememberInitialFocus(enabled = groups.isEmpty())

    LaunchedEffect(restore?.token, groups.size) {
        val wanted = restore?.itemId
        val index = wanted?.let { id ->
            groups.indexOfFirst { group ->
                group.poster.id == id || group.episodes.any { it.id == id }
            }
        } ?: -1
        if (index >= 0) {
            gridState.scrollToItem(index)
            delay(140)
            requesters[groups[index].poster.id]?.requestFocusReliably()
        } else if (groups.isNotEmpty()) {
            delay(160)
            requesters[groups.first().poster.id]?.requestFocusReliably()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        Column(modifier = Modifier.padding(horizontal = 36.dp)) {
            SectionHeader(
                title = "Series",
                subtitle = "${groups.size} shows · ${catalog.episodes.size} episodes",
                trailing = {
                    TvButton(
                        text = "Reload",
                        onClick = { vm.reloadPlaylists() },
                        primary = false,
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        if (groups.isEmpty()) {
            EmptyState(
                title = if (catalog.loading) "Loading series…" else "No series found",
                message = "Series are detected from episode tags and naming patterns in your playlist.",
                action = {
                    TvButton(
                        text = "Reload playlists",
                        onClick = { vm.reloadPlaylists() },
                        focusRequester = emptyFocus,
                    )
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 186.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(groups, key = { _, group -> group.name }) { _, group ->
                    val requester = requesters.getOrPut(group.poster.id) { FocusRequester() }
                    group.episodes.forEach { requesters[it.id] = requester }
                    PosterCard(
                        title = group.name,
                        imageUrl = group.poster.logo,
                        onClick = { onDetails(group.poster) },
                        modifier = Modifier.fillMaxWidth(),
                        width = Dp.Unspecified,
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
