package com.music.musica.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UserPlaylist
import com.music.musica.data.repository.ReplayPassSection
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.ui.components.AnimatedSignInBanner
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LibraryReplayPassCarousel
import com.music.musica.ui.components.LiquidGlassDialog
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.SongRow
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Musica LibraryScreen featuring:
 * - Bold 34sp displayLarge heading + floating circular Frosted '+' button
 * - Horizontal snapping 4-card Listening Pass Carousel (ISO 1.586:1 ratio, artwork-sampled mesh, embossed typography)
 * - Clean 54dp iOS LibraryLinkList with leading icons, counts, chevrons & inset dividers
 * - Local playlists with long-press Pin/Unpin + New Playlist card + Pull-to-Refresh + AnimatedSignInBanner
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    likedSongs: List<Song>,
    playlists: List<UserPlaylist>,
    remoteLibraryShelves: List<HomeShelf>,
    replaySummary: ReplaySummary,
    listenerName: String? = null,
    memberSinceMs: Long? = null,
    downloadedCount: Int,
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
    onPlayLiked: (Int) -> Unit,
    onOpenLikedSongs: () -> Unit = { if (likedSongs.isNotEmpty()) onPlayLiked(0) },
    onOpenPlaylist: (UserPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRequestCreatePlaylist: (() -> Unit)? = null,
    onTogglePinPlaylist: (String) -> Unit = {},
    onPlaylistLongClick: ((UserPlaylist) -> Unit)? = null,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReplay: (ReplayPassSection) -> Unit,
    onOpenLogin: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onBrowseLongClick: ((ShelfItem) -> Unit)? = null,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val screenHazeState = rememberHazeState()
    fun openCreatePlaylist() {
        if (onRequestCreatePlaylist != null) onRequestCreatePlaylist() else showCreateDialog = true
    }

    val orderedPlaylists = remember(playlists) {
        playlists.sortedByDescending { it.isPinned }
    }
    val dedupedRemoteShelves = remember(remoteLibraryShelves, orderedPlaylists) {
        val syncedPlaylistIds = orderedPlaylists.mapTo(mutableSetOf()) { it.playlistId.removePrefix("VL") }
        remoteLibraryShelves.mapNotNull { shelf ->
            val filtered = shelf.items.filterNot { item ->
                item is ShelfItem.Playlist && item.browseId.removePrefix("VL") in syncedPlaylistIds
            }
            if (filtered.isEmpty()) null else shelf.copy(items = filtered)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().hazeSource(screenHazeState),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
            ) {
                item(key = "lib_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.library_title),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        FrostedCircleButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.library_new_playlist),
                            hazeState = hazeState,
                            frostedGlassEnabled = frostedGlassEnabled,
                            onClick = { openCreatePlaylist() },
                        )
                    }
                }

                // Signature ISO 1.586:1 Horizontal Snapping Listening Pass Carousel
                item(key = "lib_replay_carousel") {
                    LibraryReplayPassCarousel(
                        summary = replaySummary,
                        listenerName = listenerName,
                        memberSinceMs = memberSinceMs,
                        reduceAnimation = reduceAnimation,
                        onOpenReplay = onOpenReplay,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                // Clean iOS 54dp Liquid Glass Link List with inset dividers
                item(key = "lib_links") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .glassCard(RoundedCornerShape(18.dp)),
                    ) {
                        LibraryLinkRow(
                            icon = Icons.Rounded.DownloadDone,
                            title = stringResource(R.string.library_downloads),
                            trailing = pluralStringResource(R.plurals.library_songs_count, downloadedCount, downloadedCount),
                            onClick = onOpenDownloads,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        )
                        LibraryLinkRow(
                            icon = Icons.Rounded.History,
                            title = stringResource(R.string.library_history),
                            trailing = "",
                            onClick = onOpenHistory,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        )
                        LibraryLinkRow(
                            icon = Icons.Rounded.Favorite,
                            iconTint = MusicaAccentRed,
                            title = stringResource(R.string.library_liked_songs),
                            trailing = pluralStringResource(R.plurals.library_songs_count, likedSongs.size, likedSongs.size),
                            onClick = onOpenLikedSongs,
                        )
                    }
                }

                if (!isSignedIn) {
                    item(key = "lib_signin_banner") {
                        Spacer(Modifier.height(16.dp))
                        AnimatedSignInBanner(
                            title = stringResource(R.string.library_connect_title),
                            subtitle = stringResource(R.string.library_connect_subtitle),
                            onSignIn = onOpenLogin,
                            reduceAnimation = reduceAnimation,
                        )
                    }
                }

                // Local Playlists with Pin/Unpin on long-press
                item(key = "playlists_header") {
                    Text(
                        text = stringResource(R.string.library_playlists),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
                    )
                }

                item(key = "playlists_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "new_playlist_card") {
                            Column(
                                modifier = Modifier
                                    .width(136.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { openCreatePlaylist() },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(136.dp)
                                        .glassCard(RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = stringResource(R.string.library_new_playlist),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.library_new_playlist),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        items(orderedPlaylists, key = { "pl_${it.playlistId}" }) { playlist ->
                            val isPinned = playlist.isPinned
                            Column(
                                modifier = Modifier
                                    .width(136.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onOpenPlaylist(playlist) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (onPlaylistLongClick != null) {
                                                onPlaylistLongClick(playlist)
                                            } else {
                                                onTogglePinPlaylist(playlist.playlistId)
                                            }
                                        },
                                    ),
                            ) {
                                ArtworkImage(
                                    url = playlist.thumbnailUrl,
                                    contentDescription = playlist.title,
                                    size = 136.dp,
                                    cornerRadius = 12.dp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isPinned) {
                                        Icon(
                                            imageVector = Icons.Rounded.PushPin,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = pluralStringResource(R.plurals.library_songs_count, playlist.songCount, playlist.songCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // Remote YouTube Music Library Shelves (when signed in, deduplicated against Playlists row)
                if (dedupedRemoteShelves.isNotEmpty()) {
                    itemsIndexed(
                        items = dedupedRemoteShelves,
                        key = { idx, shelf -> "remote_lib_${idx}_${shelf.title}" },
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
                        )
                    }
                }

                // Liked Songs Preview List
                if (likedSongs.isNotEmpty()) {
                    item(key = "liked_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.library_liked_songs),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (likedSongs.size > 25) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.library_see_all),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onOpenLikedSongs)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(likedSongs.take(25), key = { idx, s -> "liked_${idx}_${s.videoId}" }) { idx, song ->
                        SongRow(
                            song = song,
                            isActive = song.videoId == activeVideoId,
                            isDownloaded = song.videoId in downloadedIds,
                            showDivider = idx < likedSongs.take(25).lastIndex,
                            onClick = { onPlayLiked(idx) },
                            onMoreClick = { onSongMore(song) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                        )
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

    if (showCreateDialog) {
        CreatePlaylistDialog(
            hazeState = screenHazeState,
            onCreate = { name ->
                showCreateDialog = false
                onCreatePlaylist(name)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    }
}

@Composable
private fun LibraryLinkRow(
    icon: ImageVector,
    title: String,
    trailing: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    hazeState: HazeState? = null,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val nameState = rememberTextFieldState()
    val name = nameState.text.toString()
    val fg = glassContentColor()
    LiquidGlassDialog(
        hazeState = hazeState,
        onDismiss = onDismiss,
        title = stringResource(R.string.library_new_playlist),
        actions = {
            GlassDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
            GlassDialogButton(
                text = stringResource(R.string.action_create),
                primary = true,
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .lightweightGlass(RoundedCornerShape(percent = 50))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (name.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_playlist_name_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = fg.copy(alpha = 0.54f),
                    maxLines = 1,
                )
            }
            BasicTextField(
                state = nameState,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = fg),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun PlaylistPickerSheet(
    playlists: List<UserPlaylist>,
    hazeState: HazeState? = null,
    onSelectPlaylist: (UserPlaylist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    val fg = glassContentColor()

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_add_to_playlist),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GlassDialogButton(
                    text = stringResource(R.string.library_new_playlist),
                    primary = true,
                    onClick = { showCreate = true },
                )
            }
            playlists.forEach { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectPlaylist(playlist)
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = fg.copy(alpha = 0.72f))
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val countText = pluralStringResource(R.plurals.library_songs_count, playlist.songCount, playlist.songCount)
                        val meta = if (playlist.isRemoteYtm) {
                            "${stringResource(R.string.library_ytm_playlist_badge)} · $countText"
                        } else {
                            countText
                        }
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            hazeState = hazeState,
            onCreate = { title ->
                showCreate = false
                onCreateAndAdd(title)
                onDismiss()
            },
            onDismiss = { showCreate = false },
        )
    }
}
