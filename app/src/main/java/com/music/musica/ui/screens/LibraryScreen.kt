package com.music.musica.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.R
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.UserPlaylist
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.ui.components.AnimatedSignInBanner
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LiquidGlassDialog
import com.music.musica.ui.components.LiquidGlassSheet
import com.music.musica.ui.components.MeshGradientBackground
import com.music.musica.ui.components.ShelfSection
import com.music.musica.ui.components.SongRow
import com.music.musica.ui.components.TopFadeBlur
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.MusicaAccentRed
import com.music.musica.ui.theme.OnboardingMeshColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Musica LibraryScreen featuring:
 * - Bold 34sp displayLarge heading + floating circular Frosted '+' button
 * - Horizontal ReplayCreditCard (300dp, 1.586:1 ISO credit-card proportion) with static mesh art
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
    onOpenPlaylist: (UserPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onTogglePinPlaylist: (String) -> Unit = {},
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenLogin: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val screenHazeState = rememberHazeState()

    val orderedPlaylists = remember(playlists) {
        playlists.sortedByDescending { it.isPinned }
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
                            onClick = { showCreateDialog = true },
                        )
                    }
                }

                // Signature ISO Credit-Card Ratio Replay Carousel
                item(key = "lib_replay_carousel") {
                    val rowState = rememberLazyListState()
                    LazyRow(
                        state = rowState,
                        flingBehavior = rememberSnapFlingBehavior(rowState),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(bottom = 18.dp),
                    ) {
                        item(key = "replay_card_main") {
                            ReplayCreditCard(
                                replaySummary = replaySummary,
                                reduceAnimation = reduceAnimation,
                                onClick = onOpenReplay,
                            )
                        }
                    }
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
                            trailing = stringResource(R.string.library_songs_count, downloadedCount),
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
                            trailing = stringResource(R.string.library_songs_count, likedSongs.size),
                            onClick = { if (likedSongs.isNotEmpty()) onPlayLiked(0) },
                        )
                    }
                }

                if (!isSignedIn) {
                    item(key = "lib_signin_banner") {
                        Spacer(Modifier.height(16.dp))
                        AnimatedSignInBanner(
                            title = stringResource(R.string.onboarding_connect_title),
                            subtitle = stringResource(R.string.onboarding_connect_subtitle),
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
                                    .clickable { showCreateDialog = true },
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
                                            onTogglePinPlaylist(playlist.playlistId)
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
                                    text = stringResource(R.string.library_songs_count, playlist.songCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // Remote YouTube Music Library Shelves (when signed in)
                if (remoteLibraryShelves.isNotEmpty()) {
                    itemsIndexed(
                        items = remoteLibraryShelves,
                        key = { idx, shelf -> "remote_lib_${idx}_${shelf.title}" },
                    ) { _, shelf ->
                        ShelfSection(
                            shelf = shelf,
                            activeVideoId = activeVideoId,
                            downloadedIds = downloadedIds,
                            onItemClick = onItemClick,
                            onSongMore = onSongMore,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                        )
                    }
                }

                // Liked Songs Preview List
                if (likedSongs.isNotEmpty()) {
                    item(key = "liked_header") {
                        Text(
                            text = stringResource(R.string.library_liked_songs),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
                        )
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

/**
 * ISO 1.586:1 credit-card-proportioned Replay hero card in Library.
 */
@Composable
private fun ReplayCreditCard(
    replaySummary: ReplaySummary,
    reduceAnimation: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .width(300.dp)
            .aspectRatio(1.586f)
            .clip(shape)
            .border(0.75.dp, Color.White.copy(alpha = 0.20f), shape)
            .clickable(onClick = onClick),
    ) {
        MeshGradientBackground(
            colors = OnboardingMeshColors,
            driftKey = "replay_card",
            reduceAnimation = reduceAnimation,
            scrimAlpha = 0.18f,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.replay_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.replay_this_month),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }

            Column {
                val totalMinutes = (replaySummary.totalListenedMs / 60_000L).toInt()
                Text(
                    text = "$totalMinutes min · ${replaySummary.totalTracksPlayed} plays",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val topArtist = replaySummary.topArtists.firstOrNull()?.name
                Text(
                    text = topArtist?.let { "#1 $it" } ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            .height(54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
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
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var name by remember { mutableStateOf("") }
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
                value = name,
                onValueChange = { name = it },
                singleLine = true,
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
                    Column {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, color = fg)
                        Text(
                            stringResource(R.string.library_songs_count, playlist.songCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.65f),
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
