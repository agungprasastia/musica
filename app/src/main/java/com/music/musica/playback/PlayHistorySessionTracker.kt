package com.music.musica.playback

import com.music.musica.core.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates live playback-history persistence so every played track appears in Listening History
 * immediately when playback starts, while its `listenedDurationMs` is updated in-place as playback
 * progresses (no duplicate rows on pause/resume, and no lost plays when only one song is played).
 */
class PlayHistorySessionTracker(
    private val onInsertSession: suspend (song: Song, playedAtMs: Long, initialListenedMs: Long) -> Long,
    private val onUpdateDuration: suspend (rowId: Long, listenedMs: Long) -> Unit,
) {
    private val mutex = Mutex()
    private val activeRows = LinkedHashMap<Long, Long>()

    suspend fun onSessionStarted(
        sessionToken: Long,
        song: Song,
        playedAtMs: Long = System.currentTimeMillis(),
    ): Long = mutex.withLock {
        activeRows[sessionToken]?.let { return@withLock it }
        val rowId = onInsertSession(song, playedAtMs, 0L)
        if (rowId > 0L) {
            activeRows[sessionToken] = rowId
        }
        rowId
    }

    suspend fun onSessionProgress(
        sessionToken: Long,
        song: Song,
        listenedMs: Long,
        finalize: Boolean = false,
        playedAtMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val safeMs = listenedMs.coerceAtLeast(0L)
        val existingRowId = activeRows[sessionToken]
        if (existingRowId != null) {
            onUpdateDuration(existingRowId, safeMs)
            if (finalize) {
                activeRows.remove(sessionToken)
            }
        } else if (safeMs > 0L) {
            val insertedId = onInsertSession(song, playedAtMs, safeMs)
            if (!finalize && insertedId > 0L) {
                activeRows[sessionToken] = insertedId
            }
        }
    }

    suspend fun clearActiveSessions() = mutex.withLock {
        activeRows.clear()
    }
}
