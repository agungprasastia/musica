package com.music.musica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.UserPlaylist
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

/**
 * Unified target model for [BrowseActionsSheet], covering Album, Playlist, Artist cards,
 * as well as user-owned local/remote playlists in Library.
 */
data class BrowseActionTarget(
    val browseId: String,
    val type: BrowseType,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val params: String? = null,
    val userPlaylist: UserPlaylist? = null,
) {
    companion object {
        fun fromShelfItem(item: ShelfItem): BrowseActionTarget? = when (item) {
            is ShelfItem.Album -> BrowseActionTarget(
                browseId = item.browseId,
                type = BrowseType.ALBUM,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
            is ShelfItem.Artist -> BrowseActionTarget(
                browseId = item.browseId,
                type = BrowseType.ARTIST,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            )
            is ShelfItem.Playlist -> BrowseActionTarget(
                browseId = item.browseId,
                type = BrowseType.PLAYLIST,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                params = item.params,
            )
            is ShelfItem.Track -> null
        }

        fun fromUserPlaylist(playlist: UserPlaylist): BrowseActionTarget = BrowseActionTarget(
            browseId = playlist.playlistId,
            type = BrowseType.PLAYLIST,
            title = playlist.title,
            subtitle = null,
            thumbnailUrl = playlist.thumbnailUrl,
            userPlaylist = playlist,
        )
    }
}

/**
 * Liquid Glass context action sheet opened on long-press of Album, Playlist, or Artist cards.
 */
@Composable
fun BrowseActionsSheet(
    target: BrowseActionTarget,
    isSavedOrSubscribed: Boolean,
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleSaveOrSubscribe: () -> Unit,
    onDownloadAll: (() -> Unit)?,
    onShare: () -> Unit,
    onTogglePinPlaylist: (() -> Unit)? = null,
    onRenamePlaylist: ((String) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
) {
    val fg = glassContentColor()
    val secondaryFg = fg.copy(alpha = 0.68f)
    val isArtist = target.type == BrowseType.ARTIST
    val userPlaylist = target.userPlaylist

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    url = target.thumbnailUrl,
                    contentDescription = target.title,
                    size = 54.dp,
                    cornerRadius = 10.dp,
                    circle = isArtist,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitleText = target.subtitle ?: if (userPlaylist != null) {
                        stringResource(R.string.library_songs_count, userPlaylist.songCount)
                    } else null
                    if (!subtitleText.isNullOrBlank()) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Quick Action Tiles: Play, Shuffle, Save/Subscribe, Share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrowseQuickTile(
                    icon = Icons.Rounded.PlayArrow,
                    label = stringResource(R.string.action_play),
                    tint = MusicaAccentRed,
                    onClick = { onPlay(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                BrowseQuickTile(
                    icon = Icons.Rounded.Shuffle,
                    label = stringResource(R.string.action_shuffle),
                    tint = fg,
                    onClick = { onShuffle(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                if (userPlaylist == null) {
                    BrowseQuickTile(
                        icon = when {
                            isArtist && isSavedOrSubscribed -> Icons.Rounded.Star
                            isArtist -> Icons.Rounded.StarOutline
                            isSavedOrSubscribed -> Icons.Rounded.Bookmark
                            else -> Icons.Rounded.BookmarkBorder
                        },
                        label = stringResource(
                            when {
                                isArtist && isSavedOrSubscribed -> R.string.menu_subscribed
                                isArtist -> R.string.menu_subscribe
                                isSavedOrSubscribed -> R.string.action_saved
                                else -> R.string.action_save
                            },
                        ),
                        tint = if (isSavedOrSubscribed) MusicaAccentRed else fg,
                        onClick = { onToggleSaveOrSubscribe(); onDismiss() },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (userPlaylist == null || !userPlaylist.playlistId.startsWith("local_")) {
                    BrowseQuickTile(
                        icon = Icons.Rounded.Share,
                        label = stringResource(R.string.menu_share),
                        tint = fg,
                        onClick = { onDismiss(); onShare() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(
                color = fg.copy(alpha = 0.14f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )

            BrowseActionRow(Icons.Rounded.QueuePlayNext, stringResource(R.string.menu_play_next), fg) {
                onPlayNext(); onDismiss()
            }
            BrowseActionRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.menu_add_to_queue), fg) {
                onAddToQueue(); onDismiss()
            }
            if (onDownloadAll != null && !isArtist) {
                BrowseActionRow(Icons.Rounded.Download, stringResource(R.string.menu_download_all), fg) {
                    onDownloadAll(); onDismiss()
                }
            }

            if (userPlaylist != null) {
                HorizontalDivider(
                    color = fg.copy(alpha = 0.14f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
                if (onTogglePinPlaylist != null) {
                    BrowseActionRow(
                        icon = Icons.Rounded.PushPin,
                        label = stringResource(
                            if (userPlaylist.isPinned) R.string.menu_unpin_playlist else R.string.menu_pin_playlist,
                        ),
                        tint = if (userPlaylist.isPinned) MusicaAccentRed else fg,
                    ) {
                        onTogglePinPlaylist(); onDismiss()
                    }
                }
                if (onRenamePlaylist != null) {
                    BrowseActionRow(Icons.Rounded.Edit, stringResource(R.string.menu_rename_playlist), fg) {
                        showRenameDialog = true
                    }
                }
                if (onDeletePlaylist != null) {
                    BrowseActionRow(Icons.Rounded.DeleteOutline, stringResource(R.string.menu_delete_playlist), MusicaAccentRed) {
                        showDeleteConfirm = true
                    }
                }
            }
        }
    }

    if (showRenameDialog && userPlaylist != null && onRenamePlaylist != null) {
        var newName by remember(userPlaylist.title) { mutableStateOf(userPlaylist.title) }
        LiquidGlassDialog(
            hazeState = hazeState,
            onDismiss = { showRenameDialog = false },
            title = stringResource(R.string.menu_rename_playlist),
            actions = {
                GlassDialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showRenameDialog = false },
                )
                GlassDialogButton(
                    text = stringResource(R.string.action_save),
                    primary = true,
                    enabled = newName.isNotBlank(),
                    onClick = {
                        showRenameDialog = false
                        onRenamePlaylist(newName.trim())
                        onDismiss()
                    },
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
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = fg),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showDeleteConfirm && userPlaylist != null && onDeletePlaylist != null) {
        LiquidGlassDialog(
            hazeState = hazeState,
            onDismiss = { showDeleteConfirm = false },
            title = stringResource(R.string.menu_delete_playlist),
            actions = {
                GlassDialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showDeleteConfirm = false },
                )
                GlassDialogButton(
                    text = stringResource(R.string.action_delete),
                    destructive = true,
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePlaylist()
                        onDismiss()
                    },
                )
            },
        ) {
            Text(
                text = stringResource(R.string.menu_delete_playlist_confirm, userPlaylist.title),
                style = MaterialTheme.typography.bodyMedium,
                color = fg.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun BrowseQuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
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
private fun BrowseActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}
