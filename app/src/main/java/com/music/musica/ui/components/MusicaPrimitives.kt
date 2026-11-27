package com.music.musica.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.ui.theme.OnboardingMeshColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

val FLOATING_BAR_GUTTER = 19.dp
val FLOATING_BAR_MAX_WIDTH = 440.dp
val ROW_DIVIDER_INSET = 80.dp

/**
 * Official Musica brand emblem matching `ic_launcher_foreground.xml` 1-to-1:
 * three parallel 20-degree kinetic ribbons plus the signature rhythm dot.
 */
@Composable
fun MusicaBrandEmblem(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height)
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        val box = 44f
        fun pt(vx: Float, vy: Float): Offset = Offset(
            x = ox + (vx - 32.15f) / box * side,
            y = oy + (vy - 32.0f) / box * side,
        )
        val strokeWidth = (7.8f / box) * side
        drawLine(color = color, start = pt(36.5f, 68.9f), end = pt(43.8f, 48.9f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = color, start = pt(49.8f, 70.9f), end = pt(61.4f, 39.1f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = color, start = pt(64.5f, 68.9f), end = pt(71.8f, 48.9f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawCircle(color = color, radius = (3.9f / box) * side, center = pt(48.1f, 37.1f))
    }
}

/**
 * Crisp 1dp 15%-opacity hairline border used around every album, playlist, and track cover.
 */
@Composable
fun Modifier.thumbnailBorder(shape: Shape): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.14f)
    return this.border(1.dp, borderColor, shape)
}

/**
 * Punched-out DstOut Explicit ("E") badge where the background surface shines through the letter,
 * matching modern editorial typography craft.
 */
@Composable
fun ExplicitBadge(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .size(14.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawRoundRect(
                    color = tint,
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                )
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "E",
            style = TextStyle(
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 9.sp,
            ),
            color = Color.Black,
            modifier = Modifier.graphicsLayer {
                // Punch the letter cleanly out of the tinted rounded square
                blendMode = BlendMode.DstOut
            },
        )
    }
}

/**
 * Single-line song title with an optional leading DstOut Explicit badge and optional right-edge fade.
 */
@Composable
fun ExplicitSongTitle(
    title: String,
    isExplicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    badgeTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fadeOverflow: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isExplicit) {
            ExplicitBadge(tint = badgeTint)
            Spacer(Modifier.width(5.dp))
        }
        if (fadeOverflow) {
            FadingLine(
                text = title,
                style = style,
                color = color,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                text = title,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Single-line text that dissolves softly across its trailing 32dp edge instead of cutting off
 * abruptly with an ellipsis when space is tight.
 */
@Composable
fun FadingLine(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fadeWidth: Dp = 32.dp,
) {
    var overflows by remember(text) { mutableStateOf(false) }
    val density = LocalDensity.current
    val fadePx = with(density) { fadeWidth.toPx() }

    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            overflows = layout.didOverflowWidth
        },
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflows && size.width > fadePx) {
                    val startX = (size.width - fadePx).coerceAtLeast(0f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = startX,
                            endX = size.width,
                        ),
                        topLeft = Offset(startX, 0f),
                        size = Size(fadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    )
}

/**
 * Smooth 3-bar dancing audio equalizer overlay drawn directly over active artwork.
 */
@Composable
fun AnimatedPlayingBars(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    color: Color = Color.White,
) {
    val transition = rememberInfiniteTransition(label = "playing_bars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bars_phase",
    )

    Canvas(modifier = modifier) {
        val barCount = 3
        val barWidth = size.width / 5f
        val gap = (size.width - barWidth * barCount) / (barCount - 1)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

        val phases = floatArrayOf(0f, 1.9f, 3.7f)
        for (i in 0 until barCount) {
            val fraction = if (isPlaying) {
                val wave = (sin(phase + phases[i]) + 1f) * 0.5f
                0.28f + 0.72f * wave
            } else {
                when (i) {
                    0 -> 0.42f
                    1 -> 0.72f
                    else -> 0.34f
                }
            }
            val barH = size.height * fraction
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Horizontal linear-sweep shimmer block used for pixel-matched loading skeletons.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    reduceAnimation: Boolean = false,
    onDarkBackdrop: Boolean = false,
) {
    val isDark = onDarkBackdrop || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseColor = if (onDarkBackdrop) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val highlight = if (onDarkBackdrop) {
        Color.White.copy(alpha = 0.24f)
    } else if (isDark) {
        Color.White.copy(alpha = 0.09f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }

    if (reduceAnimation) {
        Box(modifier = modifier.clip(shape).background(baseColor))
        return
    }

    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_sweep",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .drawWithContent {
                val width = size.width.coerceAtLeast(1f)
                val startX = width * progress - width * 0.45f
                val endX = width * progress + width * 0.45f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(startX, 0f),
                        end = Offset(endX, size.height * 0.35f),
                    ),
                )
            },
    )
}

@Composable
fun FeedSkeleton(reduceAnimation: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShimmerBox(
                    modifier = Modifier.padding(horizontal = 16.dp).width(164.dp).height(22.dp),
                    shape = RoundedCornerShape(8.dp),
                    reduceAnimation = reduceAnimation,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    repeat(3) {
                        Column(modifier = Modifier.width(146.dp)) {
                            ShimmerBox(
                                modifier = Modifier.size(146.dp),
                                shape = RoundedCornerShape(12.dp),
                                reduceAnimation = reduceAnimation,
                            )
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(
                                modifier = Modifier.fillMaxWidth(0.85f).height(14.dp),
                                shape = RoundedCornerShape(6.dp),
                                reduceAnimation = reduceAnimation,
                            )
                            Spacer(Modifier.height(5.dp))
                            ShimmerBox(
                                modifier = Modifier.fillMaxWidth(0.6f).height(12.dp),
                                shape = RoundedCornerShape(6.dp),
                                reduceAnimation = reduceAnimation,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongListSkeleton(
    count: Int = 8,
    circular: Boolean = false,
    reduceAnimation: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(
                    modifier = Modifier.size(52.dp),
                    shape = if (circular) CircleShape else RoundedCornerShape(8.dp),
                    reduceAnimation = reduceAnimation,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.68f).height(15.dp),
                        shape = RoundedCornerShape(6.dp),
                        reduceAnimation = reduceAnimation,
                    )
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.42f).height(12.dp),
                        shape = RoundedCornerShape(6.dp),
                        reduceAnimation = reduceAnimation,
                    )
                }
            }
        }
    }
}

@Composable
fun DetailSkeleton(reduceAnimation: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShimmerBox(
            modifier = Modifier.size(224.dp),
            shape = RoundedCornerShape(16.dp),
            reduceAnimation = reduceAnimation,
        )
        Spacer(Modifier.height(18.dp))
        ShimmerBox(modifier = Modifier.width(200.dp).height(24.dp), reduceAnimation = reduceAnimation)
        Spacer(Modifier.height(8.dp))
        ShimmerBox(modifier = Modifier.width(132.dp).height(15.dp), reduceAnimation = reduceAnimation)
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShimmerBox(modifier = Modifier.size(48.dp), shape = CircleShape, reduceAnimation = reduceAnimation)
            ShimmerBox(modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp), reduceAnimation = reduceAnimation)
            ShimmerBox(modifier = Modifier.size(48.dp), shape = CircleShape, reduceAnimation = reduceAnimation)
        }
        Spacer(Modifier.height(24.dp))
        SongListSkeleton(count = 6, reduceAnimation = reduceAnimation)
    }
}

@Composable
fun LyricsSkeleton(
    reduceAnimation: Boolean = false,
    onDarkBackdrop: Boolean = true,
) {
    val widths = floatArrayOf(0.76f, 0.58f, 0.88f, 0.64f, 0.82f, 0.50f, 0.72f)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        widths.forEach { frac ->
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(frac).height(28.dp),
                shape = RoundedCornerShape(8.dp),
                reduceAnimation = reduceAnimation,
                onDarkBackdrop = onDarkBackdrop,
            )
        }
    }
}

/**
 * Spring-animated iOS 26 Liquid Glass segmented capsule control replacing heavy M3 filter chips.
 */
@Composable
fun PillSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDarkBackdrop: Boolean = false,
) {
    if (options.isEmpty()) return
    val glassEnabled = LocalLiquidGlassEnabled.current
    val isDark = onDarkBackdrop || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(percent = 50)
    val thumbColor = when {
        onDarkBackdrop -> Color.White.copy(alpha = 0.24f)
        glassEnabled -> glassIndicatorColor()
        isDark -> Color(0xFF636366)
        else -> Color.White
    }
    val thumbRim = rememberGlassRimBrush(isDark)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .lightweightGlass(shape = shape, enabled = glassEnabled, onDarkBackdrop = onDarkBackdrop)
            .padding(3.dp),
    ) {
        val segWidth = maxWidth / options.size
        val offset by animateDpAsState(
            targetValue = segWidth * selectedIndex.coerceIn(0, options.lastIndex),
            animationSpec = spring(stiffness = 460f, dampingRatio = 0.82f),
            label = "pill_seg_offset",
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(thumbColor)
                .then(
                    if (glassEnabled) Modifier.border(0.65.dp, thumbRim, shape) else Modifier,
                ),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = when {
                        onDarkBackdrop && selected -> Color.White
                        onDarkBackdrop -> Color.White.copy(alpha = 0.72f)
                        selected -> if (isDark) Color.White else Color.Black
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "seg_txt_$idx",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(idx)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Bidirectional swipe-to-queue wrapper for track rows:
 * - Swipe right -> Play Next
 * - Swipe left -> Add to Queue
 * Triggers a crisp armed haptic at threshold and springs smoothly back to rest.
 */
@Composable
fun SwipeToQueueBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled || (onPlayNext == null && onAddToQueue == null)) {
        Box(modifier = modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val maxSwipePx = with(density) { 104.dp.toPx() }
    val triggerPx = with(density) { 64.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    val currentOnPlayNext by androidx.compose.runtime.rememberUpdatedState(onPlayNext)
    val currentOnAddToQueue by androidx.compose.runtime.rememberUpdatedState(onAddToQueue)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                try {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val current = offsetX.value
                            val playNext = currentOnPlayNext
                            val addToQueue = currentOnAddToQueue
                            if (current >= triggerPx && playNext != null) {
                                playNext()
                            } else if (current <= -triggerPx && addToQueue != null) {
                                addToQueue()
                            }
                            armed = false
                            scope.launch {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val minBound = if (currentOnAddToQueue != null) -maxSwipePx else 0f
                            val maxBound = if (currentOnPlayNext != null) maxSwipePx else 0f
                            val next = (offsetX.value + dragAmount).coerceIn(minBound, maxBound)
                            val nowArmed = abs(next) >= triggerPx
                            if (nowArmed && !armed) {
                                armed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (!nowArmed && armed) {
                                armed = false
                            }
                            scope.launch { offsetX.snapTo(next) }
                        },
                    )
                } finally {
                    armed = false
                    scope.launch { offsetX.snapTo(0f) }
                }
            },
    ) {
        val dx = offsetX.value
        if (abs(dx) > 2f) {
            val isRight = dx > 0f
            val bg = if (isRight) Color(0xFF2D7FF9) else Color(0xFF5E5CE6)
            val progress = (abs(dx) / triggerPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(bg.copy(alpha = 0.20f + 0.72f * progress))
                    .padding(horizontal = 22.dp),
                contentAlignment = if (isRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = if (isRight) Icons.Rounded.QueuePlayNext else Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            val s = 0.8f + 0.28f * progress
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .let { mod ->
                    if (abs(dx) > 2f) mod.background(MaterialTheme.colorScheme.background) else mod
                },
        ) {
            content()
        }
    }
}

/**
 * Floating 44dp circular Liquid Glass top-bar button (Back, Search, Sort, Add, Play, etc.).
 * Set [liveBlur] = true when floating directly over scrolling full-bleed artwork (e.g. DetailScreen).
 */
@Composable
fun FrostedCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    frostedGlassEnabled: Boolean = LocalLiquidGlassEnabled.current,
    liveBlur: Boolean = false,
    onDarkBackdrop: Boolean = false,
    tint: Color = if (onDarkBackdrop) Color.White else if (frostedGlassEnabled) glassContentColor() else MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .then(
                if (liveBlur && hazeState != null && frostedGlassEnabled) {
                    Modifier.frostedGlass(hazeState = hazeState, shape = CircleShape, enabled = true)
                } else {
                    Modifier.lightweightGlass(
                        shape = CircleShape,
                        enabled = frostedGlassEnabled,
                        onDarkBackdrop = onDarkBackdrop,
                    )
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Apple-style thin expanding scrubber/slider shared across NowPlaying, Volume, Sleep Timer,
 * Speed, Lyrics Offset, and Settings. Single unified gesture handles tap-to-seek and smooth drag.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.24f),
    restHeight: Dp = 6.dp,
    dragHeight: Dp = 11.dp,
) {
    var dragging by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) dragHeight else restHeight,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
        label = "thin_slider_h",
    )
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val currentRange by androidx.compose.runtime.rememberUpdatedState(valueRange)
    val currentOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    try {
                        fun emitAt(x: Float) {
                            val r = currentRange
                            val s = (r.endInclusive - r.start).coerceAtLeast(0.0001f)
                            val f = (x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            currentOnValueChange(r.start + f * s)
                        }
                        emitAt(down.position.x)
                        horizontalDrag(down.id) { change ->
                            change.consume()
                            emitAt(change.position.x)
                        }
                        currentOnValueChangeFinished()
                    } finally {
                        dragging = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = inactiveColor,
                size = size,
                cornerRadius = corner,
            )
            val fillW = (size.width * fraction).coerceIn(0f, size.width)
            if (fillW > 0f) {
                drawRoundRect(
                    color = activeColor,
                    size = Size(fillW, size.height),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/**
 * Animated mesh-gradient Sign-In banner used on Home, Mixes, and Library when signed out.
 */
@Composable
fun AnimatedSignInBanner(
    title: String,
    subtitle: String,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    reduceAnimation: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = 480f, dampingRatio = 0.78f),
        label = "signin_banner_scale",
    )

    val rimBrush = rememberGlassRimBrush(isDark = true)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .border(0.85.dp, rimBrush, shape),
    ) {
        MeshGradientBackground(
            colors = OnboardingMeshColors,
            reduceAnimation = reduceAnimation,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.24f)),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.84f),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    pressed = true
                    onSignIn()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_sign_in_google),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
