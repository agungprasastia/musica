package com.music.musica.ui.player

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.UiState
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.lyrics.SyncedLyricsParser
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LiquidGlassMenuRow
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

private val AppleWaveEasing = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

/**
 * Word/syllable-synced lyrics panel featuring:
 * - Monotonic 60fps interpolated clock active only while synced lyrics are playing
 * - Progressive blur on inactive lines (0 / 1 / 1.7 / 2.3dp) clearing immediately on manual scroll
 * - Active syllable glow shadow, 2dp word lift, and 3-dot instrumental countdown between long gaps
 * - 28dp top & bottom DstIn fading edges, long-press line share, shimmer skeleton, and ThinSlider offset
 */
@Composable
fun SyncedLyricsPanel(
    lyricsState: UiState<LyricsResult?>,
    playerPositionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    blurUnfocusedLyrics: Boolean = true,
    reduceAnimation: Boolean,
    onSeekTo: (Long) -> Unit,
    onChangeOffset: (Long) -> Unit,
    onSwitchProvider: (LyricsSourceId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOffsetSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val syncedResult = (lyricsState as? UiState.Success)?.data?.takeIf { it.isSynced && it.lines.isNotEmpty() }

    // Keep 60fps interpolated position inside a State object read ONLY via derivedStateOf or
    // lambda providers so outer SyncedLyricsPanel and inactive lyric rows never recompose at 60fps.
    val smoothPosState = remember { mutableLongStateOf(playerPositionMs) }
    LaunchedEffect(playerPositionMs, isPlaying, playbackSpeed, syncedResult != null) {
        if (syncedResult == null) {
            smoothPosState.longValue = playerPositionMs
            return@LaunchedEffect
        }
        val drift = smoothPosState.longValue - playerPositionMs
        val startPos = if (isPlaying && drift in 1L..300L) smoothPosState.longValue else playerPositionMs
        val anchorTime = SystemClock.elapsedRealtime()
        smoothPosState.longValue = startPos
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                val elapsed = SystemClock.elapsedRealtime() - anchorTime
                smoothPosState.longValue = startPos + (elapsed * playbackSpeed).toLong()
            }
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
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.80f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassChip(
                        label = stringResource(R.string.lyrics_provider_title),
                        onClick = { showProviderSheet = true },
                    )
                }
            }

            is UiState.Success -> {
                val result = lyricsState.data
                if (result == null || result.lines.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
                            onClick = { showProviderSheet = true },
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    var isBrowsing by remember(result) { mutableStateOf(false) }

                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            isBrowsing = true
                        } else if (isBrowsing) {
                            delay(MANUAL_SCROLL_COOLDOWN_MS)
                            isBrowsing = false
                        }
                    }

                    val activeIndex by remember(result) {
                        derivedStateOf {
                            if (result.isSynced) {
                                SyncedLyricsParser.activeLineIndex(result.lines, smoothPosState.longValue, result.offsetMs)
                            } else {
                                -1
                            }
                        }
                    }

                    val showIntroDots by remember(result) {
                        derivedStateOf {
                            result.isSynced &&
                                result.lines.first().startMs > 5_000L &&
                                (smoothPosState.longValue + result.offsetMs) < result.lines.first().startMs
                        }
                    }

                    val activeGapIndex by remember(result) {
                        derivedStateOf {
                            if (!result.isSynced || activeIndex < 0) {
                                -1
                            } else {
                                val line = result.lines.getOrNull(activeIndex)
                                val next = result.lines.getOrNull(activeIndex + 1)
                                val pos = smoothPosState.longValue + result.offsetMs
                                if (line != null && next != null && (next.startMs - line.endMs) >= 5_500L && pos in line.endMs..next.startMs) {
                                    activeIndex
                                } else {
                                    -1
                                }
                            }
                        }
                    }

                    LaunchedEffect(activeIndex, isBrowsing) {
                        if (activeIndex >= 0 && !isBrowsing && !listState.isScrollInProgress) {
                            if (reduceAnimation) {
                                listState.scrollToItem(activeIndex, scrollOffset = -200)
                            } else {
                                listState.animateScrollToItem(activeIndex, scrollOffset = -200)
                            }
                        }
                    }

                    val effectivePosProvider = remember(result) {
                        { smoothPosState.longValue + result.offsetMs }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Source + Offset Glass Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassChip(
                                label = stringResource(R.string.lyrics_source_label, result.source.displayName),
                                onClick = { showProviderSheet = true },
                            )
                            if (result.isSynced) {
                                val sign = if (result.offsetMs > 0) "+" else ""
                                val offsetLabel = "$sign${"%.1f".format(result.offsetMs / 1000f)}s"
                                GlassChip(
                                    label = offsetLabel,
                                    onClick = { showOffsetSheet = true },
                                )
                            }
                        }

                        // Lyrics list with 28dp top & bottom fading edges
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
                                start = 24.dp,
                                end = 24.dp,
                                top = 28.dp,
                                bottom = 140.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // Leading instrumental indicator if first line starts > 5s in
                            if (showIntroDots) {
                                val firstStart = result.lines.first().startMs
                                item(key = "intro_dots") {
                                    InstrumentalDots(
                                        progressProvider = {
                                            (effectivePosProvider().toFloat() / firstStart.toFloat()).coerceIn(0f, 1f)
                                        },
                                        reduceAnimation = reduceAnimation,
                                    )
                                }
                            }

                            itemsIndexed(
                                items = result.lines,
                                key = { idx, line -> "${idx}_${line.startMs}" },
                            ) { index, line ->
                                val isActive = result.isSynced && index == activeIndex
                                val distance = if (activeIndex >= 0) abs(index - activeIndex) else 0
                                AppleLyricLineRow(
                                    line = line,
                                    isSynced = result.isSynced,
                                    isActive = isActive,
                                    distanceFromActive = distance,
                                    effectivePosProvider = effectivePosProvider,
                                    blurEnabled = blurUnfocusedLyrics && !isBrowsing && result.isSynced,
                                    isBrowsing = isBrowsing,
                                    reduceAnimation = reduceAnimation,
                                    onClick = {
                                        if (result.isSynced) {
                                            isBrowsing = false
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSeekTo((line.startMs - result.offsetMs).coerceAtLeast(0L))
                                        }
                                    },
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "\"${line.text}\"")
                                        }
                                        context.startActivity(Intent.createChooser(share, line.text))
                                    },
                                )

                                // Show 3-dot instrumental countdown if next line has a > 5.5s gap
                                if (index == activeGapIndex) {
                                    val nextLine = result.lines.getOrNull(index + 1)
                                    if (nextLine != null) {
                                        val gap = (nextLine.startMs - line.endMs).coerceAtLeast(1L)
                                        Spacer(Modifier.height(10.dp))
                                        InstrumentalDots(
                                            progressProvider = {
                                                ((effectivePosProvider() - line.endMs).toFloat() / gap.toFloat()).coerceIn(0f, 1f)
                                            },
                                            reduceAnimation = reduceAnimation,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOffsetSheet) {
        val currentOffset = (lyricsState as? UiState.Success)?.data?.offsetMs ?: 0L
        LyricsOffsetSheet(
            currentOffsetMs = currentOffset,
            hazeState = lyricsHazeState,
            onCommitOffset = onChangeOffset,
            onDismiss = { showOffsetSheet = false },
        )
    }

    if (showProviderSheet) {
        val currentSource = (lyricsState as? UiState.Success)?.data?.source
        LyricsProviderSheet(
            currentSource = currentSource,
            hazeState = lyricsHazeState,
            onSelect = { src ->
                showProviderSheet = false
                onSwitchProvider(src)
            },
            onDismiss = { showProviderSheet = false },
        )
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppleLyricLineRow(
    line: LyricLine,
    isSynced: Boolean,
    isActive: Boolean,
    distanceFromActive: Int,
    effectivePosProvider: () -> Long,
    blurEnabled: Boolean,
    isBrowsing: Boolean,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val targetScale = when {
        !isSynced -> 1f
        isActive -> 1.035f
        else -> 0.96f
    }
    val scale by animateFloatAsState(
        targetValue = if (reduceAnimation) 1f else targetScale,
        animationSpec = tween(durationMillis = 360, easing = AppleWaveEasing),
        label = "lyric_scale",
    )
    val targetLift by animateDpAsState(
        targetValue = if (isActive && !reduceAnimation) (-2).dp else 0.dp,
        animationSpec = spring(stiffness = 340f, dampingRatio = 0.80f),
        label = "lyric_lift",
    )
    val targetAlpha = when {
        !isSynced -> 0.92f
        isActive -> 1f
        isBrowsing -> 0.78f
        distanceFromActive == 1 -> 0.48f
        distanceFromActive == 2 -> 0.32f
        else -> 0.22f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 280),
        label = "lyric_alpha",
    )

    val targetBlur = when {
        !blurEnabled || isActive || reduceAnimation || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 0.dp
        distanceFromActive == 1 -> 1.0.dp
        distanceFromActive == 2 -> 1.7.dp
        else -> 2.3.dp
    }
    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = tween(durationMillis = 240),
        label = "lyric_blur",
    )

    val fontSize = if (line.isBackgroundVocal) 21.sp else 30.sp
    val lineHeight = if (line.isBackgroundVocal) 27.sp else 38.sp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = targetLift.toPx()
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .let { mod -> if (blurRadius > 0.dp) mod.blur(blurRadius) else mod }
            .alpha(alpha)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(vertical = 2.dp),
    ) {
        if (isActive && line.isWordSynced) {
            WordSyncedActiveText(
                line = line,
                fontSize = fontSize,
                lineHeight = lineHeight,
                effectivePosProvider = effectivePosProvider,
            )
        } else {
            Text(
                text = line.text,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = if (isActive || !isSynced) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun WordSyncedActiveText(
    line: LyricLine,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    effectivePosProvider: () -> Long,
) {
    val effectivePosMs = effectivePosProvider()
    val annotated = buildAnnotatedString {
        line.words.forEachIndexed { idx, word ->
            val wordDuration = (word.endMs - word.startMs).coerceAtLeast(1L)
            val wordProgress = when {
                effectivePosMs >= word.endMs -> 1f
                effectivePosMs <= word.startMs -> 0f
                else -> ((effectivePosMs - word.startMs).toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
            }
            val isHeldNote = wordDuration >= 700L && wordProgress in 0.1f..0.95f
            val wordColor = when {
                wordProgress >= 0.82f -> Color.White
                wordProgress > 0f -> Color.White.copy(alpha = 0.52f + 0.48f * wordProgress)
                else -> Color.White.copy(alpha = 0.40f)
            }
            withStyle(
                SpanStyle(
                    color = wordColor,
                    fontWeight = if (wordProgress > 0f) FontWeight.ExtraBold else FontWeight.Bold,
                    shadow = if (isHeldNote || wordProgress >= 0.85f) {
                        Shadow(
                            color = Color.White.copy(alpha = if (isHeldNote) 0.52f else 0.24f),
                            offset = Offset.Zero,
                            blurRadius = if (isHeldNote) 14f else 6f,
                        )
                    } else null,
                ),
            ) {
                append(word.text)
            }
            if (word.trailingSpace && idx < line.words.lastIndex) append(" ")
        }
    }
    Text(
        text = annotated,
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = (-0.4).sp,
    )
}

@Composable
private fun InstrumentalDots(
    progressProvider: () -> Float,
    reduceAnimation: Boolean,
) {
    val progress = progressProvider()
    val pulse = if (reduceAnimation) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "inst_dots")
        val value by transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "inst_pulse",
        )
        value
    }

    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val dotThreshold = (i + 1) / 3f
            val dotAlpha = when {
                progress >= dotThreshold -> 0.95f
                progress >= dotThreshold - 0.33f -> 0.42f + 0.53f * ((progress - (dotThreshold - 0.33f)) / 0.33f)
                else -> 0.26f
            }
            val dotScale = if (progress >= dotThreshold - 0.33f && progress < dotThreshold) pulse else 0.92f
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = dotScale
                        scaleY = dotScale
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha.coerceIn(0.2f, 1f))),
            )
        }
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

@Composable
private fun LyricsOffsetSheet(
    currentOffsetMs: Long,
    hazeState: HazeState?,
    onCommitOffset: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftOffsetMs by remember(currentOffsetMs) { mutableLongStateOf(currentOffsetMs) }
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
                )
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
                        draftOffsetMs = (draftOffsetMs - 100L).coerceIn(-3000L, 3000L)
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
                        draftOffsetMs = (draftOffsetMs + 100L).coerceIn(-3000L, 3000L)
                        onCommitOffset(draftOffsetMs)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            ThinSlider(
                value = draftOffsetMs.toFloat(),
                onValueChange = { draftOffsetMs = (it / 100f).toLong() * 100L },
                onValueChangeFinished = { onCommitOffset(draftOffsetMs) },
                valueRange = -3000f..3000f,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = fg.copy(alpha = 0.22f),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LyricsProviderSheet(
    currentSource: LyricsSourceId?,
    hazeState: HazeState?,
    onSelect: (LyricsSourceId) -> Unit,
    onDismiss: () -> Unit,
) {
    val fg = glassContentColor()
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
            LyricsSourceId.entries.forEach { src ->
                LiquidGlassMenuRow(
                    label = src.displayName,
                    selected = src == currentSource,
                    onClick = { onSelect(src) },
                )
            }
        }
    }
}

private const val MANUAL_SCROLL_COOLDOWN_MS = 4_000L
