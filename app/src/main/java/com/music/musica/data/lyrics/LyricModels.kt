package com.music.musica.data.lyrics

import androidx.compose.runtime.Immutable
import com.music.musica.core.model.LyricsSourceId
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val trailingSpace: Boolean = true,
)

@Immutable
@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val isBackgroundVocal: Boolean = false,
) {
    val isWordSynced: Boolean get() = words.isNotEmpty()
}

@Immutable
data class LyricsResult(
    val source: LyricsSourceId,
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val isWordSynced: Boolean,
    val offsetMs: Long = 0L,
)

data class LyricsQuery(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
)

interface LyricsProvider {
    val sourceId: LyricsSourceId
    suspend fun fetch(query: LyricsQuery): LyricsResult?
}
