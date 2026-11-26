package com.music.musica.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.components.AnimatedSignInBanner
import com.music.musica.ui.components.FeedSkeleton
import com.music.musica.ui.components.MusicaBrandEmblem
import com.music.musica.ui.components.PagedTrackCarousel
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.components.rememberBlurredArtwork
import com.music.musica.ui.components.thumbnailBorder
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

private const val TOP_PICK_RATIO = 0.75f
private const val TOP_PICK_FRACTION = 0.60f
private val TOP_PICK_MAX_WIDTH = 280.dp
private val TopPickShape = RoundedCornerShape(12.dp)

/**
 * Musica Home feed featuring:
 * - Minimalist top acoustic-wave emblem + Liquid Glass action pill & frosted circular Profile Avatar
 * - Lead 3:4 portrait "Top Picks for You" hero carousel with blurred-cover DstIn reflection & 3-line editorial captions
 * - Snapping 4-row PagedTrackCarousel for Recently Played & Quick Picks with right-edge peeking column
 * - Linear-sweep Shimmer FeedSkeletons & swipe-to-queue tracks
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    recentlyPlayed: List<Song>,
    profile: AccountProfile?,
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
    onLoadMore: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val shelves = (state as? UiState.Success)?.data.orEmpty()
    val heroIdx = remember(shelves) {
        shelves.indexOfFirst { shelf ->
            shelf.items.size >= 3 &&
                shelf.items.count { it !is ShelfItem.Artist && !it.thumbnailUrl.isNullOrBlank() } >= 3 &&
                !shelf.items.all { it is ShelfItem.Track }
        }
    }
    val heroShelf = if (heroIdx >= 0) shelves[heroIdx] else null

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
                item(key = "home_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MusicaBrandEmblem(
                            color = MusicaAccentRed,
                            modifier = Modifier.size(34.dp),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Compact Liquid Glass utility pill (History, Downloads, Settings)
                            val glassTint = if (frostedGlassEnabled) {
                                glassContentColor()
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Row(
                                modifier = Modifier
                                    .height(40.dp)
                                    .lightweightGlass(RoundedCornerShape(percent = 50))
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TopPillIcon(
                                    icon = Icons.Rounded.History,
                                    contentDescription = stringResource(R.string.library_history),
                                    tint = glassTint,
                                    onClick = onOpenHistory,
                                )
                                TopPillIcon(
                                    icon = Icons.Rounded.DownloadDone,
                                    contentDescription = stringResource(R.string.library_downloads),
                                    tint = glassTint,
                                    onClick = onOpenDownloads,
                                )
                                TopPillIcon(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.settings_title),
                                    tint = glassTint,
                                    onClick = onOpenSettings,
                                )
                            }

                            // Standalone circular frosted Profile Avatar button (zero live Haze capture)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .lightweightGlass(CircleShape)
                                    .clickable { if (!isSignedIn) onOpenLogin() else onOpenSettings() },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!profile?.avatarUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = profile.avatarUrl,
                                        contentDescription = profile.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = stringResource(R.string.auth_sign_in),
                                        tint = glassTint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isSignedIn) {
                    item(key = "home_signin_banner") {
                        AnimatedSignInBanner(
                            title = stringResource(R.string.onboarding_connect_title),
                            subtitle = stringResource(R.string.onboarding_connect_subtitle),
                            onSignIn = onOpenLogin,
                            reduceAnimation = reduceAnimation,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }

                // Lead editorial shelf: Top Picks for You sits first above Recents
                if (heroShelf != null) {
                    item(key = "home_top_picks") {
                        TopPicksHeroShelf(
                            title = stringResource(R.string.home_top_picks),
                            eyebrowFallback = heroShelf.title.ifBlank { stringResource(R.string.home_top_picks) },
                            items = heroShelf.items,
                            onItemClick = onItemClick,
                            onBrowseLongClick = onBrowseLongClick,
                            onSongMore = onSongMore,
                        )
                    }
                }

                if (recentlyPlayed.isNotEmpty()) {
                    item(key = "recently_played") {
                        Column(modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)) {
                            Text(
                                text = stringResource(R.string.home_recently_played),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            PagedTrackCarousel(
                                tracks = recentlyPlayed.take(16),
                                activeVideoId = activeVideoId,
                                downloadedIds = downloadedIds,
                                onTrackClick = { song, _ -> onItemClick(ShelfItem.Track(song)) },
                                onSongMore = onSongMore,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                rowsPerPage = if (recentlyPlayed.size >= 4) 4 else recentlyPlayed.size,
                            )
                        }
                    }
                }

                when (state) {
                    is UiState.Loading -> {
                        item(key = "home_skeleton") {
                            FeedSkeleton(reduceAnimation = reduceAnimation)
                        }
                    }

                    is UiState.Error -> {
                        item(key = "home_error") {
                            ErrorStateCard(
                                message = state.message.ifBlank { stringResource(R.string.state_error_generic) },
                                onRetry = onRefresh,
                            )
                        }
                    }

                    is UiState.Success -> {
                        if (state.data.isEmpty()) {
                            item(key = "home_empty") {
                                ErrorStateCard(
                                    message = stringResource(R.string.musica_empty_state),
                                    onRetry = onRefresh,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = state.data,
                                key = { idx, shelf -> "shelf_${idx}_${shelf.title}" },
                            ) { idx, shelf ->
                                if (idx != heroIdx) {
                                    ShelfSection(
                                        shelf = shelf,
                                        activeVideoId = activeVideoId,
                                        downloadedIds = downloadedIds,
                                        onItemClick = onItemClick,
                                        onSongMore = onSongMore,
                                        onBrowseLongClick = onBrowseLongClick,
                                        onPlayNext = onPlayNext,
                                        onAddToQueue = onAddToQueue,
                                        onSeeAllClick = shelf.browseId?.let { id ->
                                            { onItemClick(ShelfItem.Playlist(browseId = id, title = shelf.title, params = shelf.params)) }
                                        },
                                    )
                                }
                            }
                            item(key = "load_more_trigger") {
                                LaunchedEffect(state.data.size) {
                                    onLoadMore()
                                }
                                Spacer(Modifier.height(6.dp))
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
private fun TopPillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 3:4 portrait hero carousel (Top Picks for You):
 * - Card width 60% of row (max 280dp) so the next card peeks generously on the right
 * - Blurred upscaled artwork reflection across the lower 44% behind the 3-line editorial caption
 * - Crisp square cover at the top dissolving cleanly over its bottom 22% (BlendMode.DstIn)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopPicksHeroShelf(
    title: String,
    eyebrowFallback: String,
    items: List<ShelfItem>,
    onItemClick: (ShelfItem) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onSongMore: (Song) -> Unit,
) {
    val heroItems = remember(items) { items.take(10) }
    val pagerState = rememberPagerState(pageCount = { heroItems.size })
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(2),
        snapPositionalThreshold = 0.18f,
    )
    val haptics = LocalHapticFeedback.current
    val albumLabel = stringResource(R.string.search_filter_albums)
    val playlistLabel = stringResource(R.string.search_filter_playlists)
    val songLabel = stringResource(R.string.search_filter_songs)

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * TOP_PICK_FRACTION, TOP_PICK_MAX_WIDTH)
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardWidth),
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 14.dp,
                snapPosition = SnapPosition.Start,
                beyondViewportPageCount = 1,
                flingBehavior = fling,
                key = { idx -> "hero_${idx}_${heroItems[idx].id}" },
                modifier = Modifier.fillMaxWidth(),
            ) { idx ->
                val item = heroItems[idx]
                    var useRawFallback by remember(item.thumbnailUrl) { mutableStateOf(false) }
                    val coverUrl = remember(item.thumbnailUrl, useRawFallback) {
                        if (useRawFallback) item.thumbnailUrl else upsizeArtworkUrl(item.thumbnailUrl, 480)
                    }
                    val blurredReflection = rememberBlurredArtwork(item.thumbnailUrl, radius = 4)
                    val eyebrow = when (item) {
                        is ShelfItem.Album -> albumLabel
                        is ShelfItem.Playlist -> eyebrowFallback.ifBlank { playlistLabel }
                        is ShelfItem.Track -> songLabel
                        is ShelfItem.Artist -> eyebrowFallback
                    }

                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(TOP_PICK_RATIO)
                            .clip(TopPickShape)
                            .thumbnailBorder(TopPickShape)
                            .background(Color.Black)
                            .combinedClickable(
                                onClick = { onItemClick(item) },
                                onLongClick = when (item) {
                                    is ShelfItem.Track -> {
                                        {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSongMore(item.song)
                                        }
                                    }
                                    else -> onBrowseLongClick?.let { handler ->
                                        {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            handler(item)
                                        }
                                    }
                                },
                            ),
                    ) {
                        // Pre-blurred CPU-cached cover reflection (0 GPU blur passes during scroll)
                        if (blurredReflection != null) {
                            Image(
                                bitmap = blurredReflection,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.46f),
                            )
                        }

                        // Soft tonal legibility wash over the blurred footer
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.56f to Color.Black.copy(alpha = 0.08f),
                                        1.00f to Color.Black.copy(alpha = 0.44f),
                                    ),
                                ),
                        )

                        // Full-res top square artwork dissolving seamlessly into the blur below
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                0.00f to Color.Black,
                                                0.76f to Color.Black,
                                                1.00f to Color.Transparent,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    },
                            )
                        }

                        // 3-line editorial caption
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = eyebrow,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                ),
                                color = Color.White.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 17.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!item.subtitle.isNullOrBlank()) {
                                Text(
                                    text = item.subtitle.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.86f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
fun ErrorStateCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(percent = 50),
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
