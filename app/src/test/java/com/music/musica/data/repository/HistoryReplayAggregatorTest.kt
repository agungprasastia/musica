package com.music.musica.data.repository

import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.Song
import com.music.musica.ui.components.formatMemberSinceMmYy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class HistoryReplayAggregatorTest {

    private fun song(
        id: String,
        artist: String,
        album: AlbumRef? = null,
        thumb: String? = null,
    ) = Song(
        videoId = id,
        title = "Track $id",
        artists = listOf(ArtistRef(artist, "UC_$artist")),
        album = album,
        thumbnailUrl = thumb,
    )

    @Test
    fun summarize_filtersShortSkipsAndWindowAndRanksTopSongsArtistsAndAlbums() {
        val albumA = AlbumRef("Nicole", "MPREb_nicole")
        val albumB = AlbumRef("Manusia", null)
        val events = listOf(
            PlayEvent(song("s1", "Niki", albumA, "https://img/s1.jpg"), playedAtMs = 1_000L, listenedDurationMs = 180_000L), // older than sinceMs
            PlayEvent(song("s1", "Niki", albumA, "https://img/s1.jpg"), playedAtMs = 5_000L, listenedDurationMs = 10_000L),  // skip < 15s
            PlayEvent(song("s1", "Niki", albumA, "https://img/s1.jpg"), playedAtMs = 6_000L, listenedDurationMs = 120_000L),
            PlayEvent(song("s1", "Niki", albumA, "https://img/s1.jpg"), playedAtMs = 7_000L, listenedDurationMs = 180_000L),
            PlayEvent(song("s2", "Niki", albumA, "https://img/s2.jpg"), playedAtMs = 8_000L, listenedDurationMs = 60_000L),
            PlayEvent(song("s3", "Tulus", albumB, "https://img/s3.jpg"), playedAtMs = 9_000L, listenedDurationMs = 240_000L),
        )

        val summary = HistoryReplayAggregator.summarize(events, sinceMs = 4_000L)
        assertEquals(4, summary.totalTracksPlayed)
        assertEquals(600_000L, summary.totalListenedMs)
        assertEquals(6_000L, summary.firstPlayedAtMs)
        assertEquals("s1", summary.topSongs.first().song.videoId)
        assertEquals(2, summary.topSongs.first().playCount)

        // Top Artist picks most-played song's thumbnail
        assertEquals("Niki", summary.topArtists.first().name)
        assertEquals(3, summary.topArtists.first().playCount)
        assertEquals("https://img/s1.jpg", summary.topArtists.first().thumbnailUrl)

        // Top Albums ranks by play count, then duration, preserving browseId & artist
        assertEquals(2, summary.topAlbums.size)
        assertEquals("Nicole", summary.topAlbums[0].name)
        assertEquals("MPREb_nicole", summary.topAlbums[0].browseId)
        assertEquals("Niki", summary.topAlbums[0].artistName)
        assertEquals(3, summary.topAlbums[0].playCount)
        assertEquals(360_000L, summary.topAlbums[0].totalListenedMs)
        assertEquals("Manusia", summary.topAlbums[1].name)
        assertEquals(1, summary.topAlbums[1].playCount)
    }

    @Test
    fun summarize_omitsBlankAlbumsAndMergesByCaseInsensitiveNameWhenBrowseIdMissing() {
        val events = listOf(
            PlayEvent(song("s1", "Artist A", null, "https://img/1.jpg"), playedAtMs = 1_000L, listenedDurationMs = 60_000L),
            PlayEvent(song("s2", "Artist A", AlbumRef("   ", null), "https://img/2.jpg"), playedAtMs = 2_000L, listenedDurationMs = 60_000L),
            PlayEvent(song("s3", "Artist B", AlbumRef("Night Visions", null), "https://img/3.jpg"), playedAtMs = 3_000L, listenedDurationMs = 90_000L),
            PlayEvent(song("s4", "Artist B", AlbumRef("night visions", null), "https://img/4.jpg"), playedAtMs = 4_000L, listenedDurationMs = 120_000L),
        )

        val summary = HistoryReplayAggregator.summarize(events)
        assertEquals(1, summary.topAlbums.size)
        assertEquals(2, summary.topAlbums.first().playCount)
        assertEquals(210_000L, summary.topAlbums.first().totalListenedMs)
        assertEquals("https://img/4.jpg", summary.topAlbums.first().thumbnailUrl)
    }

    @Test
    fun formatMemberSinceMmYy_formatsTwoDigitMonthAndYearAndHandlesInvalid() {
        val nov2026 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
        }.timeInMillis
        val jan2030 = Calendar.getInstance().apply {
            set(2030, Calendar.JANUARY, 5, 9, 0, 0)
        }.timeInMillis

        assertEquals("11/26", formatMemberSinceMmYy(nov2026))
        assertEquals("01/30", formatMemberSinceMmYy(jan2030))
        assertNull(formatMemberSinceMmYy(null))
        assertNull(formatMemberSinceMmYy(0L))
    }
}
