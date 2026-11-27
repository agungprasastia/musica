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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.musica.R
import com.music.musica.core.model.ReplayPeriod
import com.music.musica.core.model.Song
import com.music.musica.data.repository.ReplayPassSection
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.ui.components.ArtworkImage
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.LocalLiquidGlassEnabled
import com.music.musica.ui.components.MeshGradientBackground
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.theme.MusicaAccentRed
import com.music.musica.ui.theme.OnboardingMeshColors
import dev.chrisbanes.haze.HazeState

@Composable
fun ReplayScreen(
    period: ReplayPeriod,
    summary: ReplaySummary,
    landingSection: ReplayPassSection = ReplayPassSection.MINUTES,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean = LocalLiquidGlassEnabled.current,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    onSelectPeriod: (ReplayPeriod) -> Unit,
    onBack: () -> Unit,
    onPlayTopSongs: (List<Song>, Int) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit = {},
) {
    val periodOptions = listOf(
        stringResource(R.string.replay_this_month),
        stringResource(R.string.replay_this_year),
        stringResource(R.string.replay_period_all_tab),
    )
    val listState = rememberLazyListState()
    var hasLanded by rememberSaveable(landingSection) { mutableStateOf(false) }

    LaunchedEffect(landingSection, summary.totalTracksPlayed) {
        if (hasLanded || summary.totalTracksPlayed == 0) return@LaunchedEffect
        val songsSpan = if (summary.topSongs.isNotEmpty()) summary.topSongs.size + 1 else 0
        val artistsSpan = if (summary.topArtists.isNotEmpty()) summary.topArtists.size + 1 else 0
        val albumsSpan = if (summary.topAlbums.isNotEmpty()) summary.topAlbums.size + 1 else 0
        val targetIndex = when (landingSection) {
            ReplayPassSection.MINUTES -> 0
            ReplayPassSection.TOP_SONG -> if (songsSpan > 0) 3 else 0
            ReplayPassSection.TOP_ARTIST -> if (artistsSpan > 0) 3 + songsSpan else 0
            ReplayPassSection.TOP_ALBUM -> if (albumsSpan > 0) 3 + songsSpan + artistsSpan else 0
        }
        if (targetIndex > 0) {
            listState.scrollToItem(targetIndex)
        }
        hasLanded = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MeshGradientBackground(
            colors = OnboardingMeshColors,
            reduceAnimation = reduceAnimation,
            scrimAlpha = 0.34f,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = bottomPadding + 28.dp),
        ) {
            item(key = "replay_top") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FrostedCircleButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        hazeState = hazeState,
                        frostedGlassEnabled = frostedGlassEnabled,
                        onDarkBackdrop = true,
                        onClick = onBack,
                    )
                    Text(
                        text = stringResource(R.string.replay_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                    )
                }
            }

            item(key = "replay_periods") {
                PillSegmentedControl(
                    options = periodOptions,
                    selectedIndex = ReplayPeriod.entries.indexOf(period).coerceAtLeast(0),
                    onSelect = { idx -> onSelectPeriod(ReplayPeriod.entries[idx]) },
                    onDarkBackdrop = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "replay_stats") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatGlassCard(
                        value = (summary.totalListenedMs / 60_000L).toString(),
                        label = stringResource(R.string.replay_minutes_listened),
                        modifier = Modifier.weight(1f),
                    )
                    StatGlassCard(
                        value = summary.totalTracksPlayed.toString(),
                        label = stringResource(R.string.replay_tracks_played),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (summary.topSongs.isNotEmpty()) {
                item(key = "top_songs_hdr") {
                    Text(
                        text = stringResource(R.string.replay_top_songs),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                val songs = summary.topSongs.map { it.song }
                itemsIndexed(summary.topSongs, key = { i, r -> "rs_${i}_${r.song.videoId}" }) { idx, ranked ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayTopSongs(songs, idx) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "#${idx + 1}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (idx == 0) MusicaAccentRed else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.width(34.dp),
                        )
                        ArtworkImage(url = ranked.song.thumbnailUrl, contentDescription = ranked.song.title, size = 50.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ranked.song.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = ranked.song.artistText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pluralStringResource(R.plurals.replay_plays_count, ranked.playCount, ranked.playCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.80f),
                        )
                    }
                }
            }

            if (summary.topArtists.isNotEmpty()) {
                item(key = "top_artists_hdr") {
                    Text(
                        text = stringResource(R.string.replay_top_artists),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                itemsIndexed(summary.topArtists, key = { i, a -> "ra_${i}_${a.name}" }) { idx, artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !artist.browseId.isNullOrBlank()) {
                                artist.browseId?.let(onOpenArtist)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "#${idx + 1}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (idx == 0) MusicaAccentRed else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.width(34.dp),
                        )
                        if (!artist.thumbnailUrl.isNullOrBlank()) {
                            ArtworkImage(
                                url = artist.thumbnailUrl,
                                contentDescription = artist.name,
                                size = 44.dp,
                                cornerRadius = 22.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pluralStringResource(R.plurals.replay_plays_count, artist.playCount, artist.playCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            if (summary.topAlbums.isNotEmpty()) {
                item(key = "top_albums_hdr") {
                    Text(
                        text = stringResource(R.string.replay_top_albums),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                itemsIndexed(summary.topAlbums, key = { i, a -> "ral_${i}_${a.browseId ?: a.name}" }) { idx, album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !album.browseId.isNullOrBlank()) {
                                album.browseId?.let(onOpenAlbum)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "#${idx + 1}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (idx == 0) MusicaAccentRed else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.width(34.dp),
                        )
                        ArtworkImage(
                            url = album.thumbnailUrl,
                            contentDescription = album.name,
                            size = 50.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!album.artistName.isNullOrBlank()) {
                                Text(
                                    text = album.artistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pluralStringResource(R.plurals.replay_plays_count, album.playCount, album.playCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.80f),
                        )
                    }
                }
            }

            if (summary.distinctSongs > 0) {
                item(key = "replay_habits_hdr") {
                    Text(
                        text = stringResource(R.string.replay_habits_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                    )
                }
                item(key = "replay_habits_cards") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatGlassCard(
                                value = summary.distinctSongs.toString(),
                                label = stringResource(R.string.replay_habits_songs),
                                modifier = Modifier.weight(1f),
                            )
                            StatGlassCard(
                                value = summary.distinctArtists.toString(),
                                label = stringResource(R.string.replay_habits_artists),
                                modifier = Modifier.weight(1f),
                            )
                            StatGlassCard(
                                value = summary.distinctAlbums.toString(),
                                label = stringResource(R.string.replay_habits_albums),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        summary.peakHour?.let { hour ->
                            val timeBucket = when (hour) {
                                in 5..10 -> stringResource(R.string.replay_pass_time_morning)
                                in 11..14 -> stringResource(R.string.replay_pass_time_midday)
                                in 15..18 -> stringResource(R.string.replay_pass_time_evening)
                                else -> stringResource(R.string.replay_pass_time_night)
                            }
                            val hourStr = String.format(java.util.Locale.ROOT, "%02d:00", hour)
                            val daysCount = summary.activeDaysCount.coerceAtLeast(1)
                            val daysLabel = pluralStringResource(R.plurals.replay_pass_active_days, daysCount, daysCount)
                            Text(
                                text = stringResource(
                                    R.string.replay_habits_peak_note,
                                    hourStr,
                                    timeBucket,
                                    daysLabel,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatGlassCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassCard(RoundedCornerShape(20.dp), onDarkBackdrop = true)
            .padding(18.dp),
    ) {
        Text(value, style = MaterialTheme.typography.displayLarge, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f))
    }
}
