package com.music.musica.data.repository

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.MixEntry
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.TopPickMood
import com.music.musica.core.model.WatchTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDateTime
import java.util.Locale
import kotlin.random.Random

interface TopPicksDataSource {
    suspend fun loadRemoteHistory(): List<Song>
    suspend fun loadMixedForYou(isEnglishLocale: Boolean): List<MixEntry>
    suspend fun loadDetail(browseId: String): DetailPage?
    suspend fun loadWatchQueue(target: WatchTarget, preferAudio: Boolean = false): Result<List<Song>>
    suspend fun loadMoodCategoryShelves(params: String): Result<List<HomeShelf>>
}

class YtMusicTopPicksDataSource(
    private val ytMusicRepository: YtMusicRepository,
) : TopPicksDataSource {
    override suspend fun loadRemoteHistory(): List<Song> =
        ytMusicRepository.loadRemoteHistory().getOrDefault(emptyList())

    override suspend fun loadMixedForYou(isEnglishLocale: Boolean): List<MixEntry> =
        ytMusicRepository.loadMixedForYouKeyed(isEnglishLocale).getOrDefault(emptyList())

    override suspend fun loadDetail(browseId: String): DetailPage? =
        ytMusicRepository.loadDetail(browseId).getOrNull()

    override suspend fun loadWatchQueue(target: WatchTarget, preferAudio: Boolean): Result<List<Song>> =
        ytMusicRepository.loadWatchQueue(target, preferAudio)

    override suspend fun loadMoodCategoryShelves(params: String): Result<List<HomeShelf>> =
        ytMusicRepository.loadMoodCategoryShelves(params)
}

/**
 * Builds Musica's personalized "Top Picks for You" editorial & station cards and resolves
 * fresh shuffled station queues on tap.
 *
 * Signals merged:
 * - YouTube Music listening history (signed-in) + Musica's local Room Replay chart
 * - Top 3 weighted-sampled artists' landing pages (latest release, Mix/Shuffle targets,
 *   monthly listeners, related "Fans might also like" artists)
 * - Hybrid portraits (verified HD portrait -> YouTube Music artist thumbnail fallback)
 * - Personalized YouTube Music mixes (My Supermix, Discover Mix, New Release Mix)
 * - Time-of-day core Mood station
 * - Graceful curated Home fallback on fresh guest installs with zero history
 */
class TopPicksEngine(
    private val dataSource: TopPicksDataSource,
    private val portraitLookup: ArtistPortraitLookup,
    private val random: Random = Random.Default,
    private val countryCodeProvider: () -> String = { Locale.getDefault().country },
) {

    data class LocalTaste(
        val artists: List<ArtistRef> = emptyList(),
        val songs: List<Song> = emptyList(),
    )

    private enum class MixSlot { SUPERMIX, DISCOVER, NEW_RELEASE }

    private data class RelatedArtistCandidate(
        val name: String,
        val browseId: String,
        val fallbackThumbnailUrl: String?,
    )

    private class ArtistCard(
        val page: DetailPage,
        val browseId: String,
        fallbackName: String,
    ) {
        val name: String = page.title.ifBlank { fallbackName }

        val related: List<RelatedArtistCandidate> by lazy {
            val ownKey = ArtistPortraitResolver.matchKey(name)
            page.sections
                .firstOrNull { shelf ->
                    shelf.items.isNotEmpty() && shelf.items.all { it is ShelfItem.Artist }
                }
                ?.items
                .orEmpty()
                .filterIsInstance<ShelfItem.Artist>()
                .filter { it.browseId != browseId && it.browseId.startsWith("UC") }
                .filter {
                    val key = ArtistPortraitResolver.matchKey(it.title)
                    key.isNotEmpty() && key != ownKey
                }
                .distinctBy { ArtistPortraitResolver.matchKey(it.title) }
                .take(RELATED_CANDIDATES)
                .map { RelatedArtistCandidate(it.title, it.browseId, it.thumbnailUrl) }
        }
    }

    suspend fun build(
        homeShelves: List<HomeShelf>,
        local: LocalTaste,
        isSignedIn: Boolean,
        isEnglishLocale: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<TopPick> = coroutineScope {
        val historyAsync = async {
            if (isSignedIn) dataSource.loadRemoteHistory() else emptyList()
        }
        val mixesAsync = async {
            findMixes(homeShelves, isSignedIn, isEnglishLocale)
        }

        val remoteHistory = historyAsync.await()
        val rankedArtists = weightedSample(
            ranked = rankArtists(remoteHistory, local.artists).take(ARTIST_POOL),
            count = SAMPLED_ARTISTS,
        )

        val artistPages = rankedArtists.map { ref ->
            async {
                val id = ref.browseId ?: return@async null
                dataSource.loadDetail(id)?.let { page -> ArtistCard(page, id, ref.name) }
            }
        }.awaitAll()
            .filterNotNull()
            .distinctBy { ArtistPortraitResolver.matchKey(it.name) }

        val portraits = resolveHybridPortraits(artistPages)
        val mixes = mixesAsync.await()
        val combinedHistory = (remoteHistory + local.songs).distinctBy { it.videoId }

        val lead = buildLatestRelease(artistPages)
        val body = buildList {
            buildPersonalStation(mixes, remoteHistory, local)?.let(::add)
            add(buildMoodPick(now))
            artistPages.getOrNull(0)?.let { buildArtistAndSimilar(it, portraits) }?.let(::add)
            artistPages.getOrNull(1)?.let { buildFeaturedArtist(it, portraits) }?.let(::add)
            mixes[MixSlot.NEW_RELEASE]?.let { mix ->
                add(
                    TopPick(
                        kind = TopPickKind.NEW_RELEASE_MIX,
                        title = mix.title,
                        subtitle = mix.subtitle.orEmpty(),
                        images = listOfNotNull(mix.thumbnailUrl),
                        watch = mix.toWatchTarget(),
                    ),
                )
            }
            artistPages.getOrNull(2)?.let { buildArtistAndSimilar(it, portraits) }?.let(::add)
            buildDiscoveryStation(mixes, artistPages, combinedHistory)?.let(::add)
        }.shuffled(random)

        val personalized = listOfNotNull(lead) + body

        // Fresh guest install with zero listening history: enrich past the single Mood card
        // with curated editorial picks from Home's first rich shelf so Home never looks sparse.
        if (personalized.size <= 1) {
            val fallbacks = buildGuestFallbackPicks(homeShelves)
            if (fallbacks.isNotEmpty()) {
                return@coroutineScope listOf(fallbacks.first()) + personalized + fallbacks.drop(1)
            }
        }
        personalized
    }

    /**
     * Resolves a fresh playback queue for any station Top Pick on tap.
     * Never called for [TopPickKind.LATEST_RELEASE], which navigates to DetailScreen.
     */
    suspend fun resolveQueue(
        pick: TopPick,
        localHistorySongs: List<Song> = emptyList(),
    ): Result<List<Song>> {
        if (pick.kind == TopPickKind.MOOD) {
            return resolveMoodQueue(pick)
        }
        val target = pick.watch
            ?: return Result.failure(IllegalStateException("No watch target behind ${pick.kind}"))
        if (pick.kind == TopPickKind.PERSONAL_STATION || pick.kind == TopPickKind.DISCOVERY_STATION) {
            return resolveFreshStationQueue(pick, target, localHistorySongs)
        }
        return dataSource.loadWatchQueue(target, preferAudio = false)
    }

    private suspend fun resolveFreshStationQueue(
        pick: TopPick,
        target: WatchTarget,
        localHistorySongs: List<Song>,
    ): Result<List<Song>> = coroutineScope {
        val mixAsync = async { dataSource.loadWatchQueue(target, preferAudio = false) }
        val remoteHistory = dataSource.loadRemoteHistory()
        val combinedHistory = (remoteHistory + localHistorySongs).distinctBy { it.videoId }
        val seeds = combinedHistory.take(SEED_POOL).shuffled(random).take(SEED_RADIOS)
        val radioTasks = seeds.map { seed ->
            async {
                dataSource.loadWatchQueue(
                    WatchTarget(playlistId = "RDAMVM${seed.videoId}", videoId = seed.videoId),
                    preferAudio = false,
                ).getOrDefault(emptyList())
            }
        }

        val mix = mixAsync.await().getOrElse { failure ->
            if (seeds.isEmpty()) return@coroutineScope Result.failure(failure)
            emptyList()
        }
        val around = radioTasks.awaitAll().flatten()

        val isDiscovery = pick.kind == TopPickKind.DISCOVERY_STATION
        val heardIds = if (isDiscovery) {
            combinedHistory.mapTo(HashSet()) { it.videoId }.apply { addAll(pick.excludeVideoIds) }
        } else {
            emptySet()
        }
        val excludedArtist = pick.artistName?.takeIf { isDiscovery }?.lowercase(Locale.ROOT)

        fun shouldKeep(song: Song): Boolean =
            song.videoId !in heardIds &&
                (excludedArtist == null || !song.artistText.lowercase(Locale.ROOT).contains(excludedArtist))

        val core = mix.filter(::shouldKeep).shuffled(random).take(STATION_FROM_MIX)
        val coreIds = core.mapTo(HashSet()) { it.videoId }
        val extra = around
            .filter { shouldKeep(it) && it.videoId !in coreIds }
            .distinctBy { it.videoId }
            .shuffled(random)
            .take((STATION_SIZE - core.size).coerceAtLeast(0))

        val station = (core + extra).shuffled(random)
        when {
            station.size >= MIN_FILTERED_QUEUE -> Result.success(station)
            mix.isNotEmpty() -> Result.success(mix.shuffled(random))
            station.isNotEmpty() -> Result.success(station)
            else -> Result.failure(IllegalStateException("${pick.kind} queue came back empty"))
        }
    }

    private suspend fun resolveMoodQueue(pick: TopPick): Result<List<Song>> = coroutineScope {
        val mood = pick.mood
            ?: return@coroutineScope Result.failure(IllegalStateException("Mood card missing mood"))
        val shelves = dataSource.loadMoodCategoryShelves(mood.params)
            .getOrElse { return@coroutineScope Result.failure(it) }

        fun playlistsOf(shelf: HomeShelf): List<ShelfItem.Playlist> =
            shelf.items.filterIsInstance<ShelfItem.Playlist>()
                .filter {
                    it.browseId.startsWith("VL") ||
                        it.browseId.startsWith("RD") ||
                        it.browseId.startsWith("PL")
                }

        val candidates = shelves.filter { playlistsOf(it).isNotEmpty() }
        if (candidates.isEmpty()) {
            return@coroutineScope Result.failure(IllegalStateException("No playlists found for $mood"))
        }

        fun scoreShelf(shelf: HomeShelf): Int {
            val lower = shelf.title.lowercase(Locale.ROOT)
            return 2 * mood.shelfWords.count { it in lower } +
                pick.moodHints.count { it.isNotBlank() && it in lower }
        }

        val bestScore = candidates.maxOf(::scoreShelf)
        val topShelves = candidates.filter { scoreShelf(it) == bestScore }
        val chosenShelf = topShelves[random.nextInt(topShelves.size)]
        val chosenPlaylists = playlistsOf(chosenShelf)
            .take(6)
            .shuffled(random)
            .take(MOOD_PLAYLIST_COUNT)

        val tracks = chosenPlaylists.map { playlist ->
            async {
                val playlistId = playlist.browseId.removePrefix("VL")
                dataSource.loadWatchQueue(WatchTarget(playlistId = playlistId), preferAudio = true)
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: dataSource.loadDetail(playlist.browseId)?.tracks.orEmpty()
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.videoId }
            .shuffled(random)
            .take(MOOD_QUEUE_LIMIT)

        if (tracks.isEmpty()) {
            Result.failure(IllegalStateException("$mood playlists returned no tracks"))
        } else {
            Result.success(tracks)
        }
    }

    private suspend fun resolveHybridPortraits(artists: List<ArtistCard>): Map<String, String> = coroutineScope {
        val lookupCatalogues = LinkedHashMap<String, suspend () -> Collection<String>>()
        val fallbacks = LinkedHashMap<String, String>()

        artists.forEach { artist ->
            lookupCatalogues.putIfAbsent(artist.name) { ArtistPortraitResolver.titlesOf(artist.page) }
            artist.page.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumb ->
                fallbacks.putIfAbsent(artist.name, thumb)
            }
            artist.related.forEach { rel ->
                lookupCatalogues.putIfAbsent(rel.name) {
                    dataSource.loadDetail(rel.browseId)?.let(ArtistPortraitResolver::titlesOf).orEmpty()
                }
                rel.fallbackThumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumb ->
                    fallbacks.putIfAbsent(rel.name, thumb)
                }
            }
        }

        val limiter = Semaphore(PORTRAIT_CONCURRENCY)
        val resolved = lookupCatalogues.map { (name, catalogueProvider) ->
            async {
                val portrait = limiter.withPermit {
                    runCatching { portraitLookup.photoFor(name, catalogueProvider) }.getOrNull()
                }
                val chosen = portrait ?: fallbacks[name]
                chosen?.let { name to it }
            }
        }.awaitAll().filterNotNull().toMap()

        resolved
    }

    private fun buildLatestRelease(artists: List<ArtistCard>): TopPick? {
        data class Candidate(
            val rank: Int,
            val artist: ArtistCard,
            val item: ShelfItem.Album,
            val year: Int,
            val isSingleOrEp: Boolean,
        )

        val candidates = artists.flatMapIndexed { rank, artist ->
            artist.page.sections
                .mapNotNull { shelf ->
                    shelf.items.firstOrNull() as? ShelfItem.Album
                }
                .filter { it.browseId.startsWith("MPRE") }
                .map { album ->
                    val meta = album.subtitle.orEmpty()
                    val year = yearOf(meta).takeIf { it > 0 } ?: album.year?.toIntOrNull() ?: 0
                    Candidate(
                        rank = rank,
                        artist = artist,
                        item = album,
                        year = year,
                        isSingleOrEp = SINGLE_OR_EP_REGEX.containsMatchIn(meta),
                    )
                }
        }

        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.year }
                .thenBy { it.isSingleOrEp }
                .thenByDescending { it.rank },
        ) ?: return null

        return TopPick(
            kind = TopPickKind.LATEST_RELEASE,
            title = best.item.title,
            subtitle = best.item.subtitle.orEmpty(),
            artistName = best.artist.name,
            artistBrowseId = best.artist.browseId,
            images = listOfNotNull(best.item.thumbnailUrl),
            browseId = best.item.browseId,
        )
    }

    private fun buildPersonalStation(
        mixes: Map<MixSlot, ShelfItem>,
        remoteHistory: List<Song>,
        local: LocalTaste,
    ): TopPick? {
        val target = mixes[MixSlot.SUPERMIX]?.toWatchTarget()
            ?: (local.songs.firstOrNull() ?: remoteHistory.firstOrNull())?.let { seed ->
                WatchTarget(playlistId = "RDAMVM${seed.videoId}", videoId = seed.videoId)
            }
            ?: return null
        return TopPick(kind = TopPickKind.PERSONAL_STATION, watch = target)
    }

    private fun buildMoodPick(now: LocalDateTime): TopPick =
        TopPick(
            kind = TopPickKind.MOOD,
            mood = moodFor(now),
            moodHints = regionHints(countryCodeProvider()),
        )

    private fun buildArtistAndSimilar(
        artist: ArtistCard,
        portraits: Map<String, String>,
    ): TopPick? {
        val radio = artist.page.radioTarget ?: artist.page.shuffleTarget ?: return null
        val primaryFace = portraits[artist.name] ?: artist.page.thumbnailUrl ?: return null
        val relatedFaces = artist.related
            .mapNotNull { rel -> portraits[rel.name] ?: rel.fallbackThumbnailUrl }
            .filter { it != primaryFace }
            .distinct()
            .take(2)
        return TopPick(
            kind = TopPickKind.ARTIST_AND_SIMILAR,
            title = artist.name,
            artistName = artist.name,
            artistBrowseId = artist.browseId,
            images = listOf(primaryFace) + relatedFaces,
            watch = radio,
        )
    }

    private fun buildFeaturedArtist(
        artist: ArtistCard,
        portraits: Map<String, String>,
    ): TopPick? {
        val shuffle = artist.page.shuffleTarget ?: artist.page.radioTarget ?: return null
        val face = portraits[artist.name] ?: artist.page.thumbnailUrl ?: return null
        return TopPick(
            kind = TopPickKind.FEATURED_ARTIST,
            title = artist.name,
            subtitle = artist.page.monthlyListenerCount ?: artist.page.subscriberCountText.orEmpty(),
            artistName = artist.name,
            artistBrowseId = artist.browseId,
            images = listOf(face),
            watch = shuffle,
        )
    }

    private fun buildDiscoveryStation(
        mixes: Map<MixSlot, ShelfItem>,
        artists: List<ArtistCard>,
        combinedHistory: List<Song>,
    ): TopPick? {
        mixes[MixSlot.DISCOVER]?.toWatchTarget()?.let { watch ->
            return TopPick(kind = TopPickKind.DISCOVERY_STATION, watch = watch)
        }
        val seedArtist = artists.firstOrNull() ?: return null
        val radio = seedArtist.page.radioTarget ?: return null
        return TopPick(
            kind = TopPickKind.DISCOVERY_STATION,
            artistName = seedArtist.name,
            watch = radio,
            excludeVideoIds = combinedHistory.take(200).map { it.videoId },
        )
    }

    private fun buildGuestFallbackPicks(homeShelves: List<HomeShelf>): List<TopPick> {
        val candidateShelf = homeShelves.firstOrNull { shelf ->
            shelf.items.size >= 3 &&
                shelf.items.count { it !is ShelfItem.Artist && !it.thumbnailUrl.isNullOrBlank() } >= 3 &&
                !shelf.items.all { it is ShelfItem.Track }
        } ?: return emptyList()

        return candidateShelf.items
            .asSequence()
            .filter { it !is ShelfItem.Artist && !it.thumbnailUrl.isNullOrBlank() }
            .take(5)
            .mapNotNull { item ->
                when (item) {
                    is ShelfItem.Album -> TopPick(
                        kind = TopPickKind.LATEST_RELEASE,
                        title = item.title,
                        subtitle = item.subtitle.orEmpty(),
                        artistName = candidateShelf.title.ifBlank { null },
                        images = listOfNotNull(item.thumbnailUrl),
                        browseId = item.browseId,
                        isFallbackPick = true,
                    )
                    is ShelfItem.Playlist -> TopPick(
                        kind = TopPickKind.LATEST_RELEASE,
                        title = item.title,
                        subtitle = item.subtitle.orEmpty(),
                        artistName = candidateShelf.title.ifBlank { null },
                        images = listOfNotNull(item.thumbnailUrl),
                        browseId = item.browseId,
                        fallbackParams = item.params,
                        isFallbackPick = true,
                    )
                    is ShelfItem.Track -> TopPick(
                        kind = TopPickKind.NEW_RELEASE_MIX,
                        title = item.song.title,
                        subtitle = item.song.artistText,
                        images = listOfNotNull(item.song.thumbnailUrl),
                        watch = WatchTarget(playlistId = "RDAMVM${item.song.videoId}", videoId = item.song.videoId),
                        isFallbackPick = true,
                    )
                    is ShelfItem.Artist -> null
                }
            }
            .toList()
    }

    internal fun rankArtists(history: List<Song>, localArtists: List<ArtistRef>): List<ArtistRef> {
        val scores = LinkedHashMap<String, Pair<String, Double>>()
        fun credit(id: String?, name: String, weight: Double) {
            if (id.isNullOrBlank() || !id.startsWith("UC") || name.isBlank()) return
            if (name.equals("Various Artists", ignoreCase = true)) return
            val existing = scores[id]
            val keptName = existing?.first ?: name
            scores[id] = keptName to ((existing?.second ?: 0.0) + weight)
        }

        history.forEachIndexed { index, song ->
            val recency = 1.0 - index.toDouble() / history.size.coerceAtLeast(1)
            val lead = song.artists.firstOrNull { !it.browseId.isNullOrBlank() }
            if (lead != null) {
                credit(lead.browseId, lead.name, 1.0 + recency * 0.5)
            }
        }
        localArtists.forEachIndexed { index, artist ->
            credit(artist.browseId, artist.name, (localArtists.size - index).toDouble() * 0.5)
        }

        return scores.entries
            .sortedByDescending { it.value.second }
            .map { ArtistRef(name = it.value.first, browseId = it.key) }
    }

    private fun <T> weightedSample(ranked: List<T>, count: Int): List<T> {
        val pool = ranked.withIndex().toMutableList()
        val picked = mutableListOf<T>()
        while (picked.size < count && pool.isNotEmpty()) {
            val weights = pool.map { (ranked.size - it.index).toDouble() }
            var roll = random.nextDouble() * weights.sum()
            val idx = weights.indexOfFirst {
                roll -= it
                roll <= 0.0
            }.coerceAtLeast(0)
            picked += pool.removeAt(idx).value
        }
        return picked
    }

    private suspend fun findMixes(
        homeShelves: List<HomeShelf>,
        isSignedIn: Boolean,
        isEnglishLocale: Boolean,
    ): Map<MixSlot, ShelfItem> {
        val onHome = homeShelves
            .flatMap { it.items }
            .filter { it.isPersonalizedMix() }
            .map { MixEntry(item = it, englishTitle = it.title) }

        val entries = if (onHome.size >= 3 || !isSignedIn) {
            onHome
        } else {
            val remote = dataSource.loadMixedForYou(isEnglishLocale)
                .filter { it.item.isPersonalizedMix() }
            if (remote.isNotEmpty()) remote else onHome
        }
        if (entries.isEmpty()) return emptyMap()

        val found = mutableMapOf<MixSlot, ShelfItem>()
        entries.forEach { entry ->
            val lower = entry.englishTitle.lowercase(Locale.ROOT)
            val localized = entry.item.title.lowercase(Locale.ROOT)
            val slot = when {
                "supermix" in lower || "supermix" in localized -> MixSlot.SUPERMIX
                "discover" in lower || "temukan" in localized -> MixSlot.DISCOVER
                "new release" in lower || "rilis baru" in localized -> MixSlot.NEW_RELEASE
                else -> null
            }
            if (slot != null) found.putIfAbsent(slot, entry.item)
        }
        if (found.isEmpty()) {
            found[MixSlot.SUPERMIX] = entries.first().item
        }
        return found
    }

    fun moodFor(now: LocalDateTime): TopPickMood {
        val suited = when (now.hour) {
            in 5..8 -> listOf(TopPickMood.ENERGY, TopPickMood.FEEL_GOOD, TopPickMood.FOCUS)
            in 9..11 -> listOf(TopPickMood.FOCUS, TopPickMood.FEEL_GOOD, TopPickMood.ENERGY)
            in 12..16 -> listOf(TopPickMood.FEEL_GOOD, TopPickMood.FOCUS, TopPickMood.ENERGY, TopPickMood.RELAX)
            in 17..19 -> listOf(TopPickMood.ENERGY, TopPickMood.FEEL_GOOD, TopPickMood.RELAX)
            in 20..22 -> listOf(TopPickMood.LOVE, TopPickMood.RELAX, TopPickMood.HEARTBREAK, TopPickMood.FEEL_GOOD)
            else -> listOf(TopPickMood.RELAX, TopPickMood.FEELING_BLUE, TopPickMood.HEARTBREAK, TopPickMood.LOVE)
        }
        return if (random.nextFloat() < 0.7f) {
            suited[random.nextInt(suited.size)]
        } else {
            TopPickMood.entries[random.nextInt(TopPickMood.entries.size)]
        }
    }

    private fun ShelfItem.isPersonalizedMix(): Boolean =
        this is ShelfItem.Playlist && browseId.removePrefix("VL").startsWith("RDTMAK")

    private fun ShelfItem.toWatchTarget(): WatchTarget? = when (this) {
        is ShelfItem.Playlist -> WatchTarget(playlistId = browseId.removePrefix("VL"))
        is ShelfItem.Track -> WatchTarget(playlistId = "RDAMVM${song.videoId}", videoId = song.videoId)
        else -> null
    }

    companion object {
        private const val SAMPLED_ARTISTS = 3
        private const val ARTIST_POOL = 10
        private const val RELATED_CANDIDATES = 4
        private const val PORTRAIT_CONCURRENCY = 4
        private const val SEED_POOL = 40
        private const val SEED_RADIOS = 3
        private const val STATION_FROM_MIX = 30
        private const val STATION_SIZE = 60
        private const val MIN_FILTERED_QUEUE = 12
        private const val MOOD_PLAYLIST_COUNT = 3
        private const val MOOD_QUEUE_LIMIT = 60

        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val SINGLE_OR_EP_REGEX = Regex("""\b(Single|EP)\b""", RegexOption.IGNORE_CASE)

        internal fun yearOf(subtitle: String): Int =
            YEAR_REGEX.findAll(subtitle).lastOrNull()?.value?.toIntOrNull() ?: 0

        fun regionHints(countryCode: String): List<String> = when (countryCode.uppercase(Locale.ROOT)) {
            "ID" -> listOf("indonesia", "indonesian", "pop indo", "indo")
            "IN" -> listOf("bollywood", "indian", "hindi", "punjabi", "desi")
            "PK" -> listOf("pakistani", "urdu", "punjabi")
            "KR" -> listOf("k-pop", "korean")
            "JP" -> listOf("j-pop", "japanese", "anime")
            "BR" -> listOf("brazil", "sertanejo", "funk")
            "MX", "ES", "AR", "CO", "CL", "PE" -> listOf("latin", "latino", "reggaeton")
            else -> emptyList()
        }
    }
}
