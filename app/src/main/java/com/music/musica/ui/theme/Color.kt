package com.music.musica.ui.theme

import androidx.compose.ui.graphics.Color

// Reserved for semantic states: Liked heart, destructive actions, and Replay #1 highlight.
val MusicaAccentRed = Color(0xFFFA2D48)

val IosGreenDark = Color(0xFF30D158)
val IosGreenLight = Color(0xFF34C759)

val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF0D0D0F)
val DarkSurfaceVariant = Color(0xFF1C1C1E)
val DarkOnSurfaceVariant = Color(0xFF8E8E93)
val DarkOutline = Color(0xFF2C2C2E)

val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF7F7F9)
val LightSurfaceVariant = Color(0xFFF2F2F7)
val LightOnSurfaceVariant = Color(0xFF6E6E73)
val LightOutline = Color(0xFFE5E5EA)

val DefaultMeshDarkColors = listOf(
    Color(0xFF2A0F24),
    Color(0xFF16133A),
    Color(0xFF0B2335),
    Color(0xFF1B0A18),
)

val OnboardingMeshColors = listOf(
    Color(0xFFFA2D48),
    Color(0xFF7B2FF7),
    Color(0xFFFF7A45),
    Color(0xFF2E1A8A),
)

/**
 * Luminous cycling palettes for the Welcome onboarding canvas, starting on Musica's crimson red.
 */
val WelcomeMeshPalettes: List<List<Color>> = listOf(
    // Crimson, violet, ember
    listOf(Color(0xFFFA2D48), Color(0xFF7B2FF7), Color(0xFFFF7A45), Color(0xFF2E1A8A)),
    // Sunset rose
    listOf(Color(0xFFFF4E8A), Color(0xFFFF9A3C), Color(0xFFE0245E), Color(0xFF6A1B9A)),
    // Deep ocean
    listOf(Color(0xFF1E88E5), Color(0xFF00BFA5), Color(0xFF5E35B1), Color(0xFF0D47A1)),
    // Aurora
    listOf(Color(0xFF00C853), Color(0xFF00B8D4), Color(0xFF7C4DFF), Color(0xFF1A237E)),
    // Grape & rose
    listOf(Color(0xFFD500F9), Color(0xFFFF4081), Color(0xFF651FFF), Color(0xFF4A148C)),
    // Warm amber
    listOf(Color(0xFFFFB300), Color(0xFFFF5252), Color(0xFFF06292), Color(0xFF6D2E46)),
)

/**
 * Red-forward cycling palettes for the YouTube account connection step.
 */
val ConnectMeshPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFFFF1E3C), Color(0xFFC2185B), Color(0xFFFF8F3D), Color(0xFF4A1C8C)),
    listOf(Color(0xFFE53935), Color(0xFFFF6E40), Color(0xFFAD1457), Color(0xFF311B92)),
    listOf(Color(0xFFFF3D00), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFFB71C1C)),
    listOf(Color(0xFFF50057), Color(0xFFFF9100), Color(0xFFC51162), Color(0xFF4527A0)),
)
