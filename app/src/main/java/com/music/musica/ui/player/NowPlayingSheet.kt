package com.music.musica.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.music.musica.R
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.core.model.formatDuration
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricsDisplayMode
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.lyrics.SyncedLyricsParser
import com.music.musica.playback.AudioOutputDevice
import com.music.musica.playback.AudioOutputKind
import com.music.musica.playback.PlaybackProgress
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.components.ExplicitBadge
import com.music.musica.ui.components.PlayerBackdrop
import com.music.musica.ui.components.ThinSlider
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.components.rememberArtworkTopBandLuminance
import com.music.musica.ui.components.thumbnailBorder
import com.music.musica.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class NowPlayingTab {
    ARTWORK,
    LYRICS,
    QUEUE,
}

private const val COLLAPSE_DURATION_MS = 420
private const val LYRICS_DECK_IDLE_HIDE_MS = 5_000L
private const val UNAVAILABLE_FADE_MS = 5_000L
private val TOP_STRIP_HEIGHT = 44.dp
private val COLLAPSED_THUMB_SIZE = 54.dp
private val COLLAPSED_HEADER_GAP = 12.dp
private val CREDITS_COLLAPSED_INSET = COLLAPSED_THUMB_SIZE + COLLAPSED_HEADER_GAP // 66.dp
private val LYRIC_STRIP_HEIGHT = 34.dp

/**
 * Full-bleed editorial Now Playing sheet with:
 * - Vertically aligned 6x6 seam mesh backdrop (crossfading to 128px CPU blur in Lyrics/Queue)
 * - Adaptive 8-step top status-bar scrim driven by cover top-band WCAG relative luminance
 * - Top 44dp dismiss strip with grab handle & tappable `"Playing from <source>"` caption
 * - Single-node zero-recomposition 420ms shared-element collapse (`ARTWORK <-> LYRICS/QUEUE`)
 * - Hold-over artwork memory-cache key so skips never flash blank
 * - Interactive upward swipe for Queue, downward drag to dismiss, horizontal swipe to skip with `SongInfoTransition`
 * - Credits row with `ExplicitBadge`, fading marquee title, individually tappable artists, and
 *   `GlassDiscPair` (**Star** favorite + context-aware **More / Share / Close**)
 * - Fixed-height `CurrentLyricStrip` (12 playful loading/intro lines, fading unavailable, unsynced,
 *   instrumental, 60fps `SweptLyricLine` active line, & underlined `"Lyrics by ..."` link in Lyrics)
 * - Auto-hiding lower deck (`SlidingPlayerDeck`) in Lyrics mode (5s idle / scroll-up / scroll-down / tap)
 * - Tactile Play/Pause cross-blur spring + double-arrowhead conveyor Skip buttons
 * - Hardware `VolumeControlRow` + 3-slot dock: **Lyrics cutout tile | Audio Output (+ device label) <-> Shuffle/Repeat/Autoplay | Queue**
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingSheet(
    playerState: PlayerUiState,
    progress: PlaybackProgress,
    isLiked: Boolean,
    lyricsState: UiState<LyricsResult?> = UiState.Success(null),
    lyricsDisplayMode: LyricsDisplayMode = LyricsDisplayMode.ORIGINAL,
    lyricsTranslationNotice: String? = null,
    activeAudioOutput: AudioOutputDevice? = null,
    playerTheme: PlayerTheme = PlayerTheme.DEFAULT,
    fullBleedCoverArt: Boolean = true,
    showVolumeSlider: Boolean = true,
    showRemainingTime: Boolean = true,
    showLyricsPreviewStrip: Boolean = true,
    reduceAnimation: Boolean,
    playerHazeState: HazeState? = null,
    lyricsPickingActive: Boolean = false,
    onLyricsPickingChange: (Boolean) -> Unit = {},
    lyricsContent: @Composable (modifier: Modifier, captureBackdrop: suspend () -> Bitmap?) -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleAutoplay: (Boolean) -> Unit,
    onToggleRemainingTime: () -> Unit = {},
    onSelectQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearUpcomingQueue: () -> Unit = {},
    onGoToArtist: ((String) -> Unit)? = null,
    onGoToAlbum: ((String) -> Unit)? = null,
    onOpenSourceBrowse: ((String) -> Unit)? = null,
    onOpenMoreActions: (Song) -> Unit,
    onOpenLyricsProviders: () -> Unit = {},
    onOpenAudioOutput: () -> Unit = {},
    onOpenStatsForNerds: () -> Unit,
) {
    val song = playerState.currentSong ?: return
    val palette = rememberArtworkPalette(
        imageUrl = song.thumbnailUrl,
        dark = true,
        reduceAnimation = reduceAnimation,
    )
    val topBandLuminance = rememberArtworkTopBandLuminance(song.thumbnailUrl)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val platformContext = LocalPlatformContext.current
    val backdropLayer = rememberGraphicsLayer()

    var activeTab by remember { mutableStateOf(NowPlayingTab.ARTWORK) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var lyricsDeckVisible by remember { mutableStateOf(true) }
    var lyricsDeckInteractionTick by remember { mutableIntStateOf(0) }
    var lastSwipeDirection by remember { mutableIntStateOf(0) }

    // Hold-over artwork key so skips keep the outgoing cover until the incoming cover is ready
    var lastLoadedArtworkKey by remember { mutableStateOf<MemoryCache.Key?>(null) }

    val dismissOffsetY = remember { Animatable(0f) }
    val artSwipeX = remember { Animatable(0f) }
    val collapseAnim = remember { Animatable(0f) }
    val queuePullAnim = remember { Animatable(0f) }

    val dismissThresholdPx = with(density) { 150.dp.toPx() }
    val skipThresholdPx = with(density) { 72.dp.toPx() }
    val queuePullRangePx = with(density) { 210.dp.toPx() }

    val targetCollapse = if (activeTab != NowPlayingTab.ARTWORK) 1f else 0f
    LaunchedEffect(targetCollapse, reduceAnimation) {
        if (reduceAnimation) {
            collapseAnim.snapTo(targetCollapse)
        } else {
            collapseAnim.animateTo(
                targetValue = targetCollapse,
                animationSpec = tween(durationMillis = COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
            )
        }
        if (targetCollapse == 0f) {
            queuePullAnim.snapTo(0f)
        }
    }

    // Reset picking & lower deck visibility cleanly when leaving LYRICS
    LaunchedEffect(activeTab) {
        if (activeTab != NowPlayingTab.LYRICS) {
            lyricsDeckVisible = true
            if (lyricsPickingActive) onLyricsPickingChange(false)
        } else {
            lyricsDeckVisible = true
            lyricsDeckInteractionTick++
        }
    }

    // 5-second idle auto-hide for the lower deck while reading Lyrics (paused while scrubbing or picking)
    LaunchedEffect(activeTab, lyricsDeckVisible, lyricsDeckInteractionTick, scrubbing, lyricsPickingActive) {
        if (activeTab == NowPlayingTab.LYRICS && lyricsDeckVisible && !scrubbing && !lyricsPickingActive) {
            delay(LYRICS_DECK_IDLE_HIDE_MS)
            lyricsDeckVisible = false
        }
    }

    BackHandler {
        when {
            lyricsPickingActive -> onLyricsPickingChange(false)
            activeTab != NowPlayingTab.ARTWORK -> activeTab = NowPlayingTab.ARTWORK
            else -> onDismiss()
        }
    }

    val lyricsData = (lyricsState as? UiState.Success)?.data
    val syncedResult = lyricsData?.takeIf { it.isSynced && it.lines.isNotEmpty() }
    val previewClock = rememberLyricClock(
        trackKey = song.videoId,
        positionMs = progress.positionMs,
        sampledAtNanos = progress.sampledAtNanos,
        seekGeneration = progress.seekGeneration,
        offsetMs = lyricsData?.offsetMs ?: 0L,
        isPlaying = playerState.isPlaying,
        playbackSpeed = playerState.playbackSpeed,
        active = activeTab != NowPlayingTab.LYRICS && showLyricsPreviewStrip && syncedResult != null,
    )

    val sheetCorner = ((dismissOffsetY.value / dismissThresholdPx).coerceIn(0f, 1f) * 32f).dp
    val showDeckInLyrics = activeTab != NowPlayingTab.LYRICS || (lyricsDeckVisible && !lyricsPickingActive)
    val deckVisibilityAnim by animateFloatAsState(
        targetValue = if (showDeckInLyrics) 1f else 0f,
        animationSpec = if (reduceAnimation) tween(0) else tween(320, easing = FastOutSlowInEasing),
        label = "sliding_deck_visibility",
    )

    val pauseBreathScale by animateFloatAsState(
        targetValue = if (playerState.isPlaying || reduceAnimation) 1f else 0.86f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "framed_art_pause_scale",
    )

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnSkipNext by rememberUpdatedState(onSkipNext)
    val currentOnSkipPrevious by rememberUpdatedState(onSkipPrevious)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismissOffsetY.value.roundToInt()) }
            .clip(RoundedCornerShape(topStart = sheetCorner, topEnd = sheetCorner))
            .then(if (playerHazeState != null) Modifier.hazeSource(playerHazeState) else Modifier),
    ) {
        val screenW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val screenH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val topStripHeightPx = with(density) { TOP_STRIP_HEIGHT.toPx() }
        val horizontalPadPx = with(density) { 26.dp.toPx() }
        val collapsedThumbPx = with(density) { COLLAPSED_THUMB_SIZE.toPx() }
        val collapsedThumbX = horizontalPadPx
        val collapsedThumbY = statusTopPx + topStripHeightPx + with(density) { 6.dp.toPx() }
        val collapsedHeaderBottomPx = collapsedThumbY + collapsedThumbPx + with(density) { 12.dp.toPx() }

        // Estimate lower deck height cleanly so expanded artwork & credits never collide on short screens
        val volumeBonusDp = if (showVolumeSlider && activeTab == NowPlayingTab.ARTWORK) 42.dp else 0.dp
        val fullDeckBaseDp = 248.dp + volumeBonusDp
        val fullDeckPx = with(density) { fullDeckBaseDp.toPx() } + navBottomPx
        val availableAboveDeckPx = (screenH - fullDeckPx).coerceAtLeast(with(density) { 260.dp.toPx() })

        // Expanded artwork rectangle (Full-Bleed vs Framed)
        val creditsRowEstPx = with(density) { 60.dp.toPx() }
        val fullBleedArtH = minOf(screenW * 1.04f, (availableAboveDeckPx + with(density) { 36.dp.toPx() })).coerceAtLeast(with(density) { 220.dp.toPx() })
        val framedSizeMaxW = (screenW - with(density) { 56.dp.toPx() }).coerceAtLeast(160f)
        val framedSizeMaxH = (availableAboveDeckPx - statusTopPx - topStripHeightPx - creditsRowEstPx - with(density) { 28.dp.toPx() })
            .coerceAtLeast(160f)
        val framedSize = minOf(framedSizeMaxW, framedSizeMaxH)

        val expArtX = if (fullBleedCoverArt) 0f else (screenW - framedSize) * 0.5f
        val expArtY = if (fullBleedCoverArt) 0f else statusTopPx + topStripHeightPx + ((framedSizeMaxH - framedSize) * 0.5f + with(density) { 10.dp.toPx() })
        val expArtW = if (fullBleedCoverArt) screenW else framedSize
        val expArtH = if (fullBleedCoverArt) fullBleedArtH else framedSize

        val expCreditsY = if (fullBleedCoverArt) {
            (availableAboveDeckPx - creditsRowEstPx - with(density) { 2.dp.toPx() })
                .coerceAtLeast(fullBleedArtH - with(density) { 64.dp.toPx() })
        } else {
            (availableAboveDeckPx - creditsRowEstPx - with(density) { 4.dp.toPx() })
                .coerceAtLeast(expArtY + expArtH + with(density) { 12.dp.toPx() })
        }

        val collapseProgress: () -> Float = {
            maxOf(collapseAnim.value, queuePullAnim.value).coerceIn(0f, 1f)
        }

        // ---- 1. Seamless Backdrop (recorded into GraphicsLayer for PNG LyricsShareSheet) ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)
                },
        ) {
            PlayerBackdrop(
                imageUrl = song.thumbnailUrl,
                meshColors = palette.meshColors,
                theme = playerTheme,
                reduceAnimation = reduceAnimation,
                dimMore = activeTab != NowPlayingTab.ARTWORK,
                seamYPx = if (fullBleedCoverArt) expArtH else -1f,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- 2. Subview Layer (Lyrics or Queue) fading in below collapsed header ----
        val showingQueuePull = activeTab == NowPlayingTab.ARTWORK && queuePullAnim.value > 0.02f
        val effectiveSubview = when {
            activeTab != NowPlayingTab.ARTWORK -> activeTab
            showingQueuePull -> NowPlayingTab.QUEUE
            else -> null
        }

        if (effectiveSubview != null) {
            val scrollSlopPx = with(density) { 20.dp.toPx() }
            val lyricsNestedScroll = remember(scrollSlopPx) {
                var accumY = 0f
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && activeTab == NowPlayingTab.LYRICS && !lyricsPickingActive) {
                            if ((available.y < 0f && accumY > 0f) || (available.y > 0f && accumY < 0f)) {
                                accumY = 0f
                            }
                            accumY += available.y
                            if (accumY <= -scrollSlopPx && lyricsDeckVisible) {
                                lyricsDeckVisible = false
                                accumY = 0f
                            } else if (accumY >= scrollSlopPx && !lyricsDeckVisible) {
                                lyricsDeckVisible = true
                                lyricsDeckInteractionTick++
                                accumY = 0f
                            }
                        }
                        return Offset.Zero
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val topY = collapsedHeaderBottomPx.roundToInt()
                        val reservedBottom = (fullDeckPx * deckVisibilityAnim).roundToInt()
                            .coerceAtLeast(navBottomPx.roundToInt())
                        val h = (constraints.maxHeight - topY - reservedBottom).coerceAtLeast(0)
                        val placeable = measurable.measure(
                            Constraints.fixed(constraints.maxWidth, h),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(0, topY)
                        }
                    }
                    .graphicsLayer {
                        val p = collapseProgress()
                        alpha = ((p - 0.18f) / 0.82f).coerceIn(0f, 1f)
                        translationY = (1f - p) * 28.dp.toPx()
                    }
                    .nestedScroll(lyricsNestedScroll)
                    .pointerInput(activeTab, lyricsDeckVisible, lyricsPickingActive) {
                        if (activeTab == NowPlayingTab.LYRICS && !lyricsDeckVisible && !lyricsPickingActive) {
                            detectTapGestures { offset ->
                                if (offset.y >= size.height * 0.45f) {
                                    lyricsDeckVisible = true
                                    lyricsDeckInteractionTick++
                                }
                            }
                        }
                    },
            ) {
                when (effectiveSubview) {
                    NowPlayingTab.LYRICS -> {
                        lyricsContent(
                            Modifier.fillMaxSize(),
                            suspend {
                                runCatching { backdropLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                            },
                        )
                    }
                    NowPlayingTab.QUEUE -> {
                        QueuePanel(
                            queue = playerState.queue,
                            currentIndex = playerState.currentIndex,
                            autoplayEnabled = playerState.autoplayEnabled,
                            onSelectIndex = onSelectQueueIndex,
                            onMoveItem = onMoveQueueItem,
                            onRemoveItem = onRemoveQueueItem,
                            onClearUpcoming = onClearUpcomingQueue,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    NowPlayingTab.ARTWORK -> Unit
                }
            }
        }

        // ---- 3. Unified Single-Node Shared-Element Artwork (0 outer recompositions!) ----
        val fullCoverUrl = remember(song.thumbnailUrl, fullBleedCoverArt) {
            upsizeArtworkUrl(song.thumbnailUrl, if (fullBleedCoverArt) 960 else 800)
        }
        val artworkRequest = remember(fullCoverUrl, lastLoadedArtworkKey, platformContext) {
            ImageRequest.Builder(platformContext)
                .data(fullCoverUrl)
                .apply {
                    lastLoadedArtworkKey?.let { placeholderMemoryCacheKey(it) }
                }
                .build()
        }

        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val p = collapseProgress()
                    val w = (expArtW + (collapsedThumbPx - expArtW) * p).roundToInt().coerceAtLeast(1)
                    val h = (expArtH + (collapsedThumbPx - expArtH) * p).roundToInt().coerceAtLeast(1)
                    val x = (expArtX + (collapsedThumbX - expArtX) * p).roundToInt()
                    val y = (expArtY + (collapsedThumbY - expArtY) * p).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) {
                        placeable.place(x, y)
                    }
                }
                .graphicsLayer {
                    val p = collapseProgress()
                    val breath = if (!fullBleedCoverArt) {
                        pauseBreathScale + (1f - pauseBreathScale) * p
                    } else 1f
                    scaleX = breath
                    scaleY = breath
                    translationX = artSwipeX.value * (1f - p)
                    val cornerStart = if (fullBleedCoverArt) 0f else 12.dp.toPx()
                    val cornerEnd = 10.dp.toPx()
                    val radius = cornerStart + (cornerEnd - cornerStart) * p
                    shape = RoundedCornerShape(radius)
                    clip = radius > 0.5f || !fullBleedCoverArt || p > 0.01f
                    shadowElevation = if (!fullBleedCoverArt) {
                        ((if (playerState.isPlaying) 24.dp else 10.dp).toPx()) * (1f - p)
                    } else {
                        (10.dp.toPx()) * p
                    }
                }
                .then(
                    if (!fullBleedCoverArt) {
                        Modifier.thumbnailBorder(RoundedCornerShape(12.dp))
                    } else Modifier,
                )
                .artworkBottomFade(
                    enabled = fullBleedCoverArt,
                    collapseProgress = collapseProgress,
                )
                .pointerInput(activeTab) {
                    if (activeTab != NowPlayingTab.ARTWORK) {
                        detectTapGestures(onTap = { activeTab = NowPlayingTab.ARTWORK })
                    } else {
                        var accumX = 0f
                        var accumY = 0f
                        var lockedAxis = 0 // 1 = horizontal, 2 = vertical
                        try {
                            detectDragGestures(
                                onDragStart = {
                                    accumX = 0f
                                    accumY = 0f
                                    lockedAxis = 0
                                },
                                onDragEnd = {
                                    val dx = artSwipeX.value
                                    val dy = dismissOffsetY.value
                                    val qPull = queuePullAnim.value
                                    when {
                                        lockedAxis == 1 && abs(dx) >= skipThresholdPx -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            lastSwipeDirection = if (dx < 0f) -1 else 1
                                            if (dx < 0f) currentOnSkipNext() else currentOnSkipPrevious()
                                        }
                                        lockedAxis == 2 && dy >= dismissThresholdPx -> {
                                            currentOnDismiss()
                                        }
                                        lockedAxis == 2 && qPull >= 0.34f -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            activeTab = NowPlayingTab.QUEUE
                                        }
                                    }
                                    scope.launch {
                                        artSwipeX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                                    }
                                    scope.launch {
                                        dismissOffsetY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                    }
                                    if (activeTab == NowPlayingTab.ARTWORK) {
                                        scope.launch {
                                            queuePullAnim.animateTo(0f, spring(dampingRatio = 0.80f, stiffness = 380f))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { artSwipeX.animateTo(0f, spring()) }
                                    scope.launch { dismissOffsetY.animateTo(0f, spring()) }
                                    scope.launch { queuePullAnim.animateTo(0f, spring()) }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumX += dragAmount.x
                                    accumY += dragAmount.y
                                    if (lockedAxis == 0 && (abs(accumX) > 10f || abs(accumY) > 10f)) {
                                        lockedAxis = if (abs(accumX) > abs(accumY)) 1 else 2
                                    }
                                    if (lockedAxis == 1) {
                                        val next = (artSwipeX.value + dragAmount.x * 0.75f)
                                            .coerceIn(-skipThresholdPx * 1.6f, skipThresholdPx * 1.6f)
                                        scope.launch { artSwipeX.snapTo(next) }
                                    } else if (lockedAxis == 2) {
                                        if (accumY > 0f && queuePullAnim.value <= 0.01f) {
                                            val nextY = (dismissOffsetY.value + dragAmount.y).coerceAtLeast(0f)
                                            scope.launch { dismissOffsetY.snapTo(nextY) }
                                        } else if (accumY < 0f && dismissOffsetY.value <= 1f) {
                                            val pull = (-accumY / queuePullRangePx).coerceIn(0f, 1f)
                                            scope.launch { queuePullAnim.snapTo(pull) }
                                        }
                                    }
                                },
                            )
                        } finally {
                            scope.launch { artSwipeX.snapTo(0f) }
                            scope.launch { dismissOffsetY.snapTo(0f) }
                            if (activeTab == NowPlayingTab.ARTWORK) {
                                scope.launch { queuePullAnim.snapTo(0f) }
                            }
                        }
                    }
                },
        ) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    lastLoadedArtworkKey = state.result.memoryCacheKey
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- 4. Adaptive 8-Step Top Status-Bar Scrim + Top Dismiss Strip ("Playing from ...") ----
        val scrimMaxAlpha by animateFloatAsState(
            targetValue = run {
                val base = (0.24f + topBandLuminance * 0.50f).coerceIn(0.24f, 0.70f)
                if (activeTab != NowPlayingTab.ARTWORK) maxOf(base, 0.44f) else base
            },
            animationSpec = tween(durationMillis = if (reduceAnimation) 0 else 360),
            label = "top_scrim_alpha",
        )
        val sourceLabel = playerState.playbackSourceLabel?.takeIf { it.isNotBlank() }
            ?: song.album?.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.player_source_queue)
        val sourceBrowseId = playerState.playbackSourceBrowseId ?: song.album?.browseId

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { (statusTopPx + topStripHeightPx + 30.dp.toPx()).toDp() })
                .background(
                    Brush.verticalGradient(
                        colorStops = Array(8) { idx ->
                            val t = idx / 7f
                            t to Color.Black.copy(alpha = scrimMaxAlpha * (1f - smootherStepCurve(t)))
                        },
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = with(density) { statusTopPx.toDp() })
                .height(TOP_STRIP_HEIGHT)
                .pointerInput(Unit) {
                    try {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dismissOffsetY.value >= dismissThresholdPx) {
                                    currentOnDismiss()
                                } else {
                                    scope.launch {
                                        dismissOffsetY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { dismissOffsetY.animateTo(0f, spring()) }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val next = (dismissOffsetY.value + dragAmount).coerceAtLeast(0f)
                                scope.launch { dismissOffsetY.snapTo(next) }
                            },
                        )
                    } finally {
                        scope.launch { dismissOffsetY.snapTo(0f) }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(16.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.56f)),
                )
            }
            Text(
                text = stringResource(R.string.player_playing_from, sourceLabel),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.45f),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f,
                    ),
                ),
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (!sourceBrowseId.isNullOrBlank() && onOpenSourceBrowse != null) {
                            onDismiss()
                            onOpenSourceBrowse(sourceBrowseId)
                        } else {
                            activeTab = if (activeTab == NowPlayingTab.QUEUE) NowPlayingTab.ARTWORK else NowPlayingTab.QUEUE
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }

        // ---- 5. Shared Credits Row (glides smoothly from below art into collapsed header) ----
        val collapsedInsetPx = with(density) { CREDITS_COLLAPSED_INSET.toPx() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    val p = collapseProgress()
                    val y = expCreditsY + (collapsedThumbY - expCreditsY) * p
                    IntOffset(0, y.roundToInt())
                }
                .padding(horizontal = 26.dp)
                .height(COLLAPSED_THUMB_SIZE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongInfoTransition(
                trackKey = song.videoId,
                dragOffsetPx = { artSwipeX.value * (1f - collapseProgress()) },
                lastSwipeDirection = lastSwipeDirection,
                reduceAnimation = reduceAnimation,
                modifier = Modifier
                    .weight(1f)
                    .layout { measurable, constraints ->
                        val p = collapseProgress()
                        val startPad = (collapsedInsetPx * p).roundToInt()
                        val maxW = (constraints.maxWidth - startPad).coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW))
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.place(startPad, 0)
                        }
                    }
                    .graphicsLayer {
                        val p = collapseProgress()
                        val sc = 1f - 0.16f * p
                        scaleX = sc
                        scaleY = sc
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .then(
                        if (activeTab != NowPlayingTab.ARTWORK) {
                            Modifier.clickable { activeTab = NowPlayingTab.ARTWORK }
                        } else Modifier,
                    ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.let { mod ->
                            val albumId = song.album?.browseId
                            if (activeTab == NowPlayingTab.ARTWORK && albumId != null && onGoToAlbum != null) {
                                mod.clickable {
                                    onDismiss()
                                    onGoToAlbum(albumId)
                                }
                            } else mod
                        },
                    ) {
                        if (song.isExplicit) {
                            ExplicitBadge(tint = Color.White.copy(alpha = 0.78f))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fadingEndMarquee()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 3500,
                                    velocity = 28.dp,
                                ),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    TappableArtistsLine(
                        song = song,
                        interactive = activeTab == NowPlayingTab.ARTWORK && onGoToArtist != null,
                        onArtistClick = { artistId ->
                            onDismiss()
                            onGoToArtist?.invoke(artistId)
                        },
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Frosted GlassDiscPair: Star favorite + context-aware More / Share / Close
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDiscButton(
                    icon = if (isLiked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(if (isLiked) R.string.menu_unlike else R.string.menu_like),
                    tint = Color.White,
                    activeHighlight = isLiked,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleLike()
                    },
                )

                val rightIcon = when {
                    lyricsPickingActive -> Icons.Rounded.Close
                    activeTab == NowPlayingTab.LYRICS && !lyricsDeckVisible -> Icons.Rounded.Share
                    else -> Icons.Rounded.MoreHoriz
                }
                val rightDesc = when {
                    lyricsPickingActive -> stringResource(R.string.action_cancel)
                    activeTab == NowPlayingTab.LYRICS && !lyricsDeckVisible -> stringResource(R.string.lyrics_share_title)
                    else -> stringResource(R.string.action_more)
                }
                GlassDiscButton(
                    icon = rightIcon,
                    contentDescription = rightDesc,
                    tint = Color.White,
                    activeHighlight = false,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        when {
                            lyricsPickingActive -> onLyricsPickingChange(false)
                            activeTab == NowPlayingTab.LYRICS && !lyricsDeckVisible -> onLyricsPickingChange(true)
                            else -> onOpenMoreActions(song)
                        }
                    },
                )
            }
        }

        // ---- 6. Sliding Lower Player Deck (Auto-hides cleanly in Lyrics mode) ----
        val durationMs = progress.durationMs.coerceAtLeast(1L)
        val displayPosMs = if (scrubbing) (scrubValue * durationMs).toLong() else progress.positionMs
        val sliderFraction = if (scrubbing) {
            scrubValue
        } else {
            (progress.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
        val remainingMs = (durationMs - displayPosMs).coerceAtLeast(0L)
        val qualityBadge = remember(playerState.nerdStats.mimeType, playerState.nerdStats.bitrateKbps) {
            buildQualityPillLabel(playerState.nerdStats.mimeType, playerState.nerdStats.bitrateKbps)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    val hidden = 1f - deckVisibilityAnim
                    translationY = hidden * (fullDeckPx + 24.dp.toPx())
                    alpha = (deckVisibilityAnim * 1.25f).coerceIn(0f, 1f)
                }
                .padding(horizontal = 26.dp)
                .padding(bottom = with(density) { navBottomPx.toDp() }),
        ) {
            // Fixed-height Live Lyrics Preview Strip / Lyrics Provider Status Link
            CurrentLyricStrip(
                song = song,
                activeTab = activeTab,
                lyricsState = lyricsState,
                lyricsDisplayMode = lyricsDisplayMode,
                lyricsTranslationNotice = lyricsTranslationNotice,
                showPreviewStrip = showLyricsPreviewStrip,
                clock = previewClock,
                reduceAnimation = reduceAnimation,
                onOpenLyrics = { activeTab = NowPlayingTab.LYRICS },
                onOpenProviders = onOpenLyricsProviders,
            )

            // Scrubber + Lifted Timestamps + Audio Quality Pill
            ThinSlider(
                value = sliderFraction,
                onValueChange = {
                    scrubbing = true
                    scrubValue = it
                    lyricsDeckInteractionTick++
                },
                onValueChangeFinished = {
                    onSeekTo((scrubValue * durationMs).toLong())
                    scrubbing = false
                    lyricsDeckInteractionTick++
                },
                activeColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.26f),
                restHeight = 7.dp,
                dragHeight = 12.dp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-6).dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDuration(displayPosMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = Color.White.copy(alpha = 0.70f),
                )

                if (qualityBadge != null) {
                    Box(
                        modifier = Modifier
                            .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
                            .clickable(onClick = onOpenStatsForNerds)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = qualityBadge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                            ),
                            color = Color.White.copy(alpha = 0.88f),
                        )
                    }
                }

                Text(
                    text = if (showRemainingTime) "-${formatDuration(remainingMs)}" else formatDuration(progress.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = Color.White.copy(alpha = 0.70f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            lyricsDeckInteractionTick++
                            onToggleRemainingTime()
                        }
                        .padding(horizontal = 2.dp),
                )
            }

            // Tactile Transport Row: Conveyor SkipPrev | Cross-Blur Play/Pause | Conveyor SkipNext
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipConveyorButton(
                    isNext = false,
                    contentDescription = stringResource(R.string.action_previous),
                    reduceAnimation = reduceAnimation,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lyricsDeckInteractionTick++
                        lastSwipeDirection = 1
                        onSkipPrevious()
                    },
                )

                TactilePlayPauseButton(
                    isPlaying = playerState.isPlaying,
                    isBuffering = playerState.isBuffering,
                    reduceAnimation = reduceAnimation,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lyricsDeckInteractionTick++
                        onTogglePlayPause()
                    },
                )

                SkipConveyorButton(
                    isNext = true,
                    contentDescription = stringResource(R.string.action_next),
                    reduceAnimation = reduceAnimation,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lyricsDeckInteractionTick++
                        lastSwipeDirection = -1
                        onSkipNext()
                    },
                )
            }

            // Hardware Volume Row (visible in ARTWORK when enabled)
            AnimatedVisibility(visible = showVolumeSlider && activeTab == NowPlayingTab.ARTWORK) {
                VolumeControlRow(
                    tint = Color.White,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            // 3-Slot Bottom Dock: Lyrics Cutout | Audio Output (+ device label) <-> Shuffle/Repeat/Autoplay | Queue
            val outputLabel = activeAudioOutput?.name?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.player_this_phone)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.width(64.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LyricsQuoteDockButton(
                        active = activeTab == NowPlayingTab.LYRICS,
                        contentDescription = stringResource(R.string.player_lyrics),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            activeTab = if (activeTab == NowPlayingTab.LYRICS) NowPlayingTab.ARTWORK else NowPlayingTab.LYRICS
                        },
                    )
                }

                // Center slot: morphs cleanly between Audio Output route button + caption and Queue 3-toggle pill
                AnimatedContent(
                    targetState = activeTab == NowPlayingTab.QUEUE,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.TopCenter,
                    label = "center_dock_morph",
                ) { queueMode ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (queueMode) {
                            Row(
                                modifier = Modifier
                                    .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CapsuleIconToggle(
                                    icon = Icons.Rounded.Shuffle,
                                    contentDescription = stringResource(R.string.action_shuffle),
                                    active = playerState.shuffleEnabled,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleShuffle()
                                    },
                                )
                                CapsuleIconToggle(
                                    icon = if (playerState.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    contentDescription = stringResource(R.string.player_repeat),
                                    active = playerState.repeatMode != RepeatMode.OFF,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onCycleRepeat()
                                    },
                                )
                                CapsuleIconToggle(
                                    icon = Icons.Rounded.AllInclusive,
                                    contentDescription = stringResource(R.string.player_autoplay),
                                    active = playerState.autoplayEnabled,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleAutoplay(!playerState.autoplayEnabled)
                                    },
                                )
                            }
                        } else {
                            AudioOutputDockButton(
                                device = activeAudioOutput,
                                label = outputLabel,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lyricsDeckInteractionTick++
                                    onOpenAudioOutput()
                                },
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.width(64.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    BottomDeckTileButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.player_queue),
                        active = activeTab == NowPlayingTab.QUEUE,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            activeTab = if (activeTab == NowPlayingTab.QUEUE) NowPlayingTab.ARTWORK else NowPlayingTab.QUEUE
                        },
                    )
                }
            }
        }
    }
}

// ---- Live Lyrics Preview & Status Strip (Fixed 34dp Height, Zero Layout Jump) ----

private sealed interface LyricStripUi {
    data class Loading(val line: String) : LyricStripUi
    data object Unavailable : LyricStripUi
    data object Unsynced : LyricStripUi
    data class Intro(val line: String) : LyricStripUi
    data object Instrumental : LyricStripUi
    data class ActiveLine(val line: LyricLine) : LyricStripUi
    data object Hidden : LyricStripUi
}

@Composable
private fun CurrentLyricStrip(
    song: Song,
    activeTab: NowPlayingTab,
    lyricsState: UiState<LyricsResult?>,
    lyricsDisplayMode: LyricsDisplayMode,
    lyricsTranslationNotice: String?,
    showPreviewStrip: Boolean,
    clock: androidx.compose.runtime.MutableLongState,
    reduceAnimation: Boolean,
    onOpenLyrics: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val resources = LocalResources.current
    val loadingLines = remember(resources) { resources.getStringArray(R.array.lyrics_loading_lines) }
    val introLines = remember(resources) { resources.getStringArray(R.array.lyrics_intro_lines) }

    val trackSeed = remember(song.videoId) { abs(song.videoId.hashCode()) }
    val loadingLine = loadingLines[trackSeed % loadingLines.size.coerceAtLeast(1)]
    val introLine = introLines[(trackSeed / 7) % introLines.size.coerceAtLeast(1)]

    val lyricsData = (lyricsState as? UiState.Success)?.data

    // In LYRICS tab, the fixed slot transforms into the clickable underlined provider/status link
    if (activeTab == NowPlayingTab.LYRICS) {
        val statusText = when {
            !lyricsTranslationNotice.isNullOrBlank() && lyricsDisplayMode != LyricsDisplayMode.ORIGINAL ->
                lyricsTranslationNotice
            lyricsState is UiState.Loading -> loadingLine
            lyricsData != null && lyricsData.lines.isNotEmpty() ->
                stringResource(R.string.player_lyrics_by, lyricsData.source.displayName)
            else -> stringResource(R.string.player_lyrics_none_found)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LYRIC_STRIP_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (lyricsState !is UiState.Loading) TextDecoration.Underline else TextDecoration.None,
                ),
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenProviders)
                    .padding(vertical = 4.dp),
            )
        }
        return
    }

    if (!showPreviewStrip) {
        Spacer(Modifier.height(10.dp))
        return
    }

    // Fade "Lyrics not available" away gently after 5s while preserving the 34dp slot height
    val isUnavailableNow = lyricsState is UiState.Error ||
        (lyricsState is UiState.Success && (lyricsData == null || lyricsData.lines.isEmpty()))
    var unavailableFaded by remember(song.videoId, isUnavailableNow) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, isUnavailableNow) {
        unavailableFaded = false
        if (isUnavailableNow) {
            delay(UNAVAILABLE_FADE_MS)
            unavailableFaded = true
        }
    }

    val stripState by remember(lyricsState, loadingLine, introLine, unavailableFaded) {
        derivedStateOf(structuralEqualityPolicy()) {
            when (lyricsState) {
                is UiState.Loading -> LyricStripUi.Loading(loadingLine)
                is UiState.Error -> if (unavailableFaded) LyricStripUi.Hidden else LyricStripUi.Unavailable
                is UiState.Success -> {
                    val data = lyricsState.data
                    when {
                        data == null || data.lines.isEmpty() ->
                            if (unavailableFaded) LyricStripUi.Hidden else LyricStripUi.Unavailable
                        !data.isSynced -> LyricStripUi.Unsynced
                        else -> {
                            val now = clock.longValue
                            val rows = SyncedLyricsParser.activeLyricRows(data.lines, now)
                            val firstNonGap = data.lines.firstOrNull { !it.isGap }
                            val activeLine = rows.firstOrNull()?.let { data.lines.getOrNull(it) }
                            when {
                                activeLine != null && !activeLine.isGap -> LyricStripUi.ActiveLine(activeLine)
                                activeLine != null && activeLine.isGap -> {
                                    if (firstNonGap != null && now < firstNonGap.startMs) {
                                        LyricStripUi.Intro(introLine)
                                    } else {
                                        LyricStripUi.Instrumental
                                    }
                                }
                                firstNonGap != null && now < firstNonGap.startMs -> LyricStripUi.Intro(introLine)
                                else -> LyricStripUi.Instrumental
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LYRIC_STRIP_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = stripState !is LyricStripUi.Hidden && stripState !is LyricStripUi.Unavailable,
                onClick = onOpenLyrics,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = stripState,
            transitionSpec = {
                if (reduceAnimation) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(260))) togetherWith
                        (slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(200)))
                }
            },
            label = "current_lyric_strip",
        ) { state ->
            when (state) {
                LyricStripUi.Hidden -> Spacer(Modifier.height(LYRIC_STRIP_HEIGHT))

                is LyricStripUi.Loading -> Text(
                    text = state.line,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                LyricStripUi.Unavailable -> Text(
                    text = stringResource(R.string.player_lyrics_not_available),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color.White.copy(alpha = 0.56f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                LyricStripUi.Unsynced -> StripIconTextRow(
                    text = stringResource(R.string.player_lyrics_open),
                    showChevron = true,
                )

                is LyricStripUi.Intro -> StripIconTextRow(
                    text = state.line,
                    showChevron = false,
                )

                LyricStripUi.Instrumental -> StripIconTextRow(
                    text = stringResource(R.string.player_lyrics_instrumental),
                    showChevron = true,
                )

                is LyricStripUi.ActiveLine -> SweptLyricLine(
                    line = state.line,
                    clock = clock,
                    isSynced = true,
                    isActive = true,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                    baseColor = Color.White,
                    unsungAlpha = 0.55f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    enableWordLift = false,
                    enableGrowingWave = false,
                    enableBloom = false,
                    roomPadding = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StripIconTextRow(
    text: String,
    showChevron: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = Color.White.copy(alpha = 0.74f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- Credits, GlassDiscPair & Dock Controls ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TappableArtistsLine(
    song: Song,
    interactive: Boolean,
    onArtistClick: (String) -> Unit,
) {
    val artists = song.artists
    if (!interactive || artists.size <= 1) {
        val singleId = song.primaryArtistId
        Text(
            text = song.artistText,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = Color.White.copy(alpha = 0.74f),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .fadingEndMarquee()
                .let { mod ->
                    if (interactive && singleId != null) mod.clickable { onArtistClick(singleId) } else mod
                }
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 5000,
                    velocity = 26.dp,
                ),
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadingEndMarquee()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 5000,
                velocity = 26.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artists.forEachIndexed { idx, artist ->
            val browseId = artist.browseId
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = 0.74f),
                maxLines = 1,
                modifier = if (!browseId.isNullOrBlank()) {
                    Modifier.clickable { onArtistClick(browseId) }
                } else Modifier,
            )
            if (idx < artists.lastIndex) {
                Text(
                    text = ", ",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.5.sp),
                    color = Color.White.copy(alpha = 0.56f),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun Modifier.fadingEndMarquee(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = 14.dp.toPx().coerceAtMost(size.width * 0.14f)
        if (fadePx > 1f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - fadePx, 0f),
                size = Size(fadePx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun GlassDiscButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    activeHighlight: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .then(
                if (activeHighlight) {
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.28f))
                } else {
                    Modifier.lightweightGlass(CircleShape, onDarkBackdrop = true)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Quote-bubble Lyrics button with an Apple rounded-square `BlendMode.SrcOut` cutout when active.
 */
@Composable
private fun LyricsQuoteDockButton(
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics {
                stateDescription = if (active) "Selected" else "Not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val h = size.height
            if (active) {
                drawRoundRect(
                    color = Color.White,
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    size = size,
                )
            }
            val bubbleLeft = w * 0.18f
            val bubbleTop = h * 0.18f
            val bubbleW = w * 0.64f
            val bubbleH = h * 0.50f
            val bubbleRadius = 4.dp.toPx()
            val tailPath = Path().apply {
                moveTo(bubbleLeft + bubbleW * 0.24f, bubbleTop + bubbleH - 1f)
                lineTo(bubbleLeft + bubbleW * 0.16f, bubbleTop + bubbleH + h * 0.14f)
                lineTo(bubbleLeft + bubbleW * 0.44f, bubbleTop + bubbleH - 1f)
                close()
            }
            val fgColor = if (active) Color.Black else Color.White.copy(alpha = 0.86f)
            val blend = if (active) BlendMode.SrcOut else BlendMode.SrcOver
            if (active) {
                drawRoundRect(
                    color = fgColor,
                    topLeft = Offset(bubbleLeft, bubbleTop),
                    size = Size(bubbleW, bubbleH),
                    cornerRadius = CornerRadius(bubbleRadius, bubbleRadius),
                    blendMode = blend,
                )
                drawPath(path = tailPath, color = fgColor, blendMode = blend)
            } else {
                val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
                drawRoundRect(
                    color = fgColor,
                    topLeft = Offset(bubbleLeft, bubbleTop),
                    size = Size(bubbleW, bubbleH),
                    cornerRadius = CornerRadius(bubbleRadius, bubbleRadius),
                    style = stroke,
                )
                drawPath(path = tailPath, color = fgColor)
                // Two subtle quote marks inside the bubble
                val qy = bubbleTop + bubbleH * 0.50f
                drawCircle(color = fgColor, radius = 1.5.dp.toPx(), center = Offset(w * 0.42f, qy))
                drawCircle(color = fgColor, radius = 1.5.dp.toPx(), center = Offset(w * 0.58f, qy))
            }
        }
    }
}

/**
 * Center bottom dock Audio Output route button + live output caption (`"This phone"` or BT name).
 */
@Composable
private fun AudioOutputDockButton(
    device: AudioOutputDevice?,
    label: String,
    onClick: () -> Unit,
) {
    val externalConnected = device != null && !device.isBuiltIn
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.player_audio_output),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (externalConnected) {
                Icon(
                    imageVector = audioOutputIcon(device.kind),
                    contentDescription = stringResource(R.string.player_audio_output),
                    tint = Color.White,
                    modifier = Modifier.size(23.dp),
                )
            } else {
                // Iconic concentric AirPlay / audio-output waves + triangle pointer
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    val center = Offset(w * 0.5f, h * 0.50f)
                    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    val rings = floatArrayOf(w * 0.16f, w * 0.31f, w * 0.46f)
                    for (r in rings) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.88f),
                            startAngle = 140f,
                            sweepAngle = 260f,
                            useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(r * 2f, r * 2f),
                            style = stroke,
                        )
                    }
                    val tri = Path().apply {
                        moveTo(w * 0.50f, h * 0.62f)
                        lineTo(w * 0.30f, h * 0.92f)
                        lineTo(w * 0.70f, h * 0.92f)
                        close()
                    }
                    drawPath(path = tri, color = Color.White.copy(alpha = 0.92f))
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = if (externalConnected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = Color.White.copy(alpha = if (externalConnected) 0.92f else 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BottomDeckTileButton(
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.Black else Color.White.copy(alpha = 0.86f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CapsuleIconToggle(
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.Black else Color.White.copy(alpha = 0.80f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun VolumeControlRow(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVol = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    var fraction by remember {
        mutableFloatStateOf(
            ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8).toFloat() / maxVol.toFloat())
                .coerceIn(0f, 1f),
        )
    }
    var lastUserChangeAtMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(audioManager) {
        val cur = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return@LaunchedEffect
        fraction = (cur.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
    }

    DisposableEffect(context, audioManager, maxVol) {
        if (audioManager == null) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (SystemClock.elapsedRealtime() - lastUserChangeAtMs < 250L) return
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                fraction = (cur.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = tint.copy(alpha = 0.62f),
            modifier = Modifier.size(18.dp),
        )
        ThinSlider(
            value = fraction,
            onValueChange = { next ->
                lastUserChangeAtMs = SystemClock.elapsedRealtime()
                fraction = next
                val target = (next * maxVol).roundToInt().coerceIn(0, maxVol)
                runCatching {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                }
            },
            activeColor = tint.copy(alpha = 0.90f),
            inactiveColor = tint.copy(alpha = 0.22f),
            restHeight = 5.dp,
            dragHeight = 9.dp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = tint.copy(alpha = 0.62f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun buildQualityPillLabel(mimeType: String, bitrateKbps: Int): String? {
    val codec = when {
        mimeType.contains("opus", ignoreCase = true) -> "OPUS"
        mimeType.contains("mp4a", ignoreCase = true) || mimeType.contains("aac", ignoreCase = true) -> "AAC"
        mimeType.contains("flac", ignoreCase = true) -> "FLAC"
        mimeType.isNotBlank() -> mimeType.substringAfter('/').uppercase().take(6)
        else -> ""
    }
    if (codec.isBlank() && bitrateKbps <= 0) return null
    return buildString {
        if (codec.isNotBlank()) append(codec)
        if (bitrateKbps > 0) {
            if (isNotEmpty()) append(" · ")
            append("$bitrateKbps kbps")
        }
    }
}
