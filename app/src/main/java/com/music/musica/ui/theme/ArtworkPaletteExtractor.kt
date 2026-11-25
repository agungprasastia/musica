package com.music.musica.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Immutable
data class ArtworkPalette(
    val background: Color,
    val wash: Color,
    val elevated: Color,
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
    val meshColors: List<Color>,
)

object ArtworkPaletteMath {

    fun contrastRatio(foreground: Color, background: Color): Float {
        val l1 = foreground.luminance()
        val l2 = background.luminance()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    fun derivePalette(seed: Color, dark: Boolean): ArtworkPalette {
        val (h, s, l) = rgbToHsl(seed)
        val usableSat = if (s < 0.08f) 0.14f else s.coerceIn(0.22f, 0.78f)

        val bg = if (dark) {
            hslToColor(h, (usableSat * 0.65f).coerceIn(0.12f, 0.55f), 0.09f)
        } else {
            hslToColor(h, (usableSat * 0.45f).coerceIn(0.08f, 0.40f), 0.94f)
        }

        val wash = if (dark) {
            hslToColor(h, (usableSat * 0.75f).coerceIn(0.16f, 0.62f), 0.16f)
        } else {
            hslToColor(h, (usableSat * 0.50f).coerceIn(0.10f, 0.45f), 0.87f)
        }

        val elevated = if (dark) {
            hslToColor(h, (usableSat * 0.55f).coerceIn(0.10f, 0.45f), 0.19f)
        } else {
            hslToColor(h, (usableSat * 0.35f).coerceIn(0.08f, 0.32f), 0.99f)
        }

        val rawAccent = if (dark) {
            hslToColor(h, usableSat.coerceIn(0.45f, 0.88f), max(l, 0.64f).coerceAtMost(0.76f))
        } else {
            hslToColor(h, usableSat.coerceIn(0.50f, 0.88f), min(l, 0.42f).coerceAtLeast(0.32f))
        }
        val accent = ensureContrast(rawAccent, bg, target = 4.5f, lighten = dark)

        val onBg = if (dark) Color.White else Color(0xFF111113)
        val onBgVariant = if (dark) Color.White.copy(alpha = 0.72f) else Color(0xFF424246)
        val divider = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)

        val meshSat = usableSat.coerceIn(0.35f, 0.82f)
        val mesh = listOf(
            hslToColor(h, meshSat, 0.28f),
            hslToColor((h + 36f) % 360f, (meshSat * 0.9f).coerceAtMost(0.85f), 0.20f),
            hslToColor((h + 324f) % 360f, (meshSat * 0.85f).coerceAtMost(0.80f), 0.17f),
            hslToColor((h + 18f) % 360f, (meshSat * 0.6f).coerceAtMost(0.65f), 0.11f),
        )

        return ArtworkPalette(
            background = bg,
            wash = wash,
            elevated = elevated,
            accent = accent,
            onBackground = onBg,
            onBackgroundVariant = onBgVariant,
            divider = divider,
            meshColors = mesh,
        )
    }

    private fun ensureContrast(color: Color, bg: Color, target: Float, lighten: Boolean): Color {
        if (contrastRatio(color, bg) >= target) return color
        var (h, s, l) = rgbToHsl(color)
        repeat(14) {
            l = if (lighten) (l + 0.05f).coerceAtMost(0.96f) else (l - 0.05f).coerceAtLeast(0.08f)
            val candidate = hslToColor(h, s, l)
            if (contrastRatio(candidate, bg) >= target) return candidate
        }
        return if (lighten) Color.White else Color.Black
    }

    private fun rgbToHsl(color: Color): Triple<Float, Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) / 2f
        val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
        val h = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Triple(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }
}

private val artworkSeedCache = ConcurrentHashMap<String, Color>()

@Composable
fun rememberArtworkPalette(
    imageUrl: String?,
    dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    reduceAnimation: Boolean = false,
): ArtworkPalette {
    val context = LocalPlatformContext.current
    val fallbackSeed = if (dark) Color(0xFF3A1B33) else Color(0xFF4A3B6B)
    var seed by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { artworkSeedCache[it] } ?: fallbackSeed)
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        artworkSeedCache[imageUrl]?.let {
            seed = it
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(64)
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                val bitmap: Bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val pal = Palette.from(bitmap).maximumColorCount(8).generate()
                val rgb = pal.vibrantSwatch?.rgb
                    ?: pal.dominantSwatch?.rgb
                    ?: pal.mutedSwatch?.rgb
                    ?: return@runCatching null
                Color(rgb)
            }.getOrNull()
        }
        if (extracted != null) {
            if (artworkSeedCache.size > 160) artworkSeedCache.clear()
            artworkSeedCache[imageUrl] = extracted
            seed = extracted
        }
    }

    val target = remember(seed, dark) { ArtworkPaletteMath.derivePalette(seed, dark) }
    val spec = if (reduceAnimation) snap<Color>() else tween<Color>(durationMillis = 550)

    val bg by animateColorAsState(target.background, spec, label = "art_bg")
    val wash by animateColorAsState(target.wash, spec, label = "art_wash")
    val elevated by animateColorAsState(target.elevated, spec, label = "art_elev")
    val accent by animateColorAsState(target.accent, spec, label = "art_accent")
    val m0 by animateColorAsState(target.meshColors[0], spec, label = "art_m0")
    val m1 by animateColorAsState(target.meshColors[1], spec, label = "art_m1")
    val m2 by animateColorAsState(target.meshColors[2], spec, label = "art_m2")
    val m3 by animateColorAsState(target.meshColors[3], spec, label = "art_m3")

    return target.copy(
        background = bg,
        wash = wash,
        elevated = elevated,
        accent = accent,
        meshColors = listOf(m0, m1, m2, m3),
    )
}
