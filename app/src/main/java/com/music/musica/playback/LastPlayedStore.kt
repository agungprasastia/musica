package com.music.musica.playback

import android.content.Context
import android.util.Log
import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import com.music.musica.core.network.HttpClients
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class LastPlayedSnapshot(
    val version: Int = CURRENT_VERSION,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val savedAtMs: Long = 0L,
) {
    val currentSong: Song?
        get() = queue.getOrNull(currentIndex)

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Atomic JSON store for the user's last playback queue, active track, and timestamp.
 *
 * Caps persisted queues to [MAX_PERSISTED_TRACKS] tracks centered around `currentIndex`
 * so startup restoration stays instantaneous even for 1,000+ track playlists.
 */
class LastPlayedStore internal constructor(
    private val storageFile: File,
) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    @Synchronized
    fun save(snapshot: LastPlayedSnapshot) {
        if (snapshot.queue.isEmpty() || snapshot.currentIndex !in snapshot.queue.indices) {
            clear()
            return
        }
        val bounded = boundSnapshot(snapshot)
        runCatching {
            storageFile.parentFile?.mkdirs()
            val payload = HttpClients.json.encodeToString(bounded)
            val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmpFile.writeText(payload, Charsets.UTF_8)
            if (!tmpFile.renameTo(storageFile)) {
                // On platforms where renameTo does not overwrite an existing destination
                if (storageFile.exists()) storageFile.delete()
                if (!tmpFile.renameTo(storageFile)) {
                    storageFile.writeText(payload, Charsets.UTF_8)
                    tmpFile.delete()
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to persist last played snapshot: ${err.message}")
        }
    }

    @Synchronized
    fun load(): LastPlayedSnapshot? {
        if (!storageFile.isFile) return null
        return runCatching {
            val raw = storageFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            val parsed = HttpClients.json.decodeFromString<LastPlayedSnapshot>(raw)
            if (parsed.queue.isEmpty() || parsed.currentIndex !in parsed.queue.indices) {
                null
            } else {
                parsed.copy(positionMs = parsed.positionMs.coerceAtLeast(0L))
            }
        }.onFailure { err ->
            Log.w(TAG, "Ignoring unreadable last played snapshot: ${err.message}")
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        runCatching { storageFile.delete() }
    }

    companion object {
        private const val TAG = "MusicaLastPlayed"
        private const val FILE_NAME = "last_played.json"
        const val MAX_PERSISTED_TRACKS = 100
        private const val HISTORY_LEAD_WINDOW = 25

        internal fun boundSnapshot(
            snapshot: LastPlayedSnapshot,
            maxTracks: Int = MAX_PERSISTED_TRACKS,
        ): LastPlayedSnapshot {
            val queue = snapshot.queue
            if (queue.size <= maxTracks) {
                return snapshot.copy(positionMs = snapshot.positionMs.coerceAtLeast(0L))
            }
            val safeCurrent = snapshot.currentIndex.coerceIn(0, queue.lastIndex)
            val idealStart = (safeCurrent - HISTORY_LEAD_WINDOW).coerceAtLeast(0)
            val maxStart = (queue.size - maxTracks).coerceAtLeast(0)
            val windowStart = idealStart.coerceAtMost(maxStart)
            val windowEnd = (windowStart + maxTracks).coerceAtMost(queue.size)
            return snapshot.copy(
                queue = queue.subList(windowStart, windowEnd),
                currentIndex = safeCurrent - windowStart,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
            )
        }
    }
}
