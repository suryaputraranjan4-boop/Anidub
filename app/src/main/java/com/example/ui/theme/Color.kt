package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Deep Charcoal Audio Production Studio Palette
val StudioBackground = Color(0xFF0B0C12)
val StudioSurface = Color(0xFF131520)
val StudioSurfaceElevated = Color(0xFF1A1D2C)
val StudioSurfaceHigh = Color(0xFF23273A)
val StudioBorder = Color(0xFF2D324A)

// Subtle Purple & Blue Accents
val StudioViolet = Color(0xFFA78BFA)
val StudioVioletDeep = Color(0xFF6D28D9)
val StudioVioletContainer = Color(0xFF281D46)
val StudioCyan = Color(0xFF38BDF8)
val StudioCyanDeep = Color(0xFF0284C7)
val StudioCyanContainer = Color(0xFF112C40)

// Status & Metering Colors
val StudioEmerald = Color(0xFF10B981)
val StudioEmeraldContainer = Color(0xFF0F2E26)
val StudioAmber = Color(0xFFF59E0B)
val StudioAmberContainer = Color(0xFF36260E)
val StudioCrimson = Color(0xFFEF4444)
val StudioCrimsonContainer = Color(0xFF3A151B)

// Typography Contrast
val StudioTextPrimary = Color(0xFFF3F4F8)
val StudioTextSecondary = Color(0xFFA3A8C2)
val StudioTextMuted = Color(0xFF6B7190)

// Character-Specific Track Colors (A, B, C, D, E, F)
val TrackSpeakerA = Color(0xFFA78BFA) // Electric Violet
val TrackSpeakerB = Color(0xFF38BDF8) // Cyan Blue
val TrackSpeakerC = Color(0xFF34D399) // Mint Emerald
val TrackSpeakerD = Color(0xFFFBBF24) // Warm Amber
val TrackSpeakerE = Color(0xFFF472B6) // Rose Pink
val TrackSpeakerF = Color(0xFF818CF8) // Indigo
val TrackMusic = Color(0xFF60A5FA)    // BGM Blue
val TrackAmbience = Color(0xFF2DD4BF) // SFX/Ambience Teal

fun speakerColorFor(speakerLabel: String): Color {
    return when (speakerLabel.uppercase().trim()) {
        "A" -> TrackSpeakerA
        "B" -> TrackSpeakerB
        "C" -> TrackSpeakerC
        "D" -> TrackSpeakerD
        "E" -> TrackSpeakerE
        "F" -> TrackSpeakerF
        else -> {
            val palette = listOf(TrackSpeakerA, TrackSpeakerB, TrackSpeakerC, TrackSpeakerD, TrackSpeakerE, TrackSpeakerF)
            val idx = (speakerLabel.hashCode().let { if (it < 0) -it else it }) % palette.size
            palette[idx]
        }
    }
}
