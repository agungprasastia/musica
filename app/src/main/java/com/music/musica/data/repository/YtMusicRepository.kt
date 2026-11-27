package com.music.musica.data.repository

import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.BrowsePage
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.ExploreFeed
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.LikeStatus
import com.music.musica.core.model.MixEntry
import com.music.musica.core.model.MixesFeed
import com.music.musica.core.model.SearchFilter
import com.music.musica.core.model.SearchPage
import com.music.musica.core.model.Song
import com.music.musica.core.model.WatchTarget
import com.music.musica.data.innertube.MusicaInnertubeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class YtMusicRepository(
    private val client: MusicaInnertubeClient,
) {
    private val detailCache = ConcurrentHashMap<String, DetailPage>()
    private val categoryCoverCache = ConcurrentHashMap<String, String>()
    private val historyMutex = Mutex()
    @Volatile
    private var historyMemo: Pair<Long, List<Song>>? = null

    suspend fun loadHome(continuation: String? = null): Result<BrowsePage> = runCatching {
        client.browseHome(continuation)
    }

    suspend fun loadExplore(): Result<List<HomeShelf>> = runCatching {
        client.browseExplore().shelves
    }

    suspend fun loadExploreFeed(): Result<ExploreFeed> = runCatching {
        coroutineScope {
            val moodsDef = async { runCatching { client.browseMoodsAndGenres().shelves }.getOrDefault(emptyList()) }
            val exploreDef = async { runCatching { client.browseExplore().shelves }.getOrDefault(emptyList()) }
            val feed = ExploreAndMixesOrganizer.buildExploreFeed(
                exploreShelves = exploreDef.await(),
                moodsShelves = moodsDef.await(),
            )
            // Apply any already-cached covers immediately so pull-to-refresh never flashes empty art
            feed.copy(
                categories = feed.categories.map { cat ->
                    if (cat.thumbnailUrl.isNullOrBlank()) {
                        cat.copy(thumbnailUrl = categoryCoverCache[cat.key])
                    } else {
                        cat
                    }
                },
            )
        }
    }

    suspend fun fetchCategoryCover(browseId: String, params: String?): String? {
        val key = if (params.isNullOrBlank()) browseId else "$browseId|$params"
        categoryCoverCache[key]?.let { return it }
        val page = loadDetail(browseId, params).getOrNull() ?: return null
        val thumb = page.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: page.sections.asSequence()
                .flatMap { it.items.asSequence() }
                .mapNotNull { it.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }
                .firstOrNull()
            ?: page.tracks.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }
        if (thumb != null) {
            categoryCoverCache[key] = thumb
        }
        return thumb
    }

    suspend fun loadMixes(): Result<List<HomeShelf>> = runCatching {
        client.browseMixes().shelves
    }

    suspend fun loadMixedForYouKeyed(isEnglishLocale: Boolean): Result<List<MixEntry>> = runCatching {
        coroutineScope {
            val localizedDef = async { client.browseMixedForYou() }
            val englishDef = if (!isEnglishLocale) {
                async { runCatching { client.browseMixedForYou(languageOverride = "en") }.getOrDefault(emptyList()) }
            } else {
                null
            }
            val localized = localizedDef.await()
            val englishById = englishDef?.await().orEmpty().associate { it.id to it.title }
            localized.map { item -> MixEntry(item = item, englishTitle = englishById[item.id] ?: item.title) }
        }
    }

    suspend fun loadMixesFeed(isSignedIn: Boolean, isEnglishLocale: Boolean): Result<MixesFeed> = runCatching {
        val personalized = if (isSignedIn) {
            val entries = loadMixedForYouKeyed(isEnglishLocale).getOrThrow()
            ExploreAndMixesOrganizer.groupPersonalizedMixes(entries)
        } else {
            null
        }

        if (personalized != null && (
                personalized.featured.isNotEmpty() ||
                    personalized.moodShelves.isNotEmpty() ||
                    personalized.stations.size >= 4
                )
        ) {
            return@runCatching personalized
        }

        // Guest mode or new/sparse signed-in account (e.g. only 1 station so far):
        // preserve any real personalized stations on top and enrich with live Home & Explore mixes
        val curated = coroutineScope {
            val homeDef = async { runCatching { client.browseHome(null).shelves }.getOrDefault(emptyList()) }
            val exploreDef = async { runCatching { client.browseExplore().shelves }.getOrDefault(emptyList()) }
            ExploreAndMixesOrganizer.buildCuratedGuestMixes(
                homeShelves = homeDef.await(),
                exploreShelves = exploreDef.await(),
            )
        }

        if (personalized != null && !personalized.isEmpty) {
            ExploreAndMixesOrganizer.enrichSparsePersonalizedMixes(personalized, curated)
        } else {
            curated
        }
    }

    suspend fun loadRemoteLibrary(browseId: String = "FEmusic_liked_playlists"): Result<List<HomeShelf>> = runCatching {
        client.browseLibrary(browseId).shelves
    }

    suspend fun loadLikedSongs(continuation: String? = null): Result<Pair<List<Song>, String?>> = runCatching {
        client.browseLikedSongs(continuation)
    }

    suspend fun loadRemoteSavedAlbums(): Result<List<com.music.musica.core.model.ShelfItem.Album>> = runCatching {
        client.browseLibrary("FEmusic_liked_albums").shelves
            .flatMap { it.items }
            .filterIsInstance<com.music.musica.core.model.ShelfItem.Album>()
            .distinctBy { it.browseId }
    }

    suspend fun loadRemoteSubscribedArtists(): Result<List<com.music.musica.core.model.ShelfItem.Artist>> = runCatching {
        client.browseLibrary("FEmusic_library_corpus_artists").shelves
            .flatMap { it.items }
            .filterIsInstance<com.music.musica.core.model.ShelfItem.Artist>()
            .distinctBy { it.browseId }
    }

    suspend fun loadDetail(browseId: String, params: String? = null, forceRefresh: Boolean = false): Result<DetailPage> =
        runCatching {
            val cacheKey = "$browseId:${params.orEmpty()}"
            if (!forceRefresh) {
                detailCache[cacheKey]?.let { return@runCatching it }
            }
            client.browseDetail(browseId, params).also { page ->
                if (page.title.isNotBlank() || page.tracks.isNotEmpty()) {
                    detailCache[cacheKey] = page
                }
            }
        }

    suspend fun loadDetailContinuation(
        browseId: String,
        continuation: String,
        fallbackCover: String? = null,
        fallbackAlbum: com.music.musica.core.model.AlbumRef? = null,
        fallbackArtist: com.music.musica.core.model.ArtistRef? = null,
    ): Result<Pair<List<Song>, String?>> =
        runCatching {
            client.browseDetailContinuation(
                browseId = browseId,
                continuation = continuation,
                fallbackCover = fallbackCover,
                fallbackAlbum = fallbackAlbum,
                fallbackArtist = fallbackArtist,
            )
        }

    suspend fun search(query: String, filter: SearchFilter, continuation: String? = null): Result<SearchPage> =
        runCatching {
            client.search(query, filter, continuation)
        }

    suspend fun suggestions(query: String): List<String> =
        runCatching { client.searchSuggestions(query) }.getOrDefault(emptyList())

    suspend fun upNext(videoId: String, playlistId: String? = null): List<Song> =
        runCatching { client.upNext(videoId, playlistId) }.getOrDefault(emptyList())

    suspend fun loadWatchQueue(target: WatchTarget, preferAudio: Boolean = false): Result<List<Song>> =
        runCatching {
            client.nextWatchQueue(
                playlistId = target.playlistId,
                videoId = target.videoId,
                params = target.params,
                preferAudio = preferAudio,
            )
        }

    suspend fun loadMoodCategoryShelves(params: String): Result<List<HomeShelf>> =
        runCatching { client.browseMoodCategory(params) }

    suspend fun loadRemoteHistory(forceRefresh: Boolean = false): Result<List<Song>> = runCatching {
        historyMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = historyMemo
            if (!forceRefresh && cached != null && now - cached.first < REMOTE_HISTORY_MEMO_MS) {
                return@withLock cached.second
            }
            val songs = client.browseHistory()
            historyMemo = now to songs
            songs
        }
    }

    suspend fun setLike(videoId: String, status: LikeStatus): Boolean =
        runCatching { client.setLikeStatus(videoId, status) }.getOrDefault(false)

    suspend fun fetchAccountProfile(): AccountProfile? =
        runCatching { client.getAccountProfile() }.getOrNull()

    suspend fun createRemotePlaylist(title: String, description: String = ""): String? =
        runCatching { client.createPlaylist(title, description) }.getOrNull()

    suspend fun addSongToRemotePlaylist(playlistId: String, videoId: String): Boolean =
        runCatching { client.addSongToPlaylist(playlistId, videoId) }.getOrDefault(false)

    suspend fun deleteRemotePlaylist(playlistId: String): Boolean =
        runCatching { client.deletePlaylist(playlistId) }.getOrDefault(false)

    suspend fun renameRemotePlaylist(playlistId: String, title: String, description: String = ""): Boolean =
        runCatching { client.renamePlaylist(playlistId, title, description) }.getOrDefault(false)

    suspend fun removeSongFromRemotePlaylist(playlistId: String, videoId: String, setVideoId: String?): Boolean =
        runCatching { client.removeSongFromPlaylist(playlistId, videoId, setVideoId) }.getOrDefault(false)

    suspend fun moveRemotePlaylistItem(
        playlistId: String,
        setVideoId: String,
        movedBeforeSetVideoId: String?,
    ): Boolean = runCatching {
        client.movePlaylistItem(playlistId, setVideoId, movedBeforeSetVideoId)
    }.getOrDefault(false)

    suspend fun setSubscribed(channelId: String, subscribe: Boolean): Boolean =
        runCatching { client.setSubscribed(channelId, subscribe) }.getOrDefault(false)

    suspend fun ratePlaylist(playlistId: String, like: Boolean): Boolean =
        runCatching { client.ratePlaylist(playlistId, like) }.getOrDefault(false)

    fun invalidateDetail(browseId: String) {
        val clean = browseId.removePrefix("VL")
        detailCache.keys.removeIf { key ->
            key.startsWith("$browseId:") ||
                key.startsWith("$clean:") ||
                key.startsWith("VL$clean:")
        }
    }

    fun clearCache() {
        detailCache.clear()
        historyMemo = null
    }

    companion object {
        private const val REMOTE_HISTORY_MEMO_MS = 120_000L
    }
}
