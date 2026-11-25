package com.music.musica.data.repository

import com.music.musica.core.model.Song

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
)

data class ReplaySummary(
    val totalTracksPlayed: Int = 0,
    val totalListenedMs: Long = 0L,
    val topSongs: List<RankedSong> = emptyList(),
    val topArtists: List<RankedArtist> = emptyList(),
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

        data class ArtistAcc(var browseId: String?, var count: Int, var listenedMs: Long)

        val artistMap = linkedMapOf<String, ArtistAcc>()
        valid.forEach { event ->
            val primary = event.song.artists.firstOrNull()
            val artistName = primary?.name?.takeIf { it.isNotBlank() } ?: event.song.artistText
            val acc = artistMap.getOrPut(artistName) { ArtistAcc(primary?.browseId, 0, 0L) }
            if (acc.browseId == null && primary?.browseId != null) acc.browseId = primary.browseId
            acc.count += 1
            acc.listenedMs += event.listenedDurationMs
        }

        val topArtists = artistMap.entries
            .map { (name, acc) ->
                RankedArtist(
                    name = name,
                    browseId = acc.browseId,
                    playCount = acc.count,
                    totalListenedMs = acc.listenedMs,
                )
            }
            .sortedWith(compareByDescending<RankedArtist> { it.playCount }.thenByDescending { it.totalListenedMs })
            .take(limit)

        return ReplaySummary(
            totalTracksPlayed = valid.size,
            totalListenedMs = totalMs,
            topSongs = topSongs,
            topArtists = topArtists,
        )
    }
}
