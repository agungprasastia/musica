package com.music.musica.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.music.musica.MusicaApplication
import com.music.musica.core.auth.GoogleSession
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.AudioQuality
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.ExploreFeed
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.MixesFeed
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.ReplayPeriod
import com.music.musica.core.model.SearchFilter
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.UiState
import com.music.musica.core.model.UserPlaylist
import com.music.musica.core.network.HttpClients
import com.music.musica.core.settings.UserSettings
import com.music.musica.data.backup.FullBackupSerializer
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricsDisplayMode
import com.music.musica.data.lyrics.LyricsProviderStatus
import com.music.musica.data.lyrics.LyricsQuery
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.lyrics.LyricsTranslationEngine
import com.music.musica.data.lyrics.LyricsTranslationOutcome
import com.music.musica.R
import com.music.musica.data.repository.ReplayPassSection
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.data.repository.StoredTopPicks
import com.music.musica.data.repository.TopPicksCacheStore
import com.music.musica.data.repository.TopPicksEngine
import com.music.musica.ui.components.BrowseActionTarget
import com.music.musica.ui.navigation.AppNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class DetailStackEntry(
    val browseId: String,
    val params: String? = null,
    val isLocalPlaylist: Boolean = false,
    val state: UiState<DetailPage> = UiState.Loading,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val container = (application as MusicaApplication).container

    val loadedSettings = container.loadedSettings
    val settings = container.settingsState
    val authSession = container.authStore.session
    val playerState = container.playerController.state
    val playbackProgress = container.playerController.progress
    val networkType = container.networkMonitor.networkType

    val likedSongs = container.libraryRepository.likedSongs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val likedIds = container.libraryRepository.likedIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val dislikedIds = container.libraryRepository.dislikedIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val localPlaylists = container.libraryRepository.localPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subscribedArtistIds = container.libraryRepository.subscribedArtistIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val savedCollectionIds = container.libraryRepository.savedCollectionIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val recentHistory = container.libraryRepository.recentHistory.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyPlayedSongs = container.libraryRepository.recentlyPlayedSongs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentSearches = container.libraryRepository.recentSearchQueries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloads = container.downloadRepository.downloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloadedIds = container.downloadRepository.downloadedIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ---- Browse feeds ----
    private val _homeState = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val homeState: StateFlow<UiState<List<HomeShelf>>> = _homeState.asStateFlow()
    private val _homeRefreshing = MutableStateFlow(false)
    val homeRefreshing: StateFlow<Boolean> = _homeRefreshing.asStateFlow()
    private var homeContinuation: String? = null
    private var loadingMoreHome = false

    // ---- Top Picks for You ----
    private val _topPicks = MutableStateFlow<List<TopPick>?>(null)
    val topPicks: StateFlow<List<TopPick>?> = _topPicks.asStateFlow()
    private val _topPicksLoading = MutableStateFlow(false)
    val topPicksLoading: StateFlow<Boolean> = _topPicksLoading.asStateFlow()
    private val _activeTopPickLoadingKey = MutableStateFlow<String?>(null)
    val activeTopPickLoadingKey: StateFlow<String?> = _activeTopPickLoadingKey.asStateFlow()
    private var topPicksJob: Job? = null
    private var topPicksSlotShown: String? = null
    private var topPicksIdentityShown: String? = null
    private val topPickPlayGeneration = AtomicLong(0L)

    private val _exploreState = MutableStateFlow<UiState<ExploreFeed>>(UiState.Loading)
    val exploreState: StateFlow<UiState<ExploreFeed>> = _exploreState.asStateFlow()
    private val _exploreRefreshing = MutableStateFlow(false)
    val exploreRefreshing: StateFlow<Boolean> = _exploreRefreshing.asStateFlow()
    private var exploreHydrationJob: Job? = null

    private val _mixesState = MutableStateFlow<UiState<MixesFeed>>(UiState.Loading)
    val mixesState: StateFlow<UiState<MixesFeed>> = _mixesState.asStateFlow()
    private val _mixesRefreshing = MutableStateFlow(false)
    val mixesRefreshing: StateFlow<Boolean> = _mixesRefreshing.asStateFlow()

    private val _remoteLibrary = MutableStateFlow<List<HomeShelf>>(emptyList())
    val remoteLibrary: StateFlow<List<HomeShelf>> = _remoteLibrary.asStateFlow()
    private val _libraryRefreshing = MutableStateFlow(false)
    val libraryRefreshing: StateFlow<Boolean> = _libraryRefreshing.asStateFlow()

    // ---- Search ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchFilter = MutableStateFlow(SearchFilter.ALL)
    val searchFilter: StateFlow<SearchFilter> = _searchFilter.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _searchResults = MutableStateFlow<UiState<List<ShelfItem>>?>(null)
    val searchResults: StateFlow<UiState<List<ShelfItem>>?> = _searchResults.asStateFlow()
    private val _searchHasMore = MutableStateFlow(false)
    val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()
    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()
    private var searchContinuation: String? = null
    private var failedSearchContinuation: String? = null
    private var searchJob: Job? = null
    private var searchLoadMoreJob: Job? = null

    // ---- Detail Stack + Surviving AppNavigator ----
    private val _detailStack = MutableStateFlow<List<DetailStackEntry>>(emptyList())
    val detailStack: StateFlow<List<DetailStackEntry>> = _detailStack.asStateFlow()
    private val _detailLoadingMore = MutableStateFlow(false)
    val detailLoadingMore: StateFlow<Boolean> = _detailLoadingMore.asStateFlow()
    private var detailLoadMoreJob: Job? = null
    private val failedDetailContinuations = mutableSetOf<String>()

    val navigator: AppNavigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = { popDetail() },
        clearDetails = { clearDetails() },
    )

    // ---- Audio Cache Size (computed off main thread) ----
    private val _audioCacheBytes = MutableStateFlow(0L)
    val audioCacheBytes: StateFlow<Long> = _audioCacheBytes.asStateFlow()

    // ---- Lyrics ----
    private val _lyricsState = MutableStateFlow<UiState<LyricsResult?>>(UiState.Success(null))
    val lyricsState: StateFlow<UiState<LyricsResult?>> = _lyricsState.asStateFlow()
    private val _lyricsProviderStatuses = MutableStateFlow<Map<LyricsSourceId, LyricsProviderStatus>>(emptyMap())
    val lyricsProviderStatuses: StateFlow<Map<LyricsSourceId, LyricsProviderStatus>> = _lyricsProviderStatuses.asStateFlow()
    private val lyricsProviderResults = java.util.concurrent.ConcurrentHashMap<LyricsSourceId, LyricsResult>()
    private var activeLyricsVideoId: String? = null
    private var lyricsJob: Job? = null
    private var singleProviderJob: Job? = null

    private val _lyricsDisplayMode = MutableStateFlow(LyricsDisplayMode.ORIGINAL)
    val lyricsDisplayMode: StateFlow<LyricsDisplayMode> = _lyricsDisplayMode.asStateFlow()
    private val _lyricsSubLines = MutableStateFlow<List<LyricLine>?>(null)
    val lyricsSubLines: StateFlow<List<LyricLine>?> = _lyricsSubLines.asStateFlow()
    private val _lyricsTranslationLoading = MutableStateFlow(false)
    val lyricsTranslationLoading: StateFlow<Boolean> = _lyricsTranslationLoading.asStateFlow()
    private val _lyricsTranslationNotice = MutableStateFlow<String?>(null)
    val lyricsTranslationNotice: StateFlow<String?> = _lyricsTranslationNotice.asStateFlow()
    private var translationJob: Job? = null

    // ---- Replay ----
    private val _replayPeriod = MutableStateFlow(ReplayPeriod.ALL_TIME)
    val replayPeriod: StateFlow<ReplayPeriod> = _replayPeriod.asStateFlow()
    private val _replayLandingSection = MutableStateFlow(ReplayPassSection.MINUTES)
    val replayLandingSection: StateFlow<ReplayPassSection> = _replayLandingSection.asStateFlow()
    val replaySummary: StateFlow<ReplaySummary> = _replayPeriod
        .flatMapLatest { container.libraryRepository.observeReplay(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ReplaySummary())
    val allTimeReplaySummary: StateFlow<ReplaySummary> = container.libraryRepository
        .observeReplay(ReplayPeriod.ALL_TIME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ReplaySummary())
    val appInstalledAtMs: Long = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())

    private var exploreLoaded = false
    private var mixesLoaded = false
    private var remoteLibraryLoaded = false

    init {
        container.playerController.connect()
        loadHome(isRefresh = false)
        loadTopPicks(rebuild = false)
        refreshAccountProfileOnly()
        observeSearchSuggestions()
        observeCurrentTrackForLyrics()
    }

    fun ensureExploreLoaded() {
        if (exploreLoaded) return
        exploreLoaded = true
        loadExplore(isRefresh = false)
    }

    fun ensureMixesLoaded() {
        if (mixesLoaded) return
        mixesLoaded = true
        loadMixes(isRefresh = false)
    }

    fun ensureRemoteLibraryLoaded() {
        if (remoteLibraryLoaded || !container.authStore.isSignedIn) return
        remoteLibraryLoaded = true
        refreshLibrary()
    }

    private fun observeSearchSuggestions() {
        viewModelScope.launch {
            _searchQuery
                .debounce(260L)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank()) {
                        _searchSuggestions.value = emptyList()
                    } else if (_searchResults.value == null) {
                        _searchSuggestions.value = container.ytMusicRepository.suggestions(q)
                    }
                }
        }
    }

    private fun observeCurrentTrackForLyrics() {
        viewModelScope.launch {
            playerState
                .map { it.currentSong }
                .distinctUntilChanged { old, new -> old?.videoId == new?.videoId }
                .collect { song ->
                    if (song == null) {
                        _lyricsState.value = UiState.Success(null)
                    } else {
                        loadLyricsFor(song, forceSource = null)
                    }
                }
        }
    }

    fun loadHome(isRefresh: Boolean = true) {
        viewModelScope.launch {
            if (isRefresh) _homeRefreshing.value = true else _homeState.value = UiState.Loading
            homeContinuation = null
            container.ytMusicRepository.loadHome(null)
                .onSuccess { page ->
                    homeContinuation = page.continuation
                    _homeState.value = UiState.Success(page.shelves)
                    // If Top Picks previously built sparse (<=1 card) before Home arrived, enrich immediately.
                    if (page.shelves.isNotEmpty() && _topPicks.value.orEmpty().size <= 1 && topPicksJob?.isActive != true) {
                        loadTopPicks(rebuild = true)
                    }
                }
                .onFailure { err ->
                    if (_homeState.value !is UiState.Success) {
                        _homeState.value = UiState.Error(err.localizedMessage ?: "", err)
                    }
                }
            _homeRefreshing.value = false
            // Top Picks keeps its hourly snapshot; only refresh if stale, empty, or fallback-only with new history.
            if (isRefresh && topPicksJob?.isActive != true) {
                loadTopPicks(rebuild = shouldRebuildFallbackPicks())
            }
        }
    }

    private fun topPicksIdentityKey(): String {
        val cookie = container.authStore.currentCookie?.takeIf { it.isNotBlank() }
        return if (cookie != null) "signed:${cookie.hashCode()}" else "guest"
    }

    private fun topPicksLanguageTag(): String =
        settings.value.appLanguage.tag.ifBlank { Locale.getDefault().language }

    private fun shouldRebuildFallbackPicks(): Boolean {
        val current = _topPicks.value.orEmpty()
        if (current.isEmpty()) return true
        val onlyFallbackOrMood = current.all { it.isFallbackPick || it.kind == TopPickKind.MOOD }
        return onlyFallbackOrMood && recentlyPlayedSongs.value.isNotEmpty()
    }

    fun refreshTopPicksIfStale() {
        if (topPicksJob?.isActive == true) return
        val identity = topPicksIdentityKey()
        val currentSlot = TopPicksCacheStore.hourSlotOf()
        val staleHour = topPicksSlotShown != null && topPicksSlotShown != currentSlot
        val identityChanged = topPicksIdentityShown != null && topPicksIdentityShown != identity
        if (staleHour || identityChanged || shouldRebuildFallbackPicks()) {
            loadTopPicks(rebuild = identityChanged || shouldRebuildFallbackPicks())
        }
    }

    fun loadTopPicks(rebuild: Boolean = false) {
        topPicksJob?.cancel()
        val identity = topPicksIdentityKey()
        val langTag = topPicksLanguageTag()
        val slot = TopPicksCacheStore.hourSlotOf()
        topPicksJob = viewModelScope.launch {
            val stored = container.topPicksCacheStore.load()
                ?.takeIf { it.identity == identity && it.languageTag == langTag && it.picks.isNotEmpty() }
            stored?.let { container.artistPortraitResolver.seed(it.artistPhotos) }

            if (stored != null) {
                _topPicks.value = stored.picks
                topPicksSlotShown = stored.hourSlot
                topPicksIdentityShown = identity
            } else if (!rebuild) {
                _topPicks.value = null
            }

            if (!rebuild && stored?.hourSlot == slot && stored.picks.size > 1 && !shouldRebuildFallbackPicks()) {
                _topPicksLoading.value = false
                return@launch
            }

            _topPicksLoading.value = _topPicks.value == null
            val shelves = withTimeoutOrNull(15_000L) {
                _homeState.first { it !is UiState.Loading }
            }.let { (it as? UiState.Success)?.data.orEmpty() }

            val localTaste = collectLocalTaste()
            val isEnglish = langTag.startsWith("en", ignoreCase = true)
            val built = try {
                container.topPicksEngine.build(
                    homeShelves = shelves,
                    local = localTaste,
                    isSignedIn = container.authStore.isSignedIn,
                    isEnglishLocale = isEnglish,
                ).takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (identity != topPicksIdentityKey()) return@launch
            _topPicksLoading.value = false
            if (built == null) {
                if (_topPicks.value == null) _topPicks.value = emptyList()
                return@launch
            }

            _topPicks.value = built
            topPicksSlotShown = slot
            topPicksIdentityShown = identity
            // Avoid locking in a sparse 1-card cache when Home hadn't settled yet.
            if (built.size > 1 || shelves.isNotEmpty()) {
                container.topPicksCacheStore.save(
                    StoredTopPicks(
                        identity = identity,
                        hourSlot = slot,
                        languageTag = langTag,
                        picks = built,
                        artistPhotos = container.artistPortraitResolver.snapshot(),
                    ),
                )
            }
        }
    }

    private suspend fun collectLocalTaste(): TopPicksEngine.LocalTaste {
        val month = runCatching {
            container.libraryRepository.observeReplay(ReplayPeriod.THIS_MONTH).first()
        }.getOrNull()
        val summary = if (month != null && (month.topArtists.isNotEmpty() || month.topSongs.isNotEmpty())) {
            month
        } else {
            runCatching {
                container.libraryRepository.observeReplay(ReplayPeriod.ALL_TIME).first()
            }.getOrNull()
        } ?: return TopPicksEngine.LocalTaste()

        return TopPicksEngine.LocalTaste(
            artists = summary.topArtists.take(10).map { ArtistRef(name = it.name, browseId = it.browseId) },
            songs = summary.topSongs.take(5).map { it.song },
        )
    }

    fun openTopPick(pick: TopPick, onFailure: () -> Unit) {
        val browseId = pick.browseId
        if (pick.kind == TopPickKind.LATEST_RELEASE && !browseId.isNullOrBlank()) {
            openDetail(browseId, pick.fallbackParams)
            return
        }
        val requestId = topPickPlayGeneration.incrementAndGet()
        val pickKey = pick.stableKey
        _activeTopPickLoadingKey.value = pickKey
        viewModelScope.launch {
            try {
                val localSongs = recentlyPlayedSongs.value
                val songs = container.topPicksEngine.resolveQueue(pick, localHistorySongs = localSongs)
                    .getOrNull()
                    .orEmpty()
                if (topPickPlayGeneration.get() != requestId) return@launch
                if (songs.isEmpty()) {
                    onFailure()
                } else {
                    container.playerController.playQueue(
                        songs = songs,
                        startIndex = 0,
                        shuffled = false,
                        sourceLabel = pick.title,
                        sourceBrowseId = pick.browseId ?: pick.artistBrowseId,
                    )
                }
            } finally {
                if (_activeTopPickLoadingKey.value == pickKey && topPickPlayGeneration.get() == requestId) {
                    _activeTopPickLoadingKey.value = null
                }
            }
        }
    }

    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (loadingMoreHome) return
        loadingMoreHome = true
        viewModelScope.launch {
            container.ytMusicRepository.loadHome(token)
                .onSuccess { page ->
                    homeContinuation = page.continuation
                    val existing = (_homeState.value as? UiState.Success)?.data.orEmpty()
                    val seenTitles = existing.map { it.title }.toSet()
                    val added = page.shelves.filter { it.title.isBlank() || it.title !in seenTitles }
                    if (added.isNotEmpty()) {
                        _homeState.value = UiState.Success(existing + added)
                    }
                }
            loadingMoreHome = false
        }
    }

    fun loadExplore(isRefresh: Boolean = true) {
        viewModelScope.launch {
            if (isRefresh) _exploreRefreshing.value = true else _exploreState.value = UiState.Loading
            container.ytMusicRepository.loadExploreFeed()
                .onSuccess { feed ->
                    _exploreState.value = UiState.Success(feed)
                    hydrateExploreCovers(feed)
                }
                .onFailure { err ->
                    if (_exploreState.value !is UiState.Success) {
                        _exploreState.value = UiState.Error(err.localizedMessage ?: "", err)
                    }
                }
            _exploreRefreshing.value = false
        }
    }

    private fun hydrateExploreCovers(feed: ExploreFeed) {
        exploreHydrationJob?.cancel()
        val missing = feed.categories
            .filter { it.thumbnailUrl.isNullOrBlank() }
            .take(14)
        if (missing.isEmpty()) return

        exploreHydrationJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(2)
            val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()
            missing.chunked(4).forEach { batch ->
                kotlinx.coroutines.coroutineScope {
                    batch.forEach { cat ->
                        launch {
                            val thumb = gate.withPermit {
                                runCatching {
                                    container.ytMusicRepository.fetchCategoryCover(cat.browseId, cat.params)
                                }.getOrNull()
                            }
                            if (!thumb.isNullOrBlank()) {
                                resolved[cat.key] = thumb
                            }
                        }
                    }
                }
                if (resolved.isNotEmpty()) {
                    val snapshot = resolved.toMap()
                    _exploreState.update { current ->
                        val success = current as? UiState.Success ?: return@update current
                        val updated = success.data.categories.map { existing ->
                            val found = snapshot[existing.key]
                            if (found != null && existing.thumbnailUrl.isNullOrBlank()) {
                                existing.copy(thumbnailUrl = found)
                            } else {
                                existing
                            }
                        }
                        UiState.Success(success.data.copy(categories = updated))
                    }
                }
            }
        }
    }

    fun loadMixes(isRefresh: Boolean = true) {
        viewModelScope.launch {
            if (isRefresh) _mixesRefreshing.value = true else _mixesState.value = UiState.Loading
            val langTag = settings.value.appLanguage.tag.ifBlank { Locale.getDefault().language }
            val isEnglish = langTag.startsWith("en", ignoreCase = true)
            container.ytMusicRepository.loadMixesFeed(
                isSignedIn = container.authStore.isSignedIn,
                isEnglishLocale = isEnglish,
            )
                .onSuccess { _mixesState.value = UiState.Success(it) }
                .onFailure { err ->
                    if (_mixesState.value !is UiState.Success) {
                        _mixesState.value = UiState.Error(err.localizedMessage ?: "", err)
                    }
                }
            _mixesRefreshing.value = false
        }
    }

    // ---- Search actions ----

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
        // Editing the query returns cleanly to live suggestions instead of showing stale results
        if (_searchResults.value != null) {
            searchJob?.cancel()
            searchLoadMoreJob?.cancel()
            searchContinuation = null
            failedSearchContinuation = null
            _searchHasMore.value = false
            _searchLoadingMore.value = false
            _searchResults.value = null
        }
    }

    fun submitSearch(query: String, filter: SearchFilter = _searchFilter.value) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        _searchQuery.value = clean
        _searchFilter.value = filter
        _searchResults.value = UiState.Loading
        searchContinuation = null
        failedSearchContinuation = null
        _searchHasMore.value = false
        _searchLoadingMore.value = false
        searchJob?.cancel()
        searchLoadMoreJob?.cancel()
        searchJob = viewModelScope.launch {
            container.libraryRepository.recordSearchQuery(clean)
            container.ytMusicRepository.search(clean, filter)
                .onSuccess { page ->
                    searchContinuation = page.continuation
                    _searchHasMore.value = !page.continuation.isNullOrBlank()
                    _searchResults.value = UiState.Success(page.items)
                }
                .onFailure { err -> _searchResults.value = UiState.Error(err.localizedMessage ?: "", err) }
        }
    }

    fun loadMoreSearch() {
        val token = searchContinuation?.takeIf { it.isNotBlank() && it != failedSearchContinuation } ?: return
        if (_searchLoadingMore.value) return
        val currentQuery = _searchQuery.value
        val currentFilter = _searchFilter.value
        _searchLoadingMore.value = true
        searchLoadMoreJob?.cancel()
        searchLoadMoreJob = viewModelScope.launch {
            try {
                container.ytMusicRepository.search(currentQuery, currentFilter, continuation = token)
                    .onSuccess { page ->
                        if (_searchQuery.value != currentQuery || _searchFilter.value != currentFilter) return@onSuccess
                        val existing = (_searchResults.value as? UiState.Success)?.data.orEmpty()
                        val seen = existing.map { it.id + it::class.simpleName }.toHashSet()
                        val fresh = page.items.filter { seen.add(it.id + it::class.simpleName) }
                        if (fresh.isNotEmpty()) {
                            _searchResults.value = UiState.Success(existing + fresh)
                        }
                        val nextToken = page.continuation?.takeIf { it.isNotBlank() && it != token && fresh.isNotEmpty() }
                        searchContinuation = nextToken
                        _searchHasMore.value = nextToken != null
                    }
                    .onFailure {
                        failedSearchContinuation = token
                        _searchHasMore.value = false
                    }
            } finally {
                _searchLoadingMore.value = false
            }
        }
    }

    fun selectSearchFilter(filter: SearchFilter) {
        _searchFilter.value = filter
        if (_searchQuery.value.isNotBlank()) {
            submitSearch(_searchQuery.value, filter)
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch { container.libraryRepository.deleteSearchQuery(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { container.libraryRepository.clearSearchHistory() }
    }

    // ---- Detail navigation ----

    fun openDetail(browseId: String, params: String? = null) {
        detailLoadMoreJob?.cancel()
        _detailLoadingMore.value = false
        val entry = DetailStackEntry(browseId = browseId, params = params, isLocalPlaylist = false, state = UiState.Loading)
        _detailStack.update { it + entry }
        fetchDetailForTop(browseId, params, isLocal = false, forceRefresh = false)
    }

    fun openLocalPlaylist(playlist: UserPlaylist) {
        if (playlist.isRemoteYtm && !playlist.playlistId.startsWith("local_")) {
            val browseId = if (playlist.playlistId.startsWith("VL")) playlist.playlistId else "VL${playlist.playlistId}"
            openDetail(browseId)
            return
        }
        detailLoadMoreJob?.cancel()
        _detailLoadingMore.value = false
        val entry = DetailStackEntry(browseId = playlist.playlistId, isLocalPlaylist = true, state = UiState.Loading)
        _detailStack.update { it + entry }
        fetchDetailForTop(playlist.playlistId, null, isLocal = true, forceRefresh = true)
    }

    fun retryTopDetail() {
        detailLoadMoreJob?.cancel()
        _detailLoadingMore.value = false
        failedDetailContinuations.clear()
        val top = _detailStack.value.lastOrNull() ?: return
        fetchDetailForTop(top.browseId, top.params, top.isLocalPlaylist, forceRefresh = true)
    }

    fun loadMoreDetail() {
        if (_detailLoadingMore.value) return
        val top = _detailStack.value.lastOrNull() ?: return
        if (top.isLocalPlaylist) return
        val currentPage = (top.state as? UiState.Success)?.data ?: return
        val token = currentPage.continuation?.takeIf { it.isNotBlank() && it !in failedDetailContinuations } ?: return

        _detailLoadingMore.value = true
        val fallbackAlbum = if (currentPage.type == BrowseType.ALBUM && currentPage.title.isNotBlank()) {
            AlbumRef(currentPage.title, currentPage.browseId)
        } else {
            null
        }
        detailLoadMoreJob?.cancel()
        detailLoadMoreJob = viewModelScope.launch {
            try {
                container.ytMusicRepository.loadDetailContinuation(
                    browseId = top.browseId,
                    continuation = token,
                    fallbackCover = currentPage.thumbnailUrl,
                    fallbackAlbum = fallbackAlbum,
                    fallbackArtist = currentPage.artist,
                )
                    .onSuccess { (moreTracks, nextToken) ->
                        _detailStack.update { stack ->
                            stack.map { entry ->
                                if (entry.browseId == top.browseId && entry.params == top.params) {
                                    val latest = (entry.state as? UiState.Success)?.data ?: return@map entry
                                    val seenIds = latest.tracks.map { it.videoId }.toHashSet()
                                    val fresh = moreTracks.filter { seenIds.add(it.videoId) }
                                    val effectiveNext = nextToken?.takeIf {
                                        it.isNotBlank() && it != token && fresh.isNotEmpty()
                                    }
                                    entry.copy(
                                        state = UiState.Success(
                                            latest.copy(
                                                tracks = latest.tracks + fresh,
                                                continuation = effectiveNext,
                                            ),
                                        ),
                                    )
                                } else entry
                            }
                        }
                    }
                    .onFailure {
                        failedDetailContinuations += token
                        _detailStack.update { stack ->
                            stack.map { entry ->
                                if (entry.browseId == top.browseId && entry.params == top.params) {
                                    val latest = (entry.state as? UiState.Success)?.data ?: return@map entry
                                    entry.copy(state = UiState.Success(latest.copy(continuation = null)))
                                } else entry
                            }
                        }
                    }
            } finally {
                _detailLoadingMore.value = false
            }
        }
    }

    fun popDetail(): Boolean {
        val current = _detailStack.value
        if (current.isEmpty()) return false
        detailLoadMoreJob?.cancel()
        _detailLoadingMore.value = false
        val next = current.dropLast(1)
        _detailStack.value = next
        navigator.onDetailStackShrunk(next.size)
        return true
    }

    fun clearDetails() {
        detailLoadMoreJob?.cancel()
        _detailLoadingMore.value = false
        _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

    private fun fetchDetailForTop(browseId: String, params: String?, isLocal: Boolean, forceRefresh: Boolean) {
        viewModelScope.launch {
            val result = if (isLocal) {
                val page = container.libraryRepository.loadLocalPlaylistDetail(browseId)
                if (page != null) Result.success(page) else Result.failure(IllegalStateException("Playlist not found"))
            } else {
                container.ytMusicRepository.loadDetail(browseId, params, forceRefresh)
            }
            _detailStack.update { stack ->
                stack.map { entry ->
                    if (entry.browseId == browseId && entry.params == params) {
                        entry.copy(
                            state = result.fold(
                                onSuccess = { UiState.Success(it) },
                                onFailure = { UiState.Error(it.localizedMessage ?: "", it) },
                            ),
                        )
                    } else entry
                }
            }
        }
    }

    // ---- Auth ----

    fun completeOnboarding() {
        viewModelScope.launch { container.settingsStore.setOnboardingCompleted(true) }
    }

    fun onGoogleCookieCaptured(cookie: String) {
        container.authStore.saveSession(GoogleSession(cookie = cookie))
        container.innertubeClient.clearSessionScope()
        container.ytMusicRepository.clearCache()
        container.streamResolver.clearCache()
        viewModelScope.launch { container.settingsStore.setOnboardingCompleted(true) }
        refreshAccountAndRemoteLibrary()
        loadHome(isRefresh = true)
        loadTopPicks(rebuild = true)
        loadMixes(isRefresh = true)
    }

    fun signOut() {
        container.authStore.clearSession()
        container.innertubeClient.clearSessionScope()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
        container.ytMusicRepository.clearCache()
        container.streamResolver.clearCache()
        _remoteLibrary.value = emptyList()
        loadHome(isRefresh = true)
        loadTopPicks(rebuild = true)
        loadMixes(isRefresh = true)
    }

    private fun refreshAccountProfileOnly() {
        if (!container.authStore.isSignedIn) return
        viewModelScope.launch {
            container.ytMusicRepository.fetchAccountProfile()?.let { profile ->
                container.authStore.updateProfile(profile)
            }
        }
    }

    private fun refreshAccountAndRemoteLibrary() {
        if (!container.authStore.isSignedIn) return
        remoteLibraryLoaded = true
        viewModelScope.launch {
            container.ytMusicRepository.fetchAccountProfile()?.let { profile ->
                container.authStore.updateProfile(profile)
            }
            container.libraryRepository.syncFromYouTubeMusic()
            container.ytMusicRepository.loadRemoteLibrary().onSuccess { shelves ->
                _remoteLibrary.value = shelves
            }
        }
    }

    // ---- Playback, Library & Lyrics ----

    fun handleShelfItemClick(
        item: ShelfItem,
        contextTracks: List<Song> = emptyList(),
        sourceLabel: String? = null,
        sourceBrowseId: String? = null,
    ) {
        when (item) {
            is ShelfItem.Track -> {
                val effectiveLabel = sourceLabel ?: item.song.album?.name
                val effectiveBrowseId = sourceBrowseId ?: item.song.album?.browseId
                if (contextTracks.isNotEmpty()) {
                    val idx = contextTracks.indexOfFirst { it.videoId == item.song.videoId }.coerceAtLeast(0)
                    container.playerController.playQueue(
                        songs = contextTracks,
                        startIndex = idx,
                        shuffled = false,
                        sourceLabel = effectiveLabel,
                        sourceBrowseId = effectiveBrowseId,
                    )
                } else {
                    container.playerController.playSong(
                        song = item.song,
                        sourceLabel = effectiveLabel,
                        sourceBrowseId = effectiveBrowseId,
                    )
                }
            }
            is ShelfItem.Album -> openDetail(item.browseId)
            is ShelfItem.Artist -> openDetail(item.browseId)
            is ShelfItem.Playlist -> openDetail(item.browseId, item.params)
        }
    }

    fun playTracks(
        tracks: List<Song>,
        startIndex: Int,
        shuffled: Boolean,
        sourceLabel: String? = null,
        sourceBrowseId: String? = null,
    ) {
        if (tracks.isEmpty()) return
        container.playerController.playQueue(
            songs = tracks,
            startIndex = startIndex,
            shuffled = shuffled,
            sourceLabel = sourceLabel,
            sourceBrowseId = sourceBrowseId,
        )
    }

    fun setPreferredAudioDevice(deviceId: Int?) {
        container.playerController.setPreferredAudioDevice(deviceId)
    }

    fun toggleLike(song: Song) {
        viewModelScope.launch { container.libraryRepository.toggleLike(song) }
    }

    fun toggleDislike(song: Song) {
        viewModelScope.launch { container.libraryRepository.toggleDislike(song) }
    }

    fun startRadio(song: Song) {
        viewModelScope.launch {
            val related = container.ytMusicRepository.upNext(song.videoId, "RDAMVM${song.videoId}")
                .filterNot { it.videoId == song.videoId }
            val queue = listOf(song) + related
            container.playerController.playQueue(
                songs = queue,
                startIndex = 0,
                shuffled = false,
                sourceLabel = "${song.title} Radio",
            )
        }
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch { container.libraryRepository.createPlaylist(title) }
    }

    fun renamePlaylist(playlistId: String, newTitle: String) {
        viewModelScope.launch {
            val clean = newTitle.trim()
            if (clean.isEmpty()) return@launch
            container.libraryRepository.renamePlaylist(playlistId, clean)
            container.ytMusicRepository.invalidateDetail(playlistId)
            val cleanId = playlistId.removePrefix("VL")
            _detailStack.update { stack ->
                stack.map { entry ->
                    if (entry.browseId.removePrefix("VL") == cleanId) {
                        val page = (entry.state as? UiState.Success)?.data
                        if (page != null) entry.copy(state = UiState.Success(page.copy(title = clean))) else entry
                    } else entry
                }
            }
            if (container.authStore.isSignedIn) {
                container.ytMusicRepository.loadRemoteLibrary().onSuccess { _remoteLibrary.value = it }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            container.libraryRepository.deletePlaylist(playlistId)
            container.ytMusicRepository.invalidateDetail(playlistId)
            val cleanId = playlistId.removePrefix("VL")
            val filtered = _detailStack.value.filterNot { it.browseId.removePrefix("VL") == cleanId }
            if (filtered.size != _detailStack.value.size) {
                _detailStack.value = filtered
                navigator.onDetailStackShrunk(filtered.size)
            }
            if (container.authStore.isSignedIn) {
                container.ytMusicRepository.loadRemoteLibrary().onSuccess { _remoteLibrary.value = it }
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch { container.libraryRepository.removeSongFromPlaylist(playlistId, song) }
    }

    fun togglePlaylistPin(playlistId: String) {
        viewModelScope.launch { container.libraryRepository.togglePlaylistPin(playlistId) }
    }

    fun toggleArtistSubscribed(browseId: String, name: String, thumbnailUrl: String?) {
        viewModelScope.launch { container.libraryRepository.toggleArtistSubscribed(browseId, name, thumbnailUrl) }
    }

    fun toggleSaveCollection(
        browseId: String,
        type: BrowseType,
        title: String,
        subtitle: String?,
        thumbnailUrl: String?,
    ) {
        viewModelScope.launch {
            container.libraryRepository.toggleSaveCollection(browseId, type, title, subtitle, thumbnailUrl)
        }
    }

    private suspend fun resolveTracksForTarget(target: BrowseActionTarget): List<Song> {
        val localPlaylist = target.userPlaylist
        if (localPlaylist != null && (!localPlaylist.isRemoteYtm || localPlaylist.playlistId.startsWith("local_"))) {
            return container.libraryRepository.loadLocalPlaylistDetail(localPlaylist.playlistId)?.tracks.orEmpty()
        }
        val browseId = if (localPlaylist != null && !localPlaylist.playlistId.startsWith("VL")) {
            "VL${localPlaylist.playlistId}"
        } else {
            target.browseId
        }
        return container.ytMusicRepository.loadDetail(browseId, target.params).getOrNull()?.tracks.orEmpty()
    }

    fun playBrowseTarget(target: BrowseActionTarget, shuffled: Boolean) {
        viewModelScope.launch {
            val tracks = resolveTracksForTarget(target)
            if (tracks.isNotEmpty()) {
                container.playerController.playQueue(
                    songs = tracks,
                    startIndex = 0,
                    shuffled = shuffled,
                    sourceLabel = target.title,
                    sourceBrowseId = target.browseId,
                )
            }
        }
    }

    fun queueBrowseTarget(target: BrowseActionTarget, playNext: Boolean) {
        viewModelScope.launch {
            val tracks = resolveTracksForTarget(target)
            if (tracks.isEmpty()) return@launch
            if (playNext) {
                container.playerController.playNextAll(tracks)
            } else {
                container.playerController.addAllToQueue(tracks)
            }
        }
    }

    fun downloadBrowseTarget(target: BrowseActionTarget) {
        viewModelScope.launch {
            val tracks = resolveTracksForTarget(target)
            if (tracks.isNotEmpty()) {
                container.downloadRepository.downloadSongs(tracks, target.browseId, target.title)
            }
        }
    }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch { container.libraryRepository.addSongToPlaylist(playlistId, song) }
    }

    fun createPlaylistAndAddSong(title: String, song: Song) {
        viewModelScope.launch {
            val created = container.libraryRepository.createPlaylist(title)
            container.libraryRepository.addSongToPlaylist(created.playlistId, song)
        }
    }

    fun toggleDownload(song: Song) {
        if (song.videoId in downloadedIds.value) {
            container.downloadRepository.removeDownload(song.videoId)
        } else {
            container.downloadRepository.downloadSong(song)
        }
    }

    fun downloadAll(
        songs: List<Song>,
        collectionId: String? = null,
        collectionTitle: String? = null,
    ) {
        container.downloadRepository.downloadSongs(songs, collectionId, collectionTitle)
    }

    fun retryDownload(song: Song) {
        container.downloadRepository.retryDownload(song)
    }

    fun cancelDownload(videoId: String) {
        container.downloadRepository.cancelDownload(videoId)
    }

    fun removeDownload(videoId: String) {
        container.downloadRepository.removeDownload(videoId)
    }

    fun clearHistory() {
        viewModelScope.launch {
            container.playHistorySessionTracker.clearActiveSessions()
            container.libraryRepository.clearHistory()
        }
    }

    fun selectReplayPeriod(period: ReplayPeriod) {
        _replayPeriod.value = period
    }

    fun prepareReplayLaunch(section: ReplayPassSection) {
        _replayPeriod.value = ReplayPeriod.ALL_TIME
        _replayLandingSection.value = section
    }

    fun loadLyricsFor(song: Song, forceSource: LyricsSourceId? = null) {
        lyricsJob?.cancel()
        singleProviderJob?.cancel()
        resetLyricsTranslationState()

        if (activeLyricsVideoId != song.videoId) {
            activeLyricsVideoId = song.videoId
            lyricsProviderResults.clear()
            _lyricsProviderStatuses.value = emptyMap()
        }

        _lyricsState.value = UiState.Loading
        lyricsJob = viewModelScope.launch {
            val s = settings.value
            val query = LyricsQuery(
                videoId = song.videoId,
                title = song.title,
                artist = song.artists.firstOrNull()?.name ?: song.artistText,
                album = song.album?.name,
                durationMs = song.durationMs,
            )
            val result = container.lyricsRepository.fetchLyrics(
                query = query,
                enabledSources = s.enabledLyricsSources,
                priorityOrder = s.lyricsSourceOrder,
                preferWordSynced = s.preferWordSyncedLyrics,
                forceSource = forceSource,
                onProviderStatus = { src, status, res ->
                    if (activeLyricsVideoId == song.videoId) {
                        if (res != null) lyricsProviderResults[src] = res
                        _lyricsProviderStatuses.update { it + (src to status) }
                    }
                },
            )
            if (activeLyricsVideoId == song.videoId) {
                if (result != null) {
                    lyricsProviderResults[result.source] = result
                    _lyricsProviderStatuses.update { it + (result.source to LyricsProviderStatus.FOUND) }
                }
                _lyricsState.value = UiState.Success(result)
            }
        }
    }

    fun selectOrFetchLyricsProvider(song: Song, source: LyricsSourceId) {
        val cached = lyricsProviderResults[source]
        if (cached != null && activeLyricsVideoId == song.videoId) {
            resetLyricsTranslationState()
            val curOffset = (_lyricsState.value as? UiState.Success)?.data?.offsetMs ?: cached.offsetMs
            val applied = cached.copy(offsetMs = curOffset)
            _lyricsState.value = UiState.Success(applied)
            viewModelScope.launch {
                container.lyricsRepository.saveOffset(song.videoId, curOffset)
            }
            return
        }
        singleProviderJob?.cancel()
        _lyricsProviderStatuses.update { it + (source to LyricsProviderStatus.FETCHING) }
        singleProviderJob = viewModelScope.launch {
            val query = LyricsQuery(
                videoId = song.videoId,
                title = song.title,
                artist = song.artists.firstOrNull()?.name ?: song.artistText,
                album = song.album?.name,
                durationMs = song.durationMs,
            )
            val fetched = container.lyricsRepository.fetchSingleProvider(query, source)
            if (activeLyricsVideoId != song.videoId) return@launch
            if (fetched != null) {
                lyricsProviderResults[source] = fetched
                _lyricsProviderStatuses.update { it + (source to LyricsProviderStatus.FOUND) }
                resetLyricsTranslationState()
                val curOffset = (_lyricsState.value as? UiState.Success)?.data?.offsetMs ?: 0L
                _lyricsState.value = UiState.Success(fetched.copy(offsetMs = curOffset))
            } else {
                _lyricsProviderStatuses.update { it + (source to LyricsProviderStatus.NOT_FOUND) }
            }
        }
    }

    fun toggleLyricsRomanization() {
        val currentSong = playerState.value.currentSong ?: return
        val currentLines = (_lyricsState.value as? UiState.Success)?.data?.lines.orEmpty()
        if (currentLines.isEmpty()) return

        if (_lyricsDisplayMode.value == LyricsDisplayMode.ROMANIZED) {
            resetLyricsTranslationState()
            return
        }

        translationJob?.cancel()
        _lyricsDisplayMode.value = LyricsDisplayMode.ROMANIZED
        _lyricsSubLines.value = null
        _lyricsTranslationLoading.value = true
        _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_romanizing)

        translationJob = viewModelScope.launch {
            val outcome = container.lyricsTranslationEngine.romanize(currentSong.videoId, currentLines)
            _lyricsTranslationLoading.value = false
            when (outcome) {
                is LyricsTranslationOutcome.Success -> {
                    _lyricsSubLines.value = outcome.lines
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_romanized)
                }
                LyricsTranslationOutcome.AlreadyRomanized -> {
                    _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
                    _lyricsSubLines.value = null
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_already_romanized)
                }
                is LyricsTranslationOutcome.AlreadyInTargetLanguage,
                is LyricsTranslationOutcome.Failure,
                -> {
                    _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
                    _lyricsSubLines.value = null
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_translation_failed)
                }
            }
        }
    }

    fun toggleLyricsTranslation() {
        val currentSong = playerState.value.currentSong ?: return
        val currentLines = (_lyricsState.value as? UiState.Success)?.data?.lines.orEmpty()
        if (currentLines.isEmpty()) return

        if (_lyricsDisplayMode.value == LyricsDisplayMode.TRANSLATED) {
            resetLyricsTranslationState()
            return
        }

        translationJob?.cancel()
        val s = settings.value
        val targetCode = LyricsTranslationEngine.resolveTargetLanguageCode(
            explicitSetting = s.translationLanguage,
            appLanguageTag = s.appLanguage.tag,
        )
        val targetLang = LyricsTranslationEngine.LANGUAGES.firstOrNull { it.code.equals(targetCode, ignoreCase = true) }
        val targetName = targetLang?.displayName() ?: Locale.forLanguageTag(targetCode).displayName.ifBlank { targetCode }

        _lyricsDisplayMode.value = LyricsDisplayMode.TRANSLATED
        _lyricsSubLines.value = null
        _lyricsTranslationLoading.value = true
        _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_translating_to, targetName)

        translationJob = viewModelScope.launch {
            val outcome = container.lyricsTranslationEngine.translate(
                trackId = currentSong.videoId,
                lines = currentLines,
                targetLanguageCode = targetCode,
                targetDisplayName = targetName,
            )
            _lyricsTranslationLoading.value = false
            when (outcome) {
                is LyricsTranslationOutcome.Success -> {
                    _lyricsSubLines.value = outcome.lines
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_translated_to, targetName)
                }
                is LyricsTranslationOutcome.AlreadyInTargetLanguage -> {
                    _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
                    _lyricsSubLines.value = null
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_already_in_language, outcome.targetDisplayName)
                }
                LyricsTranslationOutcome.AlreadyRomanized,
                is LyricsTranslationOutcome.Failure,
                -> {
                    _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
                    _lyricsSubLines.value = null
                    _lyricsTranslationNotice.value = getApplication<Application>().getString(R.string.lyrics_translation_failed)
                }
            }
        }
    }

    private fun resetLyricsTranslationState() {
        translationJob?.cancel()
        _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
        _lyricsSubLines.value = null
        _lyricsTranslationLoading.value = false
        _lyricsTranslationNotice.value = null
    }

    fun updateLyricsOffset(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-5_000L, 5_000L)
        val song = playerState.value.currentSong ?: return
        val current = (_lyricsState.value as? UiState.Success)?.data ?: return
        _lyricsState.value = UiState.Success(current.copy(offsetMs = clamped))
        viewModelScope.launch { container.lyricsRepository.saveOffset(song.videoId, clamped) }
    }

    // ---- Settings & Cache ----

    fun refreshAudioCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _audioCacheBytes.value = container.audioCacheProvider.streamCacheSizeBytes()
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { container.settingsStore.setThemeMode(mode) }
    fun setPlayerTheme(theme: PlayerTheme) = viewModelScope.launch { container.settingsStore.setPlayerTheme(theme) }
    fun setFullBleedCoverArt(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setFullBleedCoverArt(enabled) }
    fun setShowVolumeSlider(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setShowVolumeSlider(enabled) }
    fun setShowRemainingTime(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setShowRemainingTime(enabled) }
    fun setBlurUnfocusedLyrics(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setBlurUnfocusedLyrics(enabled) }
    fun setShowLyricsPreviewStrip(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setShowLyricsPreviewStrip(enabled) }
    fun setSleepTimerFadeOut(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setSleepTimerFadeOut(enabled) }
    fun setAudioCacheLimitMb(mb: Int) = viewModelScope.launch {
        container.settingsStore.setAudioCacheLimitMb(mb)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            container.audioCacheProvider.updateStreamCacheLimitMb(mb)
            _audioCacheBytes.value = container.audioCacheProvider.streamCacheSizeBytes()
        }
    }
    fun setFrostedGlass(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setFrostedGlass(enabled) }
    fun setReduceAnimation(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setReduceAnimation(enabled) }
    fun setHideTopPicks(hidden: Boolean) = viewModelScope.launch { container.settingsStore.setHideTopPicks(hidden) }

    fun refreshLibrary() {
        viewModelScope.launch {
            _libraryRefreshing.value = true
            if (container.authStore.isSignedIn) {
                container.libraryRepository.syncFromYouTubeMusic()
                container.ytMusicRepository.loadRemoteLibrary().onSuccess { _remoteLibrary.value = it }
            }
            _libraryRefreshing.value = false
        }
    }

    fun setSyncPlaybackHistoryToYtm(enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.setSyncPlaybackHistoryToYtm(enabled)
    }

    suspend fun exportFullBackupJson(): String =
        FullBackupSerializer.exportFromDatabase(container.database, settings.value)

    suspend fun importBackupJson(raw: String): Boolean =
        FullBackupSerializer.importIntoDatabase(container.database, container.settingsStore, raw)
    fun setCrossfadeSeconds(seconds: Int) = viewModelScope.launch { container.settingsStore.setCrossfadeSeconds(seconds) }
    fun setSkipSilence(enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.setSkipSilence(enabled)
        container.playerController.setSkipSilence(enabled)
    }
    fun setAutoplay(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setAutoplay(enabled) }
    fun setPlaybackSpeed(speed: Float) = viewModelScope.launch {
        container.settingsStore.setPlaybackSpeed(speed)
        container.playerController.setPlaybackSpeed(speed)
    }
    fun setWifiQuality(quality: AudioQuality) = viewModelScope.launch {
        container.settingsStore.setWifiQuality(quality)
        container.streamResolver.clearCache()
    }
    fun setCellularQuality(quality: AudioQuality) = viewModelScope.launch {
        container.settingsStore.setCellularQuality(quality)
        container.streamResolver.clearCache()
    }
    fun setDownloadQuality(quality: AudioQuality) = viewModelScope.launch {
        container.settingsStore.setDownloadQuality(quality)
    }
    fun setWifiOnlyDownloads(enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.setWifiOnlyDownloads(enabled)
        container.downloadRepository.applyWifiOnlyRequirement(enabled)
    }
    fun setPreferWordLyrics(prefer: Boolean) = viewModelScope.launch { container.settingsStore.setPreferWordSyncedLyrics(prefer) }
    fun setTranslationLanguage(languageCode: String) = viewModelScope.launch { container.settingsStore.setTranslationLanguage(languageCode) }
    fun setShowLyricsCardLogo(show: Boolean) = viewModelScope.launch { container.settingsStore.setShowLyricsCardLogo(show) }
    fun toggleLyricsSource(src: LyricsSourceId, enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.toggleLyricsSource(src, enabled)
    }
    fun moveLyricsSourceUp(src: LyricsSourceId) = viewModelScope.launch {
        val order = settings.value.lyricsSourceOrder.toMutableList()
        val idx = order.indexOf(src)
        if (idx > 0) {
            order.removeAt(idx)
            order.add(idx - 1, src)
            container.settingsStore.setLyricsSourceOrder(order)
        }
    }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch {
        container.settingsStore.setAppLanguage(language)
        container.ytMusicRepository.clearCache()
        loadHome(isRefresh = true)
        loadTopPicks(rebuild = true)
        loadExplore(isRefresh = true)
        loadMixes(isRefresh = true)
    }
    fun clearAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            container.audioCacheProvider.clearStreamCache()
            container.streamResolver.clearCache()
            _audioCacheBytes.value = container.audioCacheProvider.streamCacheSizeBytes()
        }
    }
    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(container.appContext)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }
}
