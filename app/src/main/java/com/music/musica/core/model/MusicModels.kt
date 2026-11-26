package com.music.musica.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

private val GOOGLE_THUMB_REGEX = Regex("""=w\d+-h\d+[^&\s]*""")
private val GOOGLE_S_SIZE_REGEX = Regex("""=s\d+[^&\s]*""")

@Immutable
@Serializable
data class ArtistRef(
    val name: String,
    val browseId: String? = null,
)

@Immutable
@Serializable
data class AlbumRef(
    val name: String,
    val browseId: String? = null,
)

@Immutable
@Serializable
data class Song(
    val videoId: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val isExplicit: Boolean = false,
    val setVideoId: String? = null,
    val playlistId: String? = null,
) {
    val artistText: String
        get() = artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }

    val primaryArtistId: String?
        get() = artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId

    fun artworkAt(sizePx: Int = 544): String? = upsizeArtworkUrl(thumbnailUrl, sizePx)
}

fun upsizeArtworkUrl(url: String?, sizePx: Int = 544): String? {
    if (url.isNullOrBlank()) return null
    if (GOOGLE_THUMB_REGEX.containsMatchIn(url)) {
        return url.replace(GOOGLE_THUMB_REGEX, "=w$sizePx-h$sizePx-l90-rj")
    }
    if (GOOGLE_S_SIZE_REGEX.containsMatchIn(url)) {
        return url.replace(GOOGLE_S_SIZE_REGEX, "=s$sizePx-c-k-c0x00ffffff-no-rj")
    }
    return url
}

enum class BrowseType {
    ALBUM,
    ARTIST,
    PLAYLIST,
    MOOD,
}

@Immutable
sealed interface ShelfItem {
    val id: String
    val title: String
    val subtitle: String?
    val thumbnailUrl: String?

    @Immutable
    data class Track(val song: Song) : ShelfItem {
        override val id: String get() = song.videoId
        override val title: String get() = song.title
        override val subtitle: String get() = song.artistText
        override val thumbnailUrl: String? get() = song.thumbnailUrl
    }

    @Immutable
    data class Album(
        val browseId: String,
        override val title: String,
        override val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
        val year: String? = null,
    ) : ShelfItem {
        override val id: String get() = browseId
    }

    @Immutable
    data class Artist(
        val browseId: String,
        override val title: String,
        override val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
    ) : ShelfItem {
        override val id: String get() = browseId
    }

    @Immutable
    data class Playlist(
        val browseId: String,
        override val title: String,
        override val subtitle: String? = null,
        override val thumbnailUrl: String? = null,
        val params: String? = null,
        val accentColorArgb: Long? = null,
    ) : ShelfItem {
        override val id: String
            get() = if (params.isNullOrBlank()) browseId else "$browseId:$params"
    }
}

@Immutable
data class HomeShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<ShelfItem> = emptyList(),
    val browseId: String? = null,
    val params: String? = null,
)

@Immutable
data class BrowsePage(
    val shelves: List<HomeShelf>,
    val continuation: String? = null,
)

@Immutable
data class ExploreCategory(
    val title: String,
    val browseId: String,
    val params: String? = null,
    val stripeColorArgb: Long? = null,
    val thumbnailUrl: String? = null,
) {
    val key: String
        get() = if (params.isNullOrBlank()) browseId else "$browseId|$params"
}

@Immutable
data class ExploreFeed(
    val categories: List<ExploreCategory> = emptyList(),
)

@Immutable
data class MixEntry(
    val item: ShelfItem,
    val englishTitle: String = item.title,
)

@Immutable
data class MixMoodShelf(
    val key: String,
    val title: String,
    val items: List<ShelfItem>,
)

@Immutable
data class MixesFeed(
    val stations: List<ShelfItem> = emptyList(),
    val featured: List<ShelfItem> = emptyList(),
    val moodShelves: List<MixMoodShelf> = emptyList(),
    val isPersonalized: Boolean = false,
) {
    val isEmpty: Boolean
        get() = stations.isEmpty() && featured.isEmpty() && moodShelves.isEmpty()
}

@Immutable
data class DetailPage(
    val browseId: String,
    val type: BrowseType,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val artist: ArtistRef? = null,
    val tracks: List<Song> = emptyList(),
    val sections: List<HomeShelf> = emptyList(),
    val continuation: String? = null,
)

enum class SearchFilter(val innertubeParams: String?) {
    ALL(null),
    SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUMS("EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"),
    ARTISTS("EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D"),
    PLAYLISTS("EgeKAQQoAEABagoQAxAEEAoQCRAF"),
}

@Immutable
data class SearchPage(
    val items: List<ShelfItem>,
    val continuation: String? = null,
)

enum class LikeStatus {
    LIKE,
    DISLIKE,
    INDIFFERENT,
}

@Immutable
@Serializable
data class AccountProfile(
    val name: String,
    val handle: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
)

@Immutable
data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val isRemoteYtm: Boolean = false,
    val isPinned: Boolean = false,
)

enum class AudioQuality(val maxKbps: Int) {
    LOW(64),
    MEDIUM(128),
    HIGH(256),
    MAX(Int.MAX_VALUE),
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class PlayerTheme {
    DEFAULT,
    LEGACY,
    SIMPLE,
}

enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    INDONESIAN("id"),
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

enum class LyricsSourceId(val displayName: String) {
    BETTER_LYRICS("BetterLyrics"),
    LYRICS_PLUS("LyricsPlus"),
    LRCLIB("LRCLIB"),
    YOUTUBE("YouTube Music"),
}

enum class PlaybackSourceMode {
    STREAMING,
    CACHED,
    OFFLINE,
}

@Immutable
data class NerdStats(
    val videoId: String = "",
    val mimeType: String = "",
    val codecs: String = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val loudnessDb: Double? = null,
    val sourceClient: String = "",
    val sourceMode: PlaybackSourceMode = PlaybackSourceMode.STREAMING,
    val bufferedMs: Long = 0L,
)

enum class DownloadState {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

enum class ReplayPeriod {
    THIS_MONTH,
    THIS_YEAR,
    ALL_TIME,
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
