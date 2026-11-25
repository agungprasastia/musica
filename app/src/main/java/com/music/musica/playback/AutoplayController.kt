package com.music.musica.playback

import com.music.musica.core.model.Song
import com.music.musica.data.repository.YtMusicRepository

/**
 * Fetches related tracks from YouTube Music's Up-Next radio endpoint when the queue nears its end.
 */
class AutoplayController(
    private val ytMusicRepository: YtMusicRepository,
) {
    private var lastSeedVideoId: String? = null

    suspend fun fetchNextSuggestions(seed: Song, existingQueue: List<Song>): List<Song> {
        if (seed.videoId == lastSeedVideoId && existingQueue.size > 1) return emptyList()
        val existingIds = existingQueue.map { it.videoId }.toSet()
        val fetched = ytMusicRepository.upNext(seed.videoId, seed.playlistId)
            .filter { it.videoId != seed.videoId && it.videoId !in existingIds }
            .take(15)
        if (fetched.isNotEmpty()) {
            lastSeedVideoId = seed.videoId
        }
        return fetched
    }

    fun reset() {
        lastSeedVideoId = null
    }
}
