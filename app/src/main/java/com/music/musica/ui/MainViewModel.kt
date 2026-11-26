package com.music.musica.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.music.musica.MusicaApplication
import com.music.musica.core.auth.GoogleSession
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.AudioQuality
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
import com.music.musica.core.model.UiState
import com.music.musica.core.model.UserPlaylist
import com.music.musica.core.network.HttpClients
import com.music.musica.core.settings.UserSettings
import com.music.musica.data.lyrics.LyricsQuery
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.ui.navigation.AppNavigator
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

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
    val localPlaylists = container.libraryRepository.localPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subscribedArtistIds = container.libraryRepository.subscribedArtistIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
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
    private var searchJob: Job? = null

    // ---- Detail Stack + Surviving AppNavigator ----
    private val _detailStack = MutableStateFlow<List<DetailStackEntry>>(emptyList())
    val detailStack: StateFlow<List<DetailStackEntry>> = _detailStack.asStateFlow()

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
    private var lyricsJob: Job? = null

    // ---- Replay ----
    private val _replayPeriod = MutableStateFlow(ReplayPeriod.THIS_MONTH)
    val replayPeriod: StateFlow<ReplayPeriod> = _replayPeriod.asStateFlow()
    val replaySummary: StateFlow<ReplaySummary> = _replayPeriod
        .flatMapLatest { container.libraryRepository.observeReplay(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ReplaySummary())

    private var exploreLoaded = false
    private var mixesLoaded = false
    private var remoteLibraryLoaded = false

    init {
        container.playerController.connect()
        loadHome(isRefresh = false)
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
                }
                .onFailure { err ->
                    if (_homeState.value !is UiState.Success) {
                        _homeState.value = UiState.Error(err.localizedMessage ?: "", err)
                    }
                }
            _homeRefreshing.value = false
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
            _searchResults.value = null
        }
    }

    fun submitSearch(query: String, filter: SearchFilter = _searchFilter.value) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        _searchQuery.value = clean
        _searchFilter.value = filter
        _searchResults.value = UiState.Loading
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            container.libraryRepository.recordSearchQuery(clean)
            container.ytMusicRepository.search(clean, filter)
                .onSuccess { page -> _searchResults.value = UiState.Success(page.items) }
                .onFailure { err -> _searchResults.value = UiState.Error(err.localizedMessage ?: "", err) }
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
        val entry = DetailStackEntry(browseId = playlist.playlistId, isLocalPlaylist = true, state = UiState.Loading)
        _detailStack.update { it + entry }
        fetchDetailForTop(playlist.playlistId, null, isLocal = true, forceRefresh = true)
    }

    fun retryTopDetail() {
        val top = _detailStack.value.lastOrNull() ?: return
        fetchDetailForTop(top.browseId, top.params, top.isLocalPlaylist, forceRefresh = true)
    }

    fun popDetail(): Boolean {
        val current = _detailStack.value
        if (current.isEmpty()) return false
        val next = current.dropLast(1)
        _detailStack.value = next
        navigator.onDetailStackShrunk(next.size)
        return true
    }

    fun clearDetails() {
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
            container.ytMusicRepository.loadRemoteLibrary().onSuccess { shelves ->
                _remoteLibrary.value = shelves
            }
        }
    }

    // ---- Playback, Library & Lyrics ----

    fun handleShelfItemClick(item: ShelfItem, contextTracks: List<Song> = emptyList()) {
        when (item) {
            is ShelfItem.Track -> {
                if (contextTracks.isNotEmpty()) {
                    val idx = contextTracks.indexOfFirst { it.videoId == item.song.videoId }.coerceAtLeast(0)
                    container.playerController.playQueue(contextTracks, idx, shuffled = false)
                } else {
                    container.playerController.playSong(item.song)
                }
            }
            is ShelfItem.Album -> openDetail(item.browseId)
            is ShelfItem.Artist -> openDetail(item.browseId)
            is ShelfItem.Playlist -> openDetail(item.browseId, item.params)
        }
    }

    fun playTracks(tracks: List<Song>, startIndex: Int, shuffled: Boolean) {
        if (tracks.isEmpty()) return
        container.playerController.playQueue(tracks, startIndex, shuffled = shuffled)
    }

    fun toggleLike(song: Song) {
        viewModelScope.launch { container.libraryRepository.toggleLike(song) }
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch { container.libraryRepository.createPlaylist(title) }
    }

    fun togglePlaylistPin(playlistId: String) {
        viewModelScope.launch { container.libraryRepository.togglePlaylistPin(playlistId) }
    }

    fun toggleArtistSubscribed(browseId: String, name: String, thumbnailUrl: String?) {
        viewModelScope.launch { container.libraryRepository.toggleArtistSubscribed(browseId, name, thumbnailUrl) }
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
        viewModelScope.launch { container.libraryRepository.clearHistory() }
    }

    fun selectReplayPeriod(period: ReplayPeriod) {
        _replayPeriod.value = period
    }

    fun loadLyricsFor(song: Song, forceSource: LyricsSourceId? = null) {
        lyricsJob?.cancel()
        _lyricsState.value = UiState.Loading
        lyricsJob = viewModelScope.launch {
            val s = settings.value
            val result = container.lyricsRepository.fetchLyrics(
                query = LyricsQuery(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artists.firstOrNull()?.name ?: song.artistText,
                    album = song.album?.name,
                    durationMs = song.durationMs,
                ),
                enabledSources = s.enabledLyricsSources,
                priorityOrder = s.lyricsSourceOrder,
                preferWordSynced = s.preferWordSyncedLyrics,
                forceSource = forceSource,
            )
            _lyricsState.value = UiState.Success(result)
        }
    }

    fun updateLyricsOffset(offsetMs: Long) {
        val song = playerState.value.currentSong ?: return
        val current = (_lyricsState.value as? UiState.Success)?.data ?: return
        _lyricsState.value = UiState.Success(current.copy(offsetMs = offsetMs))
        viewModelScope.launch { container.lyricsRepository.saveOffset(song.videoId, offsetMs) }
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

    fun refreshLibrary() {
        viewModelScope.launch {
            _libraryRefreshing.value = true
            if (container.authStore.isSignedIn) {
                container.ytMusicRepository.loadRemoteLibrary().onSuccess { _remoteLibrary.value = it }
            }
            _libraryRefreshing.value = false
        }
    }

    fun exportSettingsJson(): String =
        HttpClients.json.encodeToString(settings.value)

    suspend fun importSettingsJson(raw: String): Boolean = runCatching {
        val parsed = HttpClients.json.decodeFromString<UserSettings>(raw)
        container.settingsStore.restoreFrom(parsed)
        true
    }.getOrDefault(false)
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
