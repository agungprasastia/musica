package com.music.musica.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.theme.DefaultMeshDarkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val blurredMeshCache = ConcurrentHashMap<String, ImageBitmap>()

/**
 * Three selectable Now Playing backdrops:
 * - [PlayerTheme.DEFAULT]: 6x6 artwork color grid CPU-blurred to a 32x32 smooth mesh (zero live GPU blur cost,
 *   faithfully honors dark/neutral covers without fake neon tinting)
 * - [PlayerTheme.LEGACY]: 4-blob radial mesh with smootherstep falloff that gently drifts and settles after 8s
 * - [PlayerTheme.SIMPLE]: Pre-blurred album artwork with atmospheric scrim
 */
@Composable
fun PlayerBackdrop(
    imageUrl: String?,
    meshColors: List<Color>,
    theme: PlayerTheme,
    reduceAnimation: Boolean,
    modifier: Modifier = Modifier,
    dimMore: Boolean = false,
) {
    val scrim = if (dimMore) 0.44f else 0.24f
    when (theme) {
        PlayerTheme.DEFAULT -> {
            ArtworkMeshBackdrop(
                imageUrl = imageUrl,
                fallbackColors = meshColors,
                reduceAnimation = reduceAnimation,
                scrimAlpha = scrim,
                modifier = modifier,
            )
        }
        PlayerTheme.LEGACY -> {
            MeshGradientBackground(
                colors = meshColors,
                driftKey = imageUrl ?: "default",
                reduceAnimation = reduceAnimation,
                scrimAlpha = scrim,
                modifier = modifier,
            )
        }
        PlayerTheme.SIMPLE -> {
            SimpleBlurredBackdrop(
                imageUrl = imageUrl,
                fallbackColors = meshColors,
                scrimAlpha = if (dimMore) 0.54f else 0.36f,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ArtworkMeshBackdrop(
    imageUrl: String?,
    fallbackColors: List<Color>,
    reduceAnimation: Boolean,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    var texture by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { blurredMeshCache[it] })
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        blurredMeshCache[imageUrl]?.let {
            texture = it
            return@LaunchedEffect
        }
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(upsizeArtworkUrl(imageUrl, 96))
                    .size(48)
                    .allowHardware(false)
                    .build()
                val res = SingletonImageLoader.get(context).execute(req)
                val bmp = (res as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                buildArtworkMeshTexture(bmp).asImageBitmap()
            }.getOrNull()
        }
        if (built != null) {
            if (blurredMeshCache.size > 48) blurredMeshCache.clear()
            blurredMeshCache[imageUrl] = built
            texture = built
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0D))) {
        Crossfade(
            targetState = texture,
            animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 720),
            label = "artwork_mesh_xfade",
        ) { img ->
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.12f
                            scaleY = 1.12f
                        },
                )
            } else {
                MeshGradientBackground(
                    colors = fallbackColors,
                    reduceAnimation = true,
                    scrimAlpha = 0f,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = scrimAlpha * 0.65f),
                        0.45f to Color.Black.copy(alpha = scrimAlpha),
                        1f to Color.Black.copy(alpha = (scrimAlpha * 1.65f).coerceAtMost(0.72f)),
                    ),
                ),
        )
    }
}

@Composable
private fun SimpleBlurredBackdrop(
    imageUrl: String?,
    fallbackColors: List<Color>,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val blurred = rememberBlurredArtwork(imageUrl, radius = 6)
    Box(modifier = modifier.fillMaxSize().background(fallbackColors.firstOrNull() ?: Color.Black)) {
        if (blurred != null) {
            Image(
                bitmap = blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = scrimAlpha * 0.7f),
                        0.5f to Color.Black.copy(alpha = scrimAlpha),
                        1f to Color.Black.copy(alpha = (scrimAlpha * 1.45f).coerceAtMost(0.78f)),
                    ),
                ),
        )
    }
}

/**
 * Samples the cover bitmap down to a 6x6 color grid, darkens overly bright regions to preserve
 * typography legibility, upscales to 32x32, and runs a fast 3-pass separable box blur on CPU.
 */
private fun buildArtworkMeshTexture(source: Bitmap): Bitmap {
    val grid = source.scale(6, 6)
    val w = 32
    val h = 32
    val upscaled = grid.scale(w, h)
    grid.recycle()

    val pixels = IntArray(w * h)
    upscaled.getPixels(pixels, 0, w, 0, 0, w, h)
    upscaled.recycle()

    // Gently temper extreme luminance so white controls remain effortless to read
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = ((c ushr 16) and 0xFF) * 0.78f
        val g = ((c ushr 8) and 0xFF) * 0.78f
        val b = (c and 0xFF) * 0.78f
        pixels[i] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    val tmp = IntArray(w * h)
    val radius = 4
    repeat(3) {
        boxBlurHorizontal(pixels, tmp, w, h, radius)
        boxBlurVertical(tmp, pixels, w, h, radius)
    }

    return createBitmap(w, h).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (dx in -r..r) {
                val nx = (x + dx).coerceIn(0, w - 1)
                val p = src[row + nx]
                sr += (p ushr 16) and 0xFF
                sg += (p ushr 8) and 0xFF
                sb += p and 0xFF
                count++
            }
            dst[row + x] = (0xFF shl 24) or ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
        }
    }
}

private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    for (x in 0 until w) {
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (dy in -r..r) {
                val ny = (y + dy).coerceIn(0, h - 1)
                val p = src[ny * w + x]
                sr += (p ushr 16) and 0xFF
                sg += (p ushr 8) and 0xFF
                sb += p and 0xFF
                count++
            }
            dst[y * w + x] = (0xFF shl 24) or ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
        }
    }
}

const val MESH_CYCLE_MILLIS = 3_600

/**
 * Cycles smoothly through [palettes] every [stepMillis]. When [reduceAnimation] is enabled,
 * stays cleanly on the first palette.
 */
@Composable
fun rememberCyclingMeshColors(
    palettes: List<List<Color>>,
    stepMillis: Int = MESH_CYCLE_MILLIS,
    reduceAnimation: Boolean = false,
): List<Color> {
    var step by remember(palettes) { mutableIntStateOf(0) }
    LaunchedEffect(palettes, stepMillis, reduceAnimation) {
        if (reduceAnimation || palettes.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(stepMillis.toLong())
            step++
        }
    }
    return palettes[step % palettes.size]
}

/**
 * 4-blob smooth radial mesh gradient with Oklab color crossfading, smootherstep radial falloff,
 * and optional continuous orbital drift (used by Onboarding) or 8-second settle (used by Player/Cards).
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = DefaultMeshDarkColors,
    driftKey: Any = Unit,
    reduceAnimation: Boolean = false,
    continuous: Boolean = false,
    initialPhase: Float = 0.35f,
    driftMillis: Int = 8_000,
    colorFadeMillis: Int = 1_400,
    blurRadius: Dp = 56.dp,
    scrimAlpha: Float = 0.24f,
) {
    val tuned = remember(colors) {
        val base = if (colors.size >= 4) colors.take(4) else (colors + DefaultMeshDarkColors).take(4)
        base.map { it.tunedMeshColor() }
    }
    val colorSpec: AnimationSpec<Color> = if (reduceAnimation) snap() else tween(durationMillis = colorFadeMillis)
    val animatedColors = tuned.mapIndexed { idx, c ->
        animateColorAsState(targetValue = c, animationSpec = colorSpec, label = "mesh_c_$idx")
    }
    val baseColor by animateColorAsState(
        targetValue = tuned.first().dimmedMeshColor(),
        animationSpec = colorSpec,
        label = "mesh_base",
    )

    val drift = remember { Animatable(initialPhase) }

    LaunchedEffect(driftKey, reduceAnimation, continuous, driftMillis, initialPhase) {
        when {
            reduceAnimation -> drift.snapTo(initialPhase)
            continuous -> {
                while (isActive) {
                    drift.animateTo(
                        targetValue = drift.value + (2.0 * PI).toFloat(),
                        animationSpec = tween(durationMillis = driftMillis * 4, easing = LinearEasing),
                    )
                }
            }
            else -> {
                drift.snapTo(initialPhase)
                drift.animateTo(
                    targetValue = initialPhase + (1.6 * PI).toFloat(),
                    animationSpec = tween(durationMillis = driftMillis, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
        Modifier.blur(blurRadius)
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.28f
                    scaleY = 1.28f
                }
                .background(baseColor)
                .then(blurModifier),
        ) {
            val w = size.width
            val h = size.height
            val phase = drift.value
            val radius = maxOf(w, h) * 0.68f
            val anchors = listOf(
                Offset(0.20f, 0.24f),
                Offset(0.80f, 0.22f),
                Offset(0.76f, 0.78f),
                Offset(0.24f, 0.76f),
            )
            val speeds = floatArrayOf(1f, -0.7f, 0.85f, -1.15f)

            animatedColors.forEachIndexed { idx, colorState ->
                val anchor = anchors[idx]
                val center = Offset(
                    x = (anchor.x + 0.16f * cos(phase * speeds[idx] + idx * 1.7f)) * w,
                    y = (anchor.y + 0.16f * sin(phase * speeds[idx] * 0.9f + idx * 2.3f)) * h,
                )
                drawCircle(
                    brush = smootherRadial(colorState.value, center, radius),
                    radius = radius,
                    center = center,
                )
            }
        }

        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = scrimAlpha * 0.45f),
                            0.50f to Color.Black.copy(alpha = scrimAlpha * 0.85f),
                            1.00f to Color.Black.copy(alpha = (scrimAlpha * 1.75f).coerceAtMost(0.68f)),
                        ),
                    ),
            )
        }
    }
}

private fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

private fun smootherRadial(color: Color, center: Offset, radius: Float): Brush =
    Brush.radialGradient(
        colorStops = Array(7) { i ->
            val t = i / 6f
            t to color.copy(alpha = 0.86f * (1f - smootherStep(t)))
        },
        center = center,
        radius = radius,
    )

private fun Color.tunedMeshColor(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.25f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.24f, 0.56f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmedMeshColor(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
