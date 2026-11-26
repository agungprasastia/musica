package com.music.musica.data.backup

import androidx.room.withTransaction
import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.database.PlayHistoryEntity
import com.music.musica.core.database.PlaylistEntity
import com.music.musica.core.database.PlaylistSongCrossRef
import com.music.musica.core.database.SavedCollectionEntity
import com.music.musica.core.database.SongEntity
import com.music.musica.core.database.SubscribedArtistEntity
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.network.HttpClients
import com.music.musica.core.settings.SettingsStore
import com.music.musica.core.settings.UserSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class SongBackup(
    val videoId: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val albumName: String? = null,
    val albumBrowseId: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val isExplicit: Boolean = false,
    val isLiked: Boolean = false,
    val likedAtMs: Long = 0L,
    val isDisliked: Boolean = false,
)

@Serializable
data class PlaylistBackup(
    val playlistId: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val isRemoteYtm: Boolean = false,
    val updatedAtMs: Long = 0L,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long = 0L,
    val trackVideoIds: List<String> = emptyList(),
    val trackSetVideoIds: Map<String, String> = emptyMap(),
)

@Serializable
data class PlayHistoryBackup(
    val videoId: String,
    val playedAtMs: Long,
    val listenedDurationMs: Long,
)

@Serializable
data class SubscribedArtistBackup(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subscribedAtMs: Long = 0L,
)

@Serializable
data class SavedCollectionBackup(
    val browseId: String,
    val type: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val savedAtMs: Long = 0L,
)

@Serializable
data class MusicaBackupV2(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val settings: UserSettings = UserSettings(),
    val songs: List<SongBackup> = emptyList(),
    val playlists: List<PlaylistBackup> = emptyList(),
    val playHistory: List<PlayHistoryBackup> = emptyList(),
    val subscribedArtists: List<SubscribedArtistBackup> = emptyList(),
    val savedCollections: List<SavedCollectionBackup> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

sealed interface ParsedBackup {
    data class FullV2(val data: MusicaBackupV2) : ParsedBackup
    data class SettingsOnlyV1(val settings: UserSettings) : ParsedBackup
}

/**
 * Versioned local JSON backup serializer supporting full Musica v2 backups
 * (settings, liked/disliked songs, playlists with pins & order, listening history,
 * subscribed artists, saved collections) as well as backward-compatible v1 settings imports.
 */
object FullBackupSerializer {

    private val KNOWN_V1_SETTINGS_KEYS = setOf(
        "onboardingCompleted",
        "themeMode",
        "playerTheme",
        "fullBleedCoverArt",
        "showVolumeSlider",
        "showRemainingTime",
        "blurUnfocusedLyrics",
        "showLyricsPreviewStrip",
        "sleepTimerFadeOut",
        "audioCacheLimitMb",
        "frostedGlassEnabled",
        "reduceAnimation",
        "appLanguage",
        "wifiQuality",
        "cellularQuality",
        "downloadQuality",
        "wifiOnlyDownloads",
        "syncPlaybackHistoryToYtm",
        "crossfadeSeconds",
        "skipSilence",
        "autoplayEnabled",
        "playbackSpeed",
        "preferWordSyncedLyrics",
        "enabledLyricsSources",
        "lyricsSourceOrder",
    )

    fun encodeV2(backup: MusicaBackupV2): String =
        HttpClients.json.encodeToString(backup)

    fun parse(rawJson: String): ParsedBackup {
        val element = HttpClients.json.parseToJsonElement(rawJson)
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Backup JSON must be an object")

        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
        return if ((version != null && version >= MusicaBackupV2.SCHEMA_VERSION) || obj.containsKey("settings")) {
            val v2 = HttpClients.json.decodeFromString<MusicaBackupV2>(rawJson)
            ParsedBackup.FullV2(v2)
        } else {
            if (obj.keys.none { it in KNOWN_V1_SETTINGS_KEYS }) {
                throw IllegalArgumentException("Unrecognized backup JSON format")
            }
            val v1Settings = HttpClients.json.decodeFromString<UserSettings>(rawJson)
            ParsedBackup.SettingsOnlyV1(v1Settings)
        }
    }

    suspend fun exportFromDatabase(
        db: MusicaDatabase,
        settings: UserSettings,
    ): String {
        val songs = db.songDao().getAllSongs().map { entity ->
            val artists = runCatching {
                HttpClients.json.decodeFromString<List<ArtistRef>>(entity.artistsJson)
            }.getOrDefault(emptyList())
            SongBackup(
                videoId = entity.videoId,
                title = entity.title,
                artists = artists,
                albumName = entity.albumName,
                albumBrowseId = entity.albumBrowseId,
                durationMs = entity.durationMs,
                thumbnailUrl = entity.thumbnailUrl,
                isExplicit = entity.isExplicit,
                isLiked = entity.isLiked,
                likedAtMs = entity.likedAtMs,
                isDisliked = entity.isDisliked,
            )
        }

        val crossRefsByPlaylist = db.playlistDao().getAllCrossRefs().groupBy { it.playlistId }
        val playlists = db.playlistDao().getAllPlaylists().map { pl ->
            val orderedRefs = crossRefsByPlaylist[pl.playlistId]
                .orEmpty()
                .sortedBy { it.position }
            PlaylistBackup(
                playlistId = pl.playlistId,
                title = pl.title,
                description = pl.description,
                thumbnailUrl = pl.thumbnailUrl,
                isRemoteYtm = pl.isRemoteYtm,
                updatedAtMs = pl.updatedAtMs,
                isPinned = pl.isPinned,
                pinnedAtMs = pl.pinnedAtMs,
                trackVideoIds = orderedRefs.map { it.videoId },
                trackSetVideoIds = orderedRefs.mapNotNull { ref ->
                    ref.setVideoId?.takeIf { it.isNotBlank() }?.let { ref.videoId to it }
                }.toMap(),
            )
        }

        val history = db.historyDao().getAllHistoryRaw().map { h ->
            PlayHistoryBackup(
                videoId = h.videoId,
                playedAtMs = h.playedAtMs,
                listenedDurationMs = h.listenedDurationMs,
            )
        }

        val artists = db.subscribedArtistDao().getAll().map { a ->
            SubscribedArtistBackup(
                browseId = a.browseId,
                name = a.name,
                thumbnailUrl = a.thumbnailUrl,
                subscribedAtMs = a.subscribedAtMs,
            )
        }

        val collections = db.savedCollectionDao().getAll().map { c ->
            SavedCollectionBackup(
                browseId = c.browseId,
                type = c.type,
                title = c.title,
                subtitle = c.subtitle,
                thumbnailUrl = c.thumbnailUrl,
                savedAtMs = c.savedAtMs,
            )
        }

        return encodeV2(
            MusicaBackupV2(
                settings = settings,
                songs = songs,
                playlists = playlists,
                playHistory = history,
                subscribedArtists = artists,
                savedCollections = collections,
            ),
        )
    }

    suspend fun importIntoDatabase(
        db: MusicaDatabase,
        settingsStore: SettingsStore,
        rawJson: String,
    ): Boolean = runCatching {
        when (val parsed = parse(rawJson)) {
            is ParsedBackup.SettingsOnlyV1 -> {
                settingsStore.restoreFrom(parsed.settings)
            }

            is ParsedBackup.FullV2 -> {
                val data = parsed.data
                db.withTransaction {
                    data.songs.forEach { s ->
                        db.songDao().upsert(
                            SongEntity(
                                videoId = s.videoId,
                                title = s.title,
                                artistsJson = HttpClients.json.encodeToString(s.artists),
                                albumName = s.albumName,
                                albumBrowseId = s.albumBrowseId,
                                durationMs = s.durationMs,
                                thumbnailUrl = s.thumbnailUrl,
                                isExplicit = s.isExplicit,
                                isLiked = s.isLiked,
                                likedAtMs = s.likedAtMs,
                                isDisliked = s.isDisliked,
                            ),
                        )
                    }

                    data.playlists.forEach { pl ->
                        db.playlistDao().upsertPlaylist(
                            PlaylistEntity(
                                playlistId = pl.playlistId,
                                title = pl.title,
                                description = pl.description,
                                thumbnailUrl = pl.thumbnailUrl,
                                isRemoteYtm = pl.isRemoteYtm,
                                updatedAtMs = pl.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                isPinned = pl.isPinned,
                                pinnedAtMs = pl.pinnedAtMs,
                            ),
                        )
                        db.playlistDao().clearSongsInPlaylist(pl.playlistId)
                        pl.trackVideoIds.forEachIndexed { idx, videoId ->
                            db.playlistDao().addSongToPlaylist(
                                PlaylistSongCrossRef(
                                    playlistId = pl.playlistId,
                                    videoId = videoId,
                                    position = idx,
                                    setVideoId = pl.trackSetVideoIds[videoId],
                                ),
                            )
                        }
                    }

                    val existingKeys = db.historyDao().getAllHistoryRaw()
                        .map { "${it.videoId}|${it.playedAtMs}" }
                        .toHashSet()
                    data.playHistory.forEach { h ->
                        val key = "${h.videoId}|${h.playedAtMs}"
                        if (existingKeys.add(key)) {
                            db.historyDao().insert(
                                PlayHistoryEntity(
                                    videoId = h.videoId,
                                    playedAtMs = h.playedAtMs,
                                    listenedDurationMs = h.listenedDurationMs,
                                ),
                            )
                        }
                    }

                    data.subscribedArtists.forEach { a ->
                        db.subscribedArtistDao().upsert(
                            SubscribedArtistEntity(
                                browseId = a.browseId,
                                name = a.name,
                                thumbnailUrl = a.thumbnailUrl,
                                subscribedAtMs = a.subscribedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            ),
                        )
                    }

                    data.savedCollections.forEach { c ->
                        db.savedCollectionDao().upsert(
                            SavedCollectionEntity(
                                browseId = c.browseId,
                                type = c.type,
                                title = c.title,
                                subtitle = c.subtitle,
                                thumbnailUrl = c.thumbnailUrl,
                                savedAtMs = c.savedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                settingsStore.restoreFrom(data.settings)
            }
        }
        true
    }.getOrDefault(false)
}
