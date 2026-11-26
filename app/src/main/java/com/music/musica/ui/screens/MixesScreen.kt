package com.music.musica.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
private val STATION_GAP = 12.dp
private val STATION_SHAPE = RoundedCornerShape(12.dp)
private val FEATURE_CARD_WIDTH = 240.dp
private val FEATURE_CARD_SHAPE = RoundedCornerShape(14.dp)

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
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
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
                                        onBrowseLongClick = onBrowseLongClick,
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
                                        onBrowseLongClick = onBrowseLongClick,
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
                                    onBrowseLongClick = onBrowseLongClick,
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
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
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
                            onLongClick = when (item) {
                                is ShelfItem.Track -> {
                                    { onSongMore(item.song) }
                                }
                                else -> onBrowseLongClick?.let { handler -> { handler(item) } }
                            },
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
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF2D2E38), Color(0xFF1A1B22)),
                ),
            )
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
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
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
                onLongClick = when (item) {
                    is ShelfItem.Track -> {
                        { onSongMore(item.song) }
                    }
                    else -> onBrowseLongClick?.let { handler -> { handler(item) } }
                },
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
    var useRawFallback by remember(item.thumbnailUrl) { mutableStateOf(false) }
    val coverUrl = remember(item.thumbnailUrl, useRawFallback) {
        if (useRawFallback) item.thumbnailUrl else upsizeArtworkUrl(item.thumbnailUrl, 544)
    }

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
                    onError = {
                        if (!useRawFallback && coverUrl != item.thumbnailUrl) {
                            useRawFallback = true
                        }
                    },
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
