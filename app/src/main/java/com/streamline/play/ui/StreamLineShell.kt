package com.streamline.play.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.streamline.play.ui.components.FocusableSurface
import com.streamline.play.ui.components.LocalTvDpad
import com.streamline.play.ui.components.Tag
import com.streamline.play.ui.components.dpadKeys
import com.streamline.play.ui.components.rememberNow
import com.streamline.play.ui.components.rememberTvDpad
import com.streamline.play.ui.nav.DetailKey
import com.streamline.play.ui.nav.GuideKey
import com.streamline.play.ui.nav.HomeKey
import com.streamline.play.ui.nav.LiveKey
import com.streamline.play.ui.nav.MoviesKey
import com.streamline.play.ui.nav.PlayerKey
import com.streamline.play.ui.nav.RootTab
import com.streamline.play.ui.nav.SeriesKey
import com.streamline.play.ui.nav.SettingsKey
import com.streamline.play.ui.nav.rootTabs
import com.streamline.play.ui.player.PlayerScreen
import com.streamline.play.ui.screens.DetailScreen
import com.streamline.play.ui.screens.GuideScreen
import com.streamline.play.ui.screens.HomeScreen
import com.streamline.play.ui.screens.LiveScreen
import com.streamline.play.ui.screens.MoviesScreen
import com.streamline.play.ui.screens.SeriesScreen
import com.streamline.play.ui.screens.SettingsScreen
import com.streamline.play.ui.screens.SettingsSection
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.asClock
import com.streamline.play.util.asDayDate
import kotlinx.coroutines.delay

@Composable
fun StreamLineShell(vm: AppViewModel) {
    val backStack = rememberNavBackStack(HomeKey)
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val currentKey = backStack.lastOrNull()
    val showChrome = currentKey !is PlayerKey
    val now = rememberNow(15_000L)
    val context = LocalContext.current

    var focusRestore by remember { mutableStateOf<FocusRestore?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }
    var exitArmed by remember { mutableStateOf(false) }
    val dpad = rememberTvDpad()
    val lastFocusByTab = remember { mutableStateMapOf<RootTab, String>() }
    var settingsSection by remember { mutableStateOf(SettingsSection.PLAYBACK) }

    val restoreFor: (RootTab) -> FocusRestore? = { tab ->
        focusRestore ?: lastFocusByTab[tab]?.let { FocusRestore(it, restoreToken) }
    }
    val rememberFocus: (RootTab) -> (String) -> Unit = { tab ->
        { itemId -> if (itemId.isNotBlank()) lastFocusByTab[tab] = itemId }
    }

    val requestRestore: (String) -> Unit = { itemId ->
        restoreToken += 1
        focusRestore = FocusRestore(itemId, restoreToken)
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_000)
            exitArmed = false
        }
    }

    BackHandler(enabled = backStack.size == 1) {
        if (!settings.confirmExit || exitArmed) {
            (context as? Activity)?.finish()
        } else {
            exitArmed = true
        }
    }

    val push: (NavKey) -> Unit = { key -> backStack.add(key) }
    val pop: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val selectTab: (NavKey) -> Unit = { tab ->
        val changed = backStack.firstOrNull() != tab
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack[0] = tab
        focusRestore = null
        if (changed) restoreToken += 1
    }

    CompositionLocalProvider(LocalTvDpad provides dpad) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dpadKeys(if (showChrome) dpad else null)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Cinema.Ink, Cinema.Background, Cinema.Ink),
                    ),
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            if (showChrome) {
                TopBar(
                    selected = (backStack.firstOrNull() as? RootTab),
                    liveCount = catalog.liveChannels.size,
                    clock = now.asClock(),
                    date = now.asDayDate(),
                    onSelect = selectTab,
                )
            }
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = pop,
                entryProvider = entryProvider<NavKey> {
                    entry<HomeKey> {
                        HomeScreen(
                            vm = vm,
                            onPlay = { item -> push(PlayerKey(item.id)) },
                            onDetails = { item -> push(DetailKey(item.id)) },
                            onOpenTab = selectTab,
                            restore = restoreFor(HomeKey),
                            onItemFocused = rememberFocus(HomeKey),
                        )
                    }
                    entry<LiveKey> {
                        LiveScreen(
                            vm = vm,
                            onPlay = { item -> push(PlayerKey(item.id)) },
                            restore = restoreFor(LiveKey),
                            onItemFocused = rememberFocus(LiveKey),
                        )
                    }
                    entry<MoviesKey> {
                        MoviesScreen(
                            vm = vm,
                            onDetails = { item -> push(DetailKey(item.id)) },
                            restore = restoreFor(MoviesKey),
                            onItemFocused = rememberFocus(MoviesKey),
                        )
                    }
                    entry<SeriesKey> {
                        SeriesScreen(
                            vm = vm,
                            onDetails = { item -> push(DetailKey(item.id)) },
                            restore = restoreFor(SeriesKey),
                            onItemFocused = rememberFocus(SeriesKey),
                        )
                    }
                    entry<GuideKey> {
                        GuideScreen(
                            vm = vm,
                            onPlay = { item, program ->
                                push(
                                    PlayerKey(
                                        itemId = item.id,
                                        streamUrl = item.url,
                                        catchupStartMs = program?.startMs,
                                        catchupEndMs = program?.endMs,
                                    ),
                                )
                            },
                            restore = restoreFor(GuideKey),
                            onItemFocused = rememberFocus(GuideKey),
                        )
                    }
                    entry<SettingsKey> {
                        SettingsScreen(
                            vm = vm,
                            initialSection = settingsSection,
                            onSectionChanged = { settingsSection = it },
                        )
                    }
                    entry<DetailKey> { key ->
                        DetailScreen(
                            vm = vm,
                            key = key,
                            onPlay = { item -> push(PlayerKey(item.id)) },
                            onBack = pop,
                        )
                    }
                    entry<PlayerKey> { key ->
                        PlayerScreen(
                            vm = vm,
                            key = key,
                            onClose = {
                                pop()
                                requestRestore(key.itemId)
                            },
                            onFatalExit = { itemId ->
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                if (backStack.lastOrNull() is DetailKey) {
                                    backStack.removeAt(backStack.lastIndex)
                                }
                                requestRestore(itemId)
                            },
                        )
                    }
                },
            )
        }

        if (exitArmed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Cinema.SurfaceElevated)
                    .border(1.dp, Cinema.SteelBlue, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Press BACK again to exit",
                    style = MaterialTheme.typography.labelLarge,
                    color = Cinema.TextPrimary,
                )
            }
        }
    }
    }
}

@Composable
private fun TopBar(
    selected: RootTab?,
    liveCount: Int,
    clock: String,
    date: String,
    onSelect: (NavKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Cinema.Ink.copy(alpha = 0.98f), Cinema.Ink.copy(alpha = 0.82f)),
                ),
            )
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cinema.SteelBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Cinema.Ink,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "STREAMLINE",
                    style = MaterialTheme.typography.titleMedium,
                    color = Cinema.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "PLAY",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.SteelBlue,
                    letterSpacing = 5.sp,
                )
            }
        }

        Spacer(Modifier.width(28.dp))

        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = 4.dp, end = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(rootTabs, key = { it.label }) { tab ->
                TabButton(
                    label = tab.label,
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Tag(text = "$liveCount live", accent = true)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = clock,
                    style = MaterialTheme.typography.titleMedium,
                    color = Cinema.TextPrimary,
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelMedium,
                    color = Cinema.TextMuted,
                )
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Cinema.Border.copy(alpha = 0.7f)),
    )
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        restingColor = if (selected) Cinema.SteelDeep.copy(alpha = 0.35f) else Color.Transparent,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Color.Transparent,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 9.dp),
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> Cinema.SteelBright
                selected -> Cinema.SteelBright
                else -> Cinema.TextSecondary
            },
            maxLines = 1,
        )
    }
}
