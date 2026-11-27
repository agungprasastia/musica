package com.music.musica.data.repository

import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.database.PlayHistoryEntity
import com.music.musica.core.database.PlaylistEntity
import com.music.musica.core.database.PlaylistSongCrossRef
import com.music.musica.core.database.SavedCollectionEntity
import com.music.musica.core.database.SearchHistoryEntity
import com.music.musica.core.database.SongEntity
import com.music.musica.core.database.SubscribedArtistEntity
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.LikeStatus
import com.music.musica.core.model.ReplayPeriod
import com.music.musica.core.model.ShelfItem
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

    val dislikedIds: Flow<Set<String>> = db.songDao().observeDislikedIds().map { it.toSet() }

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

    val savedCollectionIds: Flow<Set<String>> = db.savedCollectionDao().observeIds().map { it.toSet() }

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
        if (isSignedIn()) {
            ytMusicRepository.setSubscribed(browseId, next)
        }
        return next
    }

    suspend fun toggleSaveCollection(
        browseId: String,
        type: BrowseType,
        title: String,
        subtitle: String?,
        thumbnailUrl: String?,
    ): Boolean {
        val currentlySaved = db.savedCollectionDao().isSaved(browseId)
        val next = !currentlySaved
        if (next) {
            db.savedCollectionDao().upsert(
                SavedCollectionEntity(
                    browseId = browseId,
                    type = type.name,
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnailUrl,
                    savedAtMs = System.currentTimeMillis(),
                ),
            )
        } else {
            db.savedCollectionDao().delete(browseId)
        }
        if (isSignedIn()) {
            val remotePlaylistId = when {
                type == BrowseType.ALBUM && browseId.startsWith("MPREb_") ->
                    ytMusicRepository.loadDetail(browseId).getOrNull()?.audioPlaylistId
                else -> browseId
            }
            if (!remotePlaylistId.isNullOrBlank()) {
                ytMusicRepository.ratePlaylist(remotePlaylistId, like = next)
            }
        }
        return next
    }

    suspend fun toggleDislike(song: Song): Boolean {
        upsertSongMetadata(song)
        val existing = db.songDao().getById(song.videoId)
        val nowDisliked = !(existing?.isDisliked ?: false)
        db.songDao().setDisliked(song.videoId, nowDisliked)
        if (isSignedIn()) {
            ytMusicRepository.setLike(
                song.videoId,
                if (nowDisliked) LikeStatus.DISLIKE else LikeStatus.INDIFFERENT,
            )
        }
        return nowDisliked
    }

    suspend fun renamePlaylist(playlistId: String, newTitle: String, description: String? = null) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isEmpty()) return
        val cleanId = playlistId.removePrefix("VL")
        val existing = db.playlistDao().getPlaylist(playlistId) ?: db.playlistDao().getPlaylist(cleanId)
        val targetId = existing?.playlistId ?: playlistId
        val nextDescription = description?.trim() ?: existing?.description.orEmpty()
        db.playlistDao().renamePlaylist(
            playlistId = targetId,
            title = cleanTitle,
            description = nextDescription,
            updatedAtMs = System.currentTimeMillis(),
        )
        if (existing?.isRemoteYtm == true && isSignedIn()) {
            ytMusicRepository.renameRemotePlaylist(targetId, cleanTitle, nextDescription)
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, song: Song) {
        val cleanId = playlistId.removePrefix("VL")
        val existing = db.playlistDao().getPlaylist(playlistId) ?: db.playlistDao().getPlaylist(cleanId)
        val targetId = existing?.playlistId ?: cleanId
        db.playlistDao().removeSongFromPlaylist(targetId, song.videoId)
        if (existing?.isRemoteYtm == true && isSignedIn()) {
            ytMusicRepository.removeSongFromRemotePlaylist(targetId, song.videoId, song.setVideoId)
        }
    }

    /**
     * Pulls remote YouTube Music account library state into local Room tables:
     * - Liked Songs (`VLLM`, up to 2 pages)
     * - Saved Albums (`FEmusic_liked_albums`)
     * - Subscribed Artists (`FEmusic_library_corpus_artists`)
     * - Writable Remote Playlists (`FEmusic_liked_playlists`) so they appear in PlaylistPickerSheet
     */
    suspend fun syncFromYouTubeMusic() {
        if (!isSignedIn()) return

        // 1. Pull Liked Songs (VLLM)
        runCatching {
            val liked = mutableListOf<Song>()
            var cont: String? = null
            var pages = 0
            do {
                val (batch, next) = ytMusicRepository.loadLikedSongs(cont).getOrNull() ?: break
                liked += batch
                cont = next
                pages++
            } while (!cont.isNullOrBlank() && pages < 2)

            val now = System.currentTimeMillis()
            liked.forEachIndexed { idx, song ->
                upsertSongMetadata(song)
                db.songDao().setLiked(
                    videoId = song.videoId,
                    liked = true,
                    likedAtMs = now - idx * 1_000L,
                )
            }
        }

        // 2. Pull Saved Albums
        runCatching {
            val albums = ytMusicRepository.loadRemoteSavedAlbums().getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            albums.forEachIndexed { idx, album ->
                db.savedCollectionDao().upsert(
                    SavedCollectionEntity(
                        browseId = album.browseId,
                        type = BrowseType.ALBUM.name,
                        title = album.title,
                        subtitle = album.subtitle,
                        thumbnailUrl = album.thumbnailUrl,
                        savedAtMs = now - idx * 1_000L,
                    ),
                )
            }
        }

        // 3. Pull Subscribed Artists
        runCatching {
            val artists = ytMusicRepository.loadRemoteSubscribedArtists().getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            artists.forEachIndexed { idx, artist ->
                db.subscribedArtistDao().upsert(
                    SubscribedArtistEntity(
                        browseId = artist.browseId,
                        name = artist.title,
                        thumbnailUrl = artist.thumbnailUrl,
                        subscribedAtMs = now - idx * 1_000L,
                    ),
                )
            }
        }

        // 4. Pull Writable Remote YTM Playlists into local playlist table so PlaylistPickerSheet includes them
        runCatching {
            val shelves = ytMusicRepository.loadRemoteLibrary("FEmusic_liked_playlists").getOrDefault(emptyList())
            val remotePlaylists = shelves.flatMap { it.items }
                .filterIsInstance<ShelfItem.Playlist>()
                .filter { pl ->
                    val raw = pl.browseId.removePrefix("VL")
                    raw.isNotBlank() && raw != "LM" && raw != "LL" && raw != "SE" && !raw.startsWith("RD")
                }
            val now = System.currentTimeMillis()
            remotePlaylists.forEachIndexed { idx, pl ->
                val cleanId = pl.browseId.removePrefix("VL")
                val existing = db.playlistDao().getPlaylist(cleanId)
                db.playlistDao().upsertPlaylist(
                    PlaylistEntity(
                        playlistId = cleanId,
                        title = pl.title,
                        description = existing?.description.orEmpty(),
                        thumbnailUrl = pl.thumbnailUrl ?: existing?.thumbnailUrl,
                        isRemoteYtm = true,
                        updatedAtMs = existing?.updatedAtMs ?: (now - idx * 1_000L),
                        isPinned = existing?.isPinned ?: false,
                        pinnedAtMs = existing?.pinnedAtMs ?: 0L,
                    ),
                )
            }
        }
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
        val existing = db.playlistDao().getPlaylist(playlistId)
        db.playlistDao().deletePlaylist(playlistId)
        if (existing?.isRemoteYtm == true && isSignedIn()) {
            ytMusicRepository.deleteRemotePlaylist(playlistId)
        }
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

    suspend fun startPlaySession(
        song: Song,
        playedAtMs: Long = System.currentTimeMillis(),
        initialListenedMs: Long = 0L,
    ): Long {
        upsertSongMetadata(song)
        return db.historyDao().insert(
            PlayHistoryEntity(
                videoId = song.videoId,
                playedAtMs = playedAtMs,
                listenedDurationMs = initialListenedMs.coerceAtLeast(0L),
            ),
        )
    }

    suspend fun updatePlaySessionDuration(historyRowId: Long, listenedMs: Long) {
        if (historyRowId <= 0L) return
        db.historyDao().updateDuration(historyRowId, listenedMs.coerceAtLeast(0L))
    }

    suspend fun recordPlay(song: Song, listenedMs: Long): Long =
        startPlaySession(song = song, initialListenedMs = listenedMs)

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
