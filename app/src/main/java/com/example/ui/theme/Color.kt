package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Refined Deep Charcoal DAW & Audio Production Palette
val StudioBackground = Color(0xFF0C0D11)
val StudioSurface = Color(0xFF14161D)
val StudioSurfaceElevated = Color(0xFF1C1F2A)
val StudioSurfaceHigh = Color(0xFF252936)
val StudioBorder = Color(0xFF2B3040)
val StudioBorderSubtle = Color(0xFF202431)

// Restrained Studio Accents (reserved for meaningful actions, selection & status)
val StudioViolet = Color(0xFF9D7CF6)
val StudioVioletDeep = Color(0xFF7C5CE0)
val StudioVioletContainer = Color(0xFF231C3A)
val StudioCyan = Color(0xFF38BDF8)
val StudioCyanDeep = Color(0xFF0EA5E9)
val StudioCyanContainer = Color(0xFF102738)

// Semantic Status & Metering Colors
val StudioEmerald = Color(0xFF22C55E)
val StudioEmeraldContainer = Color(0xFF10291D)
val StudioAmber = Color(0xFFF59E0B)
val StudioAmberContainer = Color(0xFF30230D)
val StudioCrimson = Color(0xFFE11D48)
val StudioCrimsonContainer = Color(0xFF34141D)

// High-Legibility Studio Typography Tokens
val StudioTextPrimary = Color(0xFFF4F5F8)
val StudioTextSecondary = Color(0xFFA0A6BA)
val StudioTextMuted = Color(0xFF697086)

// Subtle, Distinguishable Multitrack DAW Colors
val TrackSpeakerA = Color(0xFF9D7CF6) // Violet
val TrackSpeakerB = Color(0xFF38BDF8) // Cyan
val TrackSpeakerC = Color(0xFF34D399) // Emerald
val TrackSpeakerD = Color(0xFFFBBF24) // Amber
val TrackSpeakerE = Color(0xFFF472B6) // Rose
val TrackSpeakerF = Color(0xFF818CF8) // Indigo
val TrackOriginal = Color(0xFF94A3B8) // Neutral Slate (Original Audio)
val TrackMusic = Color(0xFF60A5FA)    // Accompaniment / BGM
val TrackAmbience = Color(0xFF2DD4BF) // SFX / Ambience

fun speakerColorFor(speakerLabel: String): Color {
    return when (speakerLabel.uppercase().trim()) {
        "A" -> TrackSpeakerA
        "B" -> TrackSpeakerB
        "C" -> TrackSpeakerC
        "D" -> TrackSpeakerD
        "E" -> TrackSpeakerE
        "F" -> TrackSpeakerF
        else -> {
            val palette = listOf(
                TrackSpeakerA, TrackSpeakerB, TrackSpeakerC,
                TrackSpeakerD, TrackSpeakerE, TrackSpeakerF
            )
            val idx = (speakerLabel.hashCode().let { if (it < 0) -it else it }) % palette.size
            palette[idx]
        }
    }
}
