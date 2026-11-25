package com.music.musica.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.ExploreCategory
import com.music.musica.core.model.ExploreFeed
import com.music.musica.core.model.UiState
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.components.ShimmerBox
import com.music.musica.ui.components.TopFadeBlur
import dev.chrisbanes.haze.HazeState

private val PAGE_GUTTER = 16.dp
private val SLEEVE_GAP = 12.dp
private val SLEEVE_SHAPE = RoundedCornerShape(18.dp)
private const val SLEEVE_ASPECT = 1.72f
private const val SLEEVE_STRIPE_FRACTION = 0.28f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<ExploreFeed>,
    isRefreshing: Boolean,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    onRefresh: () -> Unit,
    onCategoryClick: (ExploreCategory) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = computeExploreColumns(maxWidth)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 22.dp),
            ) {
                item(key = "explore_header") {
                    Text(
                        text = stringResource(R.string.explore_title),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 14.dp, bottom = 14.dp),
                    )
                }

                when (state) {
                    is UiState.Loading -> {
                        item(key = "explore_skeleton") {
                            ExploreSkeletonGrid(columns = columns, reduceAnimation = reduceAnimation)
                        }
                    }

                    is UiState.Error -> {
                        item(key = "explore_error") {
                            ErrorStateCard(
                                message = state.message.ifBlank { stringResource(R.string.state_error_generic) },
                                onRetry = onRefresh,
                            )
                        }
                    }

                    is UiState.Success -> {
                        val categories = state.data.categories
                        if (categories.isEmpty()) {
                            item(key = "explore_empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.musica_empty_state),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            val rows = categories.chunked(columns)
                            itemsIndexed(
                                items = rows,
                                key = { rowIdx, row -> "explore_row_${rowIdx}_${row.first().key}" },
                            ) { _, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = PAGE_GUTTER)
                                        .padding(bottom = SLEEVE_GAP),
                                    horizontalArrangement = Arrangement.spacedBy(SLEEVE_GAP),
                                ) {
                                    row.forEach { category ->
                                        DuotoneCategorySleeve(
                                            category = category,
                                            onClick = { onCategoryClick(category) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TopFadeBlur(
                hazeState = hazeState,
                frostedGlassEnabled = frostedGlassEnabled,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun DuotoneCategorySleeve(
    category: ExploreCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val treatment = remember(category.stripeColorArgb, category.title) {
        buildDuotoneTreatment(category.stripeColorArgb, category.title)
    }

    Box(
        modifier = modifier
            .aspectRatio(SLEEVE_ASPECT)
            .clip(SLEEVE_SHAPE)
            .background(treatment.stripe)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(1f - SLEEVE_STRIPE_FRACTION)
                .background(
                    Brush.linearGradient(
                        colors = listOf(treatment.backdropStart, treatment.backdropEnd),
                    ),
                ),
        ) {
            val coverUrl = remember(category.thumbnailUrl) {
                upsizeArtworkUrl(category.thumbnailUrl, 360)
            }
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = treatment.duotoneFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.24f),
                        0.62f to Color.Transparent,
                    ),
                ),
        )

        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.35f),
                    offset = Offset(0f, 1f),
                    blurRadius = 6f,
                ),
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private data class DuotoneTreatment(
    val stripe: Color,
    val backdropStart: Color,
    val backdropEnd: Color,
    val duotoneFilter: ColorFilter,
)

private fun buildDuotoneTreatment(stripeColorArgb: Long?, title: String): DuotoneTreatment {
    val raw = if (stripeColorArgb != null && stripeColorArgb != 0L) {
        Color((stripeColorArgb or 0xFF000000L).toInt())
    } else {
        curatedFallbackSwatch(title)
    }
    val stripe = lerp(raw, Color.Black, 0.08f)
    val shadow = lerp(raw, Color.Black, 0.68f)
    val highlight = lerp(raw, Color.White, 0.32f)
    return DuotoneTreatment(
        stripe = stripe,
        backdropStart = lerp(shadow, highlight, 0.45f),
        backdropEnd = lerp(shadow, highlight, 0.25f),
        duotoneFilter = buildDuotoneColorFilter(shadow, highlight),
    )
}

private fun buildDuotoneColorFilter(shadow: Color, highlight: Color): ColorFilter {
    fun luminanceRamp(from: Float, to: Float): FloatArray {
        val delta = to - from
        return floatArrayOf(delta * 0.299f, delta * 0.587f, delta * 0.114f, 0f, from * 255f)
    }
    val matrix = ColorMatrix(
        luminanceRamp(shadow.red, highlight.red) +
            luminanceRamp(shadow.green, highlight.green) +
            luminanceRamp(shadow.blue, highlight.blue) +
            floatArrayOf(0f, 0f, 0f, 1f, 0f),
    )
    return ColorFilter.colorMatrix(matrix)
}

private val CuratedMoodSwatches = listOf(
    Color(0xFFC86452),
    Color(0xFFB8708A),
    Color(0xFF8E7EB8),
    Color(0xFF6E84BD),
    Color(0xFFC99658),
    Color(0xFF5D80A8),
    Color(0xFF6AA4C4),
    Color(0xFF72A884),
)

private fun curatedFallbackSwatch(title: String): Color =
    CuratedMoodSwatches[(title.hashCode() and Int.MAX_VALUE) % CuratedMoodSwatches.size]

private fun computeExploreColumns(availableWidth: Dp): Int {
    val usable = availableWidth - PAGE_GUTTER * 2
    return ((usable + SLEEVE_GAP) / (220.dp + SLEEVE_GAP)).toInt().coerceIn(2, 6)
}

@Composable
private fun ExploreSkeletonGrid(
    columns: Int,
    reduceAnimation: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = SLEEVE_GAP),
                horizontalArrangement = Arrangement.spacedBy(SLEEVE_GAP),
            ) {
                repeat(columns) {
                    ShimmerBox(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(SLEEVE_ASPECT),
                        shape = SLEEVE_SHAPE,
                        reduceAnimation = reduceAnimation,
                    )
                }
            }
        }
    }
}
