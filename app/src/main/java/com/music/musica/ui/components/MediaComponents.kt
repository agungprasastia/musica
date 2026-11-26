package com.music.musica.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.formatDuration
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.theme.IosGreenDark
import com.music.musica.ui.theme.IosGreenLight
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    size: Dp,
    cornerRadius: Dp = 10.dp,
    circle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(cornerRadius)
    val context = coil3.compose.LocalPlatformContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val requestPx = when {
        size <= 56.dp -> 160
        size <= 160.dp -> 320
        else -> 544
    }
    val decodePx = with(density) { size.roundToPx().coerceAtLeast(64) }
    val model = androidx.compose.runtime.remember(url, requestPx, decodePx) {
        if (url.isNullOrBlank()) {
            null
        } else {
            coil3.request.ImageRequest.Builder(context)
                .data(upsizeArtworkUrl(url, requestPx))
                .size(decodePx)
                .build()
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .thumbnailBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
            modifier = Modifier.size(size * 0.38f),
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isActive: Boolean = false,
    isPlaying: Boolean = true,
    isDownloaded: Boolean = false,
    indexNumber: Int? = null,
    showDivider: Boolean = false,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    SwipeToQueueBox(
        enabled = onPlayNext != null || onAddToQueue != null,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onMoreClick?.let { menu ->
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu()
                            }
                        },
                    )
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (indexNumber != null) {
                    Box(
                        modifier = Modifier.width(30.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (isActive) {
                            AnimatedPlayingBars(
                                isPlaying = isPlaying,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(
                                text = indexNumber.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        ArtworkImage(
                            url = song.thumbnailUrl,
                            contentDescription = song.title,
                            size = 52.dp,
                            cornerRadius = 8.dp,
                        )
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.34f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedPlayingBars(
                                    isPlaying = isPlaying,
                                    color = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    ExplicitSongTitle(
                        title = song.title,
                        isExplicit = song.isExplicit,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDownloaded) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        val subtitle = buildString {
                            append(song.artistText)
                            if (song.durationMs > 0L) {
                                append(" · ")
                                append(formatDuration(song.durationMs))
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (onMoreClick != null) {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = if (indexNumber != null) 46.dp else ROW_DIVIDER_INSET, end = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/**
 * Compact 48dp track row used inside 4-row snapping paged carousels (Quick Picks, Recents, Artist Top Songs).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTrackRow(
    song: Song,
    isActive: Boolean = false,
    isPlaying: Boolean = true,
    isDownloaded: Boolean = false,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onPlayNext: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onMoreClick?.let { menu ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    }
                },
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(contentAlignment = Alignment.Center) {
                ArtworkImage(
                    url = song.thumbnailUrl,
                    contentDescription = song.title,
                    size = 48.dp,
                    cornerRadius = 7.dp,
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.Black.copy(alpha = 0.34f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedPlayingBars(
                            isPlaying = isPlaying,
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ExplicitSongTitle(
                    title = song.title,
                    isExplicit = song.isExplicit,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = song.artistText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onMoreClick != null) {
                IconButton(
                    onClick = onMoreClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
}

/**
 * 4-row-per-column horizontally snapping track carousel for
 * Quick Picks, Recently Played, and Artist Top Songs carousels.
 * Uses [HorizontalPager] with a light 16% positional threshold and [SnapPosition.Start]
 * so even short, relaxed thumb drags advance cleanly without snapping back.
 */
@Composable
fun PagedTrackCarousel(
    tracks: List<Song>,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    onTrackClick: (Song, Int) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: ((Song) -> Unit)? = null,
    onAddToQueue: ((Song) -> Unit)? = null,
    rowsPerPage: Int = 4,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    val pages = remember(tracks, rowsPerPage) { tracks.chunked(rowsPerPage) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(2),
        snapPositionalThreshold = 0.16f,
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val pageWidth = min(maxWidth * 0.87f, 396.dp)
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 14.dp,
            snapPosition = SnapPosition.Start,
            beyondViewportPageCount = 1,
            flingBehavior = fling,
            key = { pIdx -> "page_${pIdx}_${pages[pIdx].first().videoId}" },
            modifier = Modifier.fillMaxWidth(),
        ) { pageIndex ->
            val pageTracks = pages[pageIndex]
            Column(modifier = Modifier.fillMaxWidth()) {
                pageTracks.forEachIndexed { rowIdx, song ->
                    val flatIdx = pageIndex * rowsPerPage + rowIdx
                    CompactTrackRow(
                        song = song,
                        isActive = song.videoId == activeVideoId,
                        isDownloaded = song.videoId in downloadedIds,
                        onClick = { onTrackClick(song, flatIdx) },
                        onMoreClick = { onSongMore(song) },
                        onPlayNext = onPlayNext?.let { { it(song) } },
                        onAddToQueue = onAddToQueue?.let { { it(song) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    item: ShelfItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    cardWidth: Dp = 150.dp,
    modifier: Modifier = Modifier,
) {
    val isArtist = item is ShelfItem.Artist
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { menu ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    }
                },
            ),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ArtworkImage(
                url = item.thumbnailUrl,
                contentDescription = item.title,
                size = cardWidth,
                cornerRadius = 12.dp,
                circle = isArtist,
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(cardWidth)
                        .clip(if (isArtist) CircleShape else RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.34f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedPlayingBars(color = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!item.subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 16:9 widescreen card (248dp) used for music video shelves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoShelfCard(
    item: ShelfItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .width(248.dp)
            .clip(shape)
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
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .thumbnailBorder(shape),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = upsizeArtworkUrl(item.thumbnailUrl, 640),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedPlayingBars(color = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!item.subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ShelfSection(
    shelf: HomeShelf,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    onItemClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onPlayNext: ((Song) -> Unit)? = null,
    onAddToQueue: ((Song) -> Unit)? = null,
    onSeeAllClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (shelf.title.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (onSeeAllClick != null) mod.clickable(onClick = onSeeAllClick) else mod
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = shelf.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onSeeAllClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.action_show_all),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val allTracks = shelf.items.isNotEmpty() && shelf.items.all { it is ShelfItem.Track }
        val isVideoShelf = shelf.title.contains("video", ignoreCase = true)
        val allMoodButtons = shelf.items.isNotEmpty() &&
            shelf.items.all { it is ShelfItem.Playlist && it.thumbnailUrl.isNullOrBlank() }

        when {
            allTracks && isVideoShelf -> {
                val pagerState = rememberPagerState(pageCount = { shelf.items.size })
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fixed(248.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 14.dp,
                    snapPosition = SnapPosition.Start,
                    beyondViewportPageCount = 1,
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        pagerSnapDistance = PagerSnapDistance.atMost(2),
                        snapPositionalThreshold = 0.18f,
                    ),
                    key = { idx -> "vid_${idx}_${shelf.items[idx].id}" },
                    modifier = Modifier.fillMaxWidth(),
                ) { idx ->
                    val track = shelf.items[idx] as ShelfItem.Track
                    VideoShelfCard(
                        item = track,
                        isActive = track.song.videoId == activeVideoId,
                        onClick = { onItemClick(track) },
                        onLongClick = { onSongMore(track.song) },
                    )
                }
            }

            allTracks && shelf.items.size >= 5 -> {
                val tracks = shelf.items.filterIsInstance<ShelfItem.Track>().map { it.song }
                PagedTrackCarousel(
                    tracks = tracks,
                    activeVideoId = activeVideoId,
                    downloadedIds = downloadedIds,
                    onTrackClick = { song, _ -> onItemClick(ShelfItem.Track(song)) },
                    onSongMore = onSongMore,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                )
            }

            allTracks -> {
                shelf.items.filterIsInstance<ShelfItem.Track>().forEachIndexed { idx, track ->
                    SongRow(
                        song = track.song,
                        isActive = track.song.videoId == activeVideoId,
                        isDownloaded = track.song.videoId in downloadedIds,
                        showDivider = idx < shelf.items.lastIndex,
                        onClick = { onItemClick(track) },
                        onMoreClick = { onSongMore(track.song) },
                        onPlayNext = onPlayNext?.let { { it(track.song) } },
                        onAddToQueue = onAddToQueue?.let { { it(track.song) } },
                    )
                }
            }

            allMoodButtons -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(shelf.items, key = { idx, item -> "chip_${idx}_${item.id}" }) { idx, item ->
                        val playlist = item as ShelfItem.Playlist
                        MoodNavChip(
                            title = playlist.title,
                            accentColor = moodChipAccent(playlist.accentColorArgb, idx),
                            onClick = { onItemClick(playlist) },
                        )
                    }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    flingBehavior = rememberSnapFlingBehavior(
                        lazyListState = listState,
                        snapPosition = SnapPosition.Start,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(shelf.items, key = { idx, item -> "${idx}_${item.id}_${item::class.simpleName}" }) { _, item ->
                        MediaCard(
                            item = item,
                            isActive = activeVideoId != null && (item as? ShelfItem.Track)?.song?.videoId == activeVideoId,
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
        }
    }
}

@Composable
private fun MoodNavChip(
    title: String,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .height(50.dp)
            .glassCard(shape = shape)
            .clickable(onClick = onClick)
            .padding(end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(50.dp)
                .background(accentColor),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val FallbackMoodColors = listOf(
    Color(0xFFC86452),
    Color(0xFF8E7EB8),
    Color(0xFF6AA4C4),
    Color(0xFFC99658),
    Color(0xFF72A884),
    Color(0xFFB8708A),
)

private fun moodChipAccent(argb: Long?, index: Int): Color {
    if (argb != null && argb != 0L) {
        return Color((argb or 0xFF000000L).toInt())
    }
    return FallbackMoodColors[index % FallbackMoodColors.size]
}

/**
 * iOS 26-inspired wide capsule toggle switch (58x28dp) with elevated pill thumb and haptic feedback.
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = LocalHapticFeedback.current
    val activeGreen = if (isDark) IosGreenDark else IosGreenLight
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeGreen else MaterialTheme.colorScheme.outline,
        label = "ios_switch_track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(stiffness = 480f, dampingRatio = 0.78f),
        label = "ios_switch_thumb",
    )
    Box(
        modifier = modifier
            .width(58.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(34.dp)
                .height(24.dp)
                .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.28f))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun SongActionsSheet(
    song: Song,
    isLiked: Boolean,
    isDisliked: Boolean = false,
    isDownloaded: Boolean,
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    onToggleDislike: (() -> Unit)? = null,
    onAddToPlaylist: () -> Unit,
    onToggleDownload: () -> Unit,
    onGoToArtist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    onShare: () -> Unit,
    onShowStats: () -> Unit,
) {
    val fg = glassContentColor()
    val secondaryFg = fg.copy(alpha = 0.68f)

    LiquidGlassSheet(
        hazeState = hazeState,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(url = song.thumbnailUrl, contentDescription = song.title, size = 54.dp, cornerRadius = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ExplicitSongTitle(
                        title = song.title,
                        isExplicit = song.isExplicit,
                        style = MaterialTheme.typography.titleMedium,
                        color = fg,
                        badgeTint = secondaryFg,
                    )
                    Text(
                        song.artistText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Quick action glass tiles (Like, Download, Share) across the menu top strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickActionTile(
                    icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = stringResource(if (isLiked) R.string.menu_unlike else R.string.menu_like).substringBefore(" "),
                    tint = if (isLiked) MusicaAccentRed else fg,
                    onClick = { onToggleLike(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    icon = if (isDownloaded) Icons.Rounded.DeleteOutline else Icons.Rounded.Download,
                    label = stringResource(if (isDownloaded) R.string.action_delete else R.string.library_downloads),
                    tint = fg,
                    onClick = { onToggleDownload(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    icon = Icons.Rounded.Share,
                    label = stringResource(R.string.menu_share),
                    tint = fg,
                    onClick = { onDismiss(); onShare() },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(
                color = fg.copy(alpha = 0.14f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )

            SheetActionRow(Icons.Rounded.QueuePlayNext, stringResource(R.string.menu_play_next), tint = fg) {
                onPlayNext(); onDismiss()
            }
            SheetActionRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.menu_add_to_queue), tint = fg) {
                onAddToQueue(); onDismiss()
            }
            if (onStartRadio != null) {
                SheetActionRow(Icons.Rounded.Radio, stringResource(R.string.menu_start_radio), tint = fg) {
                    onDismiss(); onStartRadio()
                }
            }
            SheetActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.menu_add_playlist), tint = fg) {
                onDismiss(); onAddToPlaylist()
            }
            if (onToggleDislike != null) {
                SheetActionRow(
                    icon = if (isDisliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                    label = stringResource(if (isDisliked) R.string.menu_undislike else R.string.menu_dislike),
                    tint = if (isDisliked) MusicaAccentRed else fg,
                ) {
                    onToggleDislike(); onDismiss()
                }
            }
            if (onGoToArtist != null) {
                SheetActionRow(Icons.Rounded.Person, stringResource(R.string.menu_go_to_artist), tint = fg) {
                    onDismiss(); onGoToArtist()
                }
            }
            if (onGoToAlbum != null) {
                SheetActionRow(Icons.Rounded.Album, stringResource(R.string.menu_go_to_album), tint = fg) {
                    onDismiss(); onGoToAlbum()
                }
            }
            SheetActionRow(Icons.Rounded.Info, stringResource(R.string.menu_stats_for_nerds), tint = fg) {
                onDismiss(); onShowStats()
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    tint: Color = glassContentColor(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .lightweightGlass(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = glassContentColor(),
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = tint)
    }
}
