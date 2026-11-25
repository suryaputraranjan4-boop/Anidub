package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
    outlineVariant = StudioBorderSubtle,
    error = StudioCrimson,
    onError = StudioTextPrimary,
    errorContainer = StudioCrimsonContainer,
    onErrorContainer = StudioTextPrimary
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        shapes = StudioShapes,
        content = content
    )
}
