package com.music.musica.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.roundToInt

data class NavTabItem(
    val label: String,
    val icon: ImageVector,
)

private val NAV_BAR_LIFT = 6.dp

/**
 * Pure scrub position calculation in tab-step coordinates with soft elastic edge resistance.
 */
internal fun computeNavScrubPosition(
    dragStartTab: Float,
    totalDragPx: Float,
    tabWidthPx: Float,
    tabCount: Int,
): Float {
    if (tabCount <= 1 || tabWidthPx <= 0f) return 0f
    val raw = dragStartTab + totalDragPx / tabWidthPx
    val maxIdx = (tabCount - 1).toFloat()
    return when {
        raw < 0f -> (raw * 0.22f).coerceAtLeast(-0.18f)
        raw > maxIdx -> maxIdx + ((raw - maxIdx) * 0.22f).coerceAtMost(0.18f)
        else -> raw
    }
}

/**
 * Resolves the nearest valid tab index for a continuous scrub position.
 */
internal fun computeNavLandedIndex(position: Float, tabCount: Int): Int {
    if (tabCount <= 0) return 0
    return position.roundToInt().coerceIn(0, tabCount - 1)
}

/**
 * Musica floating Liquid Glass capsule navigation bar with:
 * - True 50% capsule geometry (62dp height, 19dp gutter, 4dp pill inset, 440dp max tablet width)
 * - High-contrast adaptive white/black glass content tints (26dp glyph + 10sp tracked label)
 * - Horizontal drag-to-scrub across tabs with haptic ticks & elastic edge resistance
 * - Lifted elastic stretch/squash physics on the sliding active capsule
 * - Active-tab re-tap support (scroll-to-top / clear overlay)
 */
@Composable
fun FloatingGlassNavBar(
    tabs: List<NavTabItem>,
    selectedIndex: Int,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    onSelectTab: (Int) -> Unit,
    onActiveTabTap: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pillShape = RoundedCornerShape(percent = 50)
    val capsuleColor = if (frostedGlassEnabled) {
        glassIndicatorColor()
    } else if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.09f)
    }
    val liftedRimColor = if (isDark) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.14f)
    val contentBase = if (frostedGlassEnabled) glassContentColor() else MaterialTheme.colorScheme.primary
    val activeTint = contentBase
    val inactiveTint = if (frostedGlassEnabled) {
        contentBase.copy(alpha = 0.62f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val safeSelectedIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val currentSelectedIndex by rememberUpdatedState(safeSelectedIndex)
    val currentTabCount by rememberUpdatedState(tabs.size)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)
    val currentIsRtl by rememberUpdatedState(isRtl)

    var scrubbing by remember { mutableStateOf(false) }
    var scrubTabFloat by remember { mutableFloatStateOf(safeSelectedIndex.toFloat()) }

    val targetFloat = if (scrubbing) scrubTabFloat else safeSelectedIndex.toFloat()
    val animatedIndex by animateFloatAsState(
        targetValue = targetFloat,
        animationSpec = if (scrubbing) {
            spring(dampingRatio = 0.92f, stiffness = 900f)
        } else {
            spring(dampingRatio = 0.72f, stiffness = 320f)
        },
        label = "nav_pill_index",
    )
    val liftProgress by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "nav_pill_lift",
    )
    // Elastic liquid stretch/squash proportional to travel lag + subtle lift while held
    val travelLag = abs(targetFloat - animatedIndex).coerceAtMost(1.0f)
    val pillScaleX = 1f + travelLag * 0.16f + liftProgress * 0.05f
    val pillScaleY = 1f - travelLag * 0.08f + liftProgress * 0.06f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = FLOATING_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                .fillMaxWidth()
                .height(62.dp)
                .then(
                    if (!frostedGlassEnabled) {
                        Modifier.shadow(
                            elevation = 14.dp,
                            shape = pillShape,
                            ambientColor = Color.Black.copy(alpha = 0.28f),
                            spotColor = Color.Black.copy(alpha = 0.28f),
                        )
                    } else {
                        Modifier
                    },
                )
                .frostedGlass(
                    hazeState = hazeState,
                    shape = pillShape,
                    enabled = frostedGlassEnabled,
                )
                .padding(4.dp)
                .pointerInput(Unit) {
                    try {
                        var totalDrag = 0f
                        var dragStartFloat = 0f
                        var lastHapticIdx = 0
                        var activeDrag = false
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                val count = currentTabCount
                                if (count <= 1 || size.width <= 0) {
                                    activeDrag = false
                                    return@detectHorizontalDragGestures
                                }
                                val tabW = size.width.toFloat() / count
                                val rawX = if (currentIsRtl) size.width - start.x else start.x
                                val touchedTab = (rawX / tabW).toInt().coerceIn(0, count - 1)
                                dragStartFloat = if (touchedTab == currentSelectedIndex) {
                                    currentSelectedIndex.toFloat()
                                } else {
                                    touchedTab.toFloat()
                                }
                                totalDrag = 0f
                                lastHapticIdx = dragStartFloat.roundToInt()
                                scrubTabFloat = dragStartFloat
                                scrubbing = true
                                activeDrag = true
                            },
                            onDragEnd = {
                                if (!activeDrag) return@detectHorizontalDragGestures
                                activeDrag = false
                                val landed = computeNavLandedIndex(scrubTabFloat, currentTabCount)
                                scrubbing = false
                                if (landed != currentSelectedIndex) {
                                    currentOnSelectTab(landed)
                                }
                            },
                            onDragCancel = {
                                activeDrag = false
                                scrubbing = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (!activeDrag) return@detectHorizontalDragGestures
                                change.consume()
                                val count = currentTabCount
                                val tabW = size.width.toFloat() / count.coerceAtLeast(1)
                                val dir = if (currentIsRtl) -1f else 1f
                                totalDrag += dragAmount * dir
                                val pos = computeNavScrubPosition(dragStartFloat, totalDrag, tabW, count)
                                scrubTabFloat = pos
                                val nearest = computeNavLandedIndex(pos, count)
                                if (nearest != lastHapticIdx) {
                                    lastHapticIdx = nearest
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                        )
                    } finally {
                        scrubbing = false
                    }
                },
        ) {
            val tabWidth = maxWidth / tabs.size
            val tabWidthPx = with(density) { tabWidth.toPx() }
            val rimAlpha = maxOf(liftProgress, travelLag * 0.85f)

            // Sliding elastic active capsule
            Box(
                modifier = Modifier
                    .offset { IntOffset((animatedIndex * tabWidthPx).roundToInt(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = pillScaleX
                        scaleY = pillScaleY
                    }
                    .clip(pillShape)
                    .background(capsuleColor)
                    .then(
                        if (rimAlpha > 0.05f) {
                            Modifier.border(
                                width = 0.8.dp,
                                color = liftedRimColor.copy(alpha = liftedRimColor.alpha * rimAlpha),
                                shape = pillShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )

            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                val visualActiveIndex = if (scrubbing) {
                    computeNavLandedIndex(scrubTabFloat, tabs.size)
                } else {
                    safeSelectedIndex
                }
                val labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.4.sp,
                    fontWeight = FontWeight.Medium,
                )
                tabs.forEachIndexed { index, tab ->
                    val active = visualActiveIndex == index
                    val tint by animateColorAsState(
                        targetValue = if (active) activeTint else inactiveTint,
                        animationSpec = tween(180),
                        label = "nav_tab_tint_$index",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (active) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
                        label = "nav_icon_scale_$index",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                scrubbing = false
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (index == safeSelectedIndex) {
                                    onActiveTabTap(index)
                                } else {
                                    onSelectTab(index)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = tab.label,
                            style = labelStyle,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
