package com.music.musica.data.repository

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryReplayAggregatorTest {

    private fun song(id: String, artist: String) = Song(
        videoId = id,
        title = "Track $id",
        artists = listOf(ArtistRef(artist, "UC_$artist")),
    )

    @Test
    fun summarize_filtersShortSkipsAndWindowAndRanksTopSongsAndArtists() {
        val events = listOf(
            PlayEvent(song("s1", "Niki"), playedAtMs = 1_000L, listenedDurationMs = 180_000L), // older than sinceMs
            PlayEvent(song("s1", "Niki"), playedAtMs = 5_000L, listenedDurationMs = 10_000L),  // skip < 15s
            PlayEvent(song("s1", "Niki"), playedAtMs = 6_000L, listenedDurationMs = 120_000L),
            PlayEvent(song("s1", "Niki"), playedAtMs = 7_000L, listenedDurationMs = 180_000L),
            PlayEvent(song("s2", "Niki"), playedAtMs = 8_000L, listenedDurationMs = 60_000L),
            PlayEvent(song("s3", "Tulus"), playedAtMs = 9_000L, listenedDurationMs = 240_000L),
        )

        val summary = HistoryReplayAggregator.summarize(events, sinceMs = 4_000L)
        assertEquals(4, summary.totalTracksPlayed)
        assertEquals(600_000L, summary.totalListenedMs)
        assertEquals("s1", summary.topSongs.first().song.videoId)
        assertEquals(2, summary.topSongs.first().playCount)
        assertEquals("Niki", summary.topArtists.first().name)
        assertEquals(3, summary.topArtists.first().playCount)
    }
}
