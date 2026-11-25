package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioViolet,
    onPrimary = StudioBackground,
    primaryContainer = StudioVioletContainer,
    onPrimaryContainer = StudioTextPrimary,
    secondary = StudioCyan,
    onSecondary = StudioBackground,
    secondaryContainer = StudioCyanContainer,
    onSecondaryContainer = StudioTextPrimary,
    tertiary = StudioEmerald,
    onTertiary = StudioBackground,
    tertiaryContainer = StudioEmeraldContainer,
    onTertiaryContainer = StudioTextPrimary,
    background = StudioBackground,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder,
    error = StudioCrimson,
    onError = StudioTextPrimary,
    errorContainer = StudioCrimsonContainer,
    onErrorContainer = StudioTextPrimary
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        content = content
    )
}
