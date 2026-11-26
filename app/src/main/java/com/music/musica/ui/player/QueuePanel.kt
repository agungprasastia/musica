package com.music.musica.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import com.music.musica.R
import com.music.musica.core.model.Song
import com.music.musica.ui.components.AnimatedPlayingBars
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.ExplicitSongTitle
import com.music.musica.ui.theme.MusicaAccentRed
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive Queue Panel featuring:
 * - Section header with Clear upcoming button & Autoplay status
 * - Swipe-left-to-remove with red reveal and haptic confirmation
 * - Drag-handle vertical reordering with tactile haptic ticks on each slot swap
 * - 28dp top & bottom DstIn fading edges
 */
@Composable
fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    autoplayEnabled: Boolean,
    onToggleAutoplay: (Boolean) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearUpcoming: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasUpcoming = currentIndex >= 0 && currentIndex < queue.lastIndex

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_queue),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = if (autoplayEnabled) {
                        stringResource(R.string.player_autoplay_subtitle)
                    } else {
                        stringResource(R.string.player_autoplay_off_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasUpcoming) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(onClick = onClearUpcoming)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }

        val stableKeys = remember(queue) {
            val counts = HashMap<String, Int>(queue.size)
            queue.map { s ->
                val occ = counts.getOrDefault(s.videoId, 0)
                counts[s.videoId] = occ + 1
                "q_${s.videoId}_$occ"
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edgePx = 24.dp.toPx().coerceAtMost(size.height * 0.18f)
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            itemsIndexed(
                items = queue,
                key = { idx, _ -> stableKeys[idx] },
            ) { index, song ->
                QueueSwipeDragRow(
                    song = song,
                    index = index,
                    totalCount = queue.size,
                    isCurrent = index == currentIndex,
                    onClick = { onSelectIndex(index) },
                    onMove = { from, to -> onMoveItem(from, to) },
                    onRemove = { onRemoveItem(index) },
                )
            }
        }
    }
}

@Composable
private fun QueueSwipeDragRow(
    song: Song,
    index: Int,
    totalCount: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val removeThresholdPx = with(density) { 88.dp.toPx() }
    val slotHeightPx = with(density) { 62.dp.toPx() }

    val swipeX = remember { Animatable(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }
    var liveIdx by remember { mutableIntStateOf(index) }
    val currentIndex by androidx.compose.runtime.rememberUpdatedState(index)
    val currentTotalCount by androidx.compose.runtime.rememberUpdatedState(totalCount)
    val currentOnMove by androidx.compose.runtime.rememberUpdatedState(onMove)
    val currentOnRemove by androidx.compose.runtime.rememberUpdatedState(onRemove)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                try {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeX.value <= -removeThresholdPx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnRemove()
                            } else {
                                scope.launch {
                                    swipeX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipeX.animateTo(0f, spring()) }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val next = (swipeX.value + dragAmount).coerceIn(-removeThresholdPx * 1.4f, 0f)
                            scope.launch { swipeX.snapTo(next) }
                        },
                    )
                } finally {
                    scope.launch { swipeX.snapTo(0f) }
                }
            },
    ) {
        // Red swipe-to-remove background
        if (swipeX.value < -2f) {
            val progress = (abs(swipeX.value) / removeThresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MusicaAccentRed.copy(alpha = 0.35f + 0.55f * progress))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isCurrent) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ArtworkImage(
                    url = song.thumbnailUrl,
                    contentDescription = song.title,
                    size = 46.dp,
                    cornerRadius = 8.dp,
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.36f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedPlayingBars(color = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ExplicitSongTitle(
                    title = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = Color.White,
                    badgeTint = Color.White.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artistText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Drag handle for vertical slot reordering
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .pointerInput(Unit) {
                        try {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragAccumY = 0f
                                    liveIdx = currentIndex
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onDragEnd = { dragAccumY = 0f },
                                onDragCancel = { dragAccumY = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragAccumY += dragAmount
                                    val count = currentTotalCount
                                    if (dragAccumY >= slotHeightPx && liveIdx < count - 1) {
                                        dragAccumY -= slotHeightPx
                                        val nextIdx = liveIdx + 1
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnMove(liveIdx, nextIdx)
                                        liveIdx = nextIdx
                                    } else if (dragAccumY <= -slotHeightPx && liveIdx > 0) {
                                        dragAccumY += slotHeightPx
                                        val prevIdx = liveIdx - 1
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnMove(liveIdx, prevIdx)
                                        liveIdx = prevIdx
                                    }
                                },
                            )
                        } finally {
                            dragAccumY = 0f
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.56f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
