package com.music.musica.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    dimMore: Boolean = false,
    modifier: Modifier = Modifier,
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
    val grid = Bitmap.createScaledBitmap(source, 6, 6, true)
    val w = 32
    val h = 32
    val upscaled = Bitmap.createScaledBitmap(grid, w, h, true)
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

    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
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

/**
 * Legacy 4-blob smooth radial mesh gradient that drifts gently for 8 seconds upon track/key
 * change and then settles cleanly at rest instead of running permanently at 60/120Hz.
 */
@Composable
fun MeshGradientBackground(
    colors: List<Color> = DefaultMeshDarkColors,
    driftKey: Any = Unit,
    reduceAnimation: Boolean = false,
    scrimAlpha: Float = 0.24f,
    modifier: Modifier = Modifier,
) {
    val safeColors = if (colors.size >= 4) colors else DefaultMeshDarkColors
    val drift = remember { Animatable(0f) }

    LaunchedEffect(driftKey, reduceAnimation) {
        if (reduceAnimation) {
            drift.snapTo(0.35f)
        } else {
            drift.snapTo(0f)
            drift.animateTo(
                targetValue = (1.6 * PI).toFloat(),
                animationSpec = tween(durationMillis = 8_000, easing = FastOutSlowInEasing),
            )
        }
    }

    val phase = drift.value

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baseRadius = maxOf(w, h) * 0.82f

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(safeColors[0], safeColors[3]),
                ),
            )

            val c1 = Offset(w * (0.24f + 0.16f * cos(phase)), h * (0.22f + 0.14f * sin(phase)))
            drawCircle(
                brush = smootherRadial(safeColors[0], c1, baseRadius),
                radius = baseRadius,
                center = c1,
            )

            val c2 = Offset(w * (0.78f + 0.15f * sin(phase * 0.85f)), h * (0.30f + 0.15f * cos(phase * 0.85f)))
            drawCircle(
                brush = smootherRadial(safeColors[1], c2, baseRadius * 0.94f),
                radius = baseRadius * 0.94f,
                center = c2,
            )

            val c3 = Offset(w * (0.46f + 0.20f * cos(phase + 2.1f)), h * (0.76f + 0.12f * sin(phase + 1.4f)))
            drawCircle(
                brush = smootherRadial(safeColors[2], c3, baseRadius * 0.90f),
                radius = baseRadius * 0.90f,
                center = c3,
            )

            if (scrimAlpha > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = scrimAlpha * 0.5f),
                            Color.Black.copy(alpha = scrimAlpha),
                            Color.Black.copy(alpha = (scrimAlpha * 1.6f).coerceAtMost(0.65f)),
                        ),
                    ),
                )
            }
        }
    }
}

private fun smootherRadial(color: Color, center: Offset, radius: Float): Brush =
    Brush.radialGradient(
        colorStops = arrayOf(
            0.00f to color.copy(alpha = 0.92f),
            0.25f to color.copy(alpha = 0.80f),
            0.50f to color.copy(alpha = 0.46f),
            0.75f to color.copy(alpha = 0.14f),
            1.00f to Color.Transparent,
        ),
        center = center,
        radius = radius,
    )
