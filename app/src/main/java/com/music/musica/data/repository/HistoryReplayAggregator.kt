package com.music.musica.data.repository

import com.music.musica.core.model.Song
import java.util.Locale

enum class ReplayPassSection {
    MINUTES,
    TOP_SONG,
    TOP_ARTIST,
    TOP_ALBUM,
}

data class PlayEvent(
    val song: Song,
    val playedAtMs: Long,
    val listenedDurationMs: Long,
)

data class RankedSong(
    val song: Song,
    val playCount: Int,
    val totalListenedMs: Long,
)

data class RankedArtist(
    val name: String,
    val browseId: String?,
    val playCount: Int,
    val totalListenedMs: Long,
    val thumbnailUrl: String? = null,
)

data class RankedAlbum(
    val name: String,
    val browseId: String?,
    val artistName: String?,
    val thumbnailUrl: String?,
    val playCount: Int,
    val totalListenedMs: Long,
)

data class ReplaySummary(
    val totalTracksPlayed: Int = 0,
    val totalListenedMs: Long = 0L,
    val topSongs: List<RankedSong> = emptyList(),
    val topArtists: List<RankedArtist> = emptyList(),
    val topAlbums: List<RankedAlbum> = emptyList(),
    val firstPlayedAtMs: Long? = null,
)

/**
 * Aggregates raw listening events into monthly / yearly / all-time Replay statistics,
 * filtering out brief skips under [MIN_COUNTABLE_MS].
 */
object HistoryReplayAggregator {
    const val MIN_COUNTABLE_MS = 15_000L

    fun summarize(events: List<PlayEvent>, sinceMs: Long = 0L, limit: Int = 20): ReplaySummary {
        val valid = events.filter {
            it.playedAtMs >= sinceMs && it.listenedDurationMs >= MIN_COUNTABLE_MS
        }
        if (valid.isEmpty()) return ReplaySummary()

        val totalMs = valid.sumOf { it.listenedDurationMs }
        val firstPlayedAtMs = valid.mapNotNull { it.playedAtMs.takeIf { ts -> ts > 0L } }.minOrNull()

        val topSongs = valid
            .groupBy { it.song.videoId }
            .values
            .map { group ->
                RankedSong(
                    song = group.maxBy { it.playedAtMs }.song,
                    playCount = group.size,
                    totalListenedMs = group.sumOf { it.listenedDurationMs },
                )
            }
            .sortedWith(compareByDescending<RankedSong> { it.playCount }.thenByDescending { it.totalListenedMs })
            .take(limit)

        data class ArtistAcc(
            var browseId: String?,
            var count: Int,
            var listenedMs: Long,
            val songPlays: MutableMap<String, Int> = linkedMapOf(),
            val songThumbs: MutableMap<String, String> = linkedMapOf(),
            var fallbackThumb: String? = null,
        )

        val artistMap = linkedMapOf<String, ArtistAcc>()
        valid.forEach { event ->
            val primary = event.song.artists.firstOrNull()
            val artistName = primary?.name?.takeIf { it.isNotBlank() } ?: event.song.artistText
            val acc = artistMap.getOrPut(artistName) { ArtistAcc(primary?.browseId, 0, 0L) }
            if (acc.browseId == null) {
                val bid = primary?.browseId?.takeIf { it.isNotBlank() }
                if (bid != null) acc.browseId = bid
            }
            acc.count += 1
            acc.listenedMs += event.listenedDurationMs
            val thumb = event.song.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (thumb != null) {
                acc.songPlays[event.song.videoId] = (acc.songPlays[event.song.videoId] ?: 0) + 1
                acc.songThumbs[event.song.videoId] = thumb
                acc.fallbackThumb = thumb
            }
        }

        val topArtists = artistMap.entries
            .map { (name, acc) ->
                val bestVideoId = acc.songPlays.maxByOrNull { it.value }?.key
                val thumb = bestVideoId?.let { acc.songThumbs[it] } ?: acc.fallbackThumb
                RankedArtist(
                    name = name,
                    browseId = acc.browseId,
                    playCount = acc.count,
                    totalListenedMs = acc.listenedMs,
                    thumbnailUrl = thumb,
                )
            }
            .sortedWith(compareByDescending<RankedArtist> { it.playCount }.thenByDescending { it.totalListenedMs })
            .take(limit)

        data class AlbumAcc(
            var name: String,
            var browseId: String?,
            var artistName: String?,
            var thumbnailUrl: String?,
            var latestPlayedAtMs: Long,
            var count: Int,
            var listenedMs: Long,
        )

        val albumMap = linkedMapOf<String, AlbumAcc>()
        valid.forEach { event ->
            val albumRef = event.song.album ?: return@forEach
            val albumName = albumRef.name.trim()
            if (albumName.isEmpty()) return@forEach
            val key = albumRef.browseId?.takeIf { it.isNotBlank() } ?: albumName.lowercase(Locale.ROOT)
            val artist = event.song.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
            val thumb = event.song.thumbnailUrl?.takeIf { it.isNotBlank() }
            val acc = albumMap.getOrPut(key) {
                AlbumAcc(
                    name = albumName,
                    browseId = albumRef.browseId?.takeIf { it.isNotBlank() },
                    artistName = artist,
                    thumbnailUrl = thumb,
                    latestPlayedAtMs = event.playedAtMs,
                    count = 0,
                    listenedMs = 0L,
                )
            }
            if (acc.browseId == null && !albumRef.browseId.isNullOrBlank()) acc.browseId = albumRef.browseId
            if (event.playedAtMs >= acc.latestPlayedAtMs) {
                acc.latestPlayedAtMs = event.playedAtMs
                acc.name = albumName
                if (artist != null) acc.artistName = artist
                if (thumb != null) acc.thumbnailUrl = thumb
            } else {
                if (acc.artistName == null && artist != null) acc.artistName = artist
                if (acc.thumbnailUrl == null && thumb != null) acc.thumbnailUrl = thumb
            }
            acc.count += 1
            acc.listenedMs += event.listenedDurationMs
        }

        val topAlbums = albumMap.values
            .map { acc ->
                RankedAlbum(
                    name = acc.name,
                    browseId = acc.browseId,
                    artistName = acc.artistName,
                    thumbnailUrl = acc.thumbnailUrl,
                    playCount = acc.count,
                    totalListenedMs = acc.listenedMs,
                )
            }
            .sortedWith(compareByDescending<RankedAlbum> { it.playCount }.thenByDescending { it.totalListenedMs })
            .take(limit)

        return ReplaySummary(
            totalTracksPlayed = valid.size,
            totalListenedMs = totalMs,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            firstPlayedAtMs = firstPlayedAtMs,
        )
    }
}
