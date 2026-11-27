package com.music.musica.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.UiState
import com.music.musica.ui.components.AnimatedSignInBanner
import com.music.musica.ui.components.FeedSkeleton
import com.music.musica.ui.components.MusicaBrandEmblem
import com.music.musica.ui.components.PagedTrackCarousel
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.TopPicksCarousel
import com.music.musica.ui.components.TopPicksSkeleton
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

/**
 * Musica Home feed featuring:
 * - Minimalist top acoustic-wave emblem + Liquid Glass action pill & frosted circular Profile Avatar
 * - Personalized 3:4 portrait "Top Picks for You" editorial & station carousel
 * - Snapping 4-row PagedTrackCarousel for Recently Played & Quick Picks with right-edge peeking column
 * - Linear-sweep Shimmer FeedSkeletons & swipe-to-queue tracks
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    topPicks: List<TopPick>?,
    topPicksLoading: Boolean,
    activeTopPickLoadingKey: String?,
    hideTopPicks: Boolean,
    artworkMotionEnabled: Boolean,
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
    onTopPickClick: (TopPick, String) -> Unit,
    onTopPickLongClick: ((TopPick) -> Unit)? = null,
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

                // Lead editorial & station shelf: Top Picks for You sits first above Recents
                if (!hideTopPicks) {
                    when {
                        !topPicks.isNullOrEmpty() -> {
                            item(key = "home_top_picks") {
                                TopPicksCarousel(
                                    picks = topPicks,
                                    listenerName = profile?.name,
                                    onPickClick = onTopPickClick,
                                    loadingPickKey = activeTopPickLoadingKey,
                                    artworkMotionEnabled = artworkMotionEnabled,
                                    onPickLongClick = onTopPickLongClick,
                                )
                            }
                        }
                        topPicksLoading -> {
                            item(key = "home_top_picks_skeleton") {
                                TopPicksSkeleton(reduceAnimation = reduceAnimation)
                            }
                        }
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
                            ) { _, shelf ->
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
