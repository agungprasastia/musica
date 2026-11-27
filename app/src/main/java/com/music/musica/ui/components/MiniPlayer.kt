package com.music.musica.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.playback.PlayerUiState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Musica floating Liquid Glass capsule MiniPlayer:
 * - Sleek 50dp expanded capsule (48dp inline collapsed mode) with 32dp rounded artwork
 * - Crisp adaptive high-contrast title (13.5sp Bold) & artist (12sp) with smooth DstIn fade-out
 * - Apple-style Play/Pause (28dp) + double-triangle FastForward (26dp) transport controls
 * - Tactile 1.03x press scale response, horizontal swipe to skip Next/Prev, upward drag to expand
 */
@Composable
fun MiniPlayer(
    playerState: PlayerUiState,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    isInline: Boolean = false,
) {
    val song = playerState.currentSong ?: return
    val shape = RoundedCornerShape(percent = 50)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val skipThresholdPx = with(density) { 68.dp.toPx() }
    val expandThresholdPx = with(density) { 44.dp.toPx() }

    val dragOffsetX = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }
    val currentOnSkipNext by rememberUpdatedState(onSkipNext)
    val currentOnSkipPrevious by rememberUpdatedState(onSkipPrevious)
    val currentOnExpand by rememberUpdatedState(onExpand)

    val pressSource = remember { MutableInteractionSource() }
    val isPressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 1.03f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.72f),
        label = "mini_player_press",
    )

    val contentColor = if (frostedGlassEnabled) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val secondaryColor = contentColor.copy(alpha = 0.68f)
    val barHeight = if (isInline) 48.dp else 50.dp
    val artSize = if (isInline) 30.dp else 32.dp
    val glyphSlot = if (isInline) 34.dp else 40.dp
    val playGlyphSize = if (isInline) 24.dp else 28.dp

    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = 13.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.15.sp,
    )
    val artistStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.25.sp,
    )

    val containerModifier = if (isInline) {
        modifier
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = FLOATING_BAR_GUTTER)
    }

    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (isInline) Modifier.fillMaxWidth() else Modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH).fillMaxWidth())
                .height(barHeight)
                .offset { IntOffset(dragOffsetX.value.roundToInt(), dragOffsetY.value.roundToInt()) }
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .then(
                    if (!frostedGlassEnabled) {
                        Modifier.shadow(
                            elevation = 12.dp,
                            shape = shape,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.28f),
                        )
                    } else {
                        Modifier
                    },
                )
                .frostedGlass(
                    hazeState = hazeState,
                    shape = shape,
                    enabled = frostedGlassEnabled,
                )
                .pointerInput(Unit) {
                    var axisLocked = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
                    try {
                        detectDragGestures(
                            onDragStart = { axisLocked = 0 },
                            onDragEnd = {
                                val dx = dragOffsetX.value
                                val dy = dragOffsetY.value
                                if (axisLocked == 1 && abs(dx) >= skipThresholdPx) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (dx < 0f) currentOnSkipNext() else currentOnSkipPrevious()
                                } else if (axisLocked == 2 && dy <= -expandThresholdPx) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnExpand()
                                }
                                scope.launch {
                                    dragOffsetX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                                }
                                scope.launch {
                                    dragOffsetY.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                                }
                            },
                            onDragCancel = {
                                scope.launch { dragOffsetX.animateTo(0f, spring()) }
                                scope.launch { dragOffsetY.animateTo(0f, spring()) }
                            },
                            onDrag = { change, dragAmount ->
                                if (axisLocked == 0) {
                                    axisLocked = if (abs(dragAmount.x) > abs(dragAmount.y) * 1.15f) 1 else 2
                                }
                                change.consume()
                                if (axisLocked == 1) {
                                    val nextX = (dragOffsetX.value + dragAmount.x * 0.65f)
                                        .coerceIn(-skipThresholdPx * 1.35f, skipThresholdPx * 1.35f)
                                    scope.launch { dragOffsetX.snapTo(nextX) }
                                } else if (dragAmount.y < 0f || dragOffsetY.value < 0f) {
                                    val nextY = (dragOffsetY.value + dragAmount.y * 0.55f)
                                        .coerceIn(-expandThresholdPx * 1.5f, 0f)
                                    scope.launch { dragOffsetY.snapTo(nextY) }
                                }
                            },
                        )
                    } finally {
                        scope.launch { dragOffsetX.snapTo(0f) }
                        scope.launch { dragOffsetY.snapTo(0f) }
                    }
                }
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                    onClick = onExpand,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isInline) 12.dp else 14.dp,
                        end = if (isInline) 8.dp else 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    url = song.thumbnailUrl,
                    contentDescription = song.title,
                    size = artSize,
                    cornerRadius = 6.dp,
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ExplicitSongTitle(
                        title = song.title,
                        isExplicit = song.isExplicit,
                        style = titleStyle,
                        color = contentColor,
                        badgeTint = secondaryColor,
                        fadeOverflow = false,
                    )
                    Text(
                        text = song.artistText,
                        style = artistStyle,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .size(glyphSlot)
                        .clip(CircleShape)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePlayPause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (playerState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (isInline) 18.dp else 20.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(
                                if (playerState.isPlaying) R.string.action_pause else R.string.action_play,
                            ),
                            tint = contentColor,
                            modifier = Modifier.size(playGlyphSize),
                        )
                    }
                }

                if (!isInline) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(glyphSlot)
                            .clip(CircleShape)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSkipNext()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FastForward,
                            contentDescription = stringResource(R.string.action_next),
                            tint = contentColor,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
}
