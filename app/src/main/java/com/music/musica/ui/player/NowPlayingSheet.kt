package com.music.musica.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import com.music.musica.core.model.formatDuration
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.playback.PlaybackProgress
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.ExplicitBadge
import com.music.musica.ui.components.PlayerBackdrop
import com.music.musica.ui.components.ThinSlider
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.components.thumbnailBorder
import com.music.musica.ui.theme.MusicaAccentRed
import com.music.musica.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class NowPlayingTab {
    ARTWORK,
    LYRICS,
    QUEUE,
}

/**
 * Musica full-screen Now Playing sheet with:
 * - 3 selectable backdrops (Artwork 6x6 Mesh, 8s-settling Legacy Mesh, Simple Blurred Cover)
 * - Finger-following vertical drag-to-dismiss, horizontal swipe-to-skip, upward swipe for Queue
 * - Optional Full-Bleed edge-to-edge cover mode
 * - Marquee title & tappable artist/album credits + frosted GlassDisc Pair (Like + More)
 * - Audio quality pill, toggleable -Remaining time, optional live lyric preview strip
 * - 3-button Apple transport + integrated AudioManager VolumeRow + 3-slot morphing bottom bar
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingSheet(
    playerState: PlayerUiState,
    progress: PlaybackProgress,
    isLiked: Boolean,
    playerTheme: PlayerTheme = PlayerTheme.DEFAULT,
    fullBleedCoverArt: Boolean = false,
    showVolumeSlider: Boolean = true,
    showRemainingTime: Boolean = true,
    currentLyricPreview: String? = null,
    reduceAnimation: Boolean,
    playerHazeState: HazeState? = null,
    lyricsContent: @Composable (Modifier) -> Unit,
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
    onOpenMoreActions: (Song) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenStatsForNerds: () -> Unit,
) {
    val song = playerState.currentSong ?: return
    val palette = rememberArtworkPalette(
        imageUrl = song.thumbnailUrl,
        dark = true,
        reduceAnimation = reduceAnimation,
    )
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var activeTab by remember { mutableStateOf(NowPlayingTab.ARTWORK) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val dismissOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { 150.dp.toPx() }
    val skipThresholdPx = with(density) { 72.dp.toPx() }
    val artSwipeX = remember { Animatable(0f) }

    BackHandler {
        if (activeTab != NowPlayingTab.ARTWORK) {
            activeTab = NowPlayingTab.ARTWORK
        } else {
            onDismiss()
        }
    }

    val sheetCorner = ((dismissOffsetY.value / dismissThresholdPx).coerceIn(0f, 1f) * 32f).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismissOffsetY.value.roundToInt()) }
            .clip(RoundedCornerShape(topStart = sheetCorner, topEnd = sheetCorner))
            .then(if (playerHazeState != null) Modifier.hazeSource(playerHazeState) else Modifier),
    ) {
        PlayerBackdrop(
            imageUrl = song.thumbnailUrl,
            meshColors = palette.meshColors,
            theme = playerTheme,
            reduceAnimation = reduceAnimation,
            dimMore = activeTab != NowPlayingTab.ARTWORK,
            modifier = Modifier.fillMaxSize(),
        )

        val isFullBleedArtwork = fullBleedCoverArt && activeTab == NowPlayingTab.ARTWORK
        val currentOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
        val dragHandle: @Composable (Modifier) -> Unit = { handleMod ->
            Box(
                modifier = handleMod
                    .fillMaxWidth()
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
                    }
                    .clickable(onClick = onDismiss)
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.42f)),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isFullBleedArtwork) Modifier.statusBarsPadding() else Modifier)
                .navigationBarsPadding(),
        ) {
            if (!isFullBleedArtwork) {
                dragHandle(Modifier)
            }

            // Smooth 420ms unified content area: Full Artwork vs Lyrics vs Queue
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(tween(340, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(240, easing = FastOutSlowInEasing))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "now_playing_body",
            ) { tab ->
                when (tab) {
                    NowPlayingTab.ARTWORK -> {
                        ArtworkHeroView(
                            song = song,
                            isPlaying = playerState.isPlaying,
                            isLiked = isLiked,
                            fullBleed = fullBleedCoverArt,
                            reduceAnimation = reduceAnimation,
                            currentLyricPreview = currentLyricPreview,
                            swipeOffsetX = artSwipeX.value,
                            onSwipeDrag = { dx, dy ->
                                if (abs(dx) > abs(dy)) {
                                    val next = (artSwipeX.value + dx * 0.65f)
                                        .coerceIn(-skipThresholdPx * 1.4f, skipThresholdPx * 1.4f)
                                    scope.launch { artSwipeX.snapTo(next) }
                                } else if (dy > 0f) {
                                    val nextY = (dismissOffsetY.value + dy).coerceAtLeast(0f)
                                    scope.launch { dismissOffsetY.snapTo(nextY) }
                                }
                            },
                            onSwipeEnd = { totalDy ->
                                val dx = artSwipeX.value
                                val dy = dismissOffsetY.value
                                when {
                                    abs(dx) >= skipThresholdPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (dx < 0f) onSkipNext() else onSkipPrevious()
                                    }
                                    dy >= dismissThresholdPx -> onDismiss()
                                    totalDy <= -skipThresholdPx -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeTab = NowPlayingTab.QUEUE
                                    }
                                }
                                scope.launch { artSwipeX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
                                scope.launch { dismissOffsetY.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                            },
                            onToggleLike = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleLike()
                            },
                            onMore = { onOpenMoreActions(song) },
                            onAlbumClick = song.album?.browseId?.let { id ->
                                {
                                    onDismiss()
                                    onGoToAlbum?.invoke(id)
                                }
                            },
                            onArtistClick = song.primaryArtistId?.let { id ->
                                {
                                    onDismiss()
                                    onGoToArtist?.invoke(id)
                                }
                            },
                            onOpenLyrics = { activeTab = NowPlayingTab.LYRICS },
                        )
                    }

                    NowPlayingTab.LYRICS -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CompactPlayerHeader(
                                song = song,
                                isLiked = isLiked,
                                onCollapseBack = { activeTab = NowPlayingTab.ARTWORK },
                                onToggleLike = onToggleLike,
                                onMore = { onOpenMoreActions(song) },
                            )
                            lyricsContent(Modifier.weight(1f).fillMaxWidth())
                        }
                    }

                    NowPlayingTab.QUEUE -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CompactPlayerHeader(
                                song = song,
                                isLiked = isLiked,
                                onCollapseBack = { activeTab = NowPlayingTab.ARTWORK },
                                onToggleLike = onToggleLike,
                                onMore = { onOpenMoreActions(song) },
                            )
                            QueuePanel(
                                queue = playerState.queue,
                                currentIndex = playerState.currentIndex,
                                autoplayEnabled = playerState.autoplayEnabled,
                                onToggleAutoplay = onToggleAutoplay,
                                onSelectIndex = onSelectQueueIndex,
                                onMoveItem = onMoveQueueItem,
                                onRemoveItem = onRemoveQueueItem,
                                onClearUpcoming = onClearUpcomingQueue,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- Lower Apple Music Transport Deck ----
            val durationMs = progress.durationMs.coerceAtLeast(1L)
            val displayPosMs = if (scrubbing) (scrubValue * durationMs).toLong() else progress.positionMs
            val sliderFraction = if (scrubbing) scrubValue else (progress.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val remainingMs = (durationMs - displayPosMs).coerceAtLeast(0L)
            val qualityBadge = remember(playerState.nerdStats.mimeType, playerState.nerdStats.bitrateKbps) {
                buildQualityPillLabel(playerState.nerdStats.mimeType, playerState.nerdStats.bitrateKbps)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                ThinSlider(
                    value = sliderFraction,
                    onValueChange = {
                        scrubbing = true
                        scrubValue = it
                    },
                    onValueChangeFinished = {
                        onSeekTo((scrubValue * durationMs).toLong())
                        scrubbing = false
                    },
                    activeColor = Color.White,
                    inactiveColor = Color.White.copy(alpha = 0.24f),
                    restHeight = 7.dp,
                    dragHeight = 12.dp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDuration(displayPosMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.68f),
                    )

                    if (qualityBadge != null) {
                        Box(
                            modifier = Modifier
                                .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
                                .clickable(onClick = onOpenStatsForNerds)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
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
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.68f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleRemainingTime)
                            .padding(horizontal = 2.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Clean 3-Button Apple Transport Row (Prev, unboxed Play/Pause, Next)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSkipPrevious()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.action_previous),
                            tint = Color.White,
                            modifier = Modifier
                                .size(44.dp)
                                .graphicsLayer { scaleY = 0.86f },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTogglePlayPause()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playerState.isBuffering) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(if (playerState.isPlaying) R.string.action_pause else R.string.action_play),
                                tint = Color.White,
                                modifier = Modifier.size(54.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSkipNext()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.action_next),
                            tint = Color.White,
                            modifier = Modifier
                                .size(44.dp)
                                .graphicsLayer { scaleY = 0.86f },
                        )
                    }
                }

                // Integrated Hardware Volume Row (hideable in Settings)
                AnimatedVisibility(visible = showVolumeSlider && activeTab == NowPlayingTab.ARTWORK) {
                    VolumeControlRow(modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(10.dp))

                // 3-Slot Apple Bottom Bar: Lyrics | Morphing Center Controls Pill | Queue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomDeckTileButton(
                        icon = Icons.Rounded.Lyrics,
                        contentDescription = stringResource(R.string.player_lyrics),
                        active = activeTab == NowPlayingTab.LYRICS,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            activeTab = if (activeTab == NowPlayingTab.LYRICS) NowPlayingTab.ARTWORK else NowPlayingTab.LYRICS
                        },
                    )

                    // Morphing Center Capsule: Queue open -> Shuffle / Repeat / Autoplay; else -> Speed / Sleep / EQ
                    AnimatedContent(
                        targetState = activeTab == NowPlayingTab.QUEUE,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                        label = "center_pill_morph",
                    ) { queueMode ->
                        Row(
                            modifier = Modifier
                                .lightweightGlass(RoundedCornerShape(percent = 50), onDarkBackdrop = true)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (queueMode) {
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
                                    contentDescription = "Repeat",
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
                            } else {
                                CapsuleIconToggle(
                                    icon = Icons.Rounded.Speed,
                                    contentDescription = stringResource(R.string.player_speed_title),
                                    active = abs(playerState.playbackSpeed - 1.0f) > 0.01f,
                                    onClick = onOpenSpeedSheet,
                                )
                                CapsuleIconToggle(
                                    icon = Icons.Rounded.Bedtime,
                                    contentDescription = stringResource(R.string.player_sleep_timer),
                                    active = (playerState.sleepTimerRemainingMs ?: 0L) > 0L || playerState.sleepAfterTrack,
                                    onClick = onOpenSleepTimerSheet,
                                )
                                CapsuleIconToggle(
                                    icon = Icons.Rounded.Equalizer,
                                    contentDescription = stringResource(R.string.player_equalizer),
                                    active = false,
                                    onClick = onOpenEqualizer,
                                )
                            }
                        }
                    }

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

        if (isFullBleedArtwork) {
            dragHandle(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtworkHeroView(
    song: Song,
    isPlaying: Boolean,
    isLiked: Boolean,
    fullBleed: Boolean,
    reduceAnimation: Boolean,
    currentLyricPreview: String?,
    swipeOffsetX: Float,
    onSwipeDrag: (Float, Float) -> Unit,
    onSwipeEnd: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onMore: () -> Unit,
    onAlbumClick: (() -> Unit)?,
    onArtistClick: (() -> Unit)?,
    onOpenLyrics: () -> Unit,
) {
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying || reduceAnimation) 1f else 0.86f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "art_breathing_scale",
    )

    val currentOnSwipeDrag by androidx.compose.runtime.rememberUpdatedState(onSwipeDrag)
    val currentOnSwipeEnd by androidx.compose.runtime.rememberUpdatedState(onSwipeEnd)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var accumY = 0f
                try {
                    detectDragGestures(
                        onDragStart = { accumY = 0f },
                        onDragEnd = { currentOnSwipeEnd(accumY) },
                        onDragCancel = { currentOnSwipeEnd(0f) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumY += dragAmount.y
                            currentOnSwipeDrag(dragAmount.x, dragAmount.y)
                        },
                    )
                } finally {
                    currentOnSwipeEnd(0f)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (fullBleed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        translationX = swipeOffsetX
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.Black,
                                0.72f to Color.Black,
                                1.00f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                AsyncImage(
                    model = upsizeArtworkUrl(song.thumbnailUrl, 900),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            val shape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .padding(horizontal = 30.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = swipeOffsetX
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .shadow(
                        elevation = if (isPlaying) 26.dp else 10.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.55f),
                        spotColor = Color.Black.copy(alpha = 0.55f),
                    )
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .thumbnailBorder(shape),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = upsizeArtworkUrl(song.thumbnailUrl, 800),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Credits row: Marquee Title & Tappable Artist + GlassDisc Pair (Like & More)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.let { mod ->
                        if (onAlbumClick != null) mod.clickable(onClick = onAlbumClick) else mod
                    },
                ) {
                    if (song.isExplicit) {
                        ExplicitBadge(tint = Color.White.copy(alpha = 0.72f))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 3500,
                            velocity = 28.dp,
                        ),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artistText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color.White.copy(alpha = 0.74f),
                    maxLines = 1,
                    modifier = Modifier
                        .let { mod ->
                            if (onArtistClick != null) mod.clickable(onClick = onArtistClick) else mod
                        }
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 5000,
                            velocity = 26.dp,
                        ),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Frosted GlassDisc Pair (36dp circles with subtle top-lit rim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDiscButton(
                    icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) R.string.menu_unlike else R.string.menu_like),
                    tint = if (isLiked) MusicaAccentRed else Color.White,
                    onClick = onToggleLike,
                )
                GlassDiscButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.action_more),
                    tint = Color.White,
                    onClick = onMore,
                )
            }
        }

        // Optional 1-line live synced lyric preview strip
        if (!currentLyricPreview.isNullOrBlank()) {
            Text(
                text = currentLyricPreview,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.76f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenLyrics)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CompactPlayerHeader(
    song: Song,
    isLiked: Boolean,
    onCollapseBack: () -> Unit,
    onToggleLike: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = song.thumbnailUrl,
            contentDescription = song.title,
            size = 52.dp,
            cornerRadius = 10.dp,
            modifier = Modifier.clickable(onClick = onCollapseBack),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onCollapseBack),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artistText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassDiscButton(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (isLiked) R.string.menu_unlike else R.string.menu_like),
                tint = if (isLiked) MusicaAccentRed else Color.White,
                onClick = onToggleLike,
            )
            GlassDiscButton(
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(R.string.action_more),
                tint = Color.White,
                onClick = onMore,
            )
        }
    }
}

@Composable
private fun GlassDiscButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .lightweightGlass(CircleShape, onDarkBackdrop = true)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
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
            .size(42.dp)
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
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.Black else Color.White.copy(alpha = 0.84f),
            modifier = Modifier.size(22.dp),
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
            tint = if (active) Color.Black else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun VolumeControlRow(modifier: Modifier = Modifier) {
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
            tint = Color.White.copy(alpha = 0.60f),
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
            activeColor = Color.White.copy(alpha = 0.90f),
            inactiveColor = Color.White.copy(alpha = 0.22f),
            restHeight = 5.dp,
            dragHeight = 9.dp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.60f),
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
