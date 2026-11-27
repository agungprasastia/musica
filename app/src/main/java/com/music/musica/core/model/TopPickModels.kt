package com.music.musica.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * YouTube Music `next` watch queue target behind a Top Picks station card.
 * Mixes and playlist radios only require [playlistId]; artist Mix and Shuffle actions
 * also include a seed [videoId] and decoded [params].
 */
@Immutable
@Serializable
data class WatchTarget(
    val playlistId: String,
    val videoId: String? = null,
    val params: String? = null,
)

/**
 * Distinct editorial & station card formats in Musica's Home "Top Picks for You" row.
 */
@Serializable
enum class TopPickKind {
    /** Newest single, EP, or album from one of the listener's top artists. Tap opens DetailScreen. */
    LATEST_RELEASE,

    /** Personalized station blended from My Supermix (or top track radio) + recent history radios. */
    PERSONAL_STATION,

    /** Time-of-day mood station chosen from the 7 core moods with animated vector emblem. */
    MOOD,

    /** Artist + similar artists Mix with vivid backdrop and 3-circle portrait collage. */
    ARTIST_AND_SIMILAR,

    /** Featured artist full-bleed portrait card that starts their catalog shuffle/mix station. */
    FEATURED_ARTIST,

    /** Personalized New Release Mix playlist station with blurred cover reflection. */
    NEW_RELEASE_MIX,

    /** Discovery Station blended from Discover Mix (or artist Mix) with already-heard tracks filtered out. */
    DISCOVERY_STATION,
}

/**
 * Seven core time-of-day moods for the "Mood for You" Top Pick card.
 * [params] is YouTube Music's language-independent `FEmusic_moods_and_genres_category` id,
 * while [shelfWords] steer playlist selection inside shared categories.
 */
@Serializable
enum class TopPickMood(
    val params: String,
    val shelfWords: List<String> = emptyList(),
) {
    ENERGY(MOOD_ENERGIZE),
    RELAX(MOOD_CHILL, listOf("unwind", "peaceful", "relaxing", "laidback", "santai")),
    FEELING_BLUE(MOOD_SAD, listOf("sad", "melanchol", "lonely", "galau", "sedih")),
    FEEL_GOOD(MOOD_FEEL_GOOD),
    FOCUS(MOOD_FOCUS),
    HEARTBREAK(MOOD_SAD, listOf("heartbreak", "breakup", "break-up", "broken", "heartache", "patah hati")),
    LOVE(MOOD_ROMANCE),
    ;

    companion object {
        const val CATEGORY_BROWSE_ID = "FEmusic_moods_and_genres_category"
    }
}

private const val MOOD_CHILL = "ggMPOg1uX1JOQWZFeDByc2Jm"
private const val MOOD_ENERGIZE = "ggMPOg1uX2lRZUZiMnNrQnJW"
private const val MOOD_FEEL_GOOD = "ggMPOg1uXzZQbDB5eThLRTQ3"
private const val MOOD_FOCUS = "ggMPOg1uX0NvNGNhWThMYWRh"
private const val MOOD_ROMANCE = "ggMPOg1uX0FzQ2FhZWtUY211"
private const val MOOD_SAD = "ggMPOg1uX0JLQ0gySWZKZVY1"

/**
 * A single card in the "Top Picks for You" carousel.
 * Station queues are resolved lazily on tap so rendering the row stays lightweight.
 */
@Immutable
@Serializable
data class TopPick(
    val kind: TopPickKind,
    val title: String = "",
    val subtitle: String = "",
    val artistName: String? = null,
    val artistBrowseId: String? = null,
    val images: List<String> = emptyList(),
    val browseId: String? = null,
    val watch: WatchTarget? = null,
    val mood: TopPickMood? = null,
    val excludeVideoIds: List<String> = emptyList(),
    val moodHints: List<String> = emptyList(),
    val fallbackParams: String? = null,
    val isFallbackPick: Boolean = false,
) {
    /** Stable key used for Compose LazyRow / Pager keys and per-card tap-loading feedback. */
    val stableKey: String
        get() = buildString {
            append(kind.name)
            append(':')
            append(browseId ?: artistBrowseId ?: watch?.playlistId ?: mood?.name ?: title)
        }
}
