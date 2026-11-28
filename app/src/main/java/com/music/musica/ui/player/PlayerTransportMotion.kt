package com.music.musica.ui.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import kotlinx.coroutines.launch

private const val PLAY_PRESS_SCALE = 0.90f
private const val PLAY_SPRING_DAMPING = 0.68f
private const val PLAY_SPRING_STIFFNESS = 170f
private const val SKIP_CONVEYOR_MS = 600
private const val FULL_BLEED_FADE_FRACTION = 0.42f

internal fun smootherStepCurve(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

/**
 * 16-stop `smootherStep` `DstIn` alpha fade across the bottom `42%` of full-bleed cover artwork.
 * Tightens seamlessly to zero fade as [collapseProgress] moves `0f -> 1f` so the collapsed 54dp
 * header thumbnail renders completely crisp.
 */
internal fun Modifier.artworkBottomFade(
    enabled: Boolean,
    collapseProgress: () -> Float,
): Modifier {
    if (!enabled) return this
    return this
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawContent()
            val p = collapseProgress().coerceIn(0f, 1f)
            val fadeAmount = (1f - p * 1.45f).coerceIn(0f, 1f)
            if (fadeAmount <= 0.005f) return@drawWithContent
            val fadeFraction = FULL_BLEED_FADE_FRACTION * fadeAmount
            val fadeStart = (1f - fadeFraction).coerceIn(0f, 0.99f)
            val stops = Array(16) { idx ->
                val local = idx / 15f
                val pos = fadeStart + local * (1f - fadeStart)
                val alpha = 1f - smootherStepCurve(local) * fadeAmount
                pos to Color.Black.copy(alpha = alpha)
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.Black, *stops),
                    startY = 0f,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Tactile Play/Pause button (`74.dp` icon in a `92.dp` hit zone):
 * - Spring press scale (`0.90f`, damping `0.68f`, stiffness `170f`)
 * - Subtle draw-only circular press halo disc
 * - API 31+ cross-blur morph between Play and Pause glyphs
 */
@Composable
internal fun TactilePlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PLAY_PRESS_SCALE else 1f,
        animationSpec = if (reduceAnimation) {
            tween(0)
        } else {
            spring(dampingRatio = PLAY_SPRING_DAMPING, stiffness = PLAY_SPRING_STIFFNESS)
        },
        label = "play_press_scale",
    )
    val discAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.16f else 0f,
        animationSpec = tween(durationMillis = if (pressed) 90 else 220),
        label = "play_press_disc",
    )

    val morph by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = if (reduceAnimation) {
            tween(0)
        } else {
            tween(durationMillis = 240, easing = FastOutSlowInEasing)
        },
        label = "play_pause_morph",
    )

    val playLabel = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)

    Box(
        modifier = modifier
            .size(92.dp)
            .drawBehind {
                if (discAlpha > 0.005f) {
                    drawCircle(
                        color = Color.White.copy(alpha = discAlpha),
                        radius = size.minDimension * 0.44f,
                    )
                }
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = playLabel,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(34.dp),
            )
        } else {
            // Cross-morph Play <-> Pause with subtle mid-transition Gaussian blur on API 31+
            val playAlpha = (1f - morph).coerceIn(0f, 1f)
            val pauseAlpha = morph.coerceIn(0f, 1f)
            val playScale = 0.72f + 0.28f * playAlpha
            val pauseScale = 0.72f + 0.28f * pauseAlpha
            val playBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !reduceAnimation) {
                (morph * 6f).dp
            } else 0.dp
            val pauseBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !reduceAnimation) {
                ((1f - morph) * 6f).dp
            } else 0.dp

            if (playAlpha > 0.01f) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = if (!isPlaying) playLabel else null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(74.dp)
                        .graphicsLayer {
                            alpha = playAlpha
                            scaleX = playScale
                            scaleY = playScale
                        }
                        .then(
                            if (playBlur > 0.2.dp) {
                                Modifier.blur(playBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            } else Modifier,
                        ),
                )
            }
            if (pauseAlpha > 0.01f) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = if (isPlaying) playLabel else null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(74.dp)
                        .graphicsLayer {
                            alpha = pauseAlpha
                            scaleX = pauseScale
                            scaleY = pauseScale
                        }
                        .then(
                            if (pauseBlur > 0.2.dp) {
                                Modifier.blur(pauseBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            } else Modifier,
                        ),
                )
            }
        }
    }
}

/**
 * Tactile double-arrowhead Skip button (`53.dp` canvas scaled `scaleY = 0.85f` in a `64.dp` touch target)
 * with `600ms` conveyor animation on tap:
 * - Front arrowhead shrinks out forward
 * - Back arrowhead slides into the front slot
 * - New arrowhead grows into the back slot
 */
@Composable
internal fun SkipConveyorButton(
    isNext: Boolean,
    contentDescription: String,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 53.dp,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val conveyor = remember { Animatable(0f) }
    var triggerCount by remember { mutableIntStateOf(0) }

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = if (reduceAnimation) tween(0) else spring(dampingRatio = 0.72f, stiffness = 260f),
        label = "skip_press_scale",
    )
    val discAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.14f else 0f,
        animationSpec = tween(durationMillis = if (pressed) 90 else 220),
        label = "skip_press_disc",
    )

    LaunchedEffect(triggerCount) {
        if (triggerCount == 0 || reduceAnimation) return@LaunchedEffect
        conveyor.snapTo(0f)
        conveyor.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = SKIP_CONVEYOR_MS, easing = LinearOutSlowInEasing),
        )
        conveyor.snapTo(0f)
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .drawBehind {
                if (discAlpha > 0.005f) {
                    drawCircle(
                        color = Color.White.copy(alpha = discAlpha),
                        radius = size.minDimension * 0.45f,
                    )
                }
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = {
                    if (!reduceAnimation) {
                        triggerCount++
                    } else {
                        scope.launch { conveyor.snapTo(0f) }
                    }
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleY = 0.85f
                    if (!isNext) scaleX = -1f
                },
        ) {
            val w = size.width
            val h = size.height
            val triW = w * 0.40f
            val triH = h * 0.62f
            val cy = h * 0.5f
            val backCx = w * 0.30f
            val frontCx = w * 0.68f
            val step = frontCx - backCx
            val t = conveyor.value

            val path = Path()
            fun drawArrowhead(cx: Float, scale: Float, alpha: Float) {
                if (scale <= 0.02f || alpha <= 0.02f) return
                path.reset()
                val hw = triW * 0.5f
                val hh = triH * 0.5f
                val corner = hh * 0.14f
                // Rounded-tip right-pointing triangle centered at (cx, cy)
                path.moveTo(cx - hw, cy - hh + corner)
                path.quadraticTo(cx - hw, cy - hh, cx - hw + corner, cy - hh + corner * 0.5f)
                path.lineTo(cx + hw - corner, cy - corner * 0.6f)
                path.quadraticTo(cx + hw, cy, cx + hw - corner, cy + corner * 0.6f)
                path.lineTo(cx - hw + corner, cy + hh - corner * 0.5f)
                path.quadraticTo(cx - hw, cy + hh, cx - hw, cy + hh - corner)
                path.close()
                withTransform({
                    scale(scaleX = scale, scaleY = scale, pivot = Offset(cx, cy))
                }) {
                    drawPath(path = path, color = Color.White.copy(alpha = alpha))
                }
            }

            if (t <= 0.001f || t >= 0.999f) {
                drawArrowhead(backCx, 1f, 1f)
                drawArrowhead(frontCx, 1f, 1f)
            } else {
                val eased = smootherStepCurve(t)
                // 1. Outgoing front arrow shrinks & fades slightly forward
                drawArrowhead(
                    cx = frontCx + step * 0.24f * eased,
                    scale = (1f - eased).coerceIn(0f, 1f),
                    alpha = (1f - eased * 1.15f).coerceIn(0f, 1f),
                )
                // 2. Back arrow glides smoothly into the front slot
                drawArrowhead(
                    cx = backCx + step * eased,
                    scale = 1f,
                    alpha = 1f,
                )
                // 3. New arrow grows into the back slot
                drawArrowhead(
                    cx = backCx - step * 0.24f * (1f - eased),
                    scale = eased,
                    alpha = (eased * 1.25f).coerceIn(0f, 1f),
                )
            }
        }
    }
}

/**
 * Horizontal swipe metadata slide for track title & artist credits:
 * tracks live drag offset 1:1 and animates a crisp directional slide whenever [trackKey] changes.
 */
@Composable
internal fun SongInfoTransition(
    trackKey: String,
    dragOffsetPx: () -> Float,
    lastSwipeDirection: Int,
    reduceAnimation: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val settleOffset = remember { Animatable(0f) }
    var previousKey by remember { mutableStateOf(trackKey) }

    LaunchedEffect(trackKey) {
        if (previousKey != trackKey) {
            previousKey = trackKey
            if (!reduceAnimation && lastSwipeDirection != 0) {
                val enterFrom = if (lastSwipeDirection < 0) 64f else -64f
                settleOffset.snapTo(enterFrom)
                settleOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
            } else {
                settleOffset.snapTo(0f)
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            val drag = dragOffsetPx() * 0.45f
            val totalX = drag + settleOffset.value
            translationX = totalX
            val fade = (1f - (kotlin.math.abs(totalX) / 260f)).coerceIn(0.35f, 1f)
            alpha = fade
        },
    ) {
        content()
    }
}
