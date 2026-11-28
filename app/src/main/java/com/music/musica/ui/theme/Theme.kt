package com.music.musica.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.music.musica.core.model.ThemeMode

private val MusicaDarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color.White,
    secondary = MusicaAccentRed,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFD1D1D6),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF242426),
    onTertiaryContainer = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF262629),
    outline = DarkOutline,
    outlineVariant = Color(0xFF242426),
)

private val MusicaLightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color.Black,
    secondary = MusicaAccentRed,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF48484A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECECF1),
    onTertiaryContainer = Color.Black,
    background = LightBackground,
    onBackground = Color.Black,
    surface = LightSurface,
    onSurface = Color.Black,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = Color(0xFFEBEBF0),
    surfaceContainerHighest = Color(0xFFE2E2E8),
    outline = LightOutline,
    outlineVariant = Color(0xFFDCDCE2),
)

@Composable
fun MusicaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    forceDarkStatusBarIcons: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !isDark && !forceDarkStatusBarIcons
            insets.isAppearanceLightNavigationBars = !isDark
        }
    }

    val colorScheme = if (isDark) MusicaDarkColors else MusicaLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MusicaTypography,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}

@Composable
fun DarkStatusBarEffect(enabled: Boolean) {
    val view = LocalView.current
    val isDark = MaterialTheme.colorScheme.background == DarkBackground
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insets = WindowCompat.getInsetsController(window, view)
            val useLightIcons = if (enabled) false else !isDark
            insets.isAppearanceLightStatusBars = useLightIcons
            insets.isAppearanceLightNavigationBars = useLightIcons
        }
    }
}
