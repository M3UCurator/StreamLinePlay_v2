package com.streamline.play.ui.components

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.ui.currentProgramFor
import com.streamline.play.ui.qualityLabel
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.asClock

@Composable
fun ChannelCard(
    item: MediaItem,
    epg: Map<String, List<EpgProgram>>,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
) {
    val current = epg.currentProgramFor(item, now)
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.height(164.dp),
        shape = RoundedCornerShape(14.dp),
        restingColor = Cinema.Surface,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Cinema.Border,
        focusedBorder = Cinema.SteelBlue,
        focusedScale = 1.025f,
        contentPadding = PaddingValues(16.dp),
        focusRequester = focusRequester,
        onFocusChanged = { if (it) onFocus?.invoke() },
    ) { focused ->
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LogoBadge(title = item.name, imageUrl = item.logo, size = 46.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.channelBadgeLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Cinema.SteelBright,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.displayGroup,
                            style = MaterialTheme.typography.labelMedium,
                            color = Cinema.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cinema.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (focused) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Cinema.SteelBright,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = current?.title ?: "No programme information",
                style = MaterialTheme.typography.bodyMedium,
                color = if (current == null) Cinema.TextMuted else Cinema.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val progress = current?.progressAt(now) ?: 0f
            ProgressLine(progress = progress, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.startMs?.asClock() ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = current?.endMs?.asClock() ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                )
            }
        }
    }
}

private fun MediaItem.channelBadgeLabel(): String =
    channelNumber?.toString()?.padStart(3, '0') ?: qualityLabel()

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 176.dp,
    posterHeight: Dp = 248.dp,
    badge: String? = null,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.then(
            if (width != Dp.Unspecified) Modifier.width(width) else Modifier,
        ),
        shape = RoundedCornerShape(14.dp),
        restingColor = Color.Transparent,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Color.Transparent,
        focusedBorder = Cinema.SteelBlue,
        focusedScale = 1.05f,
        contentPadding = PaddingValues(8.dp),
        focusRequester = focusRequester,
        onFocusChanged = { if (it) onFocus?.invoke() },
    ) { focused ->
        Column {
            Box {
                Artwork(
                    title = title,
                    imageUrl = imageUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(posterHeight),
                    corner = 10.dp,
                )
                if (badge != null) {
                    Tag(
                        text = badge,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        accent = true,
                    )
                }
                if (focused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                                ),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Cinema.SteelBright else Cinema.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
fun EpisodeRow(
    episode: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        restingColor = Cinema.Surface,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Cinema.Border,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                Artwork(
                    title = episode.name,
                    imageUrl = episode.logo,
                    modifier = Modifier.fillMaxSize(),
                    corner = 8.dp,
                    showInitials = false,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = if (focused) 1f else 0.8f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (focused) Cinema.SteelBright else Cinema.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "S${episode.season ?: 1} · E${episode.episode ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                )
            }
            if (episode.description != null) {
                Text(
                    text = episode.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(280.dp),
                )
            }
        }
    }
}

@Composable
fun RatingBadge(rating: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Cinema.SurfaceHigh)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = Cinema.Gold,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.1f", rating),
            style = MaterialTheme.typography.labelMedium,
            color = Cinema.TextSecondary,
        )
    }
}

@Composable
fun HeroPanel(
    item: MediaItem,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    playFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onPlayFocus: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        Artwork(
            title = item.name,
            imageUrl = item.logo,
            modifier = Modifier.fillMaxSize(),
            corner = 20.dp,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Cinema.Ink.copy(alpha = 0.97f),
                            Cinema.Ink.copy(alpha = 0.78f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Cinema.Ink.copy(alpha = 0.85f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(36.dp)
                .width(640.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(text = "FEATURED", accent = true)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.displayGroup.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.SteelBright,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.displayMedium,
                color = Cinema.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item.year?.let { Tag(text = it.toString()) }
                item.rating?.let { RatingBadge(it) }
                Tag(text = item.type.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.description ?: "Press play to start streaming instantly.",
                style = MaterialTheme.typography.bodyLarge,
                color = Cinema.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    text = "Play now",
                    onClick = onPlay,
                    icon = Icons.Rounded.PlayArrow,
                    primary = true,
                    focusRequester = playFocusRequester,
                    onFocus = onPlayFocus,
                )
                TvButton(
                    text = "More info",
                    onClick = onDetails,
                    icon = Icons.Rounded.Info,
                    primary = false,
                )
            }
        }
    }
}
