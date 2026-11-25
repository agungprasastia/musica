package com.music.musica.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import com.music.musica.core.model.UserPlaylist
import com.music.musica.core.network.HttpClients
import kotlinx.serialization.encodeToString

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artistsJson: String,
    val albumName: String? = null,
    val albumBrowseId: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val isExplicit: Boolean = false,
    val isLiked: Boolean = false,
    val likedAtMs: Long = 0L,
) {
    fun toSong(): Song {
        val artists = runCatching {
            HttpClients.json.decodeFromString<List<ArtistRef>>(artistsJson)
        }.getOrDefault(emptyList())
        return Song(
            videoId = videoId,
            title = title,
            artists = artists,
            album = albumName?.let { AlbumRef(it, albumBrowseId) },
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            isExplicit = isExplicit,
        )
    }

    companion object {
        fun fromSong(song: Song, isLiked: Boolean = false, likedAtMs: Long = 0L): SongEntity = SongEntity(
            videoId = song.videoId,
            title = song.title,
            artistsJson = HttpClients.json.encodeToString(song.artists),
            albumName = song.album?.name,
            albumBrowseId = song.album?.browseId,
            durationMs = song.durationMs,
            thumbnailUrl = song.thumbnailUrl,
            isExplicit = song.isExplicit,
            isLiked = isLiked,
            likedAtMs = likedAtMs,
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val isRemoteYtm: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun toUserPlaylist(songCount: Int = 0): UserPlaylist = UserPlaylist(
        playlistId = playlistId,
        title = title,
        description = description,
        thumbnailUrl = thumbnailUrl,
        songCount = songCount,
        isRemoteYtm = isRemoteYtm,
    )
}

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("videoId")],
)
data class PlaylistSongCrossRef(
    val playlistId: String,
    val videoId: String,
    val position: Int,
    val setVideoId: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "play_history",
    indices = [Index("videoId"), Index("playedAtMs")],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoId: String,
    val playedAtMs: Long,
    val listenedDurationMs: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val videoId: String,
    val sourceName: String,
    val isSynced: Boolean,
    val isWordSynced: Boolean,
    val payloadJson: String,
    val offsetMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val status: String = DownloadState.QUEUED.name,
    val progressPercent: Int = 0,
    val fileSizeBytes: Long = 0L,
    val qualityKbps: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val downloadState: DownloadState
        get() = runCatching { DownloadState.valueOf(status) }.getOrDefault(DownloadState.QUEUED)
}
