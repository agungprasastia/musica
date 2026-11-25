package com.music.musica.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val GESTURE_BAR_FLOOR = 15.dp
private const val SCRIM_STOPS = 16
private const val GLASS_INPUT_SCALE = 0.25f

/**
 * App-wide Liquid Glass toggle provided at [com.music.musica.MusicaAppRoot] so every
 * tier (live blur chrome/overlays, specular buttons/inputs, and subtle glass cards)
 * respects the user's "Frosted Glass" setting synchronously.
 */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }

/**
 * Bottom inset shared by floating bars and bottom fade scrim:
 * honors system navigation bar height while maintaining a 15dp floor when gesture hint is hidden.
 */
internal val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

/**
 * High-contrast foreground color for icons and text sitting on translucent Liquid Glass surfaces
 * over arbitrary blurred artwork.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Active tab / segment capsule fill on Liquid Glass: softly raised white lift in both dark and light themes.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.56f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }

@Composable
internal fun rememberGlassRimBrush(isDark: Boolean): Brush = remember(isDark) {
    Brush.linearGradient(
        colorStops = if (isDark) {
            arrayOf(
                0.00f to Color.White.copy(alpha = 0.36f),
                0.34f to Color.White.copy(alpha = 0.09f),
                0.66f to Color.White.copy(alpha = 0.06f),
                1.00f to Color.White.copy(alpha = 0.24f),
            )
        } else {
            arrayOf(
                0.00f to Color.White.copy(alpha = 0.94f),
                0.45f to Color.Black.copy(alpha = 0.09f),
                1.00f to Color.White.copy(alpha = 0.74f),
            )
        },
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
}

@Composable
internal fun rememberGlassSheenBrush(isDark: Boolean, subtle: Boolean = false): Brush = remember(isDark, subtle) {
    val alpha = when {
        isDark && subtle -> 0.045f
        isDark -> 0.085f
        subtle -> 0.16f
        else -> 0.28f
    }
    Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = alpha),
        0.44f to Color.Transparent,
    )
}

/**
 * Tier 2 — Zero-blur Specular Liquid Glass for floating controls, capsule search bars,
 * segmented controls, and player buttons sitting over stable or gradient backgrounds.
 * Shares the exact same smoked tint, vertical top sheen, and diagonal specular rim as
 * [frostedGlass] without triggering an extra GPU backdrop capture pass per scroll frame.
 */
@Composable
fun Modifier.lightweightGlass(
    shape: Shape,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    onDarkBackdrop: Boolean = false,
): Modifier {
    val isDark = onDarkBackdrop || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rim = rememberGlassRimBrush(isDark)
    val sheen = rememberGlassSheenBrush(isDark, subtle = false)
    val base = this.clip(shape)
    return if (enabled) {
        val fill = when {
            onDarkBackdrop -> Color.White.copy(alpha = 0.14f)
            isDark -> Color(0xFF18181D).copy(alpha = 0.72f)
            else -> Color(0xFFF6F6FA).copy(alpha = 0.84f)
        }
        base
            .background(fill)
            .background(sheen)
            .border(0.8.dp, rim, shape)
    } else {
        val fallback = if (onDarkBackdrop) {
            Color(0xFF222228).copy(alpha = 0.88f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        base
            .background(fallback)
            .border(0.6.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
    }
}

/**
 * Tier 3 — Subtle Liquid Glass Card surface (0 GPU blur passes) for grouped containers
 * (Settings cards, Library links, Search Top Result, Detail spotlight/bio, Mood chips, Replay stats).
 */
@Composable
fun Modifier.glassCard(
    shape: Shape,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    onDarkBackdrop: Boolean = false,
): Modifier {
    val isDark = onDarkBackdrop || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rim = rememberGlassRimBrush(isDark)
    val sheen = rememberGlassSheenBrush(isDark, subtle = true)
    val base = this.clip(shape)
    return if (enabled) {
        val fill = when {
            onDarkBackdrop -> Color.White.copy(alpha = 0.13f)
            isDark -> Color(0xFF16161B).copy(alpha = 0.76f)
            else -> Color(0xFFF5F5F9).copy(alpha = 0.88f)
        }
        base
            .background(fill)
            .background(sheen)
            .border(0.7.dp, rim, shape)
    } else {
        val fallback = if (onDarkBackdrop) {
            Color(0xFF1D1D22).copy(alpha = 0.90f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        base
            .background(fallback)
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
    }
}

/**
 * Tier 1A — Live Refractive Liquid Glass backed by downscaled Haze blur (`0.25x` scale):
 * used on persistent floating chrome (Bottom Nav Bar, MiniPlayer, Detail floating top buttons/title).
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frostedGlass(
    hazeState: HazeState,
    shape: Shape,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    tintAlpha: Float = 0.88f,
): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val specularRim = rememberGlassRimBrush(isDark)
    val topSheen = rememberGlassSheenBrush(isDark, subtle = false)

    val base = this.clip(shape)
    return if (enabled) {
        val style = remember(isDark) {
            if (isDark) {
                HazeStyle(
                    backgroundColor = Color(0xFF0E0E10),
                    tint = HazeTint(Color(0xFF121215).copy(alpha = 0.46f)),
                    blurRadius = 11.dp,
                    noiseFactor = 0f,
                )
            } else {
                HazeStyle(
                    backgroundColor = Color(0xFFF6F6F9),
                    tint = HazeTint(Color(0xFFFAFAFA).copy(alpha = 0.52f)),
                    blurRadius = 12.dp,
                    noiseFactor = 0f,
                )
            }
        }
        base
            .hazeEffect(state = hazeState, style = style) {
                inputScale = HazeInputScale.Fixed(GLASS_INPUT_SCALE)
            }
            .background(topSheen)
            .border(0.8.dp, specularRim, shape)
    } else {
        base
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = tintAlpha.coerceAtLeast(0.92f)))
            .border(0.6.dp, specularRim, shape)
    }
}

/**
 * Tier 1B — Live Refractive Liquid Glass tuned specifically for modal overlays (Sheets, Dialogs,
 * Popover Menus): richer blur radius (`18.dp` at `0.25x` input scale) and balanced smoked tint
 * so multi-row menu text stays crisp and legible over vibrant album artwork.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.frostedOverlayPanel(
    hazeState: HazeState?,
    shape: Shape,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val specularRim = rememberGlassRimBrush(isDark)
    val topSheen = rememberGlassSheenBrush(isDark, subtle = false)

    val base = this.clip(shape)
    return if (enabled && hazeState != null) {
        val style = remember(isDark) {
            if (isDark) {
                HazeStyle(
                    backgroundColor = Color(0xFF101014),
                    tint = HazeTint(Color(0xFF14141A).copy(alpha = 0.62f)),
                    blurRadius = 18.dp,
                    noiseFactor = 0f,
                )
            } else {
                HazeStyle(
                    backgroundColor = Color(0xFFF6F6FA),
                    tint = HazeTint(Color(0xFFFAFAFD).copy(alpha = 0.76f)),
                    blurRadius = 18.dp,
                    noiseFactor = 0f,
                )
            }
        }
        base
            .hazeEffect(state = hazeState, style = style) {
                inputScale = HazeInputScale.Fixed(GLASS_INPUT_SCALE)
            }
            .background(topSheen)
            .border(0.85.dp, specularRim, shape)
    } else {
        base
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(0.7.dp, specularRim, shape)
    }
}

/**
 * In-hierarchy iOS 26-style Floating Detached Liquid Glass Sheet.
 * Rendered directly over the app/player [hazeState] so live artwork and pages refract
 * visibly behind the sheet instead of opening an opaque Material 3 popup window.
 */
@Composable
fun LiquidGlassSheet(
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 88.dp.toPx() }
    val enterOffset = remember { Animatable(240f) }
    val dragOffsetY = remember { Animatable(0f) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) {
        enterOffset.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        )
    }

    val shape = RoundedCornerShape(28.dp)
    val grabberColor = glassContentColor().copy(alpha = 0.28f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .offset { IntOffset(0, (enterOffset.value + dragOffsetY.value).roundToInt()) }
                .frostedOverlayPanel(hazeState = hazeState, shape = shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle zone for smooth finger-following dismiss
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        try {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffsetY.value >= dismissThresholdPx) {
                                        currentOnDismiss()
                                    } else {
                                        scope.launch {
                                            dragOffsetY.animateTo(
                                                0f,
                                                spring(dampingRatio = 0.78f, stiffness = 420f),
                                            )
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { dragOffsetY.animateTo(0f, spring()) }
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    val next = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                                    scope.launch { dragOffsetY.snapTo(next) }
                                },
                            )
                        } finally {
                            scope.launch { dragOffsetY.snapTo(0f) }
                        }
                    }
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.5.dp)
                        .clip(CircleShape)
                        .background(grabberColor),
                )
            }

            content()
        }
    }
}

/**
 * In-hierarchy centered Liquid Glass Dialog replacing stock opaque Material 3 AlertDialog.
 */
@Composable
fun LiquidGlassDialog(
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scale = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
        )
    }

    val shape = RoundedCornerShape(28.dp)
    val fg = glassContentColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.46f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .imePadding()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .frostedOverlayPanel(hazeState = hazeState, shape = shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = fg,
            )
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/**
 * Capsule action button styled for [LiquidGlassDialog] and [LiquidGlassSheet] footers.
 */
@Composable
fun GlassDialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val containerColor = when {
        destructive -> Color(0xFFFF3B30).copy(alpha = if (enabled) 0.20f else 0.08f)
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.92f else 0.35f)
        else -> Color.Transparent
    }
    val textColor = when {
        destructive -> Color(0xFFFF453A)
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> glassContentColor().copy(alpha = if (enabled) 0.88f else 0.40f)
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .then(
                if (!primary && !destructive) {
                    Modifier.lightweightGlass(shape)
                } else {
                    Modifier
                        .clip(shape)
                        .background(containerColor)
                        .border(0.75.dp, Color.White.copy(alpha = 0.20f), shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * In-hierarchy floating iOS-style Liquid Glass Popover Menu (e.g. Detail Sort menu).
 */
@Composable
fun LiquidGlassPopoverMenu(
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopEnd,
    topPadding: Dp = 60.dp,
    endPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scale = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = topPadding, end = endPadding, start = 14.dp)
                .widthIn(min = 200.dp, max = 260.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .frostedOverlayPanel(hazeState = hazeState, shape = shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
fun LiquidGlassMenuRow(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = glassContentColor()
    val tint = when {
        destructive -> Color(0xFFFF453A)
        selected -> MaterialTheme.colorScheme.primary
        else -> fg
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(if (selected) fg.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 16-stop cubic ease-in bottom fade scrim:
 * keeps the upper half below ~12% alpha so colorful album cards remain richly visible right
 * behind and below the floating Liquid Glass bars without a harsh dark curtain.
 */
@Composable
fun BottomFadeScrim(
    hasMiniPlayer: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    val runHeight = if (hasMiniPlayer) 146.dp else 104.dp
    val targetHeight by animateDpAsState(
        targetValue = inset + runHeight,
        animationSpec = tween(durationMillis = 220),
        label = "bottom_fade_height",
    )
    val brush = remember(bg) {
        Brush.verticalGradient(
            colorStops = Array(SCRIM_STOPS) { i ->
                val t = i / (SCRIM_STOPS - 1f)
                t to bg.copy(alpha = EaseInCubic.transform(t) * 0.88f)
            },
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(targetHeight)
            .background(brush),
    )
}

/**
 * Absorbs stray thumb taps in the narrow margins around the floating bottom bars so
 * users never accidentally tap feed cards peeking through the gaps.
 */
@Composable
fun FloatingBarsTapGuard(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val inset = floatingBarInsets.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(inset + height)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/**
 * Zero-blur smooth top status-bar scrim so scrolled cards dissolve softly beneath the system clock
 * without paying for an extra live Haze blur pass on every scroll frame.
 */
@Composable
fun TopFadeBlur(
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    @Suppress("UNUSED_PARAMETER") frostedGlassEnabled: Boolean,
    extraHeight: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    val gradient = remember(bg) {
        Brush.verticalGradient(
            0.00f to bg.copy(alpha = 0.88f),
            0.55f to bg.copy(alpha = 0.36f),
            1.00f to Color.Transparent,
        )
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(extraHeight)
                .background(gradient),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(bg.copy(alpha = 0.88f)),
        )
    }
}
