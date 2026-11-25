package com.music.musica.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.ExploreCategory
import com.music.musica.core.model.ExploreFeed
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.MixesFeed
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.components.AnimatedSignInBanner
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.ShimmerBox
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.rememberBlurredArtwork
import com.music.musica.ui.components.thumbnailBorder
import com.music.musica.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState

private val PAGE_GUTTER = 16.dp
private val SLEEVE_GAP = 12.dp
private val SLEEVE_SHAPE = RoundedCornerShape(18.dp)
private const val SLEEVE_ASPECT = 1.72f
private const val SLEEVE_STRIPE_FRACTION = 0.28f

private val STATION_GAP = 12.dp
private val STATION_SHAPE = RoundedCornerShape(12.dp)
private val FEATURE_CARD_WIDTH = 240.dp
private val FEATURE_CARD_SHAPE = RoundedCornerShape(14.dp)

// ---- EXPLORE (JELAJAH) ----

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

// ---- MIXES (MIX) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixesScreen(
    state: UiState<MixesFeed>,
    isSignedIn: Boolean,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    isRefreshing: Boolean,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    onRefresh: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onSignIn: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
            ) {
                item(key = "mixes_header") {
                    Text(
                        text = stringResource(R.string.mixes_title),
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
                        item(key = "mixes_skeleton") {
                            MixesSkeletonLayout(reduceAnimation = reduceAnimation)
                        }
                    }

                    is UiState.Error -> {
                        item(key = "mixes_error") {
                            ErrorStateCard(
                                message = state.message.ifBlank { stringResource(R.string.state_error_generic) },
                                onRetry = onRefresh,
                            )
                        }
                    }

                    is UiState.Success -> {
                        val feed = state.data
                        if (feed.isEmpty) {
                            item(key = "mixes_empty") {
                                if (!isSignedIn) {
                                    AnimatedSignInBanner(
                                        title = stringResource(R.string.mixes_signin_title),
                                        subtitle = stringResource(R.string.mixes_signin_desc),
                                        onSignIn = onSignIn,
                                        reduceAnimation = reduceAnimation,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.mixes_empty),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else {
                            if (feed.stations.isNotEmpty()) {
                                item(key = "mixes_stations") {
                                    StationTilesGrid(
                                        stations = feed.stations,
                                        onItemClick = onItemClick,
                                        onSongMore = onSongMore,
                                    )
                                }
                            }

                            if (feed.featured.isNotEmpty()) {
                                item(key = "mixes_featured") {
                                    val carouselTitle = stringResource(
                                        if (feed.isPersonalized) R.string.mixes_for_you else R.string.mixes_featured,
                                    )
                                    MixFeatureCarousel(
                                        title = carouselTitle,
                                        items = feed.featured,
                                        reduceAnimation = reduceAnimation,
                                        onItemClick = onItemClick,
                                        onSongMore = onSongMore,
                                    )
                                }
                            }

                            itemsIndexed(
                                items = feed.moodShelves,
                                key = { idx, shelf -> "mix_shelf_${idx}_${shelf.key}" },
                            ) { _, shelf ->
                                ShelfSection(
                                    shelf = HomeShelf(title = shelf.title, items = shelf.items),
                                    activeVideoId = activeVideoId,
                                    downloadedIds = downloadedIds,
                                    onItemClick = onItemClick,
                                    onSongMore = onSongMore,
                                    onPlayNext = onPlayNext,
                                    onAddToQueue = onAddToQueue,
                                )
                            }

                            if (!isSignedIn && !feed.isPersonalized) {
                                item(key = "mixes_signin_prompt") {
                                    Spacer(Modifier.height(12.dp))
                                    AnimatedSignInBanner(
                                        title = stringResource(R.string.mixes_signin_title),
                                        subtitle = stringResource(R.string.mixes_signin_desc),
                                        onSignIn = onSignIn,
                                        reduceAnimation = reduceAnimation,
                                    )
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
private fun StationTilesGrid(
    stations: List<ShelfItem>,
    onItemClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 26.dp),
    ) {
        val columns = if (maxWidth >= 600.dp) 6 else 3
        Column(verticalArrangement = Arrangement.spacedBy(STATION_GAP)) {
            stations.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(STATION_GAP),
                ) {
                    row.forEach { item ->
                        BlurredStationTile(
                            item = item,
                            onClick = { onItemClick(item) },
                            onLongClick = (item as? ShelfItem.Track)?.let { t -> { onSongMore(t.song) } },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlurredStationTile(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val blurred = rememberBlurredArtwork(item.thumbnailUrl, radius = 5)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(STATION_SHAPE)
            .thumbnailBorder(STATION_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { menu ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    }
                },
            ),
    ) {
        if (blurred != null) {
            Image(
                bitmap = blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                MusicaStationEmblem(
                    color = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.35f),
                        offset = Offset(0f, 1f),
                        blurRadius = 4f,
                    ),
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 9.dp),
            )
        }
    }
}

@Composable
private fun MusicaStationEmblem(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val heights = floatArrayOf(0.38f, 0.72f, 1.0f, 0.64f, 0.42f)
        val count = heights.size
        val barWidth = size.width / (count * 2f - 0.6f)
        val gap = (size.width - barWidth * count) / (count - 1)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

        heights.forEachIndexed { idx, fraction ->
            val h = size.height * fraction
            val left = idx * (barWidth + gap)
            val top = (size.height - h) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun MixFeatureCarousel(
    title: String,
    items: List<ShelfItem>,
    reduceAnimation: Boolean,
    onItemClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(2),
        snapPositionalThreshold = 0.18f,
    )

    Column(modifier = Modifier.padding(bottom = 26.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 12.dp),
        )

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(FEATURE_CARD_WIDTH),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            pageSpacing = 14.dp,
            snapPosition = SnapPosition.Start,
            beyondViewportPageCount = 1,
            flingBehavior = fling,
            key = { idx -> "feat_${idx}_${items[idx].id}" },
            modifier = Modifier.fillMaxWidth(),
        ) { idx ->
            val item = items[idx]
            MixFeatureCard(
                item = item,
                reduceAnimation = reduceAnimation,
                onClick = { onItemClick(item) },
                onLongClick = (item as? ShelfItem.Track)?.let { t -> { onSongMore(t.song) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixFeatureCard(
    item: ShelfItem,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val palette = rememberArtworkPalette(
        imageUrl = item.thumbnailUrl,
        reduceAnimation = reduceAnimation,
    )
    val haptics = LocalHapticFeedback.current
    val coverUrl = remember(item.thumbnailUrl) { upsizeArtworkUrl(item.thumbnailUrl, 544) }

    Column(
        modifier = Modifier
            .width(FEATURE_CARD_WIDTH)
            .clip(FEATURE_CARD_SHAPE)
            .thumbnailBorder(FEATURE_CARD_SHAPE)
            .background(palette.wash)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { menu ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitle.orEmpty().ifBlank { stringResource(R.string.mixes_subtitle) },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MixesSkeletonLayout(reduceAnimation: Boolean) {
    Column(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = STATION_GAP),
                horizontalArrangement = Arrangement.spacedBy(STATION_GAP),
            ) {
                repeat(3) {
                    ShimmerBox(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        shape = STATION_SHAPE,
                        reduceAnimation = reduceAnimation,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ShimmerBox(
            modifier = Modifier.width(160.dp).height(22.dp),
            shape = RoundedCornerShape(8.dp),
            reduceAnimation = reduceAnimation,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(2) {
                ShimmerBox(
                    modifier = Modifier.width(FEATURE_CARD_WIDTH).height(308.dp),
                    shape = FEATURE_CARD_SHAPE,
                    reduceAnimation = reduceAnimation,
                )
            }
        }
    }
}
