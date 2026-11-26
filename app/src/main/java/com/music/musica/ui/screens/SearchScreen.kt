package com.music.musica.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.SearchFilter
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UiState
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.ROW_DIVIDER_INSET
import com.music.musica.ui.components.SongListSkeleton
import com.music.musica.ui.components.SongRow
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import dev.chrisbanes.haze.HazeState

/**
 * Musica SearchScreen featuring:
 * - Bold 34sp displayLarge title + crisp 46dp iOS SearchField
 * - Spring-animated PillSegmentedControl filter bar (no red M3 FilterChips)
 * - Clickable NorthWest query-fill arrows on suggestions
 * - Promoted TopResultCard (72dp art + Play & Queue buttons) + grouped ALL sections with inset dividers
 * - Shimmer SongListSkeleton (circular for Artists) + SwipeToQueueBox on all tracks
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    query: String,
    filter: SearchFilter,
    suggestions: List<String>,
    recentQueries: List<String>,
    results: UiState<List<ShelfItem>>?,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String) -> Unit,
    onLoadMore: () -> Unit = {},
    onFilterSelect: (SearchFilter) -> Unit,
    onClearRecent: () -> Unit,
    onDeleteRecent: (String) -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState, hasMore, isLoadingMore) {
        if (!hasMore || isLoadingMore) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 5
        }.collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }
    val filterOptions = listOf(
        stringResource(R.string.search_filter_all),
        stringResource(R.string.search_filter_songs),
        stringResource(R.string.search_filter_albums),
        stringResource(R.string.search_filter_artists),
        stringResource(R.string.search_filter_playlists),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.tab_search),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            )

            // Liquid Glass Capsule SearchField
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .lightweightGlass(RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.tab_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            if (query.isNotBlank()) {
                                keyboard?.hide()
                                onSubmitQuery(query)
                            }
                        },
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                onSubmitQuery(query)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (results != null) {
                PillSegmentedControl(
                    options = filterOptions,
                    selectedIndex = SearchFilter.entries.indexOf(filter).coerceAtLeast(0),
                    onSelect = { idx -> onFilterSelect(SearchFilter.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 22.dp),
            ) {
                if (results == null) {
                    if (query.isNotBlank() && suggestions.isNotEmpty()) {
                        itemsIndexed(suggestions, key = { idx, s -> "sug_${idx}_$s" }) { idx, suggestion ->
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            keyboard?.hide()
                                            onSubmitQuery(suggestion)
                                        }
                                        .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = suggestion,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // Clickable NorthWest arrow fills query into search bar without dismissing keyboard
                                    IconButton(onClick = { onQueryChange(suggestion) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.NorthWest,
                                            contentDescription = suggestion,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                if (idx < suggestions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 50.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    } else if (recentQueries.isNotEmpty()) {
                        item(key = "recent_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.search_recent),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onClearRecent) {
                                    Text(
                                        text = stringResource(R.string.search_clear_history),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        itemsIndexed(recentQueries, key = { idx, r -> "hist_${idx}_$r" }) { idx, recent ->
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            keyboard?.hide()
                                            onSubmitQuery(recent)
                                        }
                                        .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        text = recent,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { onDeleteRecent(recent) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                if (idx < recentQueries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 50.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    when (results) {
                        is UiState.Loading -> {
                            item(key = "search_skeleton") {
                                SongListSkeleton(
                                    count = 9,
                                    circular = filter == SearchFilter.ARTISTS,
                                    reduceAnimation = reduceAnimation,
                                )
                            }
                        }

                        is UiState.Error -> {
                            item(key = "search_err") {
                                ErrorStateCard(
                                    message = results.message.ifBlank { stringResource(R.string.state_error_generic) },
                                    onRetry = { onSubmitQuery(query) },
                                )
                            }
                        }

                        is UiState.Success -> {
                            if (results.data.isEmpty()) {
                                item(key = "search_empty") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_no_results, query),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else if (filter == SearchFilter.ALL) {
                                val topResult = results.data.first()
                                val rest = results.data.drop(1)
                                val tracks = rest.filterIsInstance<ShelfItem.Track>()
                                val artists = rest.filterIsInstance<ShelfItem.Artist>()
                                val albums = rest.filterIsInstance<ShelfItem.Album>()
                                val playlists = rest.filterIsInstance<ShelfItem.Playlist>()

                                item(key = "top_result") {
                                    TopResultCard(
                                        item = topResult,
                                        onClick = { onItemClick(topResult) },
                                        onLongClick = when (topResult) {
                                            is ShelfItem.Track -> {
                                                { onSongMore(topResult.song) }
                                            }
                                            else -> onBrowseLongClick?.let { handler -> { handler(topResult) } }
                                        },
                                        onQueue = (topResult as? ShelfItem.Track)?.let { t -> { onAddToQueue(t.song) } },
                                    )
                                }

                                if (tracks.isNotEmpty()) {
                                    item(key = "sec_songs_hdr") {
                                        SearchSectionHeader(stringResource(R.string.search_filter_songs))
                                    }
                                    itemsIndexed(tracks, key = { i, t -> "tr_${i}_${t.id}" }) { idx, track ->
                                        SongRow(
                                            song = track.song,
                                            isActive = track.song.videoId == activeVideoId,
                                            isDownloaded = track.song.videoId in downloadedIds,
                                            showDivider = idx < tracks.lastIndex,
                                            onClick = { onItemClick(track) },
                                            onMoreClick = { onSongMore(track.song) },
                                            onPlayNext = { onPlayNext(track.song) },
                                            onAddToQueue = { onAddToQueue(track.song) },
                                        )
                                    }
                                }

                                if (artists.isNotEmpty()) {
                                    item(key = "sec_artists_hdr") {
                                        SearchSectionHeader(stringResource(R.string.search_filter_artists))
                                    }
                                    itemsIndexed(artists, key = { i, a -> "ar_${i}_${a.id}" }) { idx, artist ->
                                        SearchBrowseRow(
                                            item = artist,
                                            showDivider = idx < artists.lastIndex,
                                            onClick = { onItemClick(artist) },
                                            onLongClick = onBrowseLongClick?.let { handler -> { handler(artist) } },
                                        )
                                    }
                                }

                                if (albums.isNotEmpty()) {
                                    item(key = "sec_albums_hdr") {
                                        SearchSectionHeader(stringResource(R.string.search_filter_albums))
                                    }
                                    itemsIndexed(albums, key = { i, al -> "al_${i}_${al.id}" }) { idx, album ->
                                        SearchBrowseRow(
                                            item = album,
                                            showDivider = idx < albums.lastIndex,
                                            onClick = { onItemClick(album) },
                                            onLongClick = onBrowseLongClick?.let { handler -> { handler(album) } },
                                        )
                                    }
                                }

                                if (playlists.isNotEmpty()) {
                                    item(key = "sec_playlists_hdr") {
                                        SearchSectionHeader(stringResource(R.string.search_filter_playlists))
                                    }
                                    itemsIndexed(playlists, key = { i, pl -> "pl_${i}_${pl.id}" }) { idx, pl ->
                                        SearchBrowseRow(
                                            item = pl,
                                            showDivider = idx < playlists.lastIndex,
                                            onClick = { onItemClick(pl) },
                                            onLongClick = onBrowseLongClick?.let { handler -> { handler(pl) } },
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = results.data,
                                    key = { idx, it -> "res_${idx}_${it.id}_${it::class.simpleName}" },
                                ) { idx, item ->
                                    when (item) {
                                        is ShelfItem.Track -> SongRow(
                                            song = item.song,
                                            isActive = item.song.videoId == activeVideoId,
                                            isDownloaded = item.song.videoId in downloadedIds,
                                            showDivider = idx < results.data.lastIndex,
                                            onClick = { onItemClick(item) },
                                            onMoreClick = { onSongMore(item.song) },
                                            onPlayNext = { onPlayNext(item.song) },
                                            onAddToQueue = { onAddToQueue(item.song) },
                                        )

                                        else -> SearchBrowseRow(
                                            item = item,
                                            showDivider = idx < results.data.lastIndex,
                                            onClick = { onItemClick(item) },
                                            onLongClick = onBrowseLongClick?.let { handler -> { handler(item) } },
                                        )
                                    }
                                }
                            }

                            if (hasMore || isLoadingMore) {
                                item(key = "search_load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 18.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onQueue: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glassCard(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { menu ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    }
                },
            )
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.search_top_result),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(
                url = item.thumbnailUrl,
                contentDescription = item.title,
                size = 72.dp,
                cornerRadius = 12.dp,
                circle = item is ShelfItem.Artist,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.subtitle.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_play), style = MaterialTheme.typography.labelLarge)
            }
            if (onQueue != null) {
                val fg = glassContentColor()
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .lightweightGlass(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onQueue)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.menu_add_to_queue),
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchBrowseRow(
    item: ShelfItem,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick?.let { menu ->
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu()
                        }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                url = item.thumbnailUrl,
                contentDescription = item.title,
                size = 54.dp,
                cornerRadius = 10.dp,
                circle = item is ShelfItem.Artist,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = ROW_DIVIDER_INSET, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            )
        }
    }
}
