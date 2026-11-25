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
    secondary = MusicaAccentRed,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)

private val MusicaLightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = MusicaAccentRed,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = Color.Black,
    surface = LightSurface,
    onSurface = Color.Black,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
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
            insets.isAppearanceLightStatusBars = if (enabled) false else !isDark
        }
    }
}
