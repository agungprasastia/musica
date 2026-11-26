package com.music.musica.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PlayHistoryWithSong(
    @Embedded val history: PlayHistoryEntity,
    @Relation(parentColumn = "videoId", entityColumn = "videoId")
    val song: SongEntity?,
)

data class DownloadWithSong(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "videoId", entityColumn = "videoId")
    val song: SongEntity?,
)

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
)

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Query(
        """
        UPDATE songs
        SET title = :title, artistsJson = :artistsJson, albumName = :albumName,
            albumBrowseId = :albumBrowseId, durationMs = :durationMs,
            thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl), isExplicit = :isExplicit
        WHERE videoId = :videoId
        """,
    )
    suspend fun updateMetadata(
        videoId: String,
        title: String,
        artistsJson: String,
        albumName: String?,
        albumBrowseId: String?,
        durationMs: Long,
        thumbnailUrl: String?,
        isExplicit: Boolean,
    )

    @Query("SELECT * FROM songs WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): SongEntity?

    @Query("UPDATE songs SET isLiked = :liked, likedAtMs = :likedAtMs, isDisliked = CASE WHEN :liked = 1 THEN 0 ELSE isDisliked END WHERE videoId = :videoId")
    suspend fun setLiked(videoId: String, liked: Boolean, likedAtMs: Long)

    @Query("UPDATE songs SET isDisliked = :disliked, isLiked = CASE WHEN :disliked = 1 THEN 0 ELSE isLiked END, likedAtMs = CASE WHEN :disliked = 1 THEN 0 ELSE likedAtMs END WHERE videoId = :videoId")
    suspend fun setDisliked(videoId: String, disliked: Boolean)

    @Query("SELECT videoId FROM songs WHERE isDisliked = 1")
    fun observeDislikedIds(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAtMs DESC")
    fun observeLikedSongs(): Flow<List<SongEntity>>

    @Query("SELECT videoId FROM songs WHERE isLiked = 1")
    fun observeLikedIds(): Flow<List<String>>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<SongEntity>

    @Upsert
    suspend fun upsert(song: SongEntity)
}

@Dao
interface PlaylistDao {
    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query(
        """
        SELECT p.*, COUNT(ps.videoId) AS songCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.playlistId = ps.playlistId
        GROUP BY p.playlistId
        ORDER BY p.isPinned DESC, p.pinnedAtMs DESC, p.updatedAtMs DESC
        """,
    )
    fun observePlaylistsWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("UPDATE playlists SET isPinned = :pinned, pinnedAtMs = :pinnedAtMs WHERE playlistId = :playlistId")
    suspend fun setPinned(playlistId: String, pinned: Boolean, pinnedAtMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeSongFromPlaylist(playlistId: String, videoId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearSongsInPlaylist(playlistId: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.videoId = ps.videoId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
        """,
    )
    suspend fun getSongsInPlaylist(playlistId: String): List<SongEntity>

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("UPDATE playlists SET title = :title, description = :description, updatedAtMs = :updatedAtMs WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: String, title: String, description: String, updatedAtMs: Long)

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    suspend fun getAllCrossRefs(): List<PlaylistSongCrossRef>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getCrossRefsForPlaylist(playlistId: String): List<PlaylistSongCrossRef>
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Transaction
    @Query("SELECT * FROM play_history ORDER BY playedAtMs DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 200): Flow<List<PlayHistoryWithSong>>

    @Transaction
    @Query("SELECT * FROM play_history ORDER BY playedAtMs DESC")
    fun observeAllHistory(): Flow<List<PlayHistoryWithSong>>

    @Query("DELETE FROM play_history")
    suspend fun clearAll()

    @Query("SELECT * FROM play_history ORDER BY playedAtMs ASC")
    suspend fun getAllHistoryRaw(): List<PlayHistoryEntity>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT query FROM search_history ORDER BY searchedAtMs DESC LIMIT 20")
    fun observeRecentQueries(): Flow<List<String>>

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM cached_lyrics WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): CachedLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedLyricsEntity)

    @Query("UPDATE cached_lyrics SET offsetMs = :offsetMs WHERE videoId = :videoId")
    suspend fun updateOffset(videoId: String, offsetMs: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY updatedAtMs DESC")
    fun observeDownloads(): Flow<List<DownloadWithSong>>

    @Query("SELECT videoId FROM downloads WHERE status = 'COMPLETED'")
    fun observeCompletedIds(): Flow<List<String>>

    @Query("SELECT videoId FROM downloads WHERE status = 'COMPLETED'")
    suspend fun getCompletedIds(): List<String>

    @Query("SELECT videoId FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getIncompleteIds(): List<String>

    @Query("UPDATE downloads SET status = 'FAILED', progressPercent = 0 WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun markIncompleteAsFailed()
}

@Dao
interface SubscribedArtistDao {
    @Upsert
    suspend fun upsert(artist: SubscribedArtistEntity)

    @Query("DELETE FROM subscribed_artists WHERE browseId = :browseId")
    suspend fun delete(browseId: String)

    @Query("SELECT * FROM subscribed_artists ORDER BY subscribedAtMs DESC")
    fun observeAll(): Flow<List<SubscribedArtistEntity>>

    @Query("SELECT browseId FROM subscribed_artists")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_artists WHERE browseId = :browseId)")
    suspend fun isSubscribed(browseId: String): Boolean

    @Query("SELECT * FROM subscribed_artists ORDER BY subscribedAtMs DESC")
    suspend fun getAll(): List<SubscribedArtistEntity>
}

@Dao
interface SavedCollectionDao {
    @Upsert
    suspend fun upsert(collection: SavedCollectionEntity)

    @Query("DELETE FROM saved_collections WHERE browseId = :browseId")
    suspend fun delete(browseId: String)

    @Query("SELECT * FROM saved_collections ORDER BY savedAtMs DESC")
    fun observeAll(): Flow<List<SavedCollectionEntity>>

    @Query("SELECT browseId FROM saved_collections")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_collections WHERE browseId = :browseId)")
    suspend fun isSaved(browseId: String): Boolean

    @Query("SELECT * FROM saved_collections ORDER BY savedAtMs DESC")
    suspend fun getAll(): List<SavedCollectionEntity>
}
