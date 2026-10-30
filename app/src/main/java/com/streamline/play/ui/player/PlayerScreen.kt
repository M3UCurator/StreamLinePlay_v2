package com.streamline.play.ui.player

import android.content.Intent
import android.view.ViewGroup
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamline.play.data.model.AspectMode
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.channelGroupOrder
import com.streamline.play.data.model.sortedChannels
import com.streamline.play.player.EngineKind
import com.streamline.play.player.TrackOption
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.components.DialogHeader
import com.streamline.play.ui.components.DialogOption
import com.streamline.play.ui.components.FocusableSurface
import com.streamline.play.ui.components.IconAction
import com.streamline.play.ui.components.LogoBadge
import com.streamline.play.ui.components.PillChip
import com.streamline.play.ui.components.ProgressLine
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.TvButton
import com.streamline.play.ui.components.TvDialog
import com.streamline.play.ui.components.dpadDirection
import com.streamline.play.ui.components.rememberInitialFocus
import com.streamline.play.ui.components.rememberTvDpad
import com.streamline.play.ui.components.rememberNow
import com.streamline.play.ui.currentProgramFor
import com.streamline.play.ui.nav.PlayerKey
import com.streamline.play.ui.nextProgramFor
import com.streamline.play.ui.qualityLabel
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.asClock
import com.streamline.play.util.asMediaClock
import com.streamline.play.util.formatBitrate
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class PlayerOverlay {
    ASPECT,
    AUDIO,
    SUBTITLES,
    INFO,
}

@Composable
fun PlayerScreen(
    vm: AppViewModel,
    key: PlayerKey,
    onClose: () -> Unit,
    onFatalExit: (String) -> Unit = { onClose() },
) {
    val context = LocalContext.current
    val playerVm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)
    val ui by playerVm.state.collectAsStateWithLifecycle()
    val item by playerVm.current.collectAsStateWithLifecycle()
    val queue by playerVm.queue.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val epg by vm.epgPrograms.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow(15_000L)

    LaunchedEffect(key, catalog.items) {
        if (catalog.items.isNotEmpty()) playerVm.start(key, catalog.items)
    }
    DisposableEffect(Unit) {
        onDispose { playerVm.release() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) playerVm.onExternalReturn()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var osdVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableStateOf(0) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<PlayerOverlay?>(null) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val overflowFocus = remember { FocusRequester() }

    LaunchedEffect(osdVisible, interaction, sidebarOpen, overflowOpen, overlay, ui.external, ui.status) {
        if (!osdVisible || sidebarOpen || overflowOpen || overlay != null || ui.external) {
            return@LaunchedEffect
        }
        if (ui.status == PlayerStatus.ERROR || ui.status == PlayerStatus.ENDED) return@LaunchedEffect
        delay(4_500)
        osdVisible = false
    }

    LaunchedEffect(osdVisible, overlay, sidebarOpen, overflowOpen, item?.id, ui.external) {
        when {
            ui.external -> Unit
            !osdVisible -> {
                delay(50)
                runCatching { rootFocus.requestFocus() }
            }
            overlay == null && !sidebarOpen && !overflowOpen -> {
                delay(90)
                runCatching { playFocus.requestFocus() }
            }
        }
    }

    LaunchedEffect(overflowOpen) {
        if (overflowOpen) {
            delay(60)
            runCatching { overflowFocus.requestFocus() }
        }
    }

    LaunchedEffect(ui.fatal, item?.id) {
        if (ui.fatal && !ui.external) {
            delay(1_600)
            item?.id?.let(onFatalExit) ?: onClose()
        }
    }

    BackHandler(enabled = osdVisible || sidebarOpen || overflowOpen || overlay != null) {
        when {
            overlay != null -> overlay = null
            overflowOpen -> overflowOpen = false
            sidebarOpen -> sidebarOpen = false
            else -> osdVisible = false
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            playerVm.loadSubtitle(uri.toString())
        }
    }

    val dpad = rememberTvDpad()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.key.dpadDirection() != null) {
                    if (!osdVisible) {
                        if (event.type == KeyEventType.KeyDown) {
                            osdVisible = true
                            interaction++
                        }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent dpad.handle(event)
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> false
                    Key.MediaPlayPause -> {
                        playerVm.togglePlay()
                        osdVisible = true
                        interaction++
                        true
                    }
                    Key.MediaPlay -> {
                        playerVm.play()
                        osdVisible = true
                        interaction++
                        true
                    }
                    Key.MediaPause -> {
                        playerVm.pause()
                        osdVisible = true
                        interaction++
                        true
                    }
                    Key.MediaNext -> {
                        playerVm.playNext()
                        osdVisible = true
                        interaction++
                        true
                    }
                    Key.MediaPrevious -> {
                        playerVm.playPrevious()
                        osdVisible = true
                        interaction++
                        true
                    }
                    Key.Menu -> {
                        osdVisible = true
                        overflowOpen = true
                        interaction++
                        true
                    }
                    else -> {
                        if (!osdVisible) {
                            osdVisible = true
                            interaction++
                            true
                        } else {
                            interaction++
                            false
                        }
                    }
                }
            }
            .pointerInput(ui.external, sidebarOpen, overflowOpen) {
                detectTapGestures {
                    if (ui.external) return@detectTapGestures
                    when {
                        sidebarOpen -> sidebarOpen = false
                        overflowOpen -> {
                            overflowOpen = false
                            osdVisible = true
                        }
                        else -> osdVisible = !osdVisible
                    }
                    interaction++
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!osdVisible) {
                        Modifier.focusRequester(rootFocus).focusable()
                    } else {
                        Modifier
                    },
                )
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && !osdVisible) {
                        osdVisible = true
                        interaction++
                        true
                    } else {
                        false
                    }
                },
        )

        PlayerSurface(playerVm = playerVm, state = ui, modifier = Modifier.fillMaxSize())

        if (osdVisible && !ui.external && !sidebarOpen && !overflowOpen && overlay == null) {
            OsdScrims()
            PlayerOsd(
                item = item,
                state = ui,
                currentProgram = item?.let { if (it.isLive) epg.currentProgramFor(it, now) else null },
                nextProgram = item?.let { if (it.isLive) epg.nextProgramFor(it, now) else null },
                now = now,
                hasSiblings = queue.size > 1,
                overflowOpen = overflowOpen,
                playFocus = playFocus,
                backFocus = backFocus,
                onBack = onClose,
                onPlayPause = { playerVm.togglePlay() },
                onPrevious = { playerVm.playPrevious() },
                onNext = { playerVm.playNext() },
                onChannels = { sidebarOpen = true },
                onAspect = { overlay = PlayerOverlay.ASPECT },
                onAudio = { overlay = PlayerOverlay.AUDIO },
                onExternal = { playerVm.openExternal() },
                onOverflow = { overflowOpen = !overflowOpen },
            )
        }

        if (overflowOpen && !ui.external) {
            OverflowMenu(
                state = ui,
                firstFocus = overflowFocus,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = 150.dp),
                onInfo = {
                    overflowOpen = false
                    overlay = PlayerOverlay.INFO
                },
                onBoost = {
                    overflowOpen = false
                    playerVm.toggleAudioBoost()
                },
                onRefresh = {
                    overflowOpen = false
                    playerVm.reload()
                },
                onDecoder = {
                    overflowOpen = false
                    playerVm.cycleDecoder()
                },
                onSubtitles = {
                    overflowOpen = false
                    overlay = PlayerOverlay.SUBTITLES
                },
            )
        }

        if (sidebarOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )
            ChannelSidebar(
                items = queue,
                currentId = item?.id,
                epg = epg,
                now = now,
                sortMode = settings.channelSort,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(480.dp),
                onSelect = { selected ->
                    sidebarOpen = false
                    playerVm.switchTo(selected)
                },
                onClose = { sidebarOpen = false },
            )
        }

        when (overlay) {
            PlayerOverlay.ASPECT -> AspectDialog(
                current = ui.aspect,
                onPick = { mode ->
                    playerVm.setAspect(mode)
                    overlay = null
                },
                onClose = { overlay = null },
            )
            PlayerOverlay.AUDIO -> AudioDialog(
                tracks = ui.audioTracks,
                onPick = { id ->
                    playerVm.selectAudio(id)
                    overlay = null
                },
                onClose = { overlay = null },
            )
            PlayerOverlay.SUBTITLES -> SubtitleDialog(
                tracks = ui.subtitleTracks,
                onOff = {
                    playerVm.selectSubtitle("off")
                    overlay = null
                },
                onPick = { id ->
                    playerVm.selectSubtitle(id)
                    overlay = null
                },
                onLoadFile = {
                    overlay = null
                    runCatching { subtitlePicker.launch(arrayOf("*/*")) }
                },
                onClose = { overlay = null },
            )
            PlayerOverlay.INFO -> InfoDialog(
                item = item,
                state = ui,
                program = item?.let { if (it.isLive) epg.currentProgramFor(it, now) else null },
                onClose = { overlay = null },
            )
            null -> Unit
        }

        if ((ui.status == PlayerStatus.PREPARING || ui.status == PlayerStatus.BUFFERING) &&
            !ui.external && !ui.fatal
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Cinema.SteelBlue)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = when {
                        ui.recovering -> ui.error ?: "Reconnecting…"
                        ui.status == PlayerStatus.BUFFERING -> "Buffering…"
                        else -> "Preparing stream…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (ui.recovering) Cinema.SteelBright else Cinema.TextSecondary,
                )
            }
        }

        if (ui.fatal && !ui.external) {
            StatusPanel(
                title = "Stream unavailable",
                message = ui.error
                    ?: "This stream could not be recovered. Returning to the channel list.",
                primaryLabel = "Back to channel list",
                onPrimary = { item?.id?.let(onFatalExit) ?: onClose() },
                secondaryLabel = "Retry",
                onSecondary = { playerVm.retry() },
                onBack = { item?.id?.let(onFatalExit) ?: onClose() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (ui.status == PlayerStatus.ERROR && !ui.external && !ui.fatal) {
            StatusPanel(
                title = "Playback error",
                message = ui.error
                    ?: "This stream could not be played. Try refreshing or switching engines.",
                primaryLabel = "Retry",
                onPrimary = { playerVm.retry() },
                secondaryLabel = "Switch engine",
                onSecondary = { playerVm.switchEngine() },
                onBack = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (ui.status == PlayerStatus.ENDED && !ui.external) {
            StatusPanel(
                title = "Playback finished",
                message = item?.name ?: "The stream ended.",
                primaryLabel = "Replay",
                onPrimary = { playerVm.play() },
                secondaryLabel = "Next",
                onSecondary = { playerVm.playNext() },
                onBack = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (ui.external) {
            ExternalPanel(
                state = ui,
                item = item,
                modifier = Modifier.align(Alignment.Center),
                onResumeHere = { playerVm.resumeHere() },
                onHandoffAgain = { playerVm.handoffAgain() },
                onBack = onClose,
            )
        }

        ui.message?.let { message ->
            LaunchedEffect(message) {
                delay(3_500)
                playerVm.clearMessage()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 136.dp),
            ) {
                MessageBanner(message)
            }
        }
    }
}

@Composable
private fun PlayerSurface(
    playerVm: PlayerViewModel,
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val ratio = when (state.aspect) {
        AspectMode.RATIO_16_9 -> 16f / 9f
        AspectMode.RATIO_4_3 -> 4f / 3f
        else -> null
    }
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (state.engineKind) {
            EngineKind.MEDIA3 -> composeKey(state.engineSerial) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShutterBackgroundColor(AndroidColor.BLACK)
                            setKeepContentOnPlayerReset(true)
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }
                    },
                    update = { view ->
                        view.player = playerVm.media3Player
                        view.resizeMode = when (state.aspect) {
                            AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = if (ratio != null) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(ratio)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
            EngineKind.VLC -> composeKey(state.engineSerial) {
                AndroidView(
                    factory = { ctx ->
                        playerVm.createVlcLayout(ctx).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.OsdScrims() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .align(Alignment.TopCenter)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent),
                ),
            ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                ),
            ),
    )
}

@Composable
private fun PlayerOsd(
    item: MediaItem?,
    state: PlayerUiState,
    currentProgram: EpgProgram?,
    nextProgram: EpgProgram?,
    now: Long,
    hasSiblings: Boolean,
    overflowOpen: Boolean,
    playFocus: FocusRequester,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onAspect: () -> Unit,
    onAudio: () -> Unit,
    onExternal: () -> Unit,
    onOverflow: () -> Unit,
) {
    val actionsFocus = remember { FocusRequester() }
    val aspectFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val externalFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    if (item == null) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                icon = Icons.Rounded.ArrowBack,
                label = "Back",
                onClick = onBack,
                modifier = Modifier.focusProperties { down = playFocus },
                focusRequester = backFocus,
            )
            Spacer(Modifier.width(16.dp))
            FormatBadges(item = item, state = state)
            Spacer(Modifier.weight(1f))
            Text(
                text = now.asClock(),
                style = MaterialTheme.typography.titleMedium,
                color = Cinema.TextSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            LogoBadge(title = item.name, imageUrl = item.logo, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.widthIn(max = 840.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.numberLabel.ifBlank { item.qualityLabel() },
                        style = MaterialTheme.typography.labelLarge,
                        color = Cinema.SteelBright,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.displayGroup.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        color = Cinema.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.catchup) {
                        Spacer(Modifier.width(10.dp))
                        Tag(text = "CATCH-UP", color = Cinema.Gold.copy(alpha = 0.2f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Cinema.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (item.isLive) {
                    if (currentProgram != null) {
                        Text(
                            text = "NOW · ${currentProgram.title}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cinema.SteelBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (currentProgram.description.isNotBlank()) {
                            Text(
                                text = currentProgram.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cinema.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(
                            progress = currentProgram.progressAt(now),
                            modifier = Modifier.fillMaxWidth(),
                            height = 3.dp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentProgram.startMs.asClock(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = currentProgram.endMs.asClock(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextMuted,
                            )
                        }
                        if (nextProgram != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "NEXT ${nextProgram.startMs.asClock()} · ${nextProgram.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = "No programme information for this channel",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextMuted,
                        )
                    }
                } else {
                    val secondary = listOfNotNull(
                        item.seriesLabel().takeIf { it.isNotBlank() },
                        item.year?.toString(),
                        item.rating?.let { String.format(Locale.US, "★ %.1f", it) },
                    ).joinToString(" · ")
                    if (secondary.isNotBlank()) {
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextSecondary,
                        )
                    }
                    item.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.durationMs > 0) {
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(
                            progress = (state.positionMs.toFloat() / state.durationMs.toFloat())
                                .coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            height = 3.dp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = state.positionMs.asMediaClock(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = state.durationMs.asMediaClock(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextMuted,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(
                icon = Icons.Rounded.SkipPrevious,
                label = "Previous channel",
                onClick = onPrevious,
                modifier = Modifier.focusProperties { down = actionsFocus },
                primary = false,
                enabled = hasSiblings,
            )
            Spacer(Modifier.width(22.dp))
            RoundControl(
                icon = if (state.status == PlayerStatus.READY) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                label = if (state.status == PlayerStatus.READY) "Pause" else "Play",
                onClick = onPlayPause,
                modifier = Modifier.focusProperties { down = actionsFocus },
                primary = true,
                size = 64.dp,
                focusRequester = playFocus,
            )
            Spacer(Modifier.width(22.dp))
            RoundControl(
                icon = Icons.Rounded.SkipNext,
                label = "Next channel",
                onClick = onNext,
                modifier = Modifier.focusProperties { down = actionsFocus },
                primary = false,
                enabled = hasSiblings,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = item.sourceName.ifBlank { item.displayGroup },
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(state.engineLabel)
                        append(" · ")
                        append(state.decoderLabel)
                        if (state.videoWidth > 0 && state.videoHeight > 0) {
                            append(" · ")
                            append("${state.videoWidth}×${state.videoHeight}")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OsdIcon(
                    icon = Icons.Rounded.LiveTv,
                    label = "Channels",
                    onClick = onChannels,
                    modifier = Modifier.focusProperties { up = playFocus; right = aspectFocus },
                    focusRequester = actionsFocus,
                )
                OsdIcon(
                    icon = Icons.Rounded.AspectRatio,
                    label = "Aspect",
                    onClick = onAspect,
                    modifier = Modifier.focusProperties {
                        up = playFocus
                        left = actionsFocus
                        right = audioFocus
                    },
                    focusRequester = aspectFocus,
                )
                OsdIcon(
                    icon = Icons.Rounded.GraphicEq,
                    label = "Audio",
                    onClick = onAudio,
                    modifier = Modifier.focusProperties {
                        up = playFocus
                        left = aspectFocus
                        right = externalFocus
                    },
                    focusRequester = audioFocus,
                )
                OsdIcon(
                    icon = Icons.Rounded.OpenInNew,
                    label = "External",
                    onClick = onExternal,
                    modifier = Modifier.focusProperties {
                        up = playFocus
                        left = audioFocus
                        right = moreFocus
                    },
                    focusRequester = externalFocus,
                )
                OsdIcon(
                    icon = Icons.Rounded.MoreVert,
                    label = "More",
                    onClick = onOverflow,
                    active = overflowOpen,
                    modifier = Modifier.focusProperties { up = playFocus; left = externalFocus },
                    focusRequester = moreFocus,
                )
            }
        }
    }
}

@Composable
private fun FormatBadges(item: MediaItem, state: PlayerUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isLive) Tag(text = "LIVE", color = Cinema.Live.copy(alpha = 0.22f))
        Tag(text = streamQuality(state, item), accent = true)
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            Tag(text = "${state.videoWidth}×${state.videoHeight}")
        }
        if (state.frameRate > 0f) {
            Tag(text = "${state.frameRate.roundToInt()} fps")
        }
        if (state.bitrate > 0) {
            Tag(text = formatBitrate(state.bitrate))
        }
        Tag(text = state.engineLabel)
    }
}

private fun streamQuality(state: PlayerUiState, item: MediaItem): String = when {
    state.videoHeight >= 2000 -> "4K"
    state.videoHeight >= 1000 -> "FHD"
    state.videoHeight >= 700 -> "HD"
    state.videoHeight > 0 -> "SD"
    else -> item.qualityLabel()
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        restingColor = if (primary) {
            Cinema.SteelDeep.copy(alpha = 0.55f)
        } else {
            Cinema.SurfaceHigh.copy(alpha = 0.85f)
        },
        focusedColor = Cinema.SteelBlue,
        restingBorder = if (primary) Cinema.SteelBright.copy(alpha = 0.4f) else Cinema.Border,
        focusedBorder = Cinema.SteelBright,
        enabled = enabled,
        focusRequester = focusRequester,
    ) { focused ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                focused -> Cinema.Ink
                !enabled -> Cinema.TextMuted.copy(alpha = 0.5f)
                primary -> Cinema.SteelBright
                else -> Cinema.TextPrimary
            },
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun OsdIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier
            .widthIn(min = 84.dp)
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp),
        restingColor = Color.Transparent,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Color.Transparent,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        focusRequester = focusRequester,
    ) { focused ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    focused -> Cinema.SteelBright
                    active -> Cinema.SteelBright
                    else -> Cinema.TextSecondary
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active || focused) Cinema.SteelBright else Cinema.TextMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    state: PlayerUiState,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onInfo: () -> Unit,
    onBoost: () -> Unit,
    onRefresh: () -> Unit,
    onDecoder: () -> Unit,
    onSubtitles: () -> Unit,
) {
    Column(
        modifier = modifier
            .width(340.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Cinema.SurfaceElevated.copy(alpha = 0.98f))
            .border(1.dp, Cinema.Border, RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        MenuRow(Icons.Rounded.Info, "Info", onInfo, focusRequester = firstFocus)
        MenuRow(
            Icons.Rounded.VolumeUp,
            "Audio boost",
            onBoost,
            trailing = "+${state.audioBoostSteps}",
        )
        MenuRow(Icons.Rounded.Refresh, "Refresh stream", onRefresh)
        MenuRow(Icons.Rounded.Memory, "Decoder", onDecoder, trailing = state.decoderLabel)
        MenuRow(Icons.Rounded.Subtitles, "Subtitles / CC", onSubtitles)
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        restingColor = Color.Transparent,
        focusedColor = Cinema.SteelBlue,
        restingBorder = Color.Transparent,
        focusedBorder = Cinema.SteelBright,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        focusRequester = focusRequester,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (focused) Cinema.Ink else Cinema.SteelBright,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Cinema.Ink else Cinema.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Cinema.Ink.copy(alpha = 0.7f) else Cinema.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChannelSidebar(
    items: List<MediaItem>,
    currentId: String?,
    epg: Map<String, List<EpgProgram>>,
    now: Long,
    sortMode: com.streamline.play.data.model.ChannelSortMode,
    modifier: Modifier = Modifier,
    onSelect: (MediaItem) -> Unit,
    onClose: () -> Unit,
) {
    var group by remember { mutableStateOf("All") }
    val ordered = remember(items, sortMode) { items.sortedChannels(sortMode) }
    val groups = remember(ordered, sortMode) {
        listOf("All") + ordered.channelGroupOrder(sortMode)
    }
    val filtered = remember(ordered, group) {
        if (group == "All") ordered else ordered.filter { it.displayGroup == group }
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val currentIndex = filtered.indexOfFirst { it.id == currentId }
    val focusIndex = if (currentIndex >= 0) currentIndex else 0

    LaunchedEffect(Unit) {
        if (currentIndex >= 0) runCatching { listState.scrollToItem(currentIndex) }
        delay(140)
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(group) {
        val index = filtered.indexOfFirst { it.id == currentId }
        if (index >= 0) runCatching { listState.scrollToItem(index) }
        delay(140)
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(Cinema.SurfaceElevated.copy(alpha = 0.99f))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.titleLarge,
                    color = Cinema.TextPrimary,
                )
                Text(
                    text = "${items.size} in playlist · ${filtered.size} shown",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                )
            }
            IconAction(
                icon = Icons.Rounded.Close,
                label = "Close channels",
                onClick = onClose,
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { name ->
                PillChip(
                    text = name,
                    selected = group == name,
                    onClick = { group = name },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(filtered, key = { _, channel -> channel.id }) { index, channel ->
                val current = channel.id == currentId
                val program = epg.currentProgramFor(channel, now)
                FocusableSurface(
                    onClick = { onSelect(channel) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    restingColor = if (current) {
                        Cinema.SteelDeep.copy(alpha = 0.35f)
                    } else {
                        Color.Transparent
                    },
                    focusedColor = Cinema.SurfaceHigh,
                    restingBorder = if (current) Cinema.SteelDeep else Color.Transparent,
                    focusedBorder = Cinema.SteelBlue,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    focusRequester = if (index == focusIndex) focusRequester else null,
                ) { focused ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LogoBadge(title = channel.name, imageUrl = channel.logo, size = 40.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = channel.numberLabel.ifBlank { channel.qualityLabel() },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Cinema.SteelBright,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = channel.displayGroup,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Cinema.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (focused) Cinema.SteelBright else Cinema.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = program?.title ?: "No Information",
                                style = MaterialTheme.typography.labelMedium,
                                color = Cinema.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Cinema.SteelBlue),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectDialog(
    current: AspectMode,
    onPick: (AspectMode) -> Unit,
    onClose: () -> Unit,
) {
    val focus = rememberInitialFocus()
    TvDialog(onDismiss = onClose) {
        DialogHeader(
            title = "Aspect ratio",
            subtitle = "Fit, zoom or stretch the picture to fill your TV",
        )
        AspectMode.entries.forEach { mode ->
            DialogOption(
                label = mode.label,
                selected = mode == current,
                onClick = { onPick(mode) },
                focusRequester = if (mode == current) focus else null,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TvButton(text = "Close", onClick = onClose, primary = false)
        }
    }
}

@Composable
private fun AudioDialog(
    tracks: List<TrackOption>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = rememberInitialFocus()
    val selectedIndex = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
    TvDialog(onDismiss = onClose) {
        DialogHeader(
            title = "Audio track",
            subtitle = "Choose the stream language or format",
        )
        if (tracks.isEmpty()) {
            Text(
                text = "No alternate audio tracks in this stream",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                tracks.forEachIndexed { index, track ->
                    DialogOption(
                        label = track.label,
                        selected = track.selected,
                        onClick = { onPick(track.id) },
                        supporting = track.language,
                        focusRequester = if (index == selectedIndex) focus else null,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TvButton(text = "Close", onClick = onClose, primary = false)
        }
    }
}

@Composable
private fun SubtitleDialog(
    tracks: List<TrackOption>,
    onOff: () -> Unit,
    onPick: (String) -> Unit,
    onLoadFile: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = rememberInitialFocus()
    val anySelected = tracks.any { it.selected }
    val selectedIndex = tracks.indexOfFirst { it.selected }
    TvDialog(onDismiss = onClose) {
        DialogHeader(
            title = "Subtitles / CC",
            subtitle = "Pick a track or load an external subtitle file",
        )
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DialogOption(
                label = "Off",
                selected = !anySelected,
                onClick = onOff,
                focusRequester = if (!anySelected) focus else null,
            )
            tracks.forEachIndexed { index, track ->
                DialogOption(
                    label = track.label,
                    selected = track.selected,
                    onClick = { onPick(track.id) },
                    supporting = track.language,
                    focusRequester = if (index == selectedIndex) focus else null,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            TvButton(text = "Load file…", onClick = onLoadFile, primary = false)
            TvButton(text = "Close", onClick = onClose, primary = false)
        }
    }
}

@Composable
private fun InfoDialog(
    item: MediaItem?,
    state: PlayerUiState,
    program: EpgProgram?,
    onClose: () -> Unit,
) {
    val focus = rememberInitialFocus()
    TvDialog(onDismiss = onClose, width = 720.dp) {
        DialogHeader(title = "Stream info", subtitle = item?.name)
        if (item != null) {
            InfoRow("Type", item.type.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() })
            InfoRow("Group", item.displayGroup)
            InfoRow("Source", item.sourceName.ifBlank { "—" })
            InfoRow("Stream", item.url.substringBefore('?'))
            InfoRow("Engine", "${state.engineLabel} · ${state.decoderLabel}")
            InfoRow(
                "Video",
                if (state.videoWidth > 0) {
                    "${state.videoWidth}×${state.videoHeight} · ${state.frameRate.roundToInt()} fps"
                } else {
                    "Waiting for video"
                },
            )
            InfoRow("Bitrate", if (state.bitrate > 0) formatBitrate(state.bitrate) else "—")
            InfoRow("Audio tracks", state.audioTracks.size.toString())
            InfoRow("Subtitles", state.subtitleTracks.size.toString())
            if (program != null) {
                InfoRow(
                    "Now",
                    "${program.startMs.asClock()} – ${program.endMs.asClock()} · ${program.title}",
                )
            }
            if (state.catchup) InfoRow("Mode", "Catch-up archive")
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TvButton(
                text = "Close",
                onClick = onClose,
                primary = true,
                focusRequester = focus,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(150.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Cinema.TextMuted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPanel(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberInitialFocus()
    Box(
        modifier = modifier
            .width(640.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Cinema.SurfaceElevated.copy(alpha = 0.97f))
            .border(1.dp, Cinema.Border, RoundedCornerShape(18.dp))
            .padding(32.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Cinema.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = primaryLabel,
                    onClick = onPrimary,
                    primary = true,
                    focusRequester = focus,
                )
                TvButton(text = secondaryLabel, onClick = onSecondary, primary = false)
                TvButton(text = "Back", onClick = onBack, primary = false)
            }
        }
    }
}

@Composable
private fun ExternalPanel(
    state: PlayerUiState,
    item: MediaItem?,
    modifier: Modifier = Modifier,
    onResumeHere: () -> Unit,
    onHandoffAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = rememberInitialFocus()
    Box(
        modifier = modifier
            .width(640.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Cinema.SurfaceElevated.copy(alpha = 0.98f))
            .border(1.dp, Cinema.Border, RoundedCornerShape(18.dp))
            .padding(32.dp),
    ) {
        Column {
            Text(
                text = "External playback",
                style = MaterialTheme.typography.headlineMedium,
                color = Cinema.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.externalMessage ?: "Playback continues in your external player",
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextSecondary,
            )
            item?.name?.let { name ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Cinema.SteelBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (item?.isLive == false && state.positionMs > 0L) {
                    "Stopped here at ${state.positionMs.asMediaClock()}. The external player starts at the same " +
                        "point, and this screen resumes from there when you come back."
                } else {
                    "The built-in player was released so only one player holds the stream."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextMuted,
            )
            Spacer(Modifier.height(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    text = if (item?.isLive == false && state.positionMs > 0L) {
                        "Continue here at ${state.positionMs.asMediaClock()}"
                    } else {
                        "Continue here"
                    },
                    onClick = onResumeHere,
                    primary = true,
                    focusRequester = focus,
                )
                TvButton(text = "Play in another app", onClick = onHandoffAgain, primary = false)
                TvButton(text = "Back", onClick = onBack, primary = false)
            }
        }
    }
}

@Composable
private fun MessageBanner(message: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Cinema.SurfaceElevated.copy(alpha = 0.95f))
            .border(1.dp, Cinema.Border, CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.SteelBright,
        )
    }
}
