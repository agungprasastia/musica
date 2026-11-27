package com.music.musica.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.TopPickMood
import com.music.musica.core.model.smartCropArtworkUrl
import com.music.musica.core.model.upsizeArtworkUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val TOP_PICK_RATIO = 0.75f
private const val TOP_PICK_FRACTION = 0.60f
private val TOP_PICK_MAX_WIDTH = 280.dp
private val TopPickShape = RoundedCornerShape(12.dp)
private val PhotoEyebrowColor = Color.White.copy(alpha = 0.68f)

private const val STATION_PERIOD_MS = 9_000
private const val MOTION_FRAME_GAP_MS = 33L
private const val TAU = (2.0 * PI).toFloat()

private val RetainedArtLayer = Modifier.graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}

/**
 * Musica Home lead "Top Picks for You" 3:4 portrait editorial & station carousel:
 * - Supports all 7 distinct card treatments (Latest Release, Artist & Similar 3-circle collage,
 *   Mood for You animated vector emblem, Discovery Station sweeping shards, Personal Station
 *   folded chevrons, Featured Artist portrait, and New Release Mix blurred reflection)
 * - Vector animations read phase strictly in draw scope (0 recompositions) and pause automatically
 *   during scroll, when Home is obscured/backgrounded, or when Reduce Animation is enabled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopPicksCarousel(
    picks: List<TopPick>,
    listenerName: String?,
    onPickClick: (TopPick, String) -> Unit,
    modifier: Modifier = Modifier,
    loadingPickKey: String? = null,
    artworkMotionEnabled: Boolean = true,
    onPickLongClick: ((TopPick) -> Unit)? = null,
) {
    if (picks.isEmpty()) return
    val rowState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(
        lazyListState = rowState,
        snapPosition = SnapPosition.Start,
    )
    val isScrollingProvider = remember(rowState) { { rowState.isScrollInProgress } }

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(
            text = stringResource(R.string.home_top_picks),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * TOP_PICK_FRACTION, TOP_PICK_MAX_WIDTH)
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                flingBehavior = snapFling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(
                    items = picks,
                    key = { idx, pick -> "${idx}_${pick.stableKey}" },
                ) { _, pick ->
                    TopPickCard(
                        pick = pick,
                        listenerName = listenerName,
                        onClick = onPickClick,
                        modifier = Modifier
                            .width(cardWidth)
                            .testTag("top_pick_card_${pick.kind.name}"),
                        isLoading = loadingPickKey == pick.stableKey,
                        artworkMotionEnabled = artworkMotionEnabled,
                        isScrollingProvider = isScrollingProvider,
                        onLongClick = onPickLongClick?.takeIf { pick.supportsLongPress() },
                    )
                }
            }
        }
    }
}

/**
 * Matching 3:4 portrait shimmer placeholder while Top Picks resolves on a fresh cache miss.
 */
@Composable
fun TopPicksSkeleton(
    modifier: Modifier = Modifier,
    reduceAnimation: Boolean = false,
) {
    val shimmerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (reduceAnimation) 0.5f else 0.72f,
    )
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Box(
            modifier = Modifier
                .padding(start = 16.dp, bottom = 12.dp)
                .width(176.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmerColor),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * TOP_PICK_FRACTION, TOP_PICK_MAX_WIDTH)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(TOP_PICK_RATIO)
                            .clip(TopPickShape)
                            .background(shimmerColor),
                    )
                }
            }
        }
    }
}

private fun TopPick.supportsLongPress(): Boolean = when (kind) {
    TopPickKind.LATEST_RELEASE -> !browseId.isNullOrBlank()
    TopPickKind.FEATURED_ARTIST,
    TopPickKind.ARTIST_AND_SIMILAR,
    -> !artistBrowseId.isNullOrBlank()
    else -> false
}

private data class TopPickCaption(
    val eyebrow: String,
    val title: String,
    val subtitle: String,
)

@Composable
private fun rememberCardCaption(pick: TopPick, listenerName: String?): TopPickCaption {
    val artist = pick.artistName.orEmpty()
    return when (pick.kind) {
        TopPickKind.LATEST_RELEASE -> TopPickCaption(
            eyebrow = if (pick.isFallbackPick || artist.isBlank()) {
                artist.ifBlank { stringResource(R.string.home_top_picks) }
            } else {
                stringResource(R.string.top_pick_new_from, artist)
            },
            title = pick.title,
            subtitle = pick.subtitle,
        )
        TopPickKind.PERSONAL_STATION -> {
            val firstName = listenerName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            TopPickCaption(
                eyebrow = stringResource(R.string.top_pick_made_for_you),
                title = if (firstName != null) {
                    stringResource(R.string.top_pick_named_station, firstName)
                } else {
                    stringResource(R.string.top_pick_your_station)
                },
                subtitle = "",
            )
        }
        TopPickKind.MOOD -> {
            val mood = pick.mood ?: TopPickMood.FEEL_GOOD
            TopPickCaption(
                eyebrow = stringResource(R.string.top_pick_mood_for_you),
                title = stringResource(mood.titleRes()),
                subtitle = stringResource(mood.lineRes()),
            )
        }
        TopPickKind.ARTIST_AND_SIMILAR -> TopPickCaption(
            eyebrow = stringResource(R.string.top_pick_featuring, artist),
            title = stringResource(R.string.top_pick_and_similar, artist),
            subtitle = "",
        )
        TopPickKind.FEATURED_ARTIST -> TopPickCaption(
            eyebrow = stringResource(R.string.top_pick_featuring, artist),
            title = pick.title.ifBlank { artist },
            subtitle = pick.subtitle,
        )
        TopPickKind.NEW_RELEASE_MIX -> TopPickCaption(
            eyebrow = stringResource(R.string.top_pick_made_for_you),
            title = pick.title,
            subtitle = pick.subtitle,
        )
        TopPickKind.DISCOVERY_STATION -> TopPickCaption(
            eyebrow = stringResource(R.string.top_pick_made_for_you),
            title = stringResource(R.string.top_pick_discovery_station),
            subtitle = "",
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopPickCard(
    pick: TopPick,
    listenerName: String?,
    onClick: (TopPick, String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    artworkMotionEnabled: Boolean = true,
    isScrollingProvider: () -> Boolean = { false },
    onLongClick: ((TopPick) -> Unit)? = null,
) {
    val caption = rememberCardCaption(pick, listenerName)
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .aspectRatio(TOP_PICK_RATIO)
            .clip(TopPickShape)
            .thumbnailBorder(TopPickShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { onClick(pick, caption.title) },
                onLongClick = onLongClick?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler(pick)
                    }
                },
            ),
    ) {
        val eyebrowColor: Color
        val branded: Boolean

        when (pick.kind) {
            TopPickKind.PERSONAL_STATION -> {
                PersonalStationArt(
                    motionEnabled = artworkMotionEnabled,
                    isScrollingProvider = isScrollingProvider,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = Color(0xFFFFD2DA)
                branded = true
            }
            TopPickKind.DISCOVERY_STATION -> {
                DiscoveryStationArt(
                    motionEnabled = artworkMotionEnabled,
                    isScrollingProvider = isScrollingProvider,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = Color(0xFFCDBDFF)
                branded = true
            }
            TopPickKind.MOOD -> {
                val mood = pick.mood ?: TopPickMood.FEEL_GOOD
                val style = mood.style()
                MoodCardArt(
                    mood = mood,
                    style = style,
                    motionEnabled = artworkMotionEnabled,
                    isScrollingProvider = isScrollingProvider,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = style.eyebrow
                branded = true
            }
            TopPickKind.ARTIST_AND_SIMILAR -> {
                val palette = collagePaletteFor(pick.artistName.orEmpty())
                ArtistCollageArt(
                    images = pick.images,
                    palette = palette,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = palette.eyebrow
                branded = true
            }
            TopPickKind.LATEST_RELEASE,
            TopPickKind.NEW_RELEASE_MIX,
            -> {
                ReleaseReflectionArt(
                    imageUrl = pick.images.firstOrNull(),
                    contentDescription = caption.title,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = PhotoEyebrowColor
                branded = false
            }
            TopPickKind.FEATURED_ARTIST -> {
                FeaturedArtistArt(
                    imageUrl = pick.images.firstOrNull(),
                    contentDescription = caption.title,
                    modifier = Modifier.fillMaxSize(),
                )
                eyebrowColor = PhotoEyebrowColor
                branded = false
            }
        }

        if (branded) {
            MusicaBrandEmblem(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(20.dp),
            )
        }

        TopPickCardCaption(
            caption = caption,
            eyebrowColor = eyebrowColor,
            modifier = Modifier.align(Alignment.BottomStart),
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun TopPickCardCaption(
    caption: TopPickCaption,
    eyebrowColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 15.dp),
    ) {
        Text(
            text = caption.eyebrow,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 16.sp,
            ),
            color = eyebrowColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = caption.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption.subtitle.isNotBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = caption.subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Photo & Release Card Treatments ----------------------------------------

@Composable
private fun ReleaseReflectionArt(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var useRawFallback by remember(imageUrl) { mutableStateOf(false) }
    val coverUrl = remember(imageUrl, useRawFallback) {
        if (useRawFallback) imageUrl else upsizeArtworkUrl(imageUrl, 544)
    }
    val blurredReflection = rememberBlurredArtwork(imageUrl, radius = 4)

    Box(modifier = modifier.background(Color.Black)) {
        if (blurredReflection != null) {
            Image(
                bitmap = blurredReflection,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.46f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.56f to Color.Black.copy(alpha = 0.08f),
                        1.00f to Color.Black.copy(alpha = 0.44f),
                    ),
                ),
        )
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = {
                    if (!useRawFallback && coverUrl != imageUrl) {
                        useRawFallback = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.Black,
                                0.76f to Color.Black,
                                1.00f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
    }
}

@Composable
private fun FeaturedArtistArt(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var useRawFallback by remember(imageUrl) { mutableStateOf(false) }
    val portraitUrl = remember(imageUrl, useRawFallback) {
        if (useRawFallback) imageUrl else smartCropArtworkUrl(imageUrl, widthPx = 600, heightPx = 800)
    }

    Box(modifier = modifier.background(Color(0xFF1A1A1E))) {
        if (!portraitUrl.isNullOrBlank()) {
            AsyncImage(
                model = portraitUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = {
                    if (!useRawFallback && portraitUrl != imageUrl) {
                        useRawFallback = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.45f),
                        1.00f to Color.Black.copy(alpha = 0.82f),
                    ),
                ),
        )
    }
}

// ---- Artist & Similar Artists Collage ---------------------------------------

private data class CollagePalette(
    val top: Color,
    val bottom: Color,
    val eyebrow: Color,
)

private val CollagePalettes = listOf(
    CollagePalette(Color(0xFF8F3F00), Color(0xFFFF6A0F), Color(0xFFFFC48A)),
    CollagePalette(Color(0xFF2D8A20), Color(0xFF4FD22C), Color(0xFFCBFFA3)),
    CollagePalette(Color(0xFF173E9E), Color(0xFF2F7BFF), Color(0xFFC0D8FF)),
    CollagePalette(Color(0xFF4A1E99), Color(0xFF8A4BFF), Color(0xFFDCC8FF)),
    CollagePalette(Color(0xFF0B6E6E), Color(0xFF14B8A6), Color(0xFFB5FFF3)),
    CollagePalette(Color(0xFF8E1426), Color(0xFFF0364F), Color(0xFFFFC4CC)),
)

private fun collagePaletteFor(artistName: String): CollagePalette {
    val idx = abs(artistName.hashCode().toLong() % CollagePalettes.size).toInt()
    return CollagePalettes[idx]
}

@Composable
private fun ArtistCollageArt(
    images: List<String>,
    palette: CollagePalette,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(palette.top, palette.bottom)),
        ),
    ) {
        val w = maxWidth
        val h = maxHeight
        val slots = when (images.size) {
            0 -> emptyList()
            1 -> listOf(Triple(0.50f, 0.34f, 0.66f))
            2 -> listOf(Triple(0.40f, 0.28f, 0.60f), Triple(0.72f, 0.52f, 0.36f))
            else -> listOf(
                Triple(0.37f, 0.257f, 0.594f),
                Triple(0.76f, 0.480f, 0.333f),
                Triple(0.457f, 0.608f, 0.290f),
            )
        }
        slots.zip(images).forEach { (slot, rawUrl) ->
            val (cx, cy, diameterFraction) = slot
            val diameter = w * diameterFraction
            val targetPx = if (diameterFraction > 0.5f) 544 else 320
            var useRawFallback by remember(rawUrl) { mutableStateOf(false) }
            val resolvedUrl = remember(rawUrl, useRawFallback) {
                if (useRawFallback) rawUrl else smartCropArtworkUrl(rawUrl, targetPx, targetPx)
            }
            AsyncImage(
                model = resolvedUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = {
                    if (!useRawFallback && resolvedUrl != rawUrl) {
                        useRawFallback = true
                    }
                },
                modifier = Modifier
                    .offset(x = w * cx - diameter / 2, y = h * cy - diameter / 2)
                    .size(diameter)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
    }
}

// ---- Animation Loop & Geometry Caches ---------------------------------------

@Composable
private fun rememberLoopPhase(
    periodMs: Int,
    motionEnabled: Boolean,
    isScrollingProvider: () -> Boolean,
): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motionEnabled, periodMs, isScrollingProvider) {
        if (!motionEnabled) return@LaunchedEffect
        var lastNanos = Long.MIN_VALUE
        while (true) {
            if (isScrollingProvider()) {
                snapshotFlow { isScrollingProvider() }.first { !it }
                lastNanos = Long.MIN_VALUE
            }
            withFrameNanos { now ->
                if (lastNanos != Long.MIN_VALUE) {
                    val delta = (now - lastNanos) / (periodMs * 1_000_000f)
                    phase.floatValue = (phase.floatValue + delta) % 1f
                }
                lastNanos = now
            }
            delay(MOTION_FRAME_GAP_MS)
        }
    }
    return phase
}

private fun sineWave(phase: Float, offset: Float): Float = sin(TAU * (phase + offset))

private class SizeScopedCache<T : Any>(private val build: (Size) -> T) {
    private var cachedSize = Size.Unspecified
    private var cachedValue: T? = null

    fun get(size: Size): T {
        val current = cachedValue
        if (current != null && size == cachedSize) return current
        cachedSize = size
        return build(size).also { cachedValue = it }
    }
}

// ---- Personal Station Vector Art --------------------------------------------

private val PersonalChevronLayers = listOf(
    Triple(1.00f, Color(0xFFFE5E83), Color(0xFFFA6196)),
    Triple(0.66f, Color(0xFFFE5065), Color(0xFFFC657C)),
    Triple(0.43f, Color(0xFFFB594C), Color(0xFFFC776D)),
    Triple(0.21f, Color(0xFFFB6729), Color(0xFFF98A48)),
)

private class PersonalStationBrushes(
    val background: Brush,
    val layers: List<Brush>,
)

@Composable
private fun PersonalStationArt(
    motionEnabled: Boolean,
    isScrollingProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val phaseState = rememberLoopPhase(STATION_PERIOD_MS, motionEnabled, isScrollingProvider)
    val path = remember { Path() }
    val brushes = remember {
        SizeScopedCache { size ->
            PersonalStationBrushes(
                background = Brush.horizontalGradient(
                    0.00f to Color(0xFFF7334F),
                    0.38f to Color(0xFFFA6094),
                    0.75f to Color(0xFFF02A4A),
                    1.00f to Color(0xFFEE213D),
                ),
                layers = PersonalChevronLayers.map { (rest, deep, light) ->
                    Brush.horizontalGradient(
                        colors = listOf(deep, light),
                        startX = 0f,
                        endX = size.width * rest,
                    )
                },
            )
        }
    }

    Canvas(modifier = modifier.then(RetainedArtLayer)) {
        val phase = phaseState.value
        val w = size.width
        val h = size.height
        val cached = brushes.get(size)
        drawRect(cached.background)
        PersonalChevronLayers.forEachIndexed { idx, (rest, _, _) ->
            val tip = if (idx == 0) rest else rest + 0.07f * sineWave(phase, idx * 0.19f)
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(w * tip, h / 2f)
            path.lineTo(0f, h)
            path.close()
            drawPath(path, cached.layers[idx])
        }
    }
}

// ---- Discovery Station Vector Art -------------------------------------------

private val DiscoveryTopShardColors = listOf(
    Color(0xFF3A2496),
    Color(0xFF2F4FB0),
    Color(0xFF3B30A0),
    Color(0xFF2E7FB8),
    Color(0xFF27988A),
    Color(0xFF3C8DA8),
)

private val DiscoveryBottomShardColors = listOf(
    Color(0xFF3A2496),
    Color(0xFF333EA4),
    Color(0xFF2F62B6),
    Color(0xFF288B99),
    Color(0xFF3477BA),
    Color(0xFF42A296),
)

private val DiscoveryShardEdges = listOf(0f, 0.24f, 0.44f, 0.62f, 0.78f, 0.91f, 1.02f)
private val DiscoveryInnerArrowTint = Color(0xFF3C1981).copy(alpha = 0.35f)

private class DiscoveryStationBrushes(
    val background: Brush,
    val topShards: List<Brush>,
    val bottomShards: List<Brush>,
    val arrow: Brush,
)

@Composable
private fun DiscoveryStationArt(
    motionEnabled: Boolean,
    isScrollingProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val phaseState = rememberLoopPhase(STATION_PERIOD_MS, motionEnabled, isScrollingProvider)
    val path = remember { Path() }
    val brushes = remember {
        SizeScopedCache { size ->
            val apex = Offset(size.width, size.height / 2f)
            fun buildShards(edgeY: Float, palette: List<Color>): List<Brush> =
                (0 until DiscoveryShardEdges.lastIndex).map { i ->
                    val midX = size.width * (DiscoveryShardEdges[i] + DiscoveryShardEdges[i + 1]) / 2f
                    val base = palette[i % palette.size]
                    Brush.linearGradient(
                        colors = listOf(lerp(base, Color.White, 0.10f), base),
                        start = apex,
                        end = Offset(midX, edgeY),
                    )
                }
            DiscoveryStationBrushes(
                background = Brush.linearGradient(
                    colors = listOf(Color(0xFF2A869F), Color(0xFF3477BA)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                topShards = buildShards(0f, DiscoveryTopShardColors),
                bottomShards = buildShards(size.height, DiscoveryBottomShardColors),
                arrow = Brush.horizontalGradient(
                    0.00f to Color(0xFF502798),
                    0.55f to Color(0xFF421C88),
                    1.00f to Color(0xFF4A2292),
                ),
            )
        }
    }

    Canvas(modifier = modifier.then(RetainedArtLayer)) {
        val phase = phaseState.value
        val w = size.width
        val h = size.height
        val cached = brushes.get(size)
        drawRect(cached.background)

        fun drawFan(edgeY: Float, shards: List<Brush>, phaseOffset: Float) {
            var prev = DiscoveryShardEdges[0]
            for (i in 1..DiscoveryShardEdges.lastIndex) {
                val rest = DiscoveryShardEdges[i]
                val next = if (i == DiscoveryShardEdges.lastIndex) {
                    rest
                } else {
                    rest + 0.06f * sineWave(phase, phaseOffset + i * 0.11f)
                }
                path.reset()
                path.moveTo(w, h / 2f)
                path.lineTo(w * prev, edgeY)
                path.lineTo(w * next, edgeY)
                path.close()
                drawPath(path, shards[i - 1])
                prev = next
            }
        }
        drawFan(0f, cached.topShards, 0f)
        drawFan(h, cached.bottomShards, 0.5f)

        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(w, h / 2f)
        path.lineTo(0f, h)
        path.close()
        drawPath(path, cached.arrow)

        val inset = 0.08f + 0.04f * sineWave(phase, 0.25f)
        path.reset()
        path.moveTo(0f, h * inset)
        path.lineTo(w * (0.90f - inset), h / 2f)
        path.lineTo(0f, h * (1f - inset))
        path.close()
        drawPath(path, DiscoveryInnerArrowTint)
    }
}

// ---- Mood for You Vector Art (7 Core Moods) ---------------------------------

private data class MoodVisualStyle(
    val background: Color,
    val stroke: Color,
    val periodMs: Int,
    val eyebrow: Color,
)

private fun TopPickMood.style(): MoodVisualStyle = when (this) {
    TopPickMood.ENERGY -> MoodVisualStyle(Color(0xFF1EBD38), Color(0xFFE2F6A2), 3_000, Color(0xFFE8FF9C))
    TopPickMood.RELAX -> MoodVisualStyle(Color(0xFF31AFF3), Color(0xFFB4E9FF), 5_600, Color(0xFFD6F3FF))
    TopPickMood.FEELING_BLUE -> MoodVisualStyle(Color(0xFF253AB2), Color(0xFF6CC4FF), 3_700, Color(0xFFA9DBFF))
    TopPickMood.FEEL_GOOD -> MoodVisualStyle(Color(0xFFF2A900), Color(0xFFFFE8A0), 4_000, Color(0xFFFFF27A))
    TopPickMood.FOCUS -> MoodVisualStyle(Color(0xFFF28400), Color(0xFFFFD6A4), 3_600, Color(0xFFFFE2B8))
    TopPickMood.HEARTBREAK -> MoodVisualStyle(Color(0xFFEA3520), Color(0xFFFFA690), 2_700, Color(0xFFFFC9BC))
    TopPickMood.LOVE -> MoodVisualStyle(Color(0xFFF34CA0), Color(0xFFFFB8DC), 1_700, Color(0xFFFFD0EA))
}

@StringRes
private fun TopPickMood.titleRes(): Int = when (this) {
    TopPickMood.ENERGY -> R.string.mood_energy
    TopPickMood.RELAX -> R.string.mood_relax
    TopPickMood.FEELING_BLUE -> R.string.mood_feeling_blue
    TopPickMood.FEEL_GOOD -> R.string.mood_feel_good
    TopPickMood.FOCUS -> R.string.mood_focus
    TopPickMood.HEARTBREAK -> R.string.mood_heartbreak
    TopPickMood.LOVE -> R.string.mood_love
}

@StringRes
private fun TopPickMood.lineRes(): Int = when (this) {
    TopPickMood.ENERGY -> R.string.mood_energy_line
    TopPickMood.RELAX -> R.string.mood_relax_line
    TopPickMood.FEELING_BLUE -> R.string.mood_feeling_blue_line
    TopPickMood.FEEL_GOOD -> R.string.mood_feel_good_line
    TopPickMood.FOCUS -> R.string.mood_focus_line
    TopPickMood.HEARTBREAK -> R.string.mood_heartbreak_line
    TopPickMood.LOVE -> R.string.mood_love_line
}

private class EmblemFrame(val left: Float, val top: Float, val side: Float) {
    fun x(f: Float): Float = left + side * f
    fun y(f: Float): Float = top + side * f
    val center: Offset get() = Offset(x(0.5f), y(0.5f))
}

private class MoodEmblemCache {
    private var builtFor = Size.Unspecified
    private val outline = Path()
    val segment = Path()
    val measure = PathMeasure()
    var pathLength = 0f
        private set
    private var strokeWidth = -1f
    private var cachedStroke = Stroke()

    fun outline(size: Size, measured: Boolean = false, build: (Path) -> Unit): Path {
        if (size != builtFor) {
            outline.reset()
            build(outline)
            if (measured) {
                measure.setPath(outline, false)
                pathLength = measure.length
            }
            builtFor = size
        }
        return outline
    }

    fun stroke(widthPx: Float): Stroke {
        if (widthPx != strokeWidth) {
            strokeWidth = widthPx
            cachedStroke = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        return cachedStroke
    }
}

@Composable
private fun MoodCardArt(
    mood: TopPickMood,
    style: MoodVisualStyle,
    motionEnabled: Boolean,
    isScrollingProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val phaseState = rememberLoopPhase(style.periodMs, motionEnabled, isScrollingProvider)
    val cache = remember(mood) { MoodEmblemCache() }

    Canvas(modifier = modifier.then(RetainedArtLayer)) {
        drawRect(style.background)
        val w = size.width
        val h = size.height
        val side = minOf(w * 0.84f, h * 0.62f)
        val frame = EmblemFrame(
            left = (w - side) / 2f,
            top = h * 0.37f - side / 2f,
            side = side,
        )
        val stroke = cache.stroke(2.5.dp.toPx())
        drawMoodEmblem(
            mood = mood,
            frame = frame,
            phase = phaseState.value,
            color = style.stroke,
            stroke = stroke,
            cache = cache,
        )
    }
}

private fun DrawScope.drawMoodEmblem(
    mood: TopPickMood,
    frame: EmblemFrame,
    phase: Float,
    color: Color,
    stroke: Stroke,
    cache: MoodEmblemCache,
) {
    val side = frame.side
    when (mood) {
        TopPickMood.ENERGY -> {
            val boltPath = cache.outline(size) { buildBoltPath(it, frame) }
            val t = (phase - 0.28f) / 0.26f
            val flash = if (t in 0f..1f) sin(PI.toFloat() * t) else 0f
            if (flash > 0f) {
                translate(left = side * 0.07f * flash) {
                    drawPath(boltPath, color.copy(alpha = 0.55f * flash), style = stroke)
                }
                translate(left = -side * 0.05f * flash) {
                    drawPath(boltPath, color.copy(alpha = 0.35f * flash), style = stroke)
                }
            }
            drawPath(boltPath, color, style = stroke)
        }
        TopPickMood.RELAX -> {
            val stones = listOf(
                Triple(0.17f, 0.44f, 0.18f),
                Triple(0.38f, 0.68f, 0.28f),
                Triple(0.66f, 0.84f, 0.36f),
            )
            stones.forEachIndexed { idx, (cy, wFrac, hFrac) ->
                val sway = sineWave(phase, idx * 0.22f)
                val center = Offset(frame.x(0.5f + 0.015f * sway), frame.y(cy))
                rotate(degrees = 4f * sway, pivot = center) {
                    drawOval(
                        color = color,
                        topLeft = Offset(center.x - side * wFrac / 2f, center.y - side * hFrac / 2f),
                        size = Size(side * wFrac, side * hFrac),
                        style = stroke,
                    )
                }
            }
        }
        TopPickMood.FEELING_BLUE -> {
            cache.outline(size, measured = true) { buildSpiralPath(it, frame) }
            val totalLen = cache.pathLength
            val from: Float
            val to: Float
            when {
                phase < 0.35f -> {
                    from = 0f
                    to = totalLen
                }
                phase < 0.80f -> {
                    from = totalLen * easeInOut((phase - 0.35f) / 0.45f)
                    to = totalLen
                }
                else -> {
                    from = 0f
                    to = totalLen * easeInOut((phase - 0.80f) / 0.20f)
                }
            }
            cache.segment.reset()
            if (to > from) {
                cache.measure.getSegment(from, to, cache.segment, true)
            }
            drawPath(cache.segment, color, style = stroke)
        }
        TopPickMood.FEEL_GOOD -> {
            val starPath = cache.outline(size) { buildStarPath(it, frame) }
            val pulse = 0.90f + 0.10f * (0.5f + 0.5f * cos(TAU * phase))
            withTransform({
                rotate(8f * sineWave(phase, 0f), frame.center)
                scale(pulse, pulse, frame.center)
            }) {
                drawPath(starPath, color, style = stroke)
            }
        }
        TopPickMood.FOCUS -> {
            for (i in 0 until 4) {
                val share = (i + 1 - phase) / 4f
                drawCircle(
                    color = color.copy(alpha = ringEdgeFade(share)),
                    radius = side * 0.5f * share,
                    center = frame.center,
                    style = stroke,
                )
            }
        }
        TopPickMood.HEARTBREAK -> {
            val heartPath = cache.outline(size) { buildHeartPath(it, frame) }
            val dash = side * 0.075f
            val gap = side * 0.055f
            drawPath(
                path = heartPath,
                color = color,
                style = Stroke(
                    width = stroke.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dash, gap),
                        -phase * (dash + gap) * 2f,
                    ),
                ),
            )
        }
        TopPickMood.LOVE -> {
            val heartPath = cache.outline(size) { buildHeartPath(it, frame) }
            fun bump(at: Float): Float {
                val d = (phase - at) / 0.06f
                return exp(-d * d)
            }
            val beat = 1f + 0.07f * (bump(0.15f) + 0.7f * bump(0.38f))
            scale(beat, beat, frame.center) {
                drawPath(heartPath, color, style = stroke)
            }
        }
    }
}

private fun ringEdgeFade(share: Float): Float = when {
    share > 0.85f -> (1f - share) / 0.15f
    share < 0.15f -> share / 0.15f
    else -> 1f
}.coerceIn(0f, 1f)

private fun easeInOut(t: Float): Float = 0.5f - 0.5f * cos(PI.toFloat() * t.coerceIn(0f, 1f))

private fun buildBoltPath(path: Path, f: EmblemFrame) {
    val pts = listOf(
        0.486f to 0.123f,
        0.886f to 0.123f,
        0.532f to 0.509f,
        0.877f to 0.509f,
        0.114f to 0.895f,
        0.409f to 0.614f,
        0.105f to 0.614f,
    )
    path.reset()
    pts.forEachIndexed { idx, (x, y) ->
        if (idx == 0) path.moveTo(f.x(x), f.y(y)) else path.lineTo(f.x(x), f.y(y))
    }
    path.close()
}

private fun buildStarPath(path: Path, f: EmblemFrame) {
    path.reset()
    path.moveTo(f.x(0.5f), f.y(0.06f))
    path.quadraticTo(f.x(0.56f), f.y(0.44f), f.x(0.94f), f.y(0.5f))
    path.quadraticTo(f.x(0.56f), f.y(0.56f), f.x(0.5f), f.y(0.94f))
    path.quadraticTo(f.x(0.44f), f.y(0.56f), f.x(0.06f), f.y(0.5f))
    path.quadraticTo(f.x(0.44f), f.y(0.44f), f.x(0.5f), f.y(0.06f))
    path.close()
}

private fun buildHeartPath(path: Path, f: EmblemFrame) {
    val r = 0.24f
    path.reset()
    path.moveTo(f.x(0.5f), f.y(0.92f))
    path.lineTo(f.x(0.28f - r * 0.707f), f.y(0.30f + r * 0.707f))
    path.arcTo(
        Rect(f.x(0.28f - r), f.y(0.30f - r), f.x(0.28f + r), f.y(0.30f + r)),
        startAngleDegrees = 135f,
        sweepAngleDegrees = 195f,
        forceMoveTo = false,
    )
    path.arcTo(
        Rect(f.x(0.72f - r), f.y(0.30f - r), f.x(0.72f + r), f.y(0.30f + r)),
        startAngleDegrees = 210f,
        sweepAngleDegrees = 195f,
        forceMoveTo = false,
    )
    path.close()
}

private fun buildSpiralPath(path: Path, f: EmblemFrame) {
    val turns = 5.5f
    val steps = 320
    path.reset()
    for (k in 0..steps) {
        val t = k.toFloat() / steps
        val angle = PI.toFloat() + t * turns * TAU
        val rx = 0.38f * (1f - 0.72f * t)
        val ry = 0.075f * (1f - 0.55f * t)
        val px = f.x(0.5f + rx * cos(angle))
        val py = f.y(0.10f + 0.78f * t + ry * sin(angle))
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
}
