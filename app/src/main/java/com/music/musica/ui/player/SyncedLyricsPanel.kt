package com.music.musica.ui.player

import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.data.lyrics.CharGrowth
import com.music.musica.data.lyrics.GrowingWord
import com.music.musica.data.lyrics.LyricAlignment
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricsDisplayMode
import com.music.musica.data.lyrics.LyricsProviderStatus
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.lyrics.SyncedLyricsParser
import com.music.musica.playback.PlaybackProgress
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.LyricsSkeleton
import com.music.musica.ui.components.ThinSlider
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 400
private const val BACKING_LEAD_MS = 250L
private const val BACKING_OPEN_MS = 400
private const val BACKING_CLOSE_MS = 450
private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L
private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f
private const val INACTIVE_SCALE = 0.98f
private const val PRESSED_SCALE = 0.96f
private const val BROWSING_ALPHA = 0.80f
private const val BROWSING_RESUME_VISIBLE_MS = 600L
private const val BROWSING_RESUME_AWAY_MS = 5_000L
private const val PARTICLE_DURATION_MS = 540
private const val PARTICLE_COUNT = 18

private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

private val WORD_RISE = 2.dp
private val WIPE_FEATHER = 30.dp
private val GLOW_RADIUS = 6.dp
private val GLOW_ROOM = 10.dp
private const val GLOW_ALPHA = 0.62f
private val DUET_LANE = 44.dp
private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_REST_SCALE = 0.76f

private val LINE_FALLOFF_ALPHA = floatArrayOf(1.0f, 0.74f, 0.60f, 0.48f, 0.40f, 0.34f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

private class HandoverRun(
    var focusIndex: Int = -1,
    var deltaPx: Float = 0f,
    var durationMs: Long = LYRIC_SETTLE_MS.toLong(),
)

/**
 * High-craft 60fps synced lyrics panel featuring:
 * - Adaptive monotonic [LyricClock] read strictly in draw/derived phases (0 outer recompositions)
 * - 3-layer [SweptLyricLine]: dim base, API 31+ bloom on held words, feathered 30dp horizontal sweep
 * - Word lift (2dp), held-word letter-by-letter wave physics ([GrowingWord]), & handover row stagger
 * - Duet alternating lanes (44dp), dynamic accordion background vocals, & 3-dot instrumental countdowns
 * - Progressive blur (0/1/1/1.7/2.4dp) clearing on drag, multi-row overlap focus, & look-ahead scroll
 * - Floating bottom-left Romanize & bottom-right Translate toggles with sub-line reveal + particles
 * - Multi-line lyric quote picker (250-char budget + `(...)` gaps) -> PNG Card/Story share sheet
 * - Live interactive [LyricsProviderSheet] & [-5.0s..+5.0s] [LyricsOffsetSheet]
 */
@Composable
fun SyncedLyricsPanel(
    song: Song?,
    lyricsState: UiState<LyricsResult?>,
    progress: PlaybackProgress,
    isPlaying: Boolean,
    playbackSpeed: Float,
    reduceAnimation: Boolean,
    providerStatuses: Map<LyricsSourceId, LyricsProviderStatus>,
    providerOrder: List<LyricsSourceId>,
    displayMode: LyricsDisplayMode,
    subLyricsLines: List<LyricLine>?,
    translationLoading: Boolean,
    translationNotice: String?,
    showLyricsCardLogo: Boolean,
    onSeekTo: (Long) -> Unit,
    onChangeOffset: (Long) -> Unit,
    onSelectProvider: (LyricsSourceId) -> Unit,
    onToggleRomanize: () -> Unit,
    onToggleTranslate: () -> Unit,
    onSetShowLyricsCardLogo: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    blurUnfocusedLyrics: Boolean = true,
    showInlineControls: Boolean = true,
    showInlineChips: Boolean = true,
    externalPickingActive: Boolean? = null,
    onPickingActiveChange: ((Boolean) -> Unit)? = null,
    captureBackdropBitmap: (suspend () -> Bitmap?)? = null,
    onRequestOpenProviderSheet: (() -> Unit)? = null,
    onRequestOpenOffsetSheet: (() -> Unit)? = null,
    onRequestOpenShareSheet: ((Song, List<LyricsShareLine>, Bitmap?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current

    // Keep screen awake while the user reads lyrics
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    var showOffsetSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }

    // Line-picking state for PNG Share Card/Story
    var internalPicking by remember(song?.videoId) { mutableStateOf(false) }
    val picking = externalPickingActive ?: internalPicking
    fun setPicking(active: Boolean) {
        internalPicking = active
        onPickingActiveChange?.invoke(active)
    }

    var selectedLineIndices by remember(song?.videoId) { mutableStateOf(emptySet<Int>()) }
    var sharePickedLines by remember { mutableStateOf<List<LyricsShareLine>?>(null) }
    var capturedBackdrop by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(picking) {
        if (!picking) selectedLineIndices = emptySet()
    }

    val lyricsData = (lyricsState as? UiState.Success)?.data
    val syncedResult = lyricsData?.takeIf { it.isSynced && it.lines.isNotEmpty() }

    val lyricClock = rememberLyricClock(
        trackKey = song?.videoId,
        positionMs = progress.positionMs,
        sampledAtNanos = progress.sampledAtNanos,
        seekGeneration = progress.seekGeneration,
        offsetMs = lyricsData?.offsetMs ?: 0L,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        active = syncedResult != null,
    )

    // Sub-lyrics smooth reveal & particle burst on displayMode switch
    val subReveal = remember { Animatable(0f) }
    val particleProgress = remember { Animatable(1f) }
    var renderedSubLines by remember(song?.videoId) { mutableStateOf<List<LyricLine>?>(null) }

    LaunchedEffect(displayMode, subLyricsLines) {
        if (displayMode == LyricsDisplayMode.ORIGINAL || subLyricsLines.isNullOrEmpty()) {
            subReveal.animateTo(
                0f,
                tween(durationMillis = if (reduceAnimation) 0 else 260, easing = FastOutSlowInEasing),
            )
            renderedSubLines = null
        } else {
            if (renderedSubLines != null && renderedSubLines !== subLyricsLines && !reduceAnimation) {
                subReveal.animateTo(0f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
            }
            renderedSubLines = subLyricsLines
            if (reduceAnimation) {
                subReveal.snapTo(1f)
                particleProgress.snapTo(1f)
            } else {
                particleProgress.snapTo(0f)
                subReveal.animateTo(1f, tween(durationMillis = 460, easing = LYRIC_EASING))
            }
        }
    }

    LaunchedEffect(renderedSubLines, reduceAnimation) {
        if (renderedSubLines != null && !reduceAnimation) {
            particleProgress.snapTo(0f)
            particleProgress.animateTo(1f, tween(durationMillis = PARTICLE_DURATION_MS, easing = LinearEasing))
        }
    }

    val lyricsHazeState = rememberHazeState()

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(lyricsHazeState)) {
            when (lyricsState) {
                is UiState.Loading -> {
                    LyricsSkeleton(reduceAnimation = reduceAnimation)
                }

                is UiState.Error -> {
                    EmptyLyricsNotice(
                        onOpenProviders = {
                            if (onRequestOpenProviderSheet != null) onRequestOpenProviderSheet() else showProviderSheet = true
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is UiState.Success -> {
                    val result = lyricsState.data
                    if (result == null || result.lines.isEmpty()) {
                        EmptyLyricsNotice(
                            onOpenProviders = {
                                if (onRequestOpenProviderSheet != null) onRequestOpenProviderSheet() else showProviderSheet = true
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        val lines = result.lines
                        val isSynced = result.isSynced
                        val hasDuet = remember(lines) { lines.any { it.alignment == LyricAlignment.END } }
                        val listState = rememberLazyListState()
                        val density = LocalDensity.current

                        var browsingByDrag by remember(song?.videoId) { mutableStateOf(false) }
                        val browsing = browsingByDrag || picking

                        // Track manual touch drags cleanly via interactionSource
                        LaunchedEffect(listState) {
                            listState.interactionSource.interactions.collect { interaction ->
                                if (interaction is DragInteraction.Start) {
                                    browsingByDrag = true
                                }
                            }
                        }

                        // Multi-row active set & look-ahead scroll focus
                        val activeRows by remember(lines, isSynced) {
                            derivedStateOf(structuralEqualityPolicy()) {
                                if (!isSynced) emptyList()
                                else SyncedLyricsParser.activeLyricRows(lines, lyricClock.longValue)
                            }
                        }
                        val scrollLine by remember(lines, isSynced) {
                            derivedStateOf(structuralEqualityPolicy()) {
                                activeRows.firstOrNull() ?: -1
                            }
                        }
                        val scrollLeadMs by remember(lines, isSynced) {
                            derivedStateOf(structuralEqualityPolicy()) {
                                val curIdx = scrollLine
                                if (!isSynced || curIdx !in lines.indices) return@derivedStateOf SCROLL_LEAD_MAX_MS
                                val cur = lines[curIdx]
                                val next = lines.getOrNull(curIdx + 1) ?: return@derivedStateOf SCROLL_LEAD_MAX_MS
                                (next.startMs - cur.effectiveEndMs).coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
                            }
                        }
                        val focusLine by remember(lines, isSynced) {
                            derivedStateOf(structuralEqualityPolicy()) {
                                if (!isSynced) return@derivedStateOf -1
                                val ahead = SyncedLyricsParser.activeLyricRows(lines, lyricClock.longValue + scrollLeadMs)
                                ahead.firstOrNull() ?: scrollLine
                            }
                        }

                        // Auto-resume from manual scroll only while playing
                        LaunchedEffect(browsingByDrag, listState.isScrollInProgress, isPlaying, focusLine) {
                            if (!browsingByDrag || listState.isScrollInProgress || !isPlaying) return@LaunchedEffect
                            val focusVisible = focusLine >= 0 &&
                                listState.layoutInfo.visibleItemsInfo.any { it.index == focusLine }
                            delay(if (focusVisible) BROWSING_RESUME_VISIBLE_MS else BROWSING_RESUME_AWAY_MS)
                            browsingByDrag = false
                        }

                        // Handover row stagger state (single shared Animatable, zero per-row allocs)
                        val handoverRun = remember(song?.videoId) { HandoverRun() }
                        val handoverProgress = remember(song?.videoId) { Animatable(1f) }
                        var placedTrack by remember(song?.videoId) { mutableStateOf(false) }
                        val anchorOffsetPx = remember(density) { with(density) { 48.dp.roundToPx() } }

                        LaunchedEffect(focusLine, browsing, isSynced) {
                            if (!isSynced || focusLine !in lines.indices || browsing || listState.isScrollInProgress) return@LaunchedEffect
                            if (!placedTrack || reduceAnimation) {
                                listState.scrollToItem(focusLine, scrollOffset = -anchorOffsetPx)
                                placedTrack = true
                                handoverProgress.snapTo(1f)
                                return@LaunchedEffect
                            }
                            val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
                            if (visibleItem != null) {
                                val delta = (visibleItem.offset - anchorOffsetPx).toFloat()
                                if (abs(delta) > 2f) {
                                    val durMs = scrollLeadMs.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
                                    handoverRun.focusIndex = focusLine
                                    handoverRun.deltaPx = delta
                                    handoverRun.durationMs = durMs
                                    handoverProgress.snapTo(0f)
                                    listState.animateScrollBy(
                                        value = delta,
                                        animationSpec = tween(durationMillis = durMs.toInt(), easing = LYRIC_EASING),
                                    )
                                    handoverProgress.snapTo(1f)
                                }
                            } else {
                                listState.animateScrollToItem(focusLine, scrollOffset = -anchorOffsetPx)
                            }
                        }

                        LaunchedEffect(handoverRun.focusIndex, handoverRun.deltaPx) {
                            if (!reduceAnimation && handoverProgress.value < 1f) {
                                handoverProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = handoverRun.durationMs.toInt().coerceAtLeast(250),
                                        easing = LinearEasing,
                                    ),
                                )
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            if (showInlineChips) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassChip(
                                        label = stringResource(R.string.lyrics_source_label, result.source.displayName),
                                        onClick = {
                                            if (onRequestOpenProviderSheet != null) onRequestOpenProviderSheet() else showProviderSheet = true
                                        },
                                    )
                                    if (isSynced) {
                                        val sign = if (result.offsetMs > 0) "+" else ""
                                        GlassChip(
                                            label = "$sign${"%.1f".format(result.offsetMs / 1000f)}s",
                                            onClick = {
                                                if (onRequestOpenOffsetSheet != null) onRequestOpenOffsetSheet() else showOffsetSheet = true
                                            },
                                        )
                                    }
                                    if (!translationNotice.isNullOrBlank()) {
                                        Text(
                                            text = translationNotice,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.72f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                val cardFullToast = stringResource(R.string.lyrics_share_card_full)
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                        .drawWithContent {
                                            drawContent()
                                            val edgePx = 28.dp.toPx().coerceAtMost(size.height * 0.2f)
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black),
                                                    startY = 0f,
                                                    endY = edgePx,
                                                ),
                                                size = Size(size.width, edgePx),
                                                blendMode = BlendMode.DstIn,
                                            )
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startY = size.height - edgePx,
                                                    endY = size.height,
                                                ),
                                                topLeft = Offset(0f, size.height - edgePx),
                                                size = Size(size.width, edgePx),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        },
                                    contentPadding = PaddingValues(
                                        start = 24.dp - GLOW_ROOM,
                                        end = 24.dp - GLOW_ROOM,
                                        top = 36.dp - GLOW_ROOM,
                                        bottom = 190.dp,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    itemsIndexed(
                                        items = lines,
                                        key = { idx, line -> "${idx}_${line.startMs}_${line.isGap}" },
                                    ) { index, line ->
                                        if (line.isGap && isSynced) {
                                            val nextLineStart = lines.getOrNull(index + 1)?.startMs ?: line.endMs
                                            val isGapActive = index in activeRows
                                            InstrumentalAccordionDots(
                                                line = line,
                                                nextStartMs = nextLineStart.coerceAtLeast(line.startMs + 1L),
                                                isActive = isGapActive,
                                                clock = lyricClock,
                                                reduceAnimation = reduceAnimation,
                                                clickable = !picking,
                                                onClick = {
                                                    browsingByDrag = false
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onSeekTo((line.startMs - result.offsetMs).coerceAtLeast(0L))
                                                },
                                            )
                                        } else {
                                            val isActive = isSynced && (index in activeRows)
                                            val distance = if (scrollLine >= 0) abs(index - scrollLine) else 0
                                            val isSelected = index in selectedLineIndices
                                            val subLine = renderedSubLines?.getOrNull(index)?.takeIf {
                                                !it.isGap && !it.text.equals(line.text, ignoreCase = true)
                                            }
                                            val particleSeed = remember(index, line.text) { (index * 31 + line.text.hashCode()) }
                                            val showParticles = !reduceAnimation &&
                                                particleProgress.value < 0.99f &&
                                                ((isSynced && distance <= 1) || (!isSynced && index < 4))

                                            ProLyricLineRow(
                                                line = line,
                                                subLine = subLine,
                                                subRevealProvider = { subReveal.value },
                                                isSynced = isSynced,
                                                isActive = isActive,
                                                isFocusLine = index == focusLine,
                                                distanceFromActive = distance,
                                                hasDuet = hasDuet,
                                                clock = lyricClock,
                                                blurEnabled = blurUnfocusedLyrics && !browsing && isSynced,
                                                isBrowsing = browsing,
                                                picking = picking,
                                                selectedForShare = isSelected,
                                                reduceAnimation = reduceAnimation,
                                                handoverOffsetProvider = {
                                                    val p = handoverProgress.value
                                                    val behind = index - handoverRun.focusIndex
                                                    if (p >= 0.999f || behind !in 1..STAGGER_STEPS || handoverRun.focusIndex < 0) {
                                                        0f
                                                    } else {
                                                        val delayFrac = behind * STAGGER_FRACTION
                                                        val lead = LYRIC_EASING.transform(p.coerceIn(0f, 1f))
                                                        val lagged = LYRIC_EASING.transform(((p - delayFrac) / (1f - delayFrac)).coerceIn(0f, 1f))
                                                        handoverRun.deltaPx * (lead - lagged)
                                                    }
                                                },
                                                particleProgressProvider = if (showParticles) ({ particleProgress.value }) else null,
                                                particleSeed = particleSeed,
                                                onClick = {
                                                    if (picking) {
                                                        if (isSelected) {
                                                            selectedLineIndices = selectedLineIndices - index
                                                            if (selectedLineIndices.isEmpty()) setPicking(false)
                                                        } else {
                                                            val nextSet = selectedLineIndices + index
                                                            if (LyricsShareCardRenderer.charCountOf(lines, nextSet) > LyricsShareCardRenderer.SHARE_CHAR_BUDGET) {
                                                                Toast.makeText(context, cardFullToast, Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                selectedLineIndices = nextSet
                                                            }
                                                        }
                                                    } else if (isSynced) {
                                                        browsingByDrag = false
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        onSeekTo((line.startMs - result.offsetMs).coerceAtLeast(0L))
                                                    }
                                                },
                                                onLongPress = {
                                                    if (song != null && !SyncedLyricsParser.isSectionHeader(line.text)) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        setPicking(true)
                                                        selectedLineIndices = setOf(index)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                // Floating bottom Romanize (start) & Translate (end) toggles
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showInlineControls && !picking,
                                    enter = fadeIn(tween(200)),
                                    exit = fadeOut(tween(180)),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        LyricsCircleToggleButton(
                                            icon = Icons.Rounded.Language,
                                            contentDescription = stringResource(R.string.lyrics_romanize),
                                            active = displayMode == LyricsDisplayMode.ROMANIZED,
                                            loading = translationLoading && displayMode == LyricsDisplayMode.ROMANIZED,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onToggleRomanize()
                                            },
                                        )
                                        LyricsCircleToggleButton(
                                            icon = Icons.Rounded.Translate,
                                            contentDescription = stringResource(R.string.lyrics_translate),
                                            active = displayMode == LyricsDisplayMode.TRANSLATED,
                                            loading = translationLoading && displayMode == LyricsDisplayMode.TRANSLATED,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onToggleTranslate()
                                            },
                                        )
                                    }
                                }

                                // Floating LyricsPickBar (Cancel / Share) during multi-line picking
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = picking,
                                    enter = fadeIn(tween(180)),
                                    exit = fadeOut(tween(160)),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                ) {
                                    LyricsPickBar(
                                        selectedCount = selectedLineIndices.size,
                                        onCancel = { setPicking(false) },
                                        onShare = {
                                            val picked = LyricsShareCardRenderer.buildPickedLines(
                                                lines = lines,
                                                subLines = renderedSubLines,
                                                selectedIndices = selectedLineIndices,
                                            )
                                            if (picked.isNotEmpty()) {
                                                sharePickedLines = picked
                                                setPicking(false)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Capture live player backdrop when opening share sheet (hoist to root overlay when wired)
        LaunchedEffect(sharePickedLines) {
            val picked = sharePickedLines
            if (picked != null) {
                val bmp = runCatching { captureBackdropBitmap?.invoke() }.getOrNull()
                if (onRequestOpenShareSheet != null && song != null) {
                    sharePickedLines = null
                    onRequestOpenShareSheet(song, picked, bmp)
                } else {
                    capturedBackdrop = bmp
                }
            } else {
                capturedBackdrop = null
            }
        }

        if (showOffsetSheet) {
            val currentOffset = lyricsData?.offsetMs ?: 0L
            LyricsOffsetSheet(
                currentOffsetMs = currentOffset,
                hazeState = lyricsHazeState,
                onCommitOffset = onChangeOffset,
                onDismiss = { showOffsetSheet = false },
            )
        }

        if (showProviderSheet) {
            LyricsProviderSheet(
                currentSource = lyricsData?.source,
                providerOrder = providerOrder,
                providerStatuses = providerStatuses,
                hazeState = lyricsHazeState,
                onSelect = { src ->
                    onSelectProvider(src)
                    if (providerStatuses[src] == LyricsProviderStatus.FOUND) {
                        showProviderSheet = false
                    }
                },
                onDismiss = { showProviderSheet = false },
            )
        }

        val pendingShare = sharePickedLines
        if (pendingShare != null && song != null && onRequestOpenShareSheet == null) {
            LyricsShareSheet(
                song = song,
                pickedLines = pendingShare,
                backdropBitmap = capturedBackdrop,
                showLogo = showLyricsCardLogo,
                hazeState = lyricsHazeState,
                onToggleShowLogo = onSetShowLyricsCardLogo,
                onDismiss = { sharePickedLines = null },
            )
        }
    }
}

// ---- Individual Lyric Row (Duet + Sweep + Lift + Wave + Backing + Sub) ------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProLyricLineRow(
    line: LyricLine,
    subLine: LyricLine?,
    subRevealProvider: () -> Float,
    isSynced: Boolean,
    isActive: Boolean,
    isFocusLine: Boolean,
    distanceFromActive: Int,
    hasDuet: Boolean,
    clock: MutableLongState,
    blurEnabled: Boolean,
    isBrowsing: Boolean,
    picking: Boolean,
    selectedForShare: Boolean,
    reduceAnimation: Boolean,
    handoverOffsetProvider: () -> Float,
    particleProgressProvider: (() -> Float)?,
    particleSeed: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Genius [Verse] / [Chorus] pill header in unsynced lyrics
    if (!isSynced && SyncedLyricsParser.isSectionHeader(line.text)) {
        val label = line.text.trim().removePrefix("[").removeSuffix("]").trim().uppercase()
        Box(
            modifier = Modifier
                .padding(horizontal = GLOW_ROOM, vertical = 4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White.copy(alpha = 0.80f),
            )
        }
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val alignEnd = hasDuet && line.alignment == LyricAlignment.END

    val targetScale = when {
        reduceAnimation -> 1f
        pressed -> PRESSED_SCALE
        !isSynced || isActive -> 1.0f
        else -> INACTIVE_SCALE
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (pressed) tween(120) else tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
        label = "lyric_row_scale",
    )

    val targetAlpha = when {
        picking -> if (selectedForShare) 1f else 0.42f
        !isSynced -> 0.95f
        isActive -> 1f
        isBrowsing -> BROWSING_ALPHA
        else -> LINE_FALLOFF_ALPHA[distanceFromActive.coerceIn(0, LINE_FALLOFF_ALPHA.lastIndex)]
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = if (reduceAnimation) 0 else LYRIC_SETTLE_MS, easing = LYRIC_EASING),
        label = "lyric_row_alpha",
    )

    val targetBlur = when {
        !blurEnabled || isActive || reduceAnimation || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 0.dp
        else -> LINE_FALLOFF_BLUR[distanceFromActive.coerceIn(0, LINE_FALLOFF_BLUR.lastIndex)]
    }
    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = tween(durationMillis = if (reduceAnimation) 0 else LYRIC_SETTLE_MS, easing = LYRIC_EASING),
        label = "lyric_row_blur",
    )

    val fontSize = when {
        line.isBackgroundVocal -> 23.sp
        isSynced -> 34.sp
        else -> 30.sp
    }
    val lineHeight = when {
        line.isBackgroundVocal -> 29.sp
        isSynced -> 41.sp
        else -> 38.sp
    }

    val startLanePad = if (hasDuet && alignEnd) DUET_LANE else 0.dp
    val endLanePad = if (hasDuet && !alignEnd) DUET_LANE else 0.dp
    val isRtlText = remember(line.text) { isRtlScript(line.text) }
    val physicalAlign = when {
        hasDuet && alignEnd -> TextAlign.Right
        hasDuet -> TextAlign.Left
        isRtlText -> TextAlign.Right
        else -> TextAlign.Left
    }

    val bgVocal = line.background
    val backingOpen by remember(bgVocal, isSynced, isActive, isFocusLine, picking) {
        derivedStateOf(structuralEqualityPolicy()) {
            when {
                bgVocal == null -> false
                !isSynced || picking -> true
                bgVocal.isWordSynced -> {
                    val now = clock.longValue
                    now >= (bgVocal.startMs - BACKING_LEAD_MS) && now < bgVocal.effectiveEndMs
                }
                else -> isActive || isFocusLine
            }
        }
    }
    val backingAnim = remember(bgVocal) { Animatable(if (backingOpen) 1f else 0f) }
    LaunchedEffect(backingOpen, reduceAnimation) {
        if (bgVocal == null) return@LaunchedEffect
        val target = if (backingOpen) 1f else 0f
        if (reduceAnimation) {
            backingAnim.snapTo(target)
        } else {
            backingAnim.animateTo(
                targetValue = target,
                animationSpec = if (backingOpen) {
                    tween(BACKING_OPEN_MS, easing = LYRIC_EASING)
                } else {
                    tween(BACKING_CLOSE_MS, easing = FastOutSlowInEasing)
                },
            )
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startLanePad, end = endLanePad)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = handoverOffsetProvider()
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                }
                .let { mod -> if (blurRadius > 0.dp) mod.blur(blurRadius, BlurredEdgeTreatment.Unbounded) else mod }
                .alpha(alpha)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (picking && selectedForShare) {
                        Modifier
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            val glowEnabled = !reduceAnimation && blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SweptLyricLine(
                line = line,
                clock = clock,
                isSynced = isSynced,
                isActive = isActive,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = FontWeight.ExtraBold,
                textAlign = physicalAlign,
                unsungAlpha = 0.45f,
                enableWordLift = isSynced && !line.isBackgroundVocal && !reduceAnimation,
                enableGrowingWave = isSynced && !line.isBackgroundVocal && !reduceAnimation,
                enableBloom = glowEnabled && !line.isBackgroundVocal,
                particleProgressProvider = particleProgressProvider,
                particleSeed = particleSeed,
                modifier = Modifier.fillMaxWidth(),
            )

            // Translated / Romanized sub-line directly beneath the lead line
            if (subLine != null) {
                val subRevealVal = subRevealProvider()
                if (subRevealVal > 0.001f) {
                    val subRtl = remember(subLine.text) { isRtlScript(subLine.text) }
                    val subAlign = when {
                        hasDuet && alignEnd -> TextAlign.Right
                        hasDuet -> TextAlign.Left
                        subRtl -> TextAlign.Right
                        else -> TextAlign.Left
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .revealAccordion(subRevealVal, alignEnd = alignEnd),
                    ) {
                        SweptLyricLine(
                            line = subLine,
                            clock = clock,
                            isSynced = isSynced,
                            isActive = isActive,
                            fontSize = 20.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = subAlign,
                            baseColor = Color.White.copy(alpha = 0.85f),
                            unsungAlpha = 0.48f,
                            enableWordLift = false,
                            enableGrowingWave = false,
                            enableBloom = false,
                            roomPadding = 4.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Dynamic accordion background vocal (`x-bg` or trailing `(...)`)
            if (bgVocal != null) {
                val open = backingAnim.value
                if (open > 0.001f) {
                    val cleanBg = remember(bgVocal) { bgVocal.withoutOuterParentheses() }
                    val bgSub = subLine?.background?.takeIf { !it.text.equals(cleanBg.text, ignoreCase = true) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .revealAccordion(open, alignEnd = alignEnd),
                        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
                    ) {
                        SweptLyricLine(
                            line = cleanBg,
                            clock = clock,
                            isSynced = isSynced,
                            isActive = isActive || backingOpen,
                            fontSize = 23.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = physicalAlign,
                            baseColor = Color.White.copy(alpha = 0.72f),
                            unsungAlpha = 0.45f,
                            enableWordLift = false,
                            enableGrowingWave = false,
                            enableBloom = false,
                            roomPadding = 4.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (bgSub != null && subRevealProvider() > 0.001f) {
                            Box(modifier = Modifier.revealAccordion(subRevealProvider(), alignEnd = alignEnd)) {
                                SweptLyricLine(
                                    line = bgSub,
                                    clock = clock,
                                    isSynced = isSynced,
                                    isActive = isActive || backingOpen,
                                    fontSize = 16.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = physicalAlign,
                                    baseColor = Color.White.copy(alpha = 0.62f),
                                    unsungAlpha = 0.48f,
                                    enableWordLift = false,
                                    enableGrowingWave = false,
                                    enableBloom = false,
                                    roomPadding = 2.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Pro 3-Layer SweptLyricLine (Reusable in Panel & Player Preview Strip) --

@Composable
internal fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    isSynced: Boolean,
    isActive: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    baseColor: Color = Color.White,
    unsungAlpha: Float = 0.45f,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    enableWordLift: Boolean = true,
    enableGrowingWave: Boolean = true,
    enableBloom: Boolean = true,
    roomPadding: Dp = GLOW_ROOM,
    particleProgressProvider: (() -> Float)? = null,
    particleSeed: Int = 0,
) {
    // Zero-redraw phase gate: inactive rows detach from the 60fps clock completely
    val phase by remember(line, isSynced) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (!isSynced || !line.isWordSynced) return@derivedStateOf PHASE_AFTER
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PHASE_BEFORE
                now > line.animatesUntilMs -> PHASE_AFTER
                else -> PHASE_MOVING
            }
        }
    }

    val isRtl = remember(line.text) { isRtlScript(line.text) }
    val textStyle = remember(fontSize, lineHeight, fontWeight, textAlign, isRtl) {
        TextStyle(
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            letterSpacing = (-0.4).sp,
            textAlign = textAlign,
            textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr,
        )
    }

    // Fast path for plain or line-synced lines
    if (!isSynced || !line.isWordSynced) {
        Box(modifier = modifier.padding(horizontal = roomPadding, vertical = roomPadding / 2)) {
            Text(
                text = line.text,
                style = textStyle,
                color = baseColor,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val dimAlpha by animateFloatAsState(
        targetValue = if (phase == PHASE_AFTER) 1f else unsungAlpha,
        animationSpec = tween(durationMillis = 280),
        label = "swept_dim_alpha",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (enableBloom && phase == PHASE_MOVING && line.growingWords.isNotEmpty()) GLOW_ALPHA else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "swept_glow_alpha",
    )

    var layoutResult by remember(line.text, fontSize) { mutableStateOf<TextLayoutResult?>(null) }
    val growthScratch = remember { CharGrowth() }

    Box(modifier = modifier.padding(horizontal = roomPadding, vertical = roomPadding / 2)) {
        // 1. Dim base text with word lift / held-word growth
        Text(
            text = line.text,
            style = textStyle,
            color = baseColor.copy(alpha = baseColor.alpha * dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val layout = layoutResult
                    if (phase != PHASE_MOVING || layout == null || (!enableWordLift && !enableGrowingWave)) {
                        drawContent()
                    } else {
                        val now = clock.longValue
                        drawLiftedAndGrown(
                            line = line,
                            layout = layout,
                            nowMs = now,
                            enableLift = enableWordLift,
                            enableGrow = enableGrowingWave,
                            scratch = growthScratch,
                            drawContent = { drawContent() },
                        )
                    }
                },
        )

        // 2. Bloom glow layer strictly clipped & masked onto active GrowingWord characters (API 31+)
        if (glowAlpha > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(
                text = line.text,
                style = textStyle,
                color = baseColor.copy(alpha = glowAlpha),
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .blur(GLOW_RADIUS, BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        val layout = layoutResult ?: return@drawWithContent
                        val now = clock.longValue
                        var anyBloom = false
                        for (gw in line.growingWords) {
                            if (now in gw.startMs..gw.restsAtMs) {
                                anyBloom = true
                                break
                            }
                        }
                        if (!anyBloom) return@drawWithContent
                        drawContent()
                        // Keep glow ONLY over growing word letters proportional to growth.bloom
                        val pad = GLOW_ROOM.toPx()
                        drawRect(
                            color = Color.Transparent,
                            topLeft = Offset(-pad, -pad),
                            size = Size(size.width + pad * 2f, size.height + pad * 2f),
                            blendMode = BlendMode.DstIn,
                        )
                        // Re-draw per-character bloom masks cleanly
                        for (gw in line.growingWords) {
                            if (now !in gw.startMs..gw.restsAtMs) continue
                            for (cIdx in 0..(gw.textRange.last - gw.textRange.first)) {
                                gw.sampleInto(cIdx, now, growthScratch)
                                val b = growthScratch.bloom
                                if (b <= 0.01f) continue
                                val textIdx = (gw.textRange.first + cIdx).coerceIn(0, line.text.lastIndex)
                                val box = runCatching { layout.getBoundingBox(textIdx) }.getOrNull() ?: continue
                                clipRect(
                                    left = box.left - pad,
                                    top = box.top - pad,
                                    right = box.right + pad,
                                    bottom = box.bottom + pad,
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                    },
            )
        }

        // 3. Lit swept layer with pixel-constant syllable sweep + 30dp DstIn feather
        if (phase != PHASE_BEFORE) {
            Text(
                text = line.text,
                style = textStyle,
                color = baseColor,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (isActive && phase == PHASE_MOVING) {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    }
                    .drawWithContent {
                        val layout = layoutResult
                        if (phase == PHASE_AFTER || layout == null) {
                            drawContent()
                            return@drawWithContent
                        }
                        val now = clock.longValue
                        val revealed = line.revealedChars(now)
                        if (revealed <= 0f) return@drawWithContent

                        drawLiftedAndGrown(
                            line = line,
                            layout = layout,
                            nowMs = now,
                            enableLift = enableWordLift,
                            enableGrow = enableGrowingWave,
                            scratch = growthScratch,
                            drawContent = { drawContent() },
                        )

                        // Apply per-visual-row feathered horizontal DstIn sweep mask
                        val featherMaxPx = WIPE_FEATHER.toPx()
                        val charLen = line.text.length
                        val clampedPos = revealed.coerceIn(0f, charLen.toFloat())
                        val headRoom = WORD_RISE.toPx() * GrowingWord.GROW_HEADROOM + 4f

                        for (visualLine in 0 until layout.lineCount) {
                            val lineStart = layout.getLineStart(visualLine)
                            val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
                            val top = layout.getLineTop(visualLine) - headRoom
                            val bottom = layout.getLineBottom(visualLine) + 4f
                            val rowH = (bottom - top).coerceAtLeast(1f)
                            val dir = layout.getParagraphDirection(lineStart)
                            val rtlRow = dir == ResolvedTextDirection.Rtl

                            when {
                                clampedPos >= lineEnd -> Unit // Fully lit row
                                clampedPos <= lineStart -> {
                                    // Unsung row -> clear completely via DstIn transparent
                                    drawRect(
                                        color = Color.Transparent,
                                        topLeft = Offset(0f, top),
                                        size = Size(size.width, rowH),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                                else -> {
                                    val sweepX = horizontalAtCharPos(layout, line, visualLine, lineStart, lineEnd, clampedPos, rtlRow)
                                    val lineLeft = layout.getLineLeft(visualLine)
                                    val lineRight = layout.getLineRight(visualLine)
                                    if (!rtlRow) {
                                        val progressPx = (sweepX - lineLeft).coerceAtLeast(0f)
                                        val feather = progressPx.coerceAtMost(featherMaxPx)
                                        val fadeEnd = (sweepX + feather).coerceAtMost(size.width)
                                        if (fadeEnd > sweepX) {
                                            drawRect(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startX = sweepX,
                                                    endX = fadeEnd,
                                                ),
                                                topLeft = Offset(sweepX, top),
                                                size = Size(fadeEnd - sweepX, rowH),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                        if (fadeEnd < size.width) {
                                            drawRect(
                                                color = Color.Transparent,
                                                topLeft = Offset(fadeEnd, top),
                                                size = Size(size.width - fadeEnd, rowH),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                    } else {
                                        val progressPx = (lineRight - sweepX).coerceAtLeast(0f)
                                        val feather = progressPx.coerceAtMost(featherMaxPx)
                                        val fadeStart = (sweepX - feather).coerceAtLeast(0f)
                                        if (sweepX > fadeStart) {
                                            drawRect(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black),
                                                    startX = fadeStart,
                                                    endX = sweepX,
                                                ),
                                                topLeft = Offset(fadeStart, top),
                                                size = Size(sweepX - fadeStart, rowH),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                        if (fadeStart > 0f) {
                                            drawRect(
                                                color = Color.Transparent,
                                                topLeft = Offset(0f, top),
                                                size = Size(fadeStart, rowH),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }

        // Optional subtle upward sparkle particles during translate/romanize reveal
        if (particleProgressProvider != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val p = particleProgressProvider()
                if (p <= 0f || p >= 1f) return@Canvas
                val alphaEnv = sin(p * Math.PI).toFloat().coerceIn(0f, 1f)
                val riseMax = 24.dp.toPx()
                for (i in 0 until PARTICLE_COUNT) {
                    val h = ((particleSeed * 1103515245 + i * 12345) ushr 1)
                    val xFrac = ((h % 1000) / 1000f).coerceIn(0.04f, 0.96f)
                    val yFrac = (((h / 1000) % 1000) / 1000f).coerceIn(0.15f, 0.85f)
                    val driftX = (cos(i * 1.7 + p * 3.0) * 6.dp.toPx()).toFloat()
                    val cx = size.width * xFrac + driftX
                    val cy = size.height * yFrac - riseMax * p
                    val r = (2.2.dp.toPx() + (i % 3) * 0.8.dp.toPx()) * (1f - 0.35f * p)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f * alphaEnv),
                        radius = r * 2.1f,
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.68f * alphaEnv),
                        radius = r,
                        center = Offset(cx, cy),
                    )
                }
            }
        }
    }
}

/**
 * Draws [drawContent] while applying per-word vertical lift (`2.dp`) and per-character held-note
 * wave scale/shift/rise without per-frame heap allocations.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiftedAndGrown(
    line: LyricLine,
    layout: TextLayoutResult,
    nowMs: Long,
    enableLift: Boolean,
    enableGrow: Boolean,
    scratch: CharGrowth,
    drawContent: () -> Unit,
) {
    val risePx = WORD_RISE.toPx()
    val headRoom = risePx * GrowingWord.GROW_HEADROOM + 4f
    val wordSpans = line.wordSpans
    if (wordSpans.isEmpty()) {
        drawContent()
        return
    }

    val growingByWord = line.growingWords.associateBy { it.wordIndex }
    var anyActiveTransform = false
    for (i in wordSpans.indices) {
        if (enableLift && line.wordLift(i, nowMs) > 0.005f) {
            anyActiveTransform = true
            break
        }
        val gw = growingByWord[i]
        if (enableGrow && gw != null && nowMs in gw.startMs..gw.restsAtMs) {
            anyActiveTransform = true
            break
        }
    }
    if (!anyActiveTransform) {
        drawContent()
        return
    }

    val emPx = layout.layoutInput.style.fontSize.toPx().coerceAtLeast(16f)

    // Draw flat untouched portions per visual line, and draw lifted/grown words in isolated clips
    for (visualLine in 0 until layout.lineCount) {
        val vStart = layout.getLineStart(visualLine)
        val vEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        val vTop = layout.getLineTop(visualLine)
        val vBottom = layout.getLineBottom(visualLine)
        val vLeft = layout.getLineLeft(visualLine)
        val vRight = layout.getLineRight(visualLine)

        // Collect lifted/grown word indices touching this visual line
        val activeSlices = mutableListOf<Pair<Int, Rect>>()
        for (wIdx in wordSpans.indices) {
            val span = wordSpans[wIdx]
            if (span.last < vStart || span.first >= vEnd) continue
            val lift = if (enableLift) line.wordLift(wIdx, nowMs) else 0f
            val gw = if (enableGrow) growingByWord[wIdx] else null
            val growingNow = gw != null && nowMs in gw.startMs..gw.restsAtMs
            if (lift <= 0.005f && !growingNow) continue

            val sChar = span.first.coerceIn(vStart, (vEnd - 1).coerceAtLeast(vStart))
            val eChar = span.last.coerceIn(sChar, (vEnd - 1).coerceAtLeast(sChar))
            val bStart = runCatching { layout.getBoundingBox(sChar) }.getOrNull() ?: continue
            val bEnd = runCatching { layout.getBoundingBox(eChar) }.getOrNull() ?: continue
            val left = minOf(bStart.left, bEnd.left)
            val right = maxOf(bStart.right, bEnd.right)
            activeSlices += wIdx to Rect(left, vTop, right, vBottom)
        }

        if (activeSlices.isEmpty()) {
            clipRect(left = vLeft - 2f, top = vTop - 2f, right = vRight + 2f, bottom = vBottom + 2f) {
                drawContent()
            }
            continue
        }

        // Draw flat gaps between active slices
        val sortedSlices = activeSlices.sortedBy { it.second.left }
        var cursorX = vLeft - 2f
        for ((_, rect) in sortedSlices) {
            if (rect.left > cursorX + 0.5f) {
                clipRect(left = cursorX, top = vTop - 2f, right = rect.left, bottom = vBottom + 2f) {
                    drawContent()
                }
            }
            cursorX = max(cursorX, rect.right)
        }
        if (cursorX < vRight + 2f) {
            clipRect(left = cursorX, top = vTop - 2f, right = vRight + 2f, bottom = vBottom + 2f) {
                drawContent()
            }
        }

        // Draw active lifted / grown words
        for ((wIdx, rect) in sortedSlices) {
            val gw = if (enableGrow) growingByWord[wIdx] else null
            val lift = if (enableLift) line.wordLift(wIdx, nowMs) else 0f
            if (gw != null && nowMs in gw.startMs..gw.restsAtMs) {
                val span = wordSpans[wIdx]
                for (cIdx in 0..(span.last - span.first)) {
                    val textIdx = span.first + cIdx
                    if (textIdx < vStart || textIdx >= vEnd) continue
                    gw.sampleInto(cIdx, nowMs, scratch)
                    val cBox = runCatching { layout.getBoundingBox(textIdx) }.getOrNull() ?: continue
                    val effectiveRise = maxOf(lift, scratch.riseUnits * (if (nowMs > gw.endMs) lift else 1f))
                    val ty = -risePx * effectiveRise
                    val tx = scratch.shiftEm * emPx
                    withTransform({
                        translate(left = tx, top = ty)
                        scale(
                            scaleX = scratch.scale,
                            scaleY = scratch.scale,
                            pivot = Offset(cBox.center.x, cBox.center.y),
                        )
                    }) {
                        clipRect(
                            left = cBox.left - 1f,
                            top = vTop - headRoom,
                            right = cBox.right + 1f,
                            bottom = vBottom + 4f,
                        ) {
                            drawContent()
                        }
                    }
                }
            } else {
                withTransform({ translate(top = -risePx * lift) }) {
                    clipRect(
                        left = rect.left - 1f,
                        top = vTop - headRoom,
                        right = rect.right + 1f,
                        bottom = vBottom + 4f,
                    ) {
                        drawContent()
                    }
                }
            }
        }
    }
}

/**
 * Computes pixel X along [visualLine] at fractional character position [charPos], interpolating
 * linearly across the pixel width of the active syllable/word span for constant visual velocity.
 */
private fun horizontalAtCharPos(
    layout: TextLayoutResult,
    line: LyricLine,
    visualLine: Int,
    lineStart: Int,
    lineEnd: Int,
    charPos: Float,
    rtlRow: Boolean,
): Float {
    val maxIdx = (line.text.length - 1).coerceAtLeast(0)
    val intIdx = charPos.toInt().coerceIn(lineStart, (lineEnd - 1).coerceAtLeast(lineStart)).coerceAtMost(maxIdx)
    val span = line.sweepSpans.firstOrNull { intIdx in it }
    if (span != null && span.first >= lineStart && span.last < lineEnd) {
        val spanLen = (span.last - span.first + 1).coerceAtLeast(1)
        val frac = ((charPos - span.first) / spanLen.toFloat()).coerceIn(0f, 1f)
        val firstBox = runCatching { layout.getBoundingBox(span.first) }.getOrNull()
        val lastBox = runCatching { layout.getBoundingBox(span.last) }.getOrNull()
        if (firstBox != null && lastBox != null) {
            return if (!rtlRow) {
                val startX = minOf(firstBox.left, lastBox.left)
                val endX = maxOf(firstBox.right, lastBox.right)
                startX + (endX - startX) * frac
            } else {
                val startX = maxOf(firstBox.right, lastBox.right)
                val endX = minOf(firstBox.left, lastBox.left)
                startX + (endX - startX) * frac
            }
        }
    }
    val box = runCatching { layout.getBoundingBox(intIdx) }.getOrNull() ?: return layout.getLineLeft(visualLine)
    val charFrac = (charPos - intIdx).coerceIn(0f, 1f)
    return if (!rtlRow) {
        box.left + (box.right - box.left) * charFrac
    } else {
        box.right - (box.right - box.left) * charFrac
    }
}

// ---- Dynamic Accordion 3-Dot Instrumental Countdown -------------------------

@Composable
private fun InstrumentalAccordionDots(
    line: LyricLine,
    nextStartMs: Long,
    isActive: Boolean,
    clock: MutableLongState,
    reduceAnimation: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    val swell by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = if (reduceAnimation) {
            tween(0)
        } else if (isActive) {
            tween(400, easing = LYRIC_EASING)
        } else {
            tween(350, easing = FastOutSlowInEasing)
        },
        label = "gap_dots_swell",
    )
    if (swell <= 0.005f) return

    val totalHeight = (GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell
    val scale = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
    val duration = (nextStartMs - line.startMs).coerceAtLeast(1L).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeight)
            .padding(horizontal = GLOW_ROOM)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = swell
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .drawBehind {
                    val now = clock.longValue
                    val through = ((now - line.startMs) / duration).coerceIn(0f, 1f)
                    val dotPx = GAP_DOT_SIZE.toPx()
                    val gapPx = GAP_DOT_GAP.toPx()
                    val r = dotPx / 2f
                    for (d in 0..2) {
                        val dotFill = (through * 3f - d).coerceIn(0f, 1f)
                        val dotAlpha = 0.25f + 0.75f * dotFill
                        drawCircle(
                            color = Color.White.copy(alpha = dotAlpha),
                            radius = r,
                            center = Offset(r + d * (dotPx + gapPx), size.height / 2f),
                        )
                    }
                },
        )
    }
}

// ---- Floating Controls, Pick Bar & Sheets -----------------------------------

@Composable
private fun LyricsCircleToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .then(
                if (active) {
                    Modifier.clip(CircleShape).background(Color.White)
                } else {
                    Modifier.lightweightGlass(CircleShape, onDarkBackdrop = true)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = if (active) Color.Black else Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) Color.Black else Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun LyricsPickBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onCancel)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.90f),
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selectedCount > 0) Color.White else Color.White.copy(alpha = 0.25f))
                .clickable(enabled = selectedCount > 0, onClick = onShare)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.Share,
                contentDescription = null,
                tint = if (selectedCount > 0) Color.Black else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.menu_share),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (selectedCount > 0) Color.Black else Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun EmptyLyricsNotice(
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.lyrics_unavailable),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        GlassChip(
            label = stringResource(R.string.lyrics_provider_title),
            onClick = onOpenProviders,
        )
    }
}

@Composable
private fun GlassChip(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/**
 * Frosted lyrics timing offset drawer spanning `-5.0s..+5.0s` in `100ms` increments.
 */
@Composable
fun LyricsOffsetSheet(
    currentOffsetMs: Long,
    hazeState: HazeState?,
    onCommitOffset: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftOffsetMs by remember(currentOffsetMs) { mutableLongStateOf(currentOffsetMs.coerceIn(-5_000L, 5_000L)) }
    val fg = glassContentColor()

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_offset_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GlassDialogButton(
                    text = stringResource(R.string.lyrics_offset_reset),
                    onClick = {
                        draftOffsetMs = 0L
                        onCommitOffset(0L)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassDialogButton(
                    text = "−0.1s",
                    onClick = {
                        draftOffsetMs = (draftOffsetMs - 100L).coerceIn(-5_000L, 5_000L)
                        onCommitOffset(draftOffsetMs)
                    },
                )
                Text(
                    text = "%+.1f s".format(draftOffsetMs / 1000f),
                    style = MaterialTheme.typography.headlineMedium,
                    color = fg,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                GlassDialogButton(
                    text = "+0.1s",
                    onClick = {
                        draftOffsetMs = (draftOffsetMs + 100L).coerceIn(-5_000L, 5_000L)
                        onCommitOffset(draftOffsetMs)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            ThinSlider(
                value = draftOffsetMs.toFloat(),
                onValueChange = { draftOffsetMs = ((it / 100f).roundToInt() * 100L).coerceIn(-5_000L, 5_000L) },
                onValueChangeFinished = { onCommitOffset(draftOffsetMs) },
                valueRange = -5_000f..5_000f,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = fg.copy(alpha = 0.22f),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Interactive provider switcher showing all 12 keyless sources with live per-track status
 * (`FOUND`, `FETCHING`, `NOT_FETCHED`, `NOT_FOUND`).
 */
@Composable
fun LyricsProviderSheet(
    currentSource: LyricsSourceId?,
    providerOrder: List<LyricsSourceId>,
    providerStatuses: Map<LyricsSourceId, LyricsProviderStatus>,
    hazeState: HazeState?,
    onSelect: (LyricsSourceId) -> Unit,
    onDismiss: () -> Unit,
) {
    val fg = glassContentColor()
    val ordered = remember(providerOrder) { LyricsSourceId.mergeOrder(providerOrder) }

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.lyrics_provider_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = fg,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
            ) {
                itemsIndexed(ordered, key = { _, src -> src.name }) { _, src ->
                    val status = providerStatuses[src] ?: LyricsProviderStatus.NOT_FETCHED
                    val isCurrent = src == currentSource
                    val statusSubtitle = when {
                        isCurrent -> stringResource(R.string.lyrics_provider_in_use)
                        status == LyricsProviderStatus.FOUND -> stringResource(R.string.lyrics_provider_found)
                        status == LyricsProviderStatus.FETCHING -> stringResource(R.string.lyrics_provider_fetching)
                        status == LyricsProviderStatus.NOT_FOUND -> stringResource(R.string.lyrics_provider_not_found)
                        else -> stringResource(R.string.lyrics_provider_tap_to_fetch)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(src) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else fg,
                            )
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = fg.copy(alpha = 0.62f),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        when {
                            isCurrent -> Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            status == LyricsProviderStatus.FETCHING -> CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            status == LyricsProviderStatus.FOUND -> Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = fg.copy(alpha = 0.78f),
                                modifier = Modifier.size(19.dp),
                            )
                            else -> Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = fg.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.revealAccordion(progress: Float, alignEnd: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val h = (p.height * progress.coerceIn(0f, 1f)).roundToInt()
        layout(p.width, h) {
            p.place(0, 0)
        }
    }
    .graphicsLayer {
        val c = progress.coerceIn(0f, 1f)
        alpha = (c * 1.6f).coerceAtMost(1f)
        val sc = 0.94f + 0.06f * c
        scaleX = sc
        scaleY = sc
        translationY = (6.dp.toPx()) * (1f - c)
        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
    }

internal fun isRtlScript(text: String): Boolean {
    for (ch in text) {
        val dir = Character.getDirectionality(ch)
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        ) {
            return true
        }
        if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
    }
    return false
}
