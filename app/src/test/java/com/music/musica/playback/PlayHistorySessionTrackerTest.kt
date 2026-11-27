package com.music.musica.playback

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayHistorySessionTrackerTest {

    private data class FakeRow(
        val id: Long,
        val videoId: String,
        val playedAtMs: Long,
        var listenedDurationMs: Long,
    )

    private fun song(id: String) = Song(
        videoId = id,
        title = "Song $id",
        artists = listOf(ArtistRef("Artist $id")),
    )

    @Test
    fun startingSessionInsertsImmediatelyAndUpdatesInPlaceWithoutDuplicates() = runTest {
        val rows = mutableListOf<FakeRow>()
        var nextId = 1L
        val tracker = PlayHistorySessionTracker(
            onInsertSession = { song, playedAtMs, initialMs ->
                val row = FakeRow(nextId++, song.videoId, playedAtMs, initialMs)
                rows += row
                row.id
            },
            onUpdateDuration = { rowId, listenedMs ->
                rows.first { it.id == rowId }.listenedDurationMs = listenedMs
            },
        )

        val track = song("v1")
        val rowId = tracker.onSessionStarted(sessionToken = 1L, song = track, playedAtMs = 1_000L)
        assertEquals(1L, rowId)
        assertEquals(1, rows.size)
        assertEquals(0L, rows[0].listenedDurationMs)

        // Duplicate start for same token does not create a second row
        val sameRowId = tracker.onSessionStarted(sessionToken = 1L, song = track, playedAtMs = 2_000L)
        assertEquals(1L, sameRowId)
        assertEquals(1, rows.size)

        // Live 5s / pause checkpoints accumulate on the exact same row
        tracker.onSessionProgress(sessionToken = 1L, song = track, listenedMs = 10_000L, finalize = false)
        tracker.onSessionProgress(sessionToken = 1L, song = track, listenedMs = 25_000L, finalize = false)
        tracker.onSessionProgress(sessionToken = 1L, song = track, listenedMs = 45_000L, finalize = true)

        assertEquals(1, rows.size)
        assertEquals(45_000L, rows[0].listenedDurationMs)

        // A new session token (repeat play) inserts a second history row
        tracker.onSessionStarted(sessionToken = 2L, song = track, playedAtMs = 90_000L)
        tracker.onSessionProgress(sessionToken = 2L, song = track, listenedMs = 30_000L, finalize = true)
        assertEquals(2, rows.size)
        assertEquals(30_000L, rows[1].listenedDurationMs)
    }

    @Test
    fun clearActiveSessionsDetachesInFlightToken() = runTest {
        val rows = mutableListOf<FakeRow>()
        var nextId = 1L
        val tracker = PlayHistorySessionTracker(
            onInsertSession = { song, playedAtMs, initialMs ->
                val row = FakeRow(nextId++, song.videoId, playedAtMs, initialMs)
                rows += row
                row.id
            },
            onUpdateDuration = { rowId, listenedMs ->
                rows.first { it.id == rowId }.listenedDurationMs = listenedMs
            },
        )

        val track = song("v1")
        tracker.onSessionStarted(sessionToken = 7L, song = track, playedAtMs = 1_000L)
        rows.clear() // Simulate user clearing play_history table
        tracker.clearActiveSessions()

        // Subsequent progress creates a clean row rather than referencing deleted id
        tracker.onSessionProgress(sessionToken = 7L, song = track, listenedMs = 18_000L, finalize = false)
        assertEquals(1, rows.size)
        assertEquals(18_000L, rows[0].listenedDurationMs)
    }
}
