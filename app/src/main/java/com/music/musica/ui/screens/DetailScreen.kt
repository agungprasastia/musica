package com.music.musica.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.musica.R
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.core.model.upsizeArtworkUrl
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.DetailSkeleton
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.LiquidGlassMenuRow
import com.music.musica.ui.components.LiquidGlassPopoverMenu
import com.music.musica.ui.components.PagedTrackCarousel
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.SongRow
import com.music.musica.ui.components.frostedGlass
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

enum class TrackSortOrder {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
}

/**
 * Musica DetailScreen supporting distinct editorial layouts:
 * - Full-bleed edge-to-edge Artist Hero with 3-circle action row, TopReleaseCard, 4-row snapping Top Songs, & expandable About
 * - Framed or Full-Bleed Album/Playlist with computed duration metadata, 3-control bar, in-page search & sort preserving track indices
 * - Clean architectural Mood/Genre Category Directory header
 * - Floating circular FrostedTopBar with collapsing sticky title on scroll
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    state: UiState<DetailPage>,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    subscribedArtistIds: Set<String> = emptySet(),
    savedCollectionIds: Set<String> = emptySet(),
    isLoadingMore: Boolean = false,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    fullBleedCoverArt: Boolean = false,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMoreTracks: () -> Unit = {},
    onPlayTracks: (List<Song>, Int, Boolean) -> Unit,
    onDownloadAll: (List<Song>, String?, String?) -> Unit,
    onToggleSubscribeArtist: (String, String, String?) -> Unit = { _, _, _ -> },
    onToggleSaveCollection: (String, BrowseType, String, String?, String?) -> Unit = { _, _, _, _, _ -> },
    onOpenArtist: (String) -> Unit = {},
    onOpenSeeAll: (String, String?) -> Unit = { _, _ -> },
    onItemClick: (ShelfItem) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onRequestOpenSortMenu: ((TrackSortOrder, (TrackSortOrder) -> Unit) -> Unit)? = null,
) {
    val page = (state as? UiState.Success)?.data
    val palette = rememberArtworkPalette(
        imageUrl = page?.thumbnailUrl,
        reduceAnimation = reduceAnimation,
    )
    val listState = rememberLazyListState()
    LaunchedEffect(page?.browseId) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }
    val showStickyTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 280 }
    }

    var searchOpen by remember(page?.browseId) { mutableStateOf(false) }
    var sortMenuOpen by remember(page?.browseId) { mutableStateOf(false) }
    val filterState = remember(page?.browseId) { TextFieldState() }
    val filterQuery = filterState.text.toString()
    var sortOrder by remember(page?.browseId) { mutableStateOf(TrackSortOrder.DEFAULT) }
    var aboutExpanded by remember(page?.browseId) { mutableStateOf(false) }
    val detailHazeState = rememberHazeState()

    LaunchedEffect(searchOpen) {
        if (searchOpen && listState.firstVisibleItemIndex > 1) {
            listState.animateScrollToItem(1)
        }
    }

    val hasContinuation = !page?.continuation.isNullOrBlank()
    val currentLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentOnLoadMore by rememberUpdatedState(onLoadMoreTracks)
    LaunchedEffect(listState, page?.browseId, hasContinuation) {
        if (!hasContinuation) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 6
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && !currentLoadingMore) currentOnLoadMore()
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(detailHazeState)
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.wash, palette.background, palette.background),
                ),
            ),
    ) {
        when (state) {
            is UiState.Loading -> {
                DetailSkeleton(reduceAnimation = reduceAnimation)
            }

            is UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorStateCard(
                        message = state.message.ifBlank { stringResource(R.string.state_error_generic) },
                        onRetry = onRetry,
                    )
                }
            }

            is UiState.Success -> {
                val detail = state.data
                val isArtist = detail.type == BrowseType.ARTIST
                val isCategoryDirectory = detail.thumbnailUrl.isNullOrBlank() && detail.tracks.isEmpty()

                val indexedTracks = remember(detail.tracks, filterQuery, sortOrder) {
                    val base = detail.tracks.mapIndexed { i, song -> (i + 1) to song }
                    val filtered = if (filterQuery.isBlank()) {
                        base
                    } else {
                        base.filter { (_, s) ->
                            s.title.contains(filterQuery, ignoreCase = true) ||
                                s.artistText.contains(filterQuery, ignoreCase = true)
                        }
                    }
                    when (sortOrder) {
                        TrackSortOrder.DEFAULT -> filtered
                        TrackSortOrder.TITLE_ASC -> filtered.sortedBy { it.second.title.lowercase() }
                        TrackSortOrder.TITLE_DESC -> filtered.sortedByDescending { it.second.title.lowercase() }
                    }
                }

                val latestRelease = remember(detail.sections) {
                    if (!isArtist) null
                    else detail.sections
                        .flatMap { it.items }
                        .firstOrNull { it is ShelfItem.Album }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding + 28.dp),
                ) {
                    // ---- Hero Header ----
                    item(key = "detail_hero") {
                        when {
                            isCategoryDirectory -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 10.dp),
                                ) {
                                    Text(
                                        text = detail.title,
                                        style = MaterialTheme.typography.displayLarge,
                                        color = palette.onBackground,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!detail.subtitle.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = detail.subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.onBackgroundVariant,
                                        )
                                    }
                                }
                            }

                            isArtist -> {
                                val isSubscribed = detail.browseId in subscribedArtistIds
                                ArtistHeroHeader(
                                    detail = detail,
                                    palette = palette,
                                    starred = isSubscribed,
                                    onToggleStar = {
                                        onToggleSubscribeArtist(detail.browseId, detail.title, detail.thumbnailUrl)
                                    },
                                    onPlay = {
                                        if (detail.tracks.isNotEmpty()) onPlayTracks(detail.tracks, 0, false)
                                    },
                                    onShuffle = {
                                        if (detail.tracks.isNotEmpty()) onPlayTracks(detail.tracks, 0, true)
                                    },
                                )
                            }

                            else -> {
                                val isSaved = detail.browseId in savedCollectionIds
                                ReleaseHeroHeader(
                                    detail = detail,
                                    palette = palette,
                                    fullBleed = fullBleedCoverArt,
                                    isSaved = isSaved,
                                    onArtistClick = detail.artist?.browseId?.let { id -> { onOpenArtist(id) } },
                                    onPlay = {
                                        if (detail.tracks.isNotEmpty()) onPlayTracks(detail.tracks, 0, false)
                                    },
                                    onShuffle = {
                                        if (detail.tracks.isNotEmpty()) onPlayTracks(detail.tracks, 0, true)
                                    },
                                    onToggleSave = {
                                        onToggleSaveCollection(
                                            detail.browseId,
                                            detail.type,
                                            detail.title,
                                            detail.subtitle,
                                            detail.thumbnailUrl,
                                        )
                                    },
                                    onDownloadAll = {
                                        if (detail.tracks.isNotEmpty()) {
                                            onDownloadAll(detail.tracks, detail.browseId, detail.title)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // In-page Liquid Glass capsule filter bar when toggled
                    if (searchOpen && !isArtist && detail.tracks.isNotEmpty()) {
                        item(key = "detail_filter_bar") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .height(44.dp)
                                    .lightweightGlass(RoundedCornerShape(percent = 50))
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = palette.onBackgroundVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (filterQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.detail_filter_tracks),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.onBackgroundVariant,
                                        )
                                    }
                                    BasicTextField(
                                        state = filterState,
                                        lineLimits = TextFieldLineLimits.SingleLine,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.onBackground),
                                        cursorBrush = SolidColor(palette.accent),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (filterQuery.isNotEmpty()) {
                                    IconButton(onClick = { filterState.clearText() }, modifier = Modifier.size(38.dp)) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.action_clear),
                                            tint = palette.onBackgroundVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Artist TopReleaseCard spotlight
                    if (isArtist && latestRelease != null) {
                        item(key = "artist_top_release") {
                            TopReleaseCard(
                                item = latestRelease,
                                palette = palette,
                                onClick = { onItemClick(latestRelease) },
                                onLongClick = onBrowseLongClick?.let { handler -> { handler(latestRelease) } },
                            )
                        }
                    }

                    // Tracks: Artist uses 4-row snapping Top Songs carousel; Album/Playlist uses numbered track list
                    if (isArtist && detail.tracks.isNotEmpty()) {
                        item(key = "artist_top_songs") {
                            Column(modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                                Text(
                                    text = stringResource(R.string.detail_top_songs),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = palette.onBackground,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                PagedTrackCarousel(
                                    tracks = detail.tracks.take(20),
                                    activeVideoId = activeVideoId,
                                    downloadedIds = downloadedIds,
                                    onTrackClick = { _, idx -> onPlayTracks(detail.tracks, idx, false) },
                                    onSongMore = onSongMore,
                                    onPlayNext = onPlayNext,
                                    onAddToQueue = onAddToQueue,
                                    rowsPerPage = 4,
                                )
                            }
                        }
                    } else if (!isArtist && indexedTracks.isNotEmpty()) {
                        itemsIndexed(
                            items = indexedTracks,
                            key = { idx, pair -> "trk_${idx}_${pair.second.videoId}" },
                        ) { listIdx, (originalNumber, song) ->
                            val playIdx = (originalNumber - 1).coerceIn(0, detail.tracks.lastIndex)
                            SongRow(
                                song = song,
                                isActive = song.videoId == activeVideoId,
                                isDownloaded = song.videoId in downloadedIds,
                                indexNumber = if (detail.type == BrowseType.ALBUM) originalNumber else null,
                                showDivider = listIdx < indexedTracks.lastIndex,
                                onClick = { onPlayTracks(detail.tracks, playIdx, false) },
                                onMoreClick = { onSongMore(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                            )
                        }
                    } else if (!isArtist && detail.tracks.isNotEmpty() && filterQuery.isNotBlank()) {
                        item(key = "detail_filter_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_filter_empty, filterQuery.trim()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.onBackgroundVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else if (!isArtist && detail.tracks.isEmpty() && detail.sections.isEmpty()) {
                        item(key = "detail_empty_tracks") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.musica_empty_state),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.onBackgroundVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    // Related shelves (Albums, Singles, Related Artists, Category Playlists)
                    if (detail.sections.isNotEmpty()) {
                        val filteredSections = if (isArtist && detail.tracks.isNotEmpty()) {
                            // Avoid repeating the top songs list that's already displayed in the 4-row carousel
                            detail.sections.filterNot { sec ->
                                sec.items.all { it is ShelfItem.Track } &&
                                    sec.items.firstOrNull()?.id == detail.tracks.firstOrNull()?.videoId
                            }
                        } else {
                            detail.sections
                        }
                        itemsIndexed(
                            items = filteredSections,
                            key = { idx, sec -> "sec_${idx}_${sec.title}" },
                        ) { _, section ->
                            ShelfSection(
                                shelf = section,
                                activeVideoId = activeVideoId,
                                downloadedIds = downloadedIds,
                                onItemClick = onItemClick,
                                onSongMore = onSongMore,
                                onBrowseLongClick = onBrowseLongClick,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onSeeAllClick = section.browseId?.let { id ->
                                    { onOpenSeeAll(id, section.params) }
                                },
                            )
                        }
                    }

                    // Bottom pagination spinner for playlists with >100 tracks
                    if (isLoadingMore || !detail.continuation.isNullOrBlank()) {
                        item(key = "detail_load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = palette.accent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }

                    // Expandable About / Bio Liquid Glass card
                    if (!detail.description.isNullOrBlank()) {
                        item(key = "detail_about") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                    .glassCard(RoundedCornerShape(20.dp))
                                    .clickable { aboutExpanded = !aboutExpanded }
                                    .padding(16.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_about),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = palette.onBackground,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = detail.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onBackgroundVariant,
                                    maxLines = if (aboutExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Soft Top Fade Scrim when scrolled so floating bar & status bar never collide with tracks ----
        AnimatedVisibility(
            visible = showStickyTitle,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0.00f to palette.background,
                            0.74f to palette.background.copy(alpha = 0.94f),
                            1.00f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .height(84.dp),
            )
        }

        // ---- Floating Circular Liquid Glass Top Bar with Collapsing Sticky Title ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FrostedCircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                hazeState = detailHazeState,
                frostedGlassEnabled = frostedGlassEnabled,
                liveBlur = true,
                onClick = onBack,
            )

            AnimatedVisibility(
                visible = showStickyTitle && !page?.title.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            ) {
                val pillShape = RoundedCornerShape(percent = 50)
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(palette.background.copy(alpha = 0.72f))
                        .frostedGlass(
                            hazeState = detailHazeState,
                            shape = pillShape,
                            enabled = frostedGlassEnabled,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = page?.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (page != null && page.type != BrowseType.ARTIST && page.tracks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrostedCircleButton(
                        icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.tab_search),
                        hazeState = detailHazeState,
                        frostedGlassEnabled = frostedGlassEnabled,
                        liveBlur = true,
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) filterState.clearText()
                        },
                    )
                    FrostedCircleButton(
                        icon = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.detail_sort),
                        hazeState = detailHazeState,
                        frostedGlassEnabled = frostedGlassEnabled,
                        liveBlur = true,
                        onClick = {
                            if (onRequestOpenSortMenu != null) {
                                onRequestOpenSortMenu(sortOrder) { chosen -> sortOrder = chosen }
                            } else {
                                sortMenuOpen = true
                            }
                        },
                    )
                }
            } else {
                Spacer(Modifier.size(44.dp))
            }
        }
    }

    if (sortMenuOpen) {
        LiquidGlassPopoverMenu(
            hazeState = detailHazeState,
            onDismiss = { sortMenuOpen = false },
        ) {
            LiquidGlassMenuRow(
                label = stringResource(R.string.detail_sort_default),
                selected = sortOrder == TrackSortOrder.DEFAULT,
                onClick = {
                    sortOrder = TrackSortOrder.DEFAULT
                    sortMenuOpen = false
                },
            )
            LiquidGlassMenuRow(
                label = stringResource(R.string.detail_sort_title_asc),
                selected = sortOrder == TrackSortOrder.TITLE_ASC,
                onClick = {
                    sortOrder = TrackSortOrder.TITLE_ASC
                    sortMenuOpen = false
                },
            )
            LiquidGlassMenuRow(
                label = stringResource(R.string.detail_sort_title_desc),
                selected = sortOrder == TrackSortOrder.TITLE_DESC,
                onClick = {
                    sortOrder = TrackSortOrder.TITLE_DESC
                    sortMenuOpen = false
                },
            )
        }
    }
    }
}

@Composable
private fun ArtistHeroHeader(
    detail: DetailPage,
    palette: com.music.musica.ui.theme.ArtworkPalette,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    var useRawFallback by remember(detail.thumbnailUrl) { mutableStateOf(false) }
    val coverUrl = remember(detail.thumbnailUrl, useRawFallback) {
        if (useRawFallback) detail.thumbnailUrl else upsizeArtworkUrl(detail.thumbnailUrl, 900)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.95f),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (!useRawFallback && coverUrl != detail.thumbnailUrl) {
                            useRawFallback = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.00f to Color.Black,
                                    0.54f to Color.Black,
                                    0.80f to Color.Black.copy(alpha = 0.28f),
                                    0.94f to Color.Transparent,
                                    1.00f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }

            // Protective top status-bar & floating-button scrim over bright full-bleed artist photography
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.46f),
                            0.58f to Color.Black.copy(alpha = 0.16f),
                            1.00f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .height(74.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            0.84f to palette.background.copy(alpha = 0.72f),
                            1.00f to palette.background,
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.displayLarge,
                    color = palette.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detail.subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackgroundVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 3-Circle Apple Music Artist Action Row: Shuffle | Large Play | Subscribe Star
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (detail.tracks.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .lightweightGlass(CircleShape)
                        .clickable(onClick = onShuffle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.action_shuffle),
                        tint = palette.onBackground,
                        modifier = Modifier.size(22.dp),
                    )
                }

                val playOnBg = if (palette.onBackground.luminance() > 0.5f) Color.Black else Color.White
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(14.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f))
                        .clip(CircleShape)
                        .background(palette.onBackground)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        tint = playOnBg,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .lightweightGlass(CircleShape)
                    .clickable(onClick = onToggleStar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = stringResource(
                        if (starred) R.string.menu_subscribed else R.string.menu_subscribe,
                    ),
                    tint = if (starred) palette.accent else palette.onBackground,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopReleaseCard(
    item: ShelfItem,
    palette: com.music.musica.ui.theme.ArtworkPalette,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glassCard(RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            url = item.thumbnailUrl,
            contentDescription = item.title,
            size = 90.dp,
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.detail_latest_release),
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReleaseHeroHeader(
    detail: DetailPage,
    palette: com.music.musica.ui.theme.ArtworkPalette,
    fullBleed: Boolean,
    isSaved: Boolean = false,
    onArtistClick: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSave: () -> Unit = {},
    onDownloadAll: () -> Unit,
) {
    val totalMinutes = remember(detail.tracks) {
        val totalMs = detail.tracks.sumOf { it.durationMs }
        (totalMs / 60_000L).toInt()
    }
    val cleanedSubtitle = remember(detail.subtitle, detail.artist, onArtistClick) {
        val raw = detail.subtitle.orEmpty().trim()
        val artistName = if (detail.artist != null && onArtistClick != null) detail.artist.name.trim() else ""
        if (raw.isBlank() || artistName.isBlank()) {
            raw
        } else {
            val parts = raw.split("•", "·")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(artistName, ignoreCase = true) }
            parts.joinToString(" • ")
        }
    }
    val metaLine = buildString {
        if (cleanedSubtitle.isNotBlank()) append(cleanedSubtitle)
        val alreadyHasCounts = cleanedSubtitle.contains("song", ignoreCase = true) ||
            cleanedSubtitle.contains("lagu", ignoreCase = true) ||
            cleanedSubtitle.contains("min", ignoreCase = true) ||
            cleanedSubtitle.contains("mnt", ignoreCase = true)
        if (!alreadyHasCounts && detail.tracks.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(pluralStringResource(R.plurals.library_songs_count, detail.tracks.size, detail.tracks.size))
            if (totalMinutes > 0) {
                append(" · ")
                append(stringResource(R.string.detail_duration_minutes, totalMinutes))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (fullBleed && !detail.thumbnailUrl.isNullOrBlank()) {
            var useRawFallback by remember(detail.thumbnailUrl) { mutableStateOf(false) }
            val fullBleedUrl = remember(detail.thumbnailUrl, useRawFallback) {
                if (useRawFallback) detail.thumbnailUrl else upsizeArtworkUrl(detail.thumbnailUrl, 900)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                AsyncImage(
                    model = fullBleedUrl,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (!useRawFallback && fullBleedUrl != detail.thumbnailUrl) {
                            useRawFallback = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.00f to Color.Black,
                                    0.58f to Color.Black,
                                    1.00f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Black.copy(alpha = 0.46f),
                                0.58f to Color.Black.copy(alpha = 0.16f),
                                1.00f to Color.Transparent,
                            ),
                        )
                        .statusBarsPadding()
                        .height(74.dp),
                )
            }
        } else {
            Spacer(Modifier.statusBarsPadding().height(56.dp))
            ArtworkImage(
                url = detail.thumbnailUrl,
                contentDescription = detail.title,
                size = 236.dp,
                cornerRadius = 14.dp,
                modifier = Modifier.shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Color.Black.copy(alpha = 0.42f),
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.artist != null && onArtistClick != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail.artist.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onArtistClick)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (metaLine.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (detail.tracks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                val playOnBg = if (palette.onBackground.luminance() > 0.5f) Color.Black else Color.White
                // 4-control bar: Shuffle circle | Solid Primary Play pill | Save circle | Download circle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .lightweightGlass(CircleShape)
                            .clickable(onClick = onShuffle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.action_shuffle),
                            tint = palette.onBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Button(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.onBackground,
                            contentColor = playOnBg,
                        ),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_play),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .lightweightGlass(CircleShape)
                            .clickable(onClick = onToggleSave),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = stringResource(
                                if (isSaved) R.string.menu_remove_from_library else R.string.menu_save_to_library,
                            ),
                            tint = if (isSaved) palette.accent else palette.onBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .lightweightGlass(CircleShape)
                            .clickable(onClick = onDownloadAll),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = stringResource(R.string.menu_download),
                            tint = palette.onBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
