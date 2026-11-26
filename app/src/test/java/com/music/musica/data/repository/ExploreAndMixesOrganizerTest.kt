package com.music.musica.data.repository

import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.MixEntry
import com.music.musica.core.model.ShelfItem
import com.music.musica.data.innertube.InnertubeParsers
import com.music.musica.data.innertube.MusicaInnertubeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreAndMixesOrganizerTest {

    @Test
    fun buildExploreFeed_placesNewReleasesAndChartsBeforeMoodsAndDeduplicates() {
        val explore = listOf(
            HomeShelf(
                title = "",
                items = listOf(
                    ShelfItem.Playlist("FEmusic_new_releases", "Rilis baru"),
                    ShelfItem.Playlist("FEmusic_charts", "Tangga lagu"),
                    ShelfItem.Playlist("FEmusic_moods_and_genres", "Suasana & genre"),
                ),
            ),
        )
        val moods = listOf(
            HomeShelf(
                title = "Suasana & momen",
                items = listOf(
                    ShelfItem.Playlist("FEmusic_moods_and_genres_category", "Santai", params = "p1", accentColorArgb = 0xFF88AAFF),
                    ShelfItem.Playlist("FEmusic_moods_and_genres_category", "Fokus", params = "p2", accentColorArgb = 0xFFFFCC66),
                    ShelfItem.Playlist("FEmusic_moods_and_genres_category", "Santai", params = "p1", accentColorArgb = 0xFF88AAFF),
                ),
            ),
        )

        val feed = ExploreAndMixesOrganizer.buildExploreFeed(explore, moods)
        assertEquals(listOf("Rilis baru", "Tangga lagu", "Santai", "Fokus"), feed.categories.map { it.title })
        assertTrue(feed.categories[0].stripeColorArgb != null)
        assertEquals(0xFF88AAFFL, feed.categories[2].stripeColorArgb)
    }

    @Test
    fun groupPersonalizedMixes_splitsStationsFeaturedAndLocalizedMoodShelves() {
        val entries = listOf(
            MixEntry(ShelfItem.Playlist("rd_rep", "Replay Mix", thumbnailUrl = "https://img/1"), "Replay Mix"),
            MixEntry(ShelfItem.Playlist("rd_sup", "Supermix Saya", thumbnailUrl = "https://img/2"), "My Supermix"),
            MixEntry(ShelfItem.Playlist("rd_mm1", "Mix Saya 1", thumbnailUrl = "https://img/3"), "My Mix 1"),
            MixEntry(ShelfItem.Playlist("rd_ind", "Indie Senja", thumbnailUrl = "https://img/4"), "Indie Senja"),
            MixEntry(ShelfItem.Playlist("rd_ch2", "Mix Santai 2", thumbnailUrl = "https://img/5"), "Chill Mix 2"),
            MixEntry(ShelfItem.Playlist("rd_ch0", "Supermix Santai", thumbnailUrl = "https://img/6"), "Chill Supermix"),
            MixEntry(ShelfItem.Playlist("rd_ch1", "Mix Santai 1", thumbnailUrl = "https://img/7"), "Chill Mix 1"),
        )

        val feed = ExploreAndMixesOrganizer.groupPersonalizedMixes(entries)
        assertTrue(feed.isPersonalized)
        // Canonical station order: My Supermix comes before Replay Mix
        assertEquals(listOf("Supermix Saya", "Replay Mix"), feed.stations.map { it.title })
        // My Mix 1 and custom titled mixes belong to featured carousel
        assertEquals(listOf("Mix Saya 1", "Indie Senja"), feed.featured.map { it.title })
        // Mood shelf puts Supermix first, then Mix 1, Mix 2, with localized Indonesian prefix heading
        assertEquals(1, feed.moodShelves.size)
        assertEquals("Mix Santai", feed.moodShelves.first().title)
        assertEquals(
            listOf("Supermix Santai", "Mix Santai 1", "Mix Santai 2"),
            feed.moodShelves.first().items.map { it.title },
        )
    }

    @Test
    fun buildCuratedGuestMixes_producesStationsFeaturedAndShelvesWithoutEmptyCards() {
        val home = listOf(
            HomeShelf(
                title = "Mix lagu",
                items = (1..8).map { i ->
                    ShelfItem.Playlist("VL$i", "Mix $i", subtitle = "Artist $i", thumbnailUrl = "https://img/$i")
                },
            ),
            HomeShelf(
                title = "Album populer",
                items = (9..14).map { i ->
                    ShelfItem.Album("MPRE$i", "Album $i", subtitle = "Artist $i", thumbnailUrl = "https://img/$i")
                },
            ),
        )
        val explore = listOf(
            HomeShelf(
                title = "Buttons",
                items = listOf(ShelfItem.Playlist("FEmusic_charts", "Charts", thumbnailUrl = null)),
            ),
        )

        val feed = ExploreAndMixesOrganizer.buildCuratedGuestMixes(home, explore)
        assertFalse(feed.isPersonalized)
        assertEquals(6, feed.stations.size)
        assertTrue(feed.featured.isNotEmpty())
        assertTrue(feed.stations.all { !it.thumbnailUrl.isNullOrBlank() })
        assertTrue(feed.featured.none { it.id in feed.stations.map { s -> s.id }.toSet() })
        assertEquals(2, feed.moodShelves.size)
    }

    @Test
    fun enrichSparsePersonalizedMixes_preservesPersonalizedStationAndFillsEmptyScreen() {
        val sparse = ExploreAndMixesOrganizer.groupPersonalizedMixes(
            listOf(
                MixEntry(
                    item = ShelfItem.Playlist("RDTMAK_new", "Rilis Baru", thumbnailUrl = "https://img/nr"),
                    englishTitle = "New Release Mix",
                ),
            ),
        )
        val curated = ExploreAndMixesOrganizer.buildCuratedGuestMixes(
            homeShelves = listOf(
                HomeShelf(
                    title = "Playlist trending",
                    items = (1..8).map { i ->
                        ShelfItem.Playlist("VL$i", "Mix $i", subtitle = "Hits $i", thumbnailUrl = "https://img/$i")
                    },
                ),
            ),
            exploreShelves = emptyList(),
        )

        val enriched = ExploreAndMixesOrganizer.enrichSparsePersonalizedMixes(sparse, curated)
        assertEquals("Rilis Baru", enriched.stations.first().title)
        assertEquals(6, enriched.stations.size)
        assertTrue(enriched.featured.isNotEmpty())
        assertTrue(enriched.moodShelves.isNotEmpty())
    }

    @Test
    fun enrichSparsePersonalizedMixes_keepsRichPersonalizedFeedUntouched() {
        val rich = ExploreAndMixesOrganizer.groupPersonalizedMixes(
            listOf(
                MixEntry(ShelfItem.Playlist("rd_sup", "My Supermix", thumbnailUrl = "https://img/1"), "My Supermix"),
                MixEntry(ShelfItem.Playlist("rd_mm1", "My Mix 1", thumbnailUrl = "https://img/2"), "My Mix 1"),
            ),
        )
        val curated = ExploreAndMixesOrganizer.buildCuratedGuestMixes(
            homeShelves = listOf(
                HomeShelf(
                    title = "Curated",
                    items = listOf(ShelfItem.Playlist("VL99", "Other", thumbnailUrl = "https://img/99")),
                ),
            ),
            exploreShelves = emptyList(),
        )

        val result = ExploreAndMixesOrganizer.enrichSparsePersonalizedMixes(rich, curated)
        assertTrue(result.isPersonalized)
        assertEquals(listOf("My Supermix"), result.stations.map { it.title })
        assertEquals(listOf("My Mix 1"), result.featured.map { it.title })
        assertTrue(result.moodShelves.isEmpty())
    }

    @Test
    fun innertubeLanguageAndDataSyncNormalizationMatchYouTubeExpectations() {
        assertEquals("id", MusicaInnertubeClient.normalizeLanguage("in"))
        assertEquals("he", MusicaInnertubeClient.normalizeLanguage("iw"))
        assertEquals("en", MusicaInnertubeClient.normalizeLanguage(""))
        assertEquals("brand123", MusicaInnertubeClient.normalizeDataSyncId("owner||brand123"))
        assertEquals("ownerOnly", MusicaInnertubeClient.normalizeDataSyncId("ownerOnly||"))
        assertEquals("plainId", MusicaInnertubeClient.normalizeDataSyncId("plainId"))
    }

    @Test
    fun parseTwoRowItemsDeep_recoversPlaylistOnlyWatchEndpointAndOverlayEndpoint() {
        val raw = """
            {
              "contents": [
                {
                  "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "My Supermix" }] },
                    "navigationEndpoint": {
                      "watchEndpoint": { "playlistId": "RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY" }
                    }
                  }
                },
                {
                  "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "Discover Mix" }] },
                    "thumbnailOverlay": {
                      "musicItemThumbnailOverlayRenderer": {
                        "content": {
                          "musicPlayButtonRenderer": {
                            "playNavigationEndpoint": {
                              "watchPlaylistEndpoint": { "playlistId": "RDTMAK5uy_n_5IN6hzAOwdVGJxzDFk" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val parsed = InnertubeParsers.parseTwoRowItemsDeep(Json.parseToJsonElement(raw).jsonObject)
        assertEquals(listOf("My Supermix", "Discover Mix"), parsed.map { it.title })
        assertTrue(parsed.all { it is ShelfItem.Playlist })
    }

    @Test
    fun buildCuratedGuestMixes_deduplicatesIdenticalStationTitlesAcrossDifferentIds() {
        val home = listOf(
            HomeShelf(
                title = "Pilihan Cepat",
                items = listOf(
                    ShelfItem.Playlist("VL_a", "Laut Kidul", thumbnailUrl = "https://img/a"),
                    ShelfItem.Playlist("VL_b", "laut kidul ", thumbnailUrl = "https://img/b"),
                    ShelfItem.Playlist("VL_c", "Kembali Pulang", thumbnailUrl = "https://img/c"),
                ),
            ),
        )
        val feed = ExploreAndMixesOrganizer.buildCuratedGuestMixes(home, emptyList())
        assertEquals(listOf("Laut Kidul", "Kembali Pulang"), feed.stations.map { it.title })
    }
}
