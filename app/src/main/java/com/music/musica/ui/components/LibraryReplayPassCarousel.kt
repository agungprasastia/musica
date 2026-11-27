package com.music.musica.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.musica.R
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.data.repository.ReplayPassSection
import com.music.musica.data.repository.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val ISO_CARD_RATIO = 1.586f
private val PassCardShape = RoundedCornerShape(22.dp)

private val PolishedInk = Brush.verticalGradient(
    listOf(Color(0xFFFFFFFF), Color(0xFFF5F6FA), Color(0xFFC8CCD8)),
)

private val EmbossShadow = Shadow(
    color = Color(0xA6000000),
    offset = Offset(0f, 2.5f),
    blurRadius = 4.5f,
)

private val MinutesFallbackPalette = listOf(
    Color(0xFFD81B4C),
    Color(0xFF6B21A8),
    Color(0xFF9F1239),
    Color(0xFF1A0D2E),
)

private val TopSongFallbackPalette = listOf(
    Color(0xFF8B2FC9),
    Color(0xFFD81B60),
    Color(0xFF4A148C),
    Color(0xFF180C2B),
)

private val TopArtistFallbackPalette = listOf(
    Color(0xFF7A5643),
    Color(0xFF3E3538),
    Color(0xFF9E6B4B),
    Color(0xFF191518),
)

private val TopAlbumFallbackPalette = listOf(
    Color(0xFF1E5AA8),
    Color(0xFF6A2C91),
    Color(0xFF0E7490),
    Color(0xFF11182E),
)

private val passPaletteCache = ConcurrentHashMap<String, List<Color>>()

@Immutable
internal data class ListeningPassCardModel(
    val section: ReplayPassSection,
    val kicker: String,
    val value: String,
    val detail: String,
    val artworkUrl: String?,
    val isCircularArt: Boolean,
    val fallbackPalette: List<Color>,
    val hueShiftDegrees: Float,
    val meshPhase: Float,
    val isNumericHero: Boolean,
)

/**
 * Horizontal snapping wallet of ISO 1.586:1 Listening Experience pass cards for LibraryScreen:
 * 1. Minutes Listened (signature Musica crimson-plum obsidian pass)
 * 2. Top Song (artwork-driven multi-swatch mesh + framed cover medallion)
 * 3. Top Artist (artwork-driven multi-swatch mesh + circular artist medallion)
 * 4. Top Album (artwork-driven multi-swatch mesh + framed album medallion)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryReplayPassCarousel(
    summary: ReplaySummary,
    listenerName: String?,
    memberSinceMs: Long?,
    reduceAnimation: Boolean,
    onOpenReplay: (ReplayPassSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = rememberPassCards(summary)
    val defaultHolder = stringResource(R.string.replay_pass_default_holder)
    val holderText = remember(listenerName, defaultHolder) {
        listenerName?.trim()?.takeIf { it.isNotBlank() } ?: defaultHolder
    }
    val memberSinceText = remember(memberSinceMs) {
        formatMemberSinceMmYy(memberSinceMs)
    }

    if (cards.size <= 1) {
        val onlyCard = cards.firstOrNull() ?: return
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val singleWidth = minOf(maxWidth, 380.dp)
            MusicaListeningPassCard(
                card = onlyCard,
                holder = holderText,
                memberSince = memberSinceText,
                reduceAnimation = reduceAnimation,
                onClick = { onOpenReplay(onlyCard.section) },
                modifier = Modifier.width(singleWidth),
            )
        }
        return
    }

    val rowState = rememberLazyListState()
    val snapFling = rememberSnapFlingBehavior(lazyListState = rowState)

    val activeIndex by remember(cards.size) {
        derivedStateOf {
            val info = rowState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf 0
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            visible.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index?.coerceIn(0, cards.lastIndex) ?: 0
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - 60.dp).coerceIn(272.dp, 326.dp)
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                flingBehavior = snapFling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(cards, key = { _, item -> item.section.name }) { index, card ->
                    MusicaListeningPassCard(
                        card = card,
                        holder = holderText,
                        memberSince = memberSinceText,
                        reduceAnimation = reduceAnimation,
                        onClick = { onOpenReplay(card.section) },
                        modifier = Modifier
                            .width(cardWidth)
                            .graphicsLayer {
                                if (reduceAnimation) return@graphicsLayer
                                val info = rowState.layoutInfo
                                val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                                    ?: return@graphicsLayer
                                val viewportWidth = (info.viewportEndOffset - info.viewportStartOffset)
                                    .coerceAtLeast(1)
                                val viewportCenter = info.viewportStartOffset + viewportWidth / 2f
                                val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                val normDist = (abs(itemCenter - viewportCenter) / (viewportWidth * 0.72f))
                                    .coerceIn(0f, 1f)
                                val focusScale = 1f - (0.036f * normDist)
                                scaleX = focusScale
                                scaleY = focusScale
                                alpha = 1f - (0.10f * normDist)
                            },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        PassPageIndicatorRow(
            count = cards.size,
            activeIndex = activeIndex,
            reduceAnimation = reduceAnimation,
        )
    }
}

@Composable
private fun rememberPassCards(summary: ReplaySummary): List<ListeningPassCardModel> {
    val locale = LocalLocale.current.platformLocale
    val minutesKicker = stringResource(R.string.replay_pass_minutes_kicker)
    val topSongKicker = stringResource(R.string.replay_pass_top_song_kicker)
    val topArtistKicker = stringResource(R.string.replay_pass_top_artist_kicker)
    val topAlbumKicker = stringResource(R.string.replay_pass_top_album_kicker)
    val allTimeLabel = stringResource(R.string.replay_pass_all_time_short)
    val emptyDetail = stringResource(R.string.replay_pass_empty_detail)

    val totalMinutes = summary.totalListenedMs / 60_000L
    val formattedMinutes = remember(totalMinutes, locale) {
        NumberFormat.getIntegerInstance(locale).format(totalMinutes)
    }

    val minutesDetail = if (summary.totalTracksPlayed > 0) {
        val playsLabel = stringResource(R.string.replay_pass_plays_short, summary.totalTracksPlayed)
        "$playsLabel · $allTimeLabel"
    } else {
        emptyDetail
    }

    val topSong = summary.topSongs.firstOrNull()
    val topSongDetail = topSong?.let { ranked ->
        val playsLabel = stringResource(R.string.replay_pass_plays_short, ranked.playCount)
        "${ranked.song.artistText} · $playsLabel"
    }

    val topArtist = summary.topArtists.firstOrNull()
    val topArtistDetail = topArtist?.let { artist ->
        val durationLabel = formatCompactListeningDuration(artist.totalListenedMs)
        val playsLabel = stringResource(R.string.replay_pass_plays_short, artist.playCount)
        "$durationLabel · $playsLabel"
    }

    val topAlbum = summary.topAlbums.firstOrNull()
    val topAlbumDetail = topAlbum?.let { album ->
        val durationLabel = formatCompactListeningDuration(album.totalListenedMs)
        val playsLabel = stringResource(R.string.replay_pass_plays_short, album.playCount)
        val lead = album.artistName?.takeIf { it.isNotBlank() } ?: durationLabel
        "$lead · $playsLabel"
    }

    return remember(
        summary,
        minutesKicker,
        topSongKicker,
        topArtistKicker,
        topAlbumKicker,
        formattedMinutes,
        minutesDetail,
        topSongDetail,
        topArtistDetail,
        topAlbumDetail,
    ) {
        buildList {
            add(
                ListeningPassCardModel(
                    section = ReplayPassSection.MINUTES,
                    kicker = minutesKicker,
                    value = formattedMinutes,
                    detail = minutesDetail,
                    artworkUrl = null,
                    isCircularArt = false,
                    fallbackPalette = MinutesFallbackPalette,
                    hueShiftDegrees = 0f,
                    meshPhase = 0.40f,
                    isNumericHero = true,
                ),
            )
            val songUrl = topSong?.song?.thumbnailUrl
            if (topSong != null && topSongDetail != null) {
                add(
                    ListeningPassCardModel(
                        section = ReplayPassSection.TOP_SONG,
                        kicker = topSongKicker,
                        value = topSong.song.title,
                        detail = topSongDetail,
                        artworkUrl = songUrl,
                        isCircularArt = false,
                        fallbackPalette = TopSongFallbackPalette,
                        hueShiftDegrees = 0f,
                        meshPhase = 1.65f,
                        isNumericHero = false,
                    ),
                )
            }
            if (topArtist != null && topArtistDetail != null) {
                val artistUrl = topArtist.thumbnailUrl
                val shift = if (artistUrl != null && artistUrl == songUrl) 34f else 0f
                add(
                    ListeningPassCardModel(
                        section = ReplayPassSection.TOP_ARTIST,
                        kicker = topArtistKicker,
                        value = topArtist.name,
                        detail = topArtistDetail,
                        artworkUrl = artistUrl,
                        isCircularArt = true,
                        fallbackPalette = TopArtistFallbackPalette,
                        hueShiftDegrees = shift,
                        meshPhase = 2.95f,
                        isNumericHero = false,
                    ),
                )
            }
            if (topAlbum != null && topAlbumDetail != null) {
                val albumUrl = topAlbum.thumbnailUrl
                val shift = if (albumUrl != null && (albumUrl == songUrl || albumUrl == topArtist?.thumbnailUrl)) {
                    -36f
                } else {
                    0f
                }
                add(
                    ListeningPassCardModel(
                        section = ReplayPassSection.TOP_ALBUM,
                        kicker = topAlbumKicker,
                        value = topAlbum.name,
                        detail = topAlbumDetail,
                        artworkUrl = albumUrl,
                        isCircularArt = false,
                        fallbackPalette = TopAlbumFallbackPalette,
                        hueShiftDegrees = shift,
                        meshPhase = 4.25f,
                        isNumericHero = false,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MusicaListeningPassCard(
    card: ListeningPassCardModel,
    holder: String,
    memberSince: String?,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberPassMeshColors(
        artworkUrl = card.artworkUrl,
        fallback = card.fallbackPalette,
        hueShiftDegrees = card.hueShiftDegrees,
    )
    val glowColor = palette.firstOrNull() ?: Color.Black
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceAnimation) 0.976f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "pass_press_scale",
    )

    val locale = LocalLocale.current.platformLocale
    val headerEyebrow = stringResource(R.string.replay_pass_eyebrow).uppercase(locale)
    val memberSinceLabel = stringResource(R.string.replay_pass_member_since).uppercase(locale)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .aspectRatio(ISO_CARD_RATIO)
            .shadow(
                elevation = 14.dp,
                shape = PassCardShape,
                clip = false,
                ambientColor = glowColor.copy(alpha = 0.36f),
                spotColor = glowColor.copy(alpha = 0.44f),
            )
            .clip(PassCardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        // Static per-card 4-blob mesh — zero per-frame blur invalidation during carousel scrolling
        MeshGradientBackground(
            colors = palette,
            driftKey = "${card.section.name}:${card.artworkUrl.orEmpty()}",
            reduceAnimation = true,
            initialPhase = card.meshPhase,
            blurRadius = 28.dp,
            scrimAlpha = 0f,
            modifier = Modifier.matchParentSize(),
        )

        // Contrast-anchoring depth scrim so embossed silver text stays legible on bright covers
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.14f),
                        0.48f to Color.Black.copy(alpha = 0.24f),
                        1.00f to Color.Black.copy(alpha = 0.56f),
                    ),
                ),
        )

        // Subtle brushed anodized specular sheen + top-left catchlight
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = 0.13f),
                        0.32f to Color.White.copy(alpha = 0.03f),
                        0.58f to Color.Transparent,
                        1.00f to Color.White.copy(alpha = 0.06f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.08f),
                    radius = size.width * 0.55f,
                ),
                center = Offset(size.width * 0.14f, size.height * 0.08f),
                radius = size.width * 0.55f,
            )
        }

        // Directional Liquid Glass specular rim border
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 0.9.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.22f),
                        ),
                    ),
                    shape = PassCardShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            // Top bar: Listening Experience eyebrow (left) + official Musica emblem (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = headerEyebrow,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 1.35.sp,
                        lineHeight = 13.sp,
                    ),
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                MusicaBrandEmblem(
                    color = Color.White.copy(alpha = 0.96f),
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // Hero middle band: headline stat or featured record + framed artwork medallion
            if (card.isNumericHero) {
                Text(
                    text = card.value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 33.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                        brush = PolishedInk,
                        shadow = EmbossShadow,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.kicker.uppercase(locale),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 1.8.sp,
                    ),
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = card.value,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 22.sp,
                                lineHeight = 25.sp,
                                fontWeight = FontWeight.ExtraBold,
                                brush = PolishedInk,
                                shadow = EmbossShadow,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = card.kicker.uppercase(locale),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.W700,
                                letterSpacing = 1.75.sp,
                            ),
                            color = Color.White.copy(alpha = 0.68f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!card.artworkUrl.isNullOrBlank()) {
                        PassArtworkMedallion(
                            url = card.artworkUrl,
                            circular = card.isCircularArt,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.85f))

            // Footer: Embossed cardholder + detail (left), Member Since MM/YY (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    EmbossedPassText(
                        text = holder.uppercase(locale),
                        fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = card.detail,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W500,
                        ),
                        color = Color.White.copy(alpha = 0.66f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (memberSince != null) {
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = memberSinceLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 7.5.sp,
                                lineHeight = 9.sp,
                                fontWeight = FontWeight.W700,
                                letterSpacing = 0.85.sp,
                            ),
                            color = Color.White.copy(alpha = 0.58f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                        EmbossedPassText(
                            text = memberSince,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PassArtworkMedallion(
    url: String,
    circular: Boolean,
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(11.dp)
    val modelUrl = remember(url) { upsizeArtworkUrl(url, 256) ?: url }
    Box(
        modifier = Modifier
            .size(48.dp)
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.30f), shape),
    ) {
        AsyncImage(
            model = modelUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmbossedPassText(
    text: String,
    fontSize: TextUnit,
) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.W700,
            fontSize = fontSize,
            letterSpacing = 1.55.sp,
            brush = PolishedInk,
            shadow = EmbossShadow,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PassPageIndicatorRow(
    count: Int,
    activeIndex: Int,
    reduceAnimation: Boolean,
) {
    val activeColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f)
    val inactiveColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { idx ->
            val isActive = idx == activeIndex
            val pillWidth by animateDpAsState(
                targetValue = if (isActive) 18.dp else 5.dp,
                animationSpec = if (reduceAnimation) tween(0) else spring(stiffness = Spring.StiffnessMediumLow),
                label = "pass_dot_w_$idx",
            )
            val pillColor by animateColorAsState(
                targetValue = if (isActive) activeColor else inactiveColor,
                animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 220),
                label = "pass_dot_c_$idx",
            )
            Box(
                modifier = Modifier
                    .height(5.dp)
                    .width(pillWidth)
                    .clip(CircleShape)
                    .background(pillColor),
            )
        }
    }
}

@Composable
private fun rememberPassMeshColors(
    artworkUrl: String?,
    fallback: List<Color>,
    hueShiftDegrees: Float,
): List<Color> {
    val context = LocalPlatformContext.current
    val cacheKey = remember(artworkUrl, hueShiftDegrees) {
        artworkUrl?.takeIf { it.isNotBlank() }?.let { "$it@${hueShiftDegrees.toInt()}" }
    }
    var extracted by remember(cacheKey, fallback) {
        mutableStateOf(cacheKey?.let { passPaletteCache[it] } ?: fallback)
    }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null || artworkUrl.isNullOrBlank()) {
            extracted = fallback
            return@LaunchedEffect
        }
        passPaletteCache[cacheKey]?.let {
            extracted = it
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.IO) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val preferred = upsizeArtworkUrl(artworkUrl, 128) ?: artworkUrl
                val candidates = listOf(preferred, artworkUrl).distinct()
                var bmp: Bitmap? = null
                for (candidate in candidates) {
                    val req = ImageRequest.Builder(context)
                        .data(candidate)
                        .size(64)
                        .allowHardware(false)
                        .build()
                    val res = loader.execute(req)
                    val decoded = (res as? SuccessResult)?.image?.toBitmap()
                    if (decoded != null) {
                        bmp = decoded
                        break
                    }
                }
                val bitmap = bmp ?: return@runCatching null
                val pal = Palette.from(bitmap).maximumColorCount(12).generate()
                val rawSwatches = listOfNotNull(
                    pal.vibrantSwatch?.rgb,
                    pal.darkVibrantSwatch?.rgb,
                    pal.mutedSwatch?.rgb,
                    pal.lightVibrantSwatch?.rgb,
                    pal.darkMutedSwatch?.rgb,
                    pal.dominantSwatch?.rgb,
                ).distinct()
                if (rawSwatches.isEmpty()) return@runCatching null

                val primary = Color(rawSwatches.first())
                val c0 = rawSwatches.getOrElse(0) { rawSwatches.first() }.let(::Color)
                val c1 = rawSwatches.getOrElse(1) { shiftHue(primary, 32f).toArgb() }.let(::Color)
                val c2 = rawSwatches.getOrElse(2) { shiftHue(primary, -34f).toArgb() }.let(::Color)
                val c3 = rawSwatches.getOrElse(3) { shiftHue(primary, 16f).toArgb() }.let(::Color)

                listOf(c0, c1, c2, c3).mapIndexed { idx, color ->
                    tunePassSwatch(
                        color = if (hueShiftDegrees != 0f) shiftHue(color, hueShiftDegrees) else color,
                        index = idx,
                    )
                }
            }.getOrNull()
        }
        if (computed != null) {
            if (passPaletteCache.size > 96) passPaletteCache.clear()
            passPaletteCache[cacheKey] = computed
            extracted = computed
        }
    }

    return extracted
}

private fun shiftHue(color: Color, deltaDegrees: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[0] = ((hsl[0] + deltaDegrees) % 360f + 360f) % 360f
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun tunePassSwatch(color: Color, index: Int): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    // Respect neutral/monochrome covers softly while enriching chromatic records
    val minSat = if (hsl[1] < 0.10f) hsl[1] else 0.34f
    hsl[1] = (hsl[1] * 1.15f).coerceIn(minSat, 0.86f)
    hsl[2] = when (index) {
        0 -> hsl[2].coerceIn(0.28f, 0.48f)
        1 -> hsl[2].coerceIn(0.22f, 0.40f)
        2 -> hsl[2].coerceIn(0.24f, 0.44f)
        else -> hsl[2].coerceIn(0.14f, 0.26f)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
internal fun formatCompactListeningDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).toInt().coerceAtLeast(0)
    return if (totalMinutes < 60) {
        stringResource(R.string.replay_pass_duration_min, totalMinutes)
    } else {
        stringResource(R.string.replay_pass_duration_hr_min, totalMinutes / 60, totalMinutes % 60)
    }
}

internal fun formatMemberSinceMmYy(timestampMs: Long?): String? {
    val ts = timestampMs?.takeIf { it > 0L } ?: return null
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val month = cal.get(Calendar.MONTH) + 1
    val yearTwoDigit = cal.get(Calendar.YEAR) % 100
    return String.format(Locale.ROOT, "%02d/%02d", month, yearTwoDigit)
}
