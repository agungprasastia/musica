package com.music.musica.data.repository

import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.database.PlayHistoryEntity
import com.music.musica.core.database.PlaylistEntity
import com.music.musica.core.database.PlaylistSongCrossRef
import com.music.musica.core.database.SearchHistoryEntity
import com.music.musica.core.database.SongEntity
import com.music.musica.core.database.SubscribedArtistEntity
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.LikeStatus
import com.music.musica.core.model.ReplayPeriod
import com.music.musica.core.model.Song
import com.music.musica.core.model.UserPlaylist
import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.Calendar
import java.util.UUID

class LibraryRepository(
    private val db: MusicaDatabase,
    private val ytMusicRepository: YtMusicRepository,
    private val isSignedIn: () -> Boolean,
) {
    val likedSongs: Flow<List<Song>> = db.songDao().observeLikedSongs().map { list ->
        list.map { it.toSong() }
    }

    val likedIds: Flow<Set<String>> = db.songDao().observeLikedIds().map { it.toSet() }

    val localPlaylists: Flow<List<UserPlaylist>> = db.playlistDao().observePlaylistsWithCounts().map { list ->
        list.map { it.playlist.toUserPlaylist(it.songCount) }
    }

    val recentHistory: Flow<List<PlayEvent>> = db.historyDao().observeRecentHistory(200).map { rows ->
        rows.mapNotNull { row ->
            val song = row.song?.toSong() ?: return@mapNotNull null
            PlayEvent(song = song, playedAtMs = row.history.playedAtMs, listenedDurationMs = row.history.listenedDurationMs)
        }
    }

    val recentlyPlayedSongs: Flow<List<Song>> = recentHistory.map { events ->
        events.map { it.song }.distinctBy { it.videoId }.take(20)
    }

    val allPlayEvents: Flow<List<PlayEvent>> = db.historyDao().observeAllHistory().map { rows ->
        rows.mapNotNull { row ->
            val song = row.song?.toSong() ?: return@mapNotNull null
            PlayEvent(song = song, playedAtMs = row.history.playedAtMs, listenedDurationMs = row.history.listenedDurationMs)
        }
    }

    val recentSearchQueries: Flow<List<String>> = db.searchHistoryDao().observeRecentQueries()

    val subscribedArtistIds: Flow<Set<String>> = db.subscribedArtistDao().observeIds().map { it.toSet() }

    suspend fun togglePlaylistPin(playlistId: String) {
        val current = db.playlistDao().getPlaylist(playlistId) ?: return
        val nextPinned = !current.isPinned
        db.playlistDao().setPinned(
            playlistId = playlistId,
            pinned = nextPinned,
            pinnedAtMs = if (nextPinned) System.currentTimeMillis() else 0L,
        )
    }

    suspend fun toggleArtistSubscribed(browseId: String, name: String, thumbnailUrl: String?): Boolean {
        val currentlySubscribed = db.subscribedArtistDao().isSubscribed(browseId)
        val next = !currentlySubscribed
        if (next) {
            db.subscribedArtistDao().upsert(
                SubscribedArtistEntity(
                    browseId = browseId,
                    name = name,
                    thumbnailUrl = thumbnailUrl,
                    subscribedAtMs = System.currentTimeMillis(),
                ),
            )
        } else {
            db.subscribedArtistDao().delete(browseId)
        }
        return next
    }

    suspend fun upsertSongMetadata(song: Song) {
        val inserted = db.songDao().insertIgnore(SongEntity.fromSong(song))
        if (inserted == -1L) {
            db.songDao().updateMetadata(
                videoId = song.videoId,
                title = song.title,
                artistsJson = HttpClients.json.encodeToString(song.artists),
                albumName = song.album?.name,
                albumBrowseId = song.album?.browseId,
                durationMs = song.durationMs,
                thumbnailUrl = song.thumbnailUrl,
                isExplicit = song.isExplicit,
            )
        }
    }

    suspend fun toggleLike(song: Song): Boolean {
        upsertSongMetadata(song)
        val existing = db.songDao().getById(song.videoId)
        val nowLiked = !(existing?.isLiked ?: false)
        db.songDao().setLiked(
            videoId = song.videoId,
            liked = nowLiked,
            likedAtMs = if (nowLiked) System.currentTimeMillis() else 0L,
        )
        if (isSignedIn()) {
            ytMusicRepository.setLike(song.videoId, if (nowLiked) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
        }
        return nowLiked
    }

    suspend fun createPlaylist(title: String, description: String = ""): UserPlaylist {
        val remoteId = if (isSignedIn()) ytMusicRepository.createRemotePlaylist(title, description) else null
        val id = remoteId ?: "local_${UUID.randomUUID().toString().take(8)}"
        val entity = PlaylistEntity(
            playlistId = id,
            title = title.trim(),
            description = description.trim(),
            isRemoteYtm = remoteId != null,
        )
        db.playlistDao().upsertPlaylist(entity)
        return entity.toUserPlaylist(0)
    }

    suspend fun deletePlaylist(playlistId: String) {
        db.playlistDao().deletePlaylist(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: String, song: Song) {
        upsertSongMetadata(song)
        val nextPos = db.playlistDao().nextPosition(playlistId)
        db.playlistDao().addSongToPlaylist(
            PlaylistSongCrossRef(
                playlistId = playlistId,
                videoId = song.videoId,
                position = nextPos,
            ),
        )
        val playlist = db.playlistDao().getPlaylist(playlistId)
        if (playlist != null) {
            db.playlistDao().upsertPlaylist(
                playlist.copy(
                    thumbnailUrl = playlist.thumbnailUrl ?: song.thumbnailUrl,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            if (playlist.isRemoteYtm && isSignedIn()) {
                ytMusicRepository.addSongToRemotePlaylist(playlistId, song.videoId)
            }
        }
    }

    suspend fun loadLocalPlaylistDetail(playlistId: String): DetailPage? {
        val entity = db.playlistDao().getPlaylist(playlistId) ?: return null
        val tracks = db.playlistDao().getSongsInPlaylist(playlistId).map { it.toSong() }
        return DetailPage(
            browseId = entity.playlistId,
            type = BrowseType.PLAYLIST,
            title = entity.title,
            subtitle = "${tracks.size} songs",
            description = entity.description.takeIf { it.isNotBlank() },
            thumbnailUrl = entity.thumbnailUrl ?: tracks.firstOrNull()?.thumbnailUrl,
            tracks = tracks,
        )
    }

    suspend fun recordPlay(song: Song, listenedMs: Long) {
        upsertSongMetadata(song)
        db.historyDao().insert(
            PlayHistoryEntity(
                videoId = song.videoId,
                playedAtMs = System.currentTimeMillis(),
                listenedDurationMs = listenedMs,
            ),
        )
    }

    suspend fun clearHistory() {
        db.historyDao().clearAll()
    }

    suspend fun recordSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.isNotEmpty()) {
            db.searchHistoryDao().upsert(SearchHistoryEntity(query = clean))
        }
    }

    suspend fun deleteSearchQuery(query: String) {
        db.searchHistoryDao().delete(query)
    }

    suspend fun clearSearchHistory() {
        db.searchHistoryDao().clearAll()
    }

    fun observeReplay(period: ReplayPeriod): Flow<ReplaySummary> = allPlayEvents.map { events ->
        val sinceMs = when (period) {
            ReplayPeriod.THIS_MONTH -> Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
            ReplayPeriod.THIS_YEAR -> Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
            ReplayPeriod.ALL_TIME -> 0L
        }
        HistoryReplayAggregator.summarize(events, sinceMs = sinceMs)
    }
}
