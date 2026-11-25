package com.music.musica.data.repository

import com.music.musica.core.model.ExploreCategory
import com.music.musica.core.model.ExploreFeed
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.MixEntry
import com.music.musica.core.model.MixMoodShelf
import com.music.musica.core.model.MixesFeed
import com.music.musica.core.model.ShelfItem

/**
 * Clean-room classification and curation helpers for the upgraded Explore (Duotone Sleeve Grid)
 * and Mixes (Blurred Station Tiles + Palette-Tinted Feature Carousel + Mood Shelves) screens.
 */
object ExploreAndMixesOrganizer {

    private val SIGNATURE_STATION_ORDER = listOf(
        "my supermix",
        "discover mix",
        "new release mix",
        "replay mix",
        "archive mix",
        "liked music",
    )

    private val MOOD_SUPERMIX_REGEX = Regex("""^(.+?)\s+supermix$""", RegexOption.IGNORE_CASE)
    private val NUMBERED_MIX_REGEX = Regex("""^(.+?)\s+mix\s*(\d+)$""", RegexOption.IGNORE_CASE)

    private const val NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"
    private const val CHARTS_BROWSE_ID = "FEmusic_charts"
    private const val NEW_RELEASES_ACCENT = 0xFFD94A5AL
    private const val CHARTS_ACCENT = 0xFF4B7BECL

    /**
     * Merges top Explore spotlight shortcuts (New Releases, Charts) with the complete
     * Moods & Genres directory into a single deduplicated category grid.
     */
    fun buildExploreFeed(
        exploreShelves: List<HomeShelf>,
        moodsShelves: List<HomeShelf>,
    ): ExploreFeed {
        val spotlight = exploreShelves
            .flatMap { it.items }
            .filterIsInstance<ShelfItem.Playlist>()
            .filter { it.browseId == NEW_RELEASES_BROWSE_ID || it.browseId == CHARTS_BROWSE_ID }
            .map { item ->
                val defaultColor = when (item.browseId) {
                    NEW_RELEASES_BROWSE_ID -> NEW_RELEASES_ACCENT
                    CHARTS_BROWSE_ID -> CHARTS_ACCENT
                    else -> null
                }
                ExploreCategory(
                    title = item.title,
                    browseId = item.browseId,
                    params = item.params,
                    stripeColorArgb = item.accentColorArgb ?: defaultColor,
                    thumbnailUrl = item.thumbnailUrl,
                )
            }

        val moods = moodsShelves
            .flatMap { it.items }
            .filterIsInstance<ShelfItem.Playlist>()
            .map { item ->
                ExploreCategory(
                    title = item.title,
                    browseId = item.browseId,
                    params = item.params,
                    stripeColorArgb = item.accentColorArgb,
                    thumbnailUrl = item.thumbnailUrl,
                )
            }

        val fallbackFromExplore = if (moods.isEmpty()) {
            exploreShelves
                .flatMap { it.items }
                .filterIsInstance<ShelfItem.Playlist>()
                .filter { it.thumbnailUrl.isNullOrBlank() }
                .map { item ->
                    ExploreCategory(
                        title = item.title,
                        browseId = item.browseId,
                        params = item.params,
                        stripeColorArgb = item.accentColorArgb,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
        } else {
            emptyList()
        }

        val merged = (spotlight + moods + fallbackFromExplore)
            .filter { it.title.isNotBlank() && it.browseId.isNotBlank() }
            .distinctBy { it.key }

        return ExploreFeed(categories = merged)
    }

    /**
     * Groups signed-in YouTube Music "Mixed for you" entries by English title into:
     * 1. Signature square station tiles (in canonical order)
     * 2. Listener's personalized featured mixes
     * 3. Per-mood horizontal shelves (Supermix first, followed by numbered mixes)
     */
    fun groupPersonalizedMixes(entries: List<MixEntry>): MixesFeed {
        val unique = entries.distinctBy { it.item.id }
        val rankedStations = mutableListOf<Pair<Int, ShelfItem>>()
        val featured = mutableListOf<ShelfItem>()
        val moodBuckets = LinkedHashMap<String, Pair<String, MutableList<Pair<Int, ShelfItem>>>>()

        fun addToMood(englishMood: String, order: Int, item: ShelfItem) {
            val normalizedKey = englishMood.trim().lowercase()
            moodBuckets.getOrPut(normalizedKey) { englishMood.trim() to mutableListOf() }.second += order to item
        }

        unique.forEach { entry ->
            val english = entry.englishTitle.trim()
            val stationRank = SIGNATURE_STATION_ORDER.indexOf(english.lowercase())
            val supermixMatch = MOOD_SUPERMIX_REGEX.matchEntire(english)
            val numberedMatch = NUMBERED_MIX_REGEX.matchEntire(english)

            when {
                stationRank >= 0 -> rankedStations += stationRank to entry.item
                supermixMatch != null -> addToMood(supermixMatch.groupValues[1], 0, entry.item)
                numberedMatch != null && !numberedMatch.groupValues[1].equals("My", ignoreCase = true) -> {
                    val idx = numberedMatch.groupValues[2].toIntOrNull() ?: 99
                    addToMood(numberedMatch.groupValues[1], idx, entry.item)
                }
                else -> featured += entry.item
            }
        }

        val englishById = unique.associate { it.item.id to it.englishTitle.trim() }
        val moodShelves = moodBuckets.map { (key, bucket) ->
            val (englishName, members) = bucket
            val ordered = members.sortedBy { it.first }
            MixMoodShelf(
                key = key,
                title = resolveLocalizedMoodHeading(englishName, ordered, englishById),
                items = ordered.map { it.second },
            )
        }

        return MixesFeed(
            stations = rankedStations.sortedBy { it.first }.map { it.second },
            featured = featured,
            moodShelves = moodShelves,
            isPersonalized = true,
        )
    }

    /**
     * Merges a sparse signed-in personalized feed (e.g. a brand-new account that only received
     * 1-2 stations like New Release Mix and no featured mixes or mood shelves yet) with live
     * curated mixes from Home & Explore so the Mixes tab is never an almost-empty void.
     * Rich personalized accounts are returned completely untouched.
     */
    fun enrichSparsePersonalizedMixes(
        personalized: MixesFeed,
        curated: MixesFeed,
    ): MixesFeed {
        if (personalized.featured.isNotEmpty() ||
            personalized.moodShelves.isNotEmpty() ||
            personalized.stations.size >= 4
        ) {
            return personalized
        }
        if (personalized.isEmpty) {
            return curated
        }

        val mergedStations = (personalized.stations + curated.stations)
            .distinctBy { it.id }
            .take(6)
        val stationIds = mergedStations.mapTo(mutableSetOf()) { it.id }
        val mergedFeatured = curated.featured
            .filter { it.id !in stationIds }
            .ifEmpty { curated.featured }
            .take(10)

        return MixesFeed(
            stations = mergedStations,
            featured = mergedFeatured,
            moodShelves = curated.moodShelves,
            isPersonalized = false,
        )
    }

    /**
     * Builds an immediately rich, playable Mixes layout for Guest Mode (or new accounts whose
     * FEmusic_mixed_for_you page is empty) using live artwork-bearing mixes & playlists from
     * Home and Explore feeds.
     */
    fun buildCuratedGuestMixes(
        homeShelves: List<HomeShelf>,
        exploreShelves: List<HomeShelf>,
    ): MixesFeed {
        val visualShelves = (homeShelves + exploreShelves)
            .map { shelf ->
                shelf.copy(
                    items = shelf.items.filter { item ->
                        !item.thumbnailUrl.isNullOrBlank() && item !is ShelfItem.Artist
                    },
                )
            }
            .filter { it.items.isNotEmpty() }

        val mixPreferred = visualShelves
            .filter { shelf ->
                val t = shelf.title.lowercase()
                t.contains("mix") || t.contains("radio") || t.contains("pilihan") || t.contains("pick")
            }
            .flatMap { it.items }

        val playlistAndAlbumPool = visualShelves
            .flatMap { it.items }
            .filter { it is ShelfItem.Playlist || it is ShelfItem.Album }

        val trackPool = visualShelves
            .flatMap { it.items }
            .filterIsInstance<ShelfItem.Track>()

        val stationCandidates = (mixPreferred + playlistAndAlbumPool + trackPool)
            .distinctBy { it.id }

        val stations = stationCandidates.take(6)
        val stationIds = stations.mapTo(mutableSetOf()) { it.id }

        val featureCandidates = (playlistAndAlbumPool + mixPreferred + trackPool)
            .distinctBy { it.id }
        val featured = featureCandidates
            .filter { it.id !in stationIds }
            .ifEmpty { featureCandidates }
            .take(10)

        val extraShelves = visualShelves
            .filter { it.title.isNotBlank() && it.items.size >= 2 }
            .distinctBy { it.title.lowercase() }
            .take(6)
            .mapIndexed { idx, shelf ->
                MixMoodShelf(
                    key = "curated_${idx}_${shelf.title.lowercase()}",
                    title = shelf.title,
                    items = shelf.items.distinctBy { it.id },
                )
            }

        return MixesFeed(
            stations = stations,
            featured = featured,
            moodShelves = extraShelves,
            isPersonalized = false,
        )
    }

    private fun resolveLocalizedMoodHeading(
        englishName: String,
        members: List<Pair<Int, ShelfItem>>,
        englishById: Map<String, String>,
    ): String {
        val isEnglish = members.all { (_, item) -> item.title.trim() == englishById[item.id] }
        if (isEnglish) return englishName

        val numberedTitles = members.filter { it.first > 0 }.map { it.second.title.trim() }
        val candidate = when {
            numberedTitles.size >= 2 -> numberedTitles.reduce { acc, next -> acc.commonPrefixWith(next) }
            numberedTitles.size == 1 -> numberedTitles.single().replace(Regex("""\s*\d+$"""), "")
            else -> members.firstOrNull()?.second?.title?.trim().orEmpty()
        }.trimEnd { !it.isLetter() }

        return candidate.takeIf { it.length >= 2 }
            ?: members.firstOrNull()?.second?.title?.trim()
            ?: englishName
    }
}
