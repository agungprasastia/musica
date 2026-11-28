package com.music.musica.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val blurredMeshCache = ConcurrentHashMap<String, ImageBitmap>()
private val fullBlurBackdropCache = ConcurrentHashMap<String, ImageBitmap>()
private val topBandLuminanceCache = ConcurrentHashMap<String, Float>()

/**
 * Three selectable Now Playing backdrops:
 * - [PlayerTheme.DEFAULT]: Vertically flipped 6x6 artwork color grid aligned with the bottom edge of
 *   the cover (eliminating any seam in full-bleed mode), with URL-seeded horizontal shift/mirror on
 *   rows 1..5, gentle 1.12x saturation lift, and 3-pass CPU blur on 32x32. Crossfades (360ms) to a
 *   128px CPU-blurred cover with 3-stop scrim when [dimMore] is true (Lyrics / Queue subviews).
 * - [PlayerTheme.LEGACY]: 4-blob radial mesh with smootherstep falloff that stays active in subviews.
 * - [PlayerTheme.SIMPLE]: Pre-blurred album artwork with atmospheric scrim.
 */
@Composable
fun PlayerBackdrop(
    imageUrl: String?,
    meshColors: List<Color>,
    theme: PlayerTheme,
    reduceAnimation: Boolean,
    modifier: Modifier = Modifier,
    dimMore: Boolean = false,
    seamYPx: Float = -1f,
) {
    val scrim = if (dimMore) 0.44f else 0.20f
    when (theme) {
        PlayerTheme.DEFAULT -> {
            ArtworkMeshBackdrop(
                imageUrl = imageUrl,
                fallbackColors = meshColors,
                reduceAnimation = reduceAnimation,
                scrimAlpha = scrim,
                dimMore = dimMore,
                seamYPx = seamYPx,
                modifier = modifier,
            )
        }
        PlayerTheme.LEGACY -> {
            MeshGradientBackground(
                colors = meshColors,
                driftKey = imageUrl ?: "default",
                reduceAnimation = reduceAnimation,
                scrimAlpha = if (dimMore) 0.46f else 0.24f,
                modifier = modifier,
            )
        }
        PlayerTheme.SIMPLE -> {
            FullArtworkBlurBackdrop(
                imageUrl = imageUrl,
                fallbackColors = meshColors,
                reduceAnimation = reduceAnimation,
                strongScrim = dimMore,
                modifier = modifier,
            )
        }
    }
}

/**
 * Samples WCAG relative luminance (`0f..1f`) across the top 10% band of the cover image so the
 * Now Playing status-bar scrim adapts cleanly to bright white covers vs dark covers.
 */
@Composable
fun rememberArtworkTopBandLuminance(imageUrl: String?): Float {
    val context = LocalPlatformContext.current
    var luminance by remember(imageUrl) {
        mutableFloatStateOf(imageUrl?.let { topBandLuminanceCache[it] } ?: 0.25f)
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            luminance = 0.25f
            return@LaunchedEffect
        }
        topBandLuminanceCache[imageUrl]?.let {
            luminance = it
            return@LaunchedEffect
        }
        val measured = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(upsizeArtworkUrl(imageUrl, 96))
                    .size(48)
                    .allowHardware(false)
                    .build()
                val res = SingletonImageLoader.get(context).execute(req)
                val bmp = (res as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                computeTopBandRelativeLuminance(bmp)
            }.getOrNull()
        }
        if (measured != null) {
            if (topBandLuminanceCache.size > 64) topBandLuminanceCache.clear()
            topBandLuminanceCache[imageUrl] = measured
            luminance = measured
        }
    }
    return luminance
}

@Composable
private fun ArtworkMeshBackdrop(
    imageUrl: String?,
    fallbackColors: List<Color>,
    reduceAnimation: Boolean,
    scrimAlpha: Float,
    dimMore: Boolean,
    seamYPx: Float,
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
                buildArtworkMeshTexture(bmp, seedKey = imageUrl).asImageBitmap()
            }.getOrNull()
        }
        if (built != null) {
            if (blurredMeshCache.size > 48) blurredMeshCache.clear()
            blurredMeshCache[imageUrl] = built
            texture = built
        }
    }

    val blurOverlayAlpha by animateFloatAsState(
        targetValue = if (dimMore) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 360, easing = FastOutSlowInEasing),
        label = "backdrop_subview_xfade",
    )

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0D))) {
        Crossfade(
            targetState = texture,
            animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 640),
            label = "artwork_mesh_xfade",
        ) { img ->
            if (img != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val dstW = size.width.roundToInt().coerceAtLeast(1)
                    val dstH = size.height.roundToInt().coerceAtLeast(1)
                    val seam = seamYPx.roundToInt()
                    if (seam in 1 until dstH) {
                        // Extend row 0 (bottom-edge cover colors) above the seam so the cover's
                        // translucent 42% fade dissolves seamlessly into identical hues.
                        drawImage(
                            image = img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, 1),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(dstW, seam + 2),
                            filterQuality = FilterQuality.Medium,
                        )
                        drawImage(
                            image = img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset(0, seam),
                            dstSize = IntSize(dstW, (dstH - seam).coerceAtLeast(1)),
                            filterQuality = FilterQuality.Medium,
                        )
                    } else {
                        drawImage(
                            image = img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(dstW, dstH),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
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
                        0f to Color.Black.copy(alpha = scrimAlpha * 0.45f),
                        0.50f to Color.Black.copy(alpha = scrimAlpha * 0.90f),
                        1f to Color.Black.copy(alpha = (scrimAlpha * 1.65f).coerceAtMost(0.68f)),
                    ),
                ),
        )

        if (blurOverlayAlpha > 0.005f) {
            FullArtworkBlurBackdrop(
                imageUrl = imageUrl,
                fallbackColors = fallbackColors,
                reduceAnimation = reduceAnimation,
                strongScrim = true,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blurOverlayAlpha },
            )
        }
    }
}

@Composable
private fun FullArtworkBlurBackdrop(
    imageUrl: String?,
    fallbackColors: List<Color>,
    reduceAnimation: Boolean,
    strongScrim: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    var blurred by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { fullBlurBackdropCache[it] })
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        fullBlurBackdropCache[imageUrl]?.let {
            blurred = it
            return@LaunchedEffect
        }
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(upsizeArtworkUrl(imageUrl, 256))
                    .size(128)
                    .allowHardware(false)
                    .build()
                val res = SingletonImageLoader.get(context).execute(req)
                val bmp = (res as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                buildFullBlurBackdrop(bmp).asImageBitmap()
            }.getOrNull()
        }
        if (built != null) {
            if (fullBlurBackdropCache.size > 32) fullBlurBackdropCache.clear()
            fullBlurBackdropCache[imageUrl] = built
            blurred = built
        }
    }

    Box(modifier = modifier.fillMaxSize().background(fallbackColors.firstOrNull() ?: Color(0xFF0A0A0D))) {
        Crossfade(
            targetState = blurred,
            animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 480),
            label = "full_blur_backdrop_xfade",
        ) { img ->
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.16f
                            scaleY = 1.16f
                        },
                )
            }
        }
        val topA = if (strongScrim) 0.34f else 0.24f
        val midA = if (strongScrim) 0.48f else 0.36f
        val botA = if (strongScrim) 0.64f else 0.54f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = topA),
                        0.50f to Color.Black.copy(alpha = midA),
                        1.00f to Color.Black.copy(alpha = botA),
                    ),
                ),
        )
    }
}

/**
 * Samples the cover bitmap down to a 6x6 color grid, flips it vertically so row 0 matches the
 * bottom edge of the cover artwork, applies URL-seeded horizontal shift + mirror on rows 1..5 to
 * avoid upside-down reflection artifacts, lifts saturation x1.12, upscales to 32x32, and applies
 * a 3-pass separable CPU box blur.
 */
private fun buildArtworkMeshTexture(source: Bitmap, seedKey: String): Bitmap {
    val grid = source.scale(6, 6)
    val raw = IntArray(36)
    grid.getPixels(raw, 0, 6, 0, 0, 6, 6)
    grid.recycle()

    val rearranged = IntArray(36)
    val seed = seedKey.hashCode()
    for (row in 0 until 6) {
        val srcRow = 5 - row // Row 0 = bottom edge of cover art
        val shift = if (row == 0) 0 else (((seed ushr (row * 3)) and 0x7) % 6)
        val mirror = row > 0 && (((seed ushr (row + 17)) and 1) == 1)
        for (col in 0 until 6) {
            val mappedCol = if (mirror) 5 - ((col + shift) % 6) else (col + shift) % 6
            rearranged[row * 6 + col] = raw[srcRow * 6 + mappedCol]
        }
    }

    val hsl = FloatArray(3)
    for (i in rearranged.indices) {
        ColorUtils.colorToHSL(rearranged[i], hsl)
        hsl[1] = (hsl[1] * 1.12f).coerceAtMost(1f)
        hsl[2] = (hsl[2] * 0.84f).coerceIn(0.06f, 0.56f)
        rearranged[i] = ColorUtils.HSLToColor(hsl)
    }

    val arrangedBmp = createBitmap(6, 6).apply {
        setPixels(rearranged, 0, 6, 0, 0, 6, 6)
    }
    val w = 32
    val h = 32
    val upscaled = arrangedBmp.scale(w, h)
    arrangedBmp.recycle()

    val pixels = IntArray(w * h)
    upscaled.getPixels(pixels, 0, w, 0, 0, w, h)
    upscaled.recycle()

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

private fun buildFullBlurBackdrop(source: Bitmap): Bitmap {
    val size = 128
    val scaled = source.scale(size, size)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    scaled.recycle()

    val tmp = IntArray(size * size)
    val radius = 10
    repeat(3) {
        boxBlurHorizontal(pixels, tmp, size, size, radius)
        boxBlurVertical(tmp, pixels, size, size, radius)
    }

    return createBitmap(size, size).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private fun computeTopBandRelativeLuminance(bitmap: Bitmap): Float {
    val w = bitmap.width.coerceAtLeast(1)
    val h = bitmap.height.coerceAtLeast(1)
    val bandRows = (h * 0.12f).roundToInt().coerceIn(1, h)
    val pixels = IntArray(w * bandRows)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, bandRows)
    var sum = 0.0
    for (p in pixels) {
        val r = linearizeChannel(((p ushr 16) and 0xFF) / 255.0)
        val g = linearizeChannel(((p ushr 8) and 0xFF) / 255.0)
        val b = linearizeChannel((p and 0xFF) / 255.0)
        sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    return (sum / pixels.size.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
}

private fun linearizeChannel(c: Double): Double =
    if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

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
