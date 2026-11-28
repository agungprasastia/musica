package com.music.musica.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.Song
import com.music.musica.data.repository.PlayEvent
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.LiquidGlassDialog
import com.music.musica.ui.components.LocalLiquidGlassEnabled
import com.music.musica.ui.components.SongRow
import com.music.musica.ui.components.glassContentColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.Calendar

@Composable
fun HistoryScreen(
    events: List<PlayEvent>,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean = LocalLiquidGlassEnabled.current,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onSongMore: (Song) -> Unit,
    onRequestClearConfirm: (() -> Unit)? = null,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val screenHazeState = rememberHazeState()

    val startOfToday = remember(events.firstOrNull()?.playedAtMs) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val startOfYesterday = startOfToday - 24L * 60L * 60L * 1000L

    val todayEvents = remember(events, startOfToday) { events.filter { it.playedAtMs >= startOfToday } }
    val yesterdayEvents = remember(events, startOfYesterday, startOfToday) {
        events.filter { it.playedAtMs in startOfYesterday until startOfToday }
    }
    val earlierEvents = remember(events, startOfYesterday) { events.filter { it.playedAtMs < startOfYesterday } }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(screenHazeState)
                .statusBarsPadding(),
        ) {
            // Consistent Liquid Glass Top Header (replaces stock Material 3 TopAppBar)
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
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (events.isNotEmpty()) {
                    FrostedCircleButton(
                        icon = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear),
                        hazeState = hazeState,
                        frostedGlassEnabled = frostedGlassEnabled,
                        onClick = {
                            if (onRequestClearConfirm != null) onRequestClearConfirm() else confirmClear = true
                        },
                    )
                }
            }

            if (events.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.musica_empty_state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding + 22.dp),
                    ) {
                        if (todayEvents.isNotEmpty()) {
                            item(key = "h_today") { SectionLabel(stringResource(R.string.history_today)) }
                            itemsIndexed(todayEvents, key = { i, e -> "t_${i}_${e.playedAtMs}" }) { _, event ->
                                SongRow(
                                    song = event.song,
                                    isActive = event.song.videoId == activeVideoId,
                                    isDownloaded = event.song.videoId in downloadedIds,
                                    onClick = { onPlaySong(event.song) },
                                    onMoreClick = { onSongMore(event.song) },
                                )
                            }
                        }
                        if (yesterdayEvents.isNotEmpty()) {
                            item(key = "h_yest") { SectionLabel(stringResource(R.string.history_yesterday)) }
                            itemsIndexed(yesterdayEvents, key = { i, e -> "y_${i}_${e.playedAtMs}" }) { _, event ->
                                SongRow(
                                    song = event.song,
                                    isActive = event.song.videoId == activeVideoId,
                                    isDownloaded = event.song.videoId in downloadedIds,
                                    onClick = { onPlaySong(event.song) },
                                    onMoreClick = { onSongMore(event.song) },
                                )
                            }
                        }
                        if (earlierEvents.isNotEmpty()) {
                            item(key = "h_old") { SectionLabel(stringResource(R.string.history_earlier)) }
                            itemsIndexed(earlierEvents, key = { i, e -> "e_${i}_${e.playedAtMs}" }) { _, event ->
                                SongRow(
                                    song = event.song,
                                    isActive = event.song.videoId == activeVideoId,
                                    isDownloaded = event.song.videoId in downloadedIds,
                                    onClick = { onPlaySong(event.song) },
                                    onMoreClick = { onSongMore(event.song) },
                                )
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

        if (confirmClear) {
            LiquidGlassDialog(
                hazeState = screenHazeState,
                onDismiss = { confirmClear = false },
                title = stringResource(R.string.history_title),
                actions = {
                    GlassDialogButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { confirmClear = false },
                    )
                    GlassDialogButton(
                        text = stringResource(R.string.action_clear),
                        destructive = true,
                        onClick = {
                            confirmClear = false
                            onClearHistory()
                        },
                    )
                },
            ) {
                Text(
                    text = stringResource(R.string.history_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = glassContentColor().copy(alpha = 0.80f),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
