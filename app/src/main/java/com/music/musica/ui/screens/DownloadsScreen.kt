package com.music.musica.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import com.music.musica.core.network.NetworkType
import com.music.musica.data.download.DownloadQueueCoordinator
import com.music.musica.data.download.DownloadedTrackItem
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.LocalLiquidGlassEnabled
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState

@Composable
fun DownloadsScreen(
    downloads: List<DownloadedTrackItem>,
    wifiOnlyDownloads: Boolean = false,
    networkType: NetworkType = NetworkType.WIFI,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean = LocalLiquidGlassEnabled.current,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlayTracks: (List<Song>, Int) -> Unit,
    onRetryDownload: (Song) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onRemoveDownload: (String) -> Unit,
) {
    val completedSongs = remember(downloads) {
        downloads.filter { it.state == DownloadState.COMPLETED }.map { it.song }
    }
    val waitingForWifi = wifiOnlyDownloads && networkType != NetworkType.WIFI
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val singlesTitle = stringResource(R.string.downloads_singles_collection)
    val offlineBadge = stringResource(R.string.downloads_badge_offline)
    val collections = remember(downloads, singlesTitle) {
        DownloadQueueCoordinator.groupByCollection(downloads, singlesTitle)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Liquid Glass Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FrostedCircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    hazeState = hazeState,
                    frostedGlassEnabled = frostedGlassEnabled,
                    onClick = onBack,
                )
                Text(
                    text = stringResource(R.string.downloads_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (completedSongs.isNotEmpty()) {
                    FrostedCircleButton(
                        icon = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        hazeState = hazeState,
                        frostedGlassEnabled = frostedGlassEnabled,
                        tint = MusicaAccentRed,
                        onClick = { onPlayTracks(completedSongs, 0) },
                    )
                }
            }

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .lightweightGlass(CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadForOffline,
                                contentDescription = null,
                                tint = MusicaAccentRed,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.downloads_empty),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.downloads_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                PillSegmentedControl(
                    options = listOf(
                        stringResource(R.string.downloads_tab_tracks),
                        stringResource(R.string.downloads_tab_collections),
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding + 22.dp),
                    ) {
                        itemsIndexed(downloads, key = { _, d -> d.song.videoId }) { _, item ->
                            val completedIndex = completedSongs.indexOfFirst { it.videoId == item.song.videoId }
                            val isActive = item.state == DownloadState.QUEUED || item.state == DownloadState.DOWNLOADING
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = completedIndex >= 0) {
                                        onPlayTracks(completedSongs, completedIndex)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(
                                    url = item.song.thumbnailUrl,
                                    contentDescription = item.song.title,
                                    size = 52.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.song.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = when (item.state) {
                                            DownloadState.QUEUED -> if (waitingForWifi) {
                                                stringResource(R.string.downloads_status_waiting_wifi)
                                            } else {
                                                stringResource(R.string.downloads_status_queued)
                                            }
                                            DownloadState.DOWNLOADING -> stringResource(
                                                R.string.downloads_status_downloading,
                                                item.progressPercent,
                                            )
                                            DownloadState.COMPLETED -> "${item.song.artistText} • ${formatMegabytes(item.fileSizeBytes, offlineBadge)}"
                                            DownloadState.FAILED -> stringResource(R.string.downloads_status_failed)
                                            DownloadState.NOT_DOWNLOADED -> ""
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.state == DownloadState.FAILED) {
                                            MusicaAccentRed
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.state == DownloadState.DOWNLOADING) {
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { item.progressPercent / 100f },
                                            color = MusicaAccentRed,
                                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else if (item.state == DownloadState.QUEUED && !waitingForWifi) {
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            color = MusicaAccentRed,
                                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                if (item.state == DownloadState.FAILED) {
                                    IconButton(onClick = { onRetryDownload(item.song) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = stringResource(R.string.action_retry),
                                            tint = MusicaAccentRed,
                                        )
                                    }
                                }
                                if (isActive) {
                                    IconButton(onClick = { onCancelDownload(item.song.videoId) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.action_cancel),
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { onRemoveDownload(item.song.videoId) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.action_delete),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 10.dp,
                            bottom = bottomPadding + 22.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(collections, key = { it.collectionId ?: "__singles__" }) { group ->
                            val playable = group.completedSongs
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .glassCard(RoundedCornerShape(14.dp))
                                    .clickable(enabled = playable.isNotEmpty()) {
                                        onPlayTracks(playable, 0)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ArtworkImage(
                                    url = group.artworkUrl,
                                    contentDescription = group.title,
                                    size = 56.dp,
                                    cornerRadius = 10.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.downloads_collection_summary,
                                            group.items.size,
                                            playable.size,
                                            group.items.size,
                                            formatMegabytes(group.totalSizeBytes, offlineBadge),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (playable.isNotEmpty()) {
                                    IconButton(onClick = { onPlayTracks(playable, 0) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = stringResource(R.string.action_play),
                                            tint = MusicaAccentRed,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                }
            }
        }
    }
}

private fun formatMegabytes(bytes: Long, offlineLabel: String): String {
    if (bytes <= 0L) return offlineLabel
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}
