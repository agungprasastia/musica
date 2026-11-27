package com.music.musica.data.repository

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.MixEntry
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.TopPickMood
import com.music.musica.core.model.WatchTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class TopPicksEngineTest {

    private class FakeDataSource(
        var remoteHistory: List<Song> = emptyList(),
        var mixes: List<MixEntry> = emptyList(),
        val details: MutableMap<String, DetailPage> = mutableMapOf(),
        val watchQueues: MutableMap<String, List<Song>> = mutableMapOf(),
        val moodShelves: MutableMap<String, List<HomeShelf>> = mutableMapOf(),
    ) : TopPicksDataSource {
        override suspend fun loadRemoteHistory(): List<Song> = remoteHistory
        override suspend fun loadMixedForYou(isEnglishLocale: Boolean): List<MixEntry> = mixes
        override suspend fun loadDetail(browseId: String): DetailPage? = details[browseId]
        override suspend fun loadWatchQueue(target: WatchTarget, preferAudio: Boolean): Result<List<Song>> {
            val songs = watchQueues[target.playlistId] ?: return Result.failure(IllegalStateException("missing ${target.playlistId}"))
            return Result.success(songs)
        }
        override suspend fun loadMoodCategoryShelves(params: String): Result<List<HomeShelf>> {
            val shelves = moodShelves[params] ?: return Result.failure(IllegalStateException("missing mood $params"))
            return Result.success(shelves)
        }
    }

    private class FakePortraitLookup(
        private val portraits: Map<String, String> = emptyMap(),
    ) : ArtistPortraitLookup {
        override suspend fun photoFor(artistName: String, catalogueTitles: suspend () -> Collection<String>): String? =
            portraits[artistName]

        override fun seed(photos: Map<String, String>) = Unit
        override fun snapshot(): Map<String, String> = portraits
    }

    private fun song(id: String, title: String, artistName: String, artistId: String? = null): Song =
        Song(
            videoId = id,
            title = title,
            artists = listOf(ArtistRef(artistName, artistId)),
            thumbnailUrl = "https://img/$id.jpg",
        )

    @Test
    fun build_placesNewestSingleOrEpFirstAndUsesHybridPortraitFallback() = runTest {
        val ds = FakeDataSource(
            remoteHistory = listOf(
                song("h1", "Track 1", "Ave Mujica", "UC_ave"),
                song("h2", "Track 2", "Ave Mujica", "UC_ave"),
                song("h3", "Track 3", "RAISE A SUILEN", "UC_ras"),
                song("h4", "Track 4", "Justice", "UC_jus"),
                song("h5", "Track 5", "Various Artists", "UC_va"),
            ),
            mixes = listOf(
                MixEntry(ShelfItem.Playlist("VLRDTMAK_super", "My Supermix"), "My Supermix"),
                MixEntry(ShelfItem.Playlist("VLRDTMAK_disc", "Discover Mix"), "Discover Mix"),
                MixEntry(ShelfItem.Playlist("VLRDTMAK_new", "New Release Mix", "Ave Mujica, Justice", "https://img/nrm.jpg"), "New Release Mix"),
            ),
            details = mutableMapOf(
                "UC_ave" to DetailPage(
                    browseId = "UC_ave",
                    type = BrowseType.ARTIST,
                    title = "Ave Mujica",
                    thumbnailUrl = "https://ytm/ave_ytm.jpg",
                    monthlyListenerCount = "228K monthly audience",
                    radioTarget = WatchTarget("RDEM_ave"),
                    shuffleTarget = WatchTarget("RDAO_ave"),
                    sections = listOf(
                        HomeShelf(
                            title = "Albums",
                            items = listOf(
                                ShelfItem.Album("MPREb_old", "Old Comp", "2026", "https://img/old.jpg"),
                            ),
                        ),
                        HomeShelf(
                            title = "Singles & EPs",
                            items = listOf(
                                ShelfItem.Album("MPREb_ep", "Kami no Na wo", "EP • 2026", "https://img/ep.jpg"),
                            ),
                        ),
                        HomeShelf(
                            title = "Fans might also like",
                            items = listOf(
                                ShelfItem.Artist("UC_rel1", "MyGO!!!!!", null, "https://ytm/mygo.jpg"),
                                ShelfItem.Artist("UC_rel2", "Roselia", null, "https://ytm/roselia.jpg"),
                            ),
                        ),
                    ),
                ),
                "UC_ras" to DetailPage(
                    browseId = "UC_ras",
                    type = BrowseType.ARTIST,
                    title = "RAISE A SUILEN",
                    thumbnailUrl = "https://ytm/ras_ytm.jpg",
                    monthlyListenerCount = "228 rb audiens bulanan",
                    radioTarget = WatchTarget("RDEM_ras"),
                    shuffleTarget = WatchTarget("RDAO_ras"),
                ),
                "UC_jus" to DetailPage(
                    browseId = "UC_jus",
                    type = BrowseType.ARTIST,
                    title = "Justice",
                    thumbnailUrl = "https://ytm/jus_ytm.jpg",
                    radioTarget = WatchTarget("RDEM_jus"),
                    shuffleTarget = WatchTarget("RDAO_jus"),
                ),
            ),
        )

        // Only Ave Mujica has a verified HD portrait; RAISE A SUILEN & Justice fall back to YTM thumbnails!
        val portraits = FakePortraitLookup(
            mapOf("Ave Mujica" to "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages/ave/600x600cc.jpg"),
        )
        val engine = TopPicksEngine(ds, portraits, random = Random(42), countryCodeProvider = { "ID" })
        val picks = engine.build(
            homeShelves = emptyList(),
            local = TopPicksEngine.LocalTaste(),
            isSignedIn = true,
            isEnglishLocale = true,
            now = LocalDateTime.of(2026, 11, 27, 21, 0),
        )

        // Latest release is always index 0, and EP wins over bare-year compilation in the same year
        assertEquals(TopPickKind.LATEST_RELEASE, picks.first().kind)
        assertEquals("MPREb_ep", picks.first().browseId)
        assertEquals("Kami no Na wo", picks.first().title)

        // All 7 card kinds are present, and artists without iTunes portrait still render via YTM fallback
        val kinds = picks.map { it.kind }.toSet()
        assertTrue(TopPickKind.PERSONAL_STATION in kinds)
        assertTrue(TopPickKind.MOOD in kinds)
        assertTrue(TopPickKind.ARTIST_AND_SIMILAR in kinds)
        assertTrue(TopPickKind.FEATURED_ARTIST in kinds)
        assertTrue(TopPickKind.NEW_RELEASE_MIX in kinds)
        assertTrue(TopPickKind.DISCOVERY_STATION in kinds)

        val artistCards = picks.filter { it.kind == TopPickKind.ARTIST_AND_SIMILAR || it.kind == TopPickKind.FEATURED_ARTIST }
        assertEquals(3, artistCards.size)
        assertTrue(artistCards.all { it.images.isNotEmpty() })
        assertTrue(artistCards.none { it.artistName == "Various Artists" })
    }

    @Test
    fun build_freshGuestInstallEnrichesMoodWithHomeFallbackCards() = runTest {
        val ds = FakeDataSource()
        val engine = TopPicksEngine(ds, FakePortraitLookup(), random = Random(7), countryCodeProvider = { "ID" })
        val homeShelves = listOf(
            HomeShelf(
                title = "New albums & singles",
                items = listOf(
                    ShelfItem.Album("MPREb_1", "Album One", "Artist 1", "https://img/1.jpg"),
                    ShelfItem.Album("MPREb_2", "Album Two", "Artist 2", "https://img/2.jpg"),
                    ShelfItem.Playlist("VLPL_3", "Hits Playlist", "Curated", "https://img/3.jpg"),
                ),
            ),
        )

        val picks = engine.build(
            homeShelves = homeShelves,
            local = TopPicksEngine.LocalTaste(),
            isSignedIn = false,
            isEnglishLocale = true,
            now = LocalDateTime.of(2026, 11, 27, 10, 0),
        )

        assertTrue(picks.size >= 4)
        assertTrue(picks.any { it.kind == TopPickKind.MOOD })
        assertTrue(picks.first().isFallbackPick)
        assertEquals("Album One", picks.first().title)
    }

    @Test
    fun resolveQueue_discoveryStationFiltersHeardSongsAndFallbackSeedArtist() = runTest {
        val heardHistory = (1..5).map { i -> song("heard_$i", "Heard $i", "Seed Artist", "UC_seed") }
        val mixSongs = heardHistory + (1..15).map { i ->
            song("fresh_$i", "Fresh $i", "New Band $i", "UC_new_$i")
        } + listOf(song("same_artist_track", "B-Side", "Seed Artist", "UC_seed"))

        val ds = FakeDataSource(
            remoteHistory = heardHistory,
            watchQueues = mutableMapOf("RDEM_seed" to mixSongs),
        )
        val engine = TopPicksEngine(ds, FakePortraitLookup(), random = Random(11))
        val discoveryPick = TopPick(
            kind = TopPickKind.DISCOVERY_STATION,
            artistName = "Seed Artist",
            watch = WatchTarget("RDEM_seed"),
            excludeVideoIds = listOf("heard_1", "heard_2"),
        )

        val queue = engine.resolveQueue(discoveryPick).getOrThrow()
        assertEquals(15, queue.size)
        assertFalse(queue.any { it.videoId.startsWith("heard_") })
        assertFalse(queue.any { it.artistText.contains("Seed Artist") })
    }

    @Test
    fun resolveQueue_moodPicksBestScoredShelfAndAudioPreferredTracks() = runTest {
        val ds = FakeDataSource(
            moodShelves = mutableMapOf(
                TopPickMood.HEARTBREAK.params to listOf(
                    HomeShelf(
                        title = "General acoustic",
                        items = listOf(ShelfItem.Playlist("VLPL_gen", "Acoustic")),
                    ),
                    HomeShelf(
                        title = "Breakups & heartbreak Indonesia",
                        items = listOf(ShelfItem.Playlist("VLPL_hb", "Galau Indonesia")),
                    ),
                ),
            ),
            watchQueues = mutableMapOf(
                "PL_hb" to listOf(
                    song("hb_1", "Hati-Hati di Jalan", "Tulus"),
                    song("hb_2", "Sial", "Mahalini"),
                ),
            ),
        )
        val engine = TopPicksEngine(ds, FakePortraitLookup(), random = Random(3))
        val moodPick = TopPick(
            kind = TopPickKind.MOOD,
            mood = TopPickMood.HEARTBREAK,
            moodHints = listOf("indonesia"),
        )

        val queue = engine.resolveQueue(moodPick).getOrThrow()
        assertEquals(setOf("hb_1", "hb_2"), queue.map { it.videoId }.toSet())
    }

    @Test
    fun cacheStore_roundTripPreservesPicksSlotAndPhotos() {
        val stored = StoredTopPicks(
            identity = "signed:123",
            hourSlot = TopPicksCacheStore.hourSlotOf(LocalDateTime.of(2026, 11, 27, 9, 45)),
            languageTag = "id",
            picks = listOf(
                TopPick(
                    kind = TopPickKind.MOOD,
                    mood = TopPickMood.LOVE,
                    moodHints = listOf("indonesia"),
                ),
            ),
            artistPhotos = mapOf("v1|avemujica" to "https://is1-ssl.mzstatic.com/image/thumb/Features/test/600x600cc.jpg"),
        )
        assertEquals("2026-11-27T09", stored.hourSlot)
        val decoded = TopPicksCacheStore.decode(TopPicksCacheStore.encode(stored))
        assertNotNull(decoded)
        assertEquals(stored, decoded)
    }
}
