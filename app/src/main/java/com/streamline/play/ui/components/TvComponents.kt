package com.streamline.play.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.streamline.play.ui.theme.Cinema
import com.streamline.play.util.initials
import kotlinx.coroutines.delay

@Composable
fun rememberNow(intervalMs: Long = 30_000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    restingColor: Color = Cinema.Surface,
    focusedColor: Color = Cinema.SurfaceHigh,
    restingBorder: Color = Cinema.Border,
    focusedBorder: Color = Cinema.SteelBlue,
    focusedScale: Float = 1.05f,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var keyHeld by remember { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val dpad = LocalTvDpad.current
    val focusToken = remember { Any() }
    val clock = remember { LongArray(1) }

    fun activate() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - clock[0] < ACTIVATION_DEBOUNCE_MS) return
        clock[0] = now
        currentOnClick()
    }

    DisposableEffect(dpad, focusToken) {
        onDispose { dpad?.setFocused(focusToken, false) }
    }

    LaunchedEffect(focused) {
        dpad?.setFocused(focusToken, focused)
        if (!focused) {
            pressed = false
            keyHeld = false
        }
        currentOnFocusChanged(focused)
    }

    val highlighted = focused || hovered
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.97f
            focused -> focusedScale
            hovered -> 1.01f
            else -> 1f
        },
        animationSpec = tween(140),
        label = "focusScale",
    )
    val container by animateColorAsState(
        targetValue = if (highlighted) focusedColor else restingColor,
        animationSpec = tween(130),
        label = "focusContainer",
    )
    val outline by animateColorAsState(
        targetValue = when {
            focused -> focusedBorder
            hovered -> focusedBorder.copy(alpha = 0.55f)
            else -> restingBorder
        },
        animationSpec = tween(130),
        label = "focusBorder",
    )
    val elevation by animateFloatAsState(
        targetValue = if (focused) 14f else if (hovered) 6f else 0f,
        animationSpec = tween(140),
        label = "focusElevation",
    )

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.42f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation.dp, shape, clip = false)
            .clip(shape)
            .background(container)
            .border(BorderStroke(if (focused) 2.5.dp else 1.dp, outline), shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (!enabled || !focused) return@onPreviewKeyEvent false
                if (!event.key.isActivationKey()) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!keyHeld) {
                            keyHeld = true
                            pressed = true
                            activate()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        keyHeld = false
                        pressed = false
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { canFocus = enabled }
            .focusable(enabled = enabled, interactionSource = focusInteraction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        pressed = false
                        runCatching { focusRequester?.requestFocus() }
                        activate()
                    },
                )
            }
            .hoverable(hoverInteraction)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/** D-pad tick debounce shared by every focus surface to swallow duplicate key/click pairs. */
private const val ACTIVATION_DEBOUNCE_MS = 280L
private const val SLIDER_STEP_THROTTLE_MS = 60L

/**
 * Ten-foot slider: Left/Right step the value, Center/Enter steps up, a tap on the track
 * jumps straight to the touched fraction. Up/Down leave the slider for vertical navigation.
 */
@Composable
fun TvSlider(
    label: String,
    valueText: String,
    fraction: Float,
    onStep: (Int) -> Unit,
    onTapFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val clock = remember { LongArray(1) }
    val dpad = LocalTvDpad.current
    val focusToken = remember { Any() }
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnTap by rememberUpdatedState(onTapFraction)

    DisposableEffect(dpad, focusToken) {
        onDispose {
            dpad?.setFocused(focusToken, false)
            dpad?.unregisterHorizontal(focusToken)
        }
    }

    LaunchedEffect(focused) {
        dpad?.setFocused(focusToken, focused)
        if (focused) {
            dpad?.registerHorizontal(focusToken)
        } else {
            dpad?.unregisterHorizontal(focusToken)
        }
    }
    val thumb = 18.dp
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, tween(140), label = "sliderScale")
    val outline by animateColorAsState(
        targetValue = if (focused) Cinema.SteelBright else Cinema.Border,
        animationSpec = tween(130),
        label = "sliderBorder",
    )
    val container by animateColorAsState(
        targetValue = if (focused) Cinema.SurfaceHigh else Cinema.Surface,
        animationSpec = tween(130),
        label = "sliderContainer",
    )
    val clamped = fraction.coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .border(BorderStroke(if (focused) 2.5.dp else 1.dp, outline), RoundedCornerShape(12.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> 1
                    else -> 0
                }
                if (delta == 0) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val now = SystemClock.uptimeMillis()
                if (now - clock[0] < SLIDER_STEP_THROTTLE_MS) return@onPreviewKeyEvent true
                clock[0] = now
                currentOnStep(delta)
                true
            }
            .focusProperties { canFocus = enabled }
            .focusable(enabled = enabled, interactionSource = focusInteraction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        runCatching { focusRequester?.requestFocus() }
                        currentOnTap((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                )
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        val usable = maxWidth - thumb
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Cinema.TextPrimary,
                    )
                    if (caption != null) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cinema.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused) Cinema.SteelBright else Cinema.SteelBlue,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumb),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Cinema.Ink.copy(alpha = 0.7f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Cinema.SteelDeep, if (focused) Cinema.SteelBright else Cinema.SteelBlue),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .offset(x = usable * clamped)
                        .size(thumb)
                        .clip(CircleShape)
                        .background(if (focused) Cinema.SteelBright else Cinema.SteelBlue)
                        .border(2.dp, Cinema.Ink, CircleShape),
                )
            }
            if (startLabel != null || endLabel != null) {
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = startLabel.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Cinema.TextMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = endLabel.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Cinema.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(10.dp),
        restingColor = if (primary) Cinema.SurfaceHigh else Color.Transparent,
        focusedColor = if (primary) Cinema.SteelBlue else Cinema.TextPrimary,
        restingBorder = if (primary) Cinema.Border else Cinema.Border,
        focusedBorder = if (primary) Cinema.SteelBright else Cinema.SteelBlue,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        focusRequester = focusRequester,
        onFocusChanged = { if (it) onFocus?.invoke() },
    ) { focused ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (focused) Cinema.Ink else Cinema.TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Cinema.Ink else Cinema.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: Boolean = true,
    size: Dp = 52.dp,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = if (circle) CircleShape else RoundedCornerShape(10.dp),
        restingColor = Cinema.SurfaceHigh,
        focusedColor = Cinema.SteelBlue,
        restingBorder = Cinema.Border,
        focusedBorder = Cinema.SteelBright,
        focusRequester = focusRequester,
    ) { focused ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (focused) Cinema.Ink else Cinema.TextPrimary,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        shape = CircleShape,
        restingColor = if (selected) Cinema.SteelDeep.copy(alpha = 0.5f) else Cinema.Surface,
        focusedColor = Cinema.SteelBlue,
        restingBorder = if (selected) Cinema.SteelDeep else Cinema.Border,
        focusedBorder = Cinema.SteelBright,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        focusRequester = focusRequester,
    ) { focused ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Cinema.Ink else if (selected) Cinema.SteelBright else Cinema.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun SortChip(
    mode: com.streamline.play.data.model.ChannelSortMode,
    onCycle: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    PillChip(
        text = "Sort · ${mode.shortLabel}",
        selected = mode != com.streamline.play.data.model.ChannelSortMode.PLAYLIST,
        onClick = onCycle,
        focusRequester = focusRequester,
    )
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, accent: Boolean = false, color: Color? = null) {
    val container = color ?: if (accent) Cinema.SteelDeep.copy(alpha = 0.55f) else Cinema.SurfaceHigh
    val content = if (accent) Cinema.SteelBright else Cinema.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .border(1.dp, if (accent) Cinema.SteelDeep else Cinema.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Cinema.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cinema.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = trailing,
        )
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(Cinema.Border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(Cinema.SteelBlue),
        )
    }
}

private val artPalettes = listOf(
    listOf(Color(0xFF17263A), Color(0xFF2C4A63)),
    listOf(Color(0xFF22202F), Color(0xFF4A3550)),
    listOf(Color(0xFF102A2A), Color(0xFF25504A)),
    listOf(Color(0xFF2A2118), Color(0xFF5A4326)),
    listOf(Color(0xFF241C1C), Color(0xFF573030)),
)

fun gradientFor(seed: String): Brush {
    val palette = artPalettes[(seed.hashCode() and 0x7fffffff) % artPalettes.size]
    return Brush.linearGradient(
        colors = palette,
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

@Composable
fun Artwork(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
    showInitials: Boolean = true,
) {
    val shape = RoundedCornerShape(corner)
    val brush = remember(title) { gradientFor(title) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        if (showInitials) {
            Text(
                text = title.initials(),
                style = MaterialTheme.typography.displayMedium,
                color = Color.White.copy(alpha = 0.14f),
                fontWeight = FontWeight.Bold,
            )
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun LogoBadge(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Cinema.Ink.copy(alpha = 0.6f))
            .border(1.dp, Cinema.Border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        } else {
            Text(
                text = title.initials(),
                style = MaterialTheme.typography.labelLarge,
                color = Cinema.SteelBright,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    // Scrollable so the action button keeps its minimum height on short windows instead of
    // being squeezed below its label.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Cinema.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Cinema.TextMuted,
                modifier = Modifier.width(560.dp),
            )
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                action()
            }
        }
    }
}

@Composable
fun TvDialog(
    onDismiss: () -> Unit,
    width: Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        val dpad = rememberTvDpad()
        LaunchedEffect(dpad) {
            delay(80)
            if (!dpad.hasFocus()) dpad.focusFirst()
        }
        CompositionLocalProvider(LocalTvDpad provides dpad) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .dpadKeys(dpad)
                    .background(Color.Black.copy(alpha = 0.74f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(width)
                        .heightIn(max = maxHeight - 32.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Cinema.SurfaceElevated)
                        .border(1.dp, Cinema.SteelDeep.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(28.dp),
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
fun DialogHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = Cinema.TextPrimary,
    )
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextMuted,
        )
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
fun DialogOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit = {},
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        restingColor = if (selected) Cinema.SteelDeep.copy(alpha = 0.25f) else Cinema.Surface,
        focusedColor = Cinema.SteelBlue,
        restingBorder = if (selected) Cinema.SteelDeep else Cinema.Border,
        focusedBorder = Cinema.SteelBright,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
        focusRequester = focusRequester,
        onFocusChanged = onFocused,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (focused) Cinema.Ink else Cinema.TextPrimary,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (focused) Cinema.Ink.copy(alpha = 0.7f) else Cinema.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (focused) Cinema.Ink else Cinema.SteelBlue),
                )
            }
        }
    }
}

@Composable
fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        restingColor = Cinema.Surface,
        focusedColor = Cinema.SurfaceHigh,
        restingBorder = Cinema.Border,
        focusedBorder = Cinema.SteelBlue,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        focusRequester = focusRequester,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (focused) Cinema.SteelBright else Cinema.TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Cinema.TextPrimary,
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cinema.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Cinema.SteelBright else Cinema.SteelBlue,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    focusRequester: FocusRequester? = null,
    label: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dpad = LocalTvDpad.current
    val focusToken = remember { Any() }

    DisposableEffect(dpad, focusToken) {
        onDispose {
            dpad?.setFocused(focusToken, false)
            dpad?.unregisterHorizontal(focusToken)
        }
    }

    LaunchedEffect(focused) {
        dpad?.setFocused(focusToken, focused)
        if (focused) {
            dpad?.registerHorizontal(focusToken)
        } else {
            dpad?.unregisterHorizontal(focusToken)
        }
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Cinema.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = LocalTextStyle.current.copy(
                color = Color.White,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            ),
            cursorBrush = SolidColor(Cinema.SteelBright),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clip(RoundedCornerShape(8.dp))
                .background(Cinema.Ink.copy(alpha = 0.55f))
                .border(
                    BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Cinema.SteelBlue else Cinema.Border),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cinema.TextMuted,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun rememberInitialFocus(enabled: Boolean = true): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        delay(120)
        focusRequester.requestFocusReliably()
    }
    return focusRequester
}

/** Low-end boxes occasionally report the focus node before it is attached; retry briefly. */
suspend fun FocusRequester.requestFocusReliably(attempts: Int = 6, stepMs: Long = 70L): Boolean {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return true
        delay(stepMs)
    }
    return false
}
