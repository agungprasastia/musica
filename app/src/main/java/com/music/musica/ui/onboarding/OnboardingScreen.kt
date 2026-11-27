package com.music.musica.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.ui.components.MESH_CYCLE_MILLIS
import com.music.musica.ui.components.MeshGradientBackground
import com.music.musica.ui.components.MusicaBrandEmblem
import com.music.musica.ui.components.rememberCyclingMeshColors
import com.music.musica.ui.theme.ConnectMeshPalettes
import com.music.musica.ui.theme.WelcomeMeshPalettes

private const val PAGE_WELCOME = 0
private const val PAGE_CONNECT = 1

/**
 * Editorial two-step onboarding experience for Musica:
 * 1. Welcome showcase with the official Kinetic Wave emblem, bold left-aligned display headline,
 *    and translucent Liquid Glass feature highlights over a continuously cycling luminous mesh.
 * 2. YouTube account connection with tactile white capsule Google Sign-In and frosted Skip pill.
 */
@Composable
fun OnboardingScreen(
    reduceAnimation: Boolean,
    onSignInWithGoogle: () -> Unit,
    onContinueGuest: () -> Unit,
    modifier: Modifier = Modifier,
    isSignedIn: Boolean = false,
) {
    var page by rememberSaveable { mutableIntStateOf(PAGE_WELCOME) }

    BackHandler(enabled = page == PAGE_CONNECT) { page = PAGE_WELCOME }

    LaunchedEffect(isSignedIn, page) {
        if (isSignedIn && page == PAGE_CONNECT) onContinueGuest()
    }

    val activePalettes = if (page == PAGE_WELCOME) WelcomeMeshPalettes else ConnectMeshPalettes
    val meshColors = rememberCyclingMeshColors(
        palettes = activePalettes,
        stepMillis = MESH_CYCLE_MILLIS,
        reduceAnimation = reduceAnimation,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MeshGradientBackground(
            colors = meshColors,
            driftKey = page,
            reduceAnimation = reduceAnimation,
            continuous = true,
            driftMillis = 6_000,
            colorFadeMillis = MESH_CYCLE_MILLIS,
            scrimAlpha = 0f,
        )

        // Subtle editorial vertical scrim: keeps upper mesh bright while grounding bottom capsules
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.08f),
                        0.50f to Color.Black.copy(alpha = 0.04f),
                        1.00f to Color.Black.copy(alpha = 0.52f),
                    ),
                ),
        )

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState > initialState
                val slide = tween<IntOffset>(if (reduceAnimation) 0 else 420)
                val enter = slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } +
                    fadeIn(tween(if (reduceAnimation) 0 else 320, delayMillis = if (reduceAnimation) 0 else 90))
                val exit = slideOutHorizontally(slide) { if (forward) -it / 3 else it / 3 } +
                    fadeOut(tween(if (reduceAnimation) 0 else 200))
                enter.togetherWith(exit)
            },
            label = "musica_onboarding_page",
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) { shown ->
            OnboardingPageContainer {
                when (shown) {
                    PAGE_WELCOME -> WelcomePage(
                        onNext = {
                            if (isSignedIn) onContinueGuest() else page = PAGE_CONNECT
                        },
                    )
                    else -> ConnectAccountPage(
                        onBack = { page = PAGE_WELCOME },
                        onSignInWithGoogle = onSignInWithGoogle,
                        onSkip = onContinueGuest,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContainer(
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp),
            content = content,
        )
    }
}

@Composable
private fun ColumnScope.WelcomePage(
    onNext: () -> Unit,
) {
    Spacer(Modifier.height(28.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicaBrandEmblem(
            color = Color.White,
            modifier = Modifier.size(44.dp),
        )
        StepDots(activeIndex = PAGE_WELCOME, total = 2)
    }

    Spacer(Modifier.height(28.dp))

    OnboardingTitle(stringResource(R.string.onboarding_welcome_title))

    Spacer(Modifier.height(14.dp))

    Text(
        text = stringResource(R.string.onboarding_welcome_subtitle),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 24.sp,
        ),
        color = Color.White.copy(alpha = 0.78f),
    )

    Spacer(Modifier.weight(1f))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OnboardingFeaturePill(
            icon = Icons.Rounded.Lyrics,
            title = stringResource(R.string.onboarding_feature_lyrics_title),
            subtitle = stringResource(R.string.onboarding_feature_lyrics_desc),
        )
        OnboardingFeaturePill(
            icon = Icons.Rounded.DownloadForOffline,
            title = stringResource(R.string.onboarding_feature_offline_title),
            subtitle = stringResource(R.string.onboarding_feature_offline_desc),
        )
        OnboardingFeaturePill(
            icon = Icons.Rounded.AutoAwesome,
            title = stringResource(R.string.onboarding_feature_glass_title),
            subtitle = stringResource(R.string.onboarding_feature_glass_desc),
        )
    }

    Spacer(Modifier.height(26.dp))

    OnboardingPillButton(
        label = stringResource(R.string.onboarding_next),
        primary = true,
        onClick = onNext,
    )

    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ColumnScope.ConnectAccountPage(
    onBack: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSkip: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(0.6.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        StepDots(activeIndex = PAGE_CONNECT, total = 2)
    }

    Spacer(Modifier.height(24.dp))

    MusicaBrandEmblem(
        color = Color.White.copy(alpha = 0.92f),
        modifier = Modifier.size(34.dp),
    )

    Spacer(Modifier.height(18.dp))

    OnboardingTitle(stringResource(R.string.onboarding_account_title))

    Spacer(Modifier.height(14.dp))

    Text(
        text = stringResource(R.string.onboarding_account_body),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 24.sp,
        ),
        color = Color.White.copy(alpha = 0.76f),
    )

    Spacer(Modifier.weight(1f))

    OnboardingPillButton(
        label = stringResource(R.string.onboarding_sign_in_google),
        primary = true,
        leading = {
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onSignInWithGoogle,
    )

    Spacer(Modifier.height(12.dp))

    OnboardingPillButton(
        label = stringResource(R.string.onboarding_continue_guest),
        primary = false,
        onClick = onSkip,
    )

    Spacer(Modifier.height(20.dp))
}

@Composable
private fun OnboardingTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.2).sp,
            fontWeight = FontWeight.ExtraBold,
        ),
        color = Color.White,
    )
}

@Composable
private fun StepDots(activeIndex: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { idx ->
            val selected = idx == activeIndex
            val w by animateDpAsState(
                targetValue = if (selected) 22.dp else 6.dp,
                animationSpec = spring(stiffness = 480f, dampingRatio = 0.8f),
                label = "step_dot_$idx",
            )
            Box(
                modifier = Modifier
                    .width(w)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (selected) 0.94f else 0.34f)),
            )
        }
    }
}

@Composable
private fun OnboardingFeaturePill(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(0.65.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.74f),
            )
        }
    }
}

@Composable
private fun OnboardingPillButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 900f),
        label = "pill_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(180),
        label = "pill_alpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .then(
                if (!primary) Modifier.border(0.65.dp, Color.White.copy(alpha = 0.24f), CircleShape) else Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (primary) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
