package com.streamline.play.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

@Composable
fun StreamLineTheme(content: @Composable () -> Unit) {
    val materialColors = darkColorScheme(
        primary = Cinema.SteelBlue,
        onPrimary = Cinema.Ink,
        primaryContainer = Cinema.SteelDeep,
        onPrimaryContainer = Cinema.TextPrimary,
        secondary = Cinema.SteelBright,
        onSecondary = Cinema.Ink,
        secondaryContainer = Cinema.SurfaceHigh,
        onSecondaryContainer = Cinema.TextPrimary,
        tertiary = Cinema.Gold,
        onTertiary = Cinema.Ink,
        background = Cinema.Background,
        onBackground = Cinema.TextPrimary,
        surface = Cinema.Surface,
        onSurface = Cinema.TextPrimary,
        surfaceVariant = Cinema.SurfaceElevated,
        onSurfaceVariant = Cinema.TextSecondary,
        outline = Cinema.Border,
        error = Cinema.Live,
        onError = Cinema.Ink,
    )

    val tvColors = tvDarkColorScheme(
        primary = Cinema.SteelBlue,
        onPrimary = Cinema.Ink,
        primaryContainer = Cinema.SteelDeep,
        onPrimaryContainer = Cinema.TextPrimary,
        secondary = Cinema.SteelBright,
        onSecondary = Cinema.Ink,
        secondaryContainer = Cinema.SurfaceHigh,
        onSecondaryContainer = Cinema.TextPrimary,
        tertiary = Cinema.Gold,
        onTertiary = Cinema.Ink,
        tertiaryContainer = Cinema.SurfaceHigh,
        onTertiaryContainer = Cinema.TextPrimary,
        background = Cinema.Background,
        onBackground = Cinema.TextPrimary,
        surface = Cinema.Surface,
        onSurface = Cinema.TextPrimary,
        surfaceVariant = Cinema.SurfaceElevated,
        onSurfaceVariant = Cinema.TextSecondary,
        border = Cinema.Border,
        borderVariant = Cinema.SurfaceHigh,
        error = Cinema.Live,
        onError = Cinema.Ink,
        scrim = Cinema.Ink,
    )

    MaterialTheme(colorScheme = materialColors, typography = AppTypography) {
        TvMaterialTheme(colorScheme = tvColors, content = content)
    }
}
