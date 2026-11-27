package com.music.musica.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.musica.R
import com.music.musica.core.auth.GoogleSession
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.UiState
import com.music.musica.core.settings.UserSettings
import com.music.musica.data.lyrics.SyncedLyricsParser
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.components.BottomFadeScrim
import com.music.musica.ui.components.BrowseActionTarget
import com.music.musica.ui.components.BrowseActionsSheet
import com.music.musica.ui.components.FloatingBarsTapGuard
import com.music.musica.ui.components.FloatingGlassNavBar
import com.music.musica.ui.components.LocalLiquidGlassEnabled
import com.music.musica.ui.components.MiniPlayer
import com.music.musica.ui.components.NavTabItem
import com.music.musica.ui.components.SongActionsSheet
import com.music.musica.ui.navigation.AppRoute
import com.music.musica.ui.onboarding.OnboardingScreen
import com.music.musica.ui.onboarding.YtMusicLoginWebView
import com.music.musica.ui.player.NowPlayingSheet
import com.music.musica.ui.player.PlaybackSpeedSheet
import com.music.musica.ui.player.SleepTimerSheet
import com.music.musica.ui.player.StatsForNerdsDialog
import com.music.musica.ui.player.SyncedLyricsPanel
import com.music.musica.ui.screens.DetailScreen
import com.music.musica.ui.screens.DownloadsScreen
import com.music.musica.ui.screens.ExploreScreen
import com.music.musica.ui.screens.HistoryScreen
import com.music.musica.ui.screens.HomeScreen
import com.music.musica.ui.screens.LibraryScreen
import com.music.musica.ui.screens.MixesScreen
import com.music.musica.ui.screens.PlaylistPickerSheet
import com.music.musica.ui.screens.ReplayScreen
import com.music.musica.ui.screens.SearchScreen
import com.music.musica.ui.screens.SettingsScreen
import com.music.musica.ui.theme.DarkStatusBarEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MusicaAppRoot(
    vm: MainViewModel,
    onOpenEqualizer: (Int) -> Unit,
    onApplyLanguage: (AppLanguage) -> Unit,
    onShareSong: (Song) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // Collect ONLY lightweight, low-frequency top-level flows here so per-tab updates
    // and 250ms playback progress ticks NEVER trigger full-tree recompositions.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val authSession by vm.authSession.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val detailStack by vm.detailStack.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val dislikedIds by vm.dislikedIds.collectAsStateWithLifecycle()
    val subscribedArtistIds by vm.subscribedArtistIds.collectAsStateWithLifecycle()
    val savedCollectionIds by vm.savedCollectionIds.collectAsStateWithLifecycle()
    val downloadedIds by vm.downloadedIds.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var nowPlayingExpanded by rememberSaveable { mutableStateOf(false) }
    var onboardingLoginOpen by remember { mutableStateOf(false) }
    var appForeground by remember { mutableStateOf(true) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        appForeground = true
        vm.refreshTopPicksIfStale()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        appForeground = false
    }

    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var browseActionTarget by remember { mutableStateOf<BrowseActionTarget?>(null) }
    var playlistPickerSong by remember { mutableStateOf<Song?>(null) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }

    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val mixesListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val searchListState = rememberLazyListState()

    val navigator = vm.navigator
    val hazeState = remember { HazeState() }
    val playerHazeState = remember { HazeState() }

    // Lazy-load secondary tabs only when visited to keep cold start fast and light
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> vm.ensureExploreLoaded()
            2 -> vm.ensureMixesLoaded()
            3 -> vm.ensureRemoteLibraryLoaded()
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = vm.exportFullBackupJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            val msgRes = if (ok) R.string.settings_backup_saved else R.string.settings_backup_failed
            Toast.makeText(context, resources.getString(msgRes), Toast.LENGTH_SHORT).show()
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching false
                    vm.importBackupJson(text)
                }.getOrDefault(false)
            }
            val msgRes = if (ok) R.string.settings_backup_restored else R.string.settings_backup_failed
            Toast.makeText(context, resources.getString(msgRes), Toast.LENGTH_SHORT).show()
        }
    }

    val onPlayNextWithToast = remember(vm, context, resources) {
        { song: Song ->
            vm.container.playerController.playNext(song)
            Toast.makeText(context, resources.getString(R.string.toast_added_play_next), Toast.LENGTH_SHORT).show()
        }
    }
    val onAddToQueueWithToast = remember(vm, context, resources) {
        { song: Song ->
            vm.container.playerController.addToQueue(song)
            Toast.makeText(context, resources.getString(R.string.toast_added_queue), Toast.LENGTH_SHORT).show()
        }
    }
    val onOpenSongMore = remember { { song: Song -> actionMenuSong = song } }
    val onOpenBrowseMore: (ShelfItem) -> Unit = remember {
        { item: ShelfItem ->
            BrowseActionTarget.fromShelfItem(item)?.let { browseActionTarget = it }
        }
    }
    val onOpenTopPickMore: (TopPick) -> Unit = remember {
        { pick: TopPick ->
            when (pick.kind) {
                TopPickKind.LATEST_RELEASE -> pick.browseId?.takeIf { it.isNotBlank() }?.let { id ->
                    browseActionTarget = BrowseActionTarget(
                        browseId = id,
                        type = if (id.startsWith("VL")) BrowseType.PLAYLIST else BrowseType.ALBUM,
                        title = pick.title,
                        subtitle = pick.artistName ?: pick.subtitle.ifBlank { null },
                        thumbnailUrl = pick.images.firstOrNull(),
                        params = pick.fallbackParams,
                    )
                }
                TopPickKind.FEATURED_ARTIST,
                TopPickKind.ARTIST_AND_SIMILAR,
                -> pick.artistBrowseId?.takeIf { it.isNotBlank() }?.let { artistId ->
                    browseActionTarget = BrowseActionTarget(
                        browseId = artistId,
                        type = BrowseType.ARTIST,
                        title = pick.artistName ?: pick.title,
                        subtitle = pick.subtitle.ifBlank { null },
                        thumbnailUrl = pick.images.firstOrNull(),
                    )
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(playerState.errorMessage) {
        val msg = playerState.errorMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.container.playerController.clearError()
        }
    }

    val visibleRoute = navigator.visibleRoute(detailStack.size)
    val topDetail = if (visibleRoute == null) detailStack.lastOrNull() else null
    val hasActiveOverlay = visibleRoute != null || topDetail != null

    val showingDarkSurface = (!settings.onboardingCompleted && !onboardingLoginOpen) ||
        (settings.onboardingCompleted && (
            (nowPlayingExpanded && playerState.currentSong != null) ||
                visibleRoute == AppRoute.Replay
            ))
    DarkStatusBarEffect(enabled = showingDarkSurface)

    // First-launch Onboarding flow
    if (!settings.onboardingCompleted) {
        BackHandler(enabled = onboardingLoginOpen) {
            onboardingLoginOpen = false
        }
        if (onboardingLoginOpen) {
            YtMusicLoginWebView(
                onCookieCaptured = { cookie ->
                    onboardingLoginOpen = false
                    vm.onGoogleCookieCaptured(cookie)
                },
                onDismiss = { onboardingLoginOpen = false },
            )
        } else {
            OnboardingScreen(
                reduceAnimation = settings.reduceAnimation,
                isSignedIn = authSession?.isSignedIn == true,
                onSignInWithGoogle = { onboardingLoginOpen = true },
                onContinueGuest = { vm.completeOnboarding() },
            )
        }
        return
    }

    LaunchedEffect(visibleRoute) {
        if (visibleRoute == AppRoute.Settings) {
            vm.refreshAudioCacheSize()
        }
    }

    BackHandler(enabled = nowPlayingExpanded || hasActiveOverlay || selectedTab != 0) {
        when {
            nowPlayingExpanded -> nowPlayingExpanded = false
            navigator.pop() -> Unit
            selectedTab != 0 -> selectedTab = 0
        }
    }

    val hasMiniPlayer = playerState.currentSong != null
    val chromeBottomPadding = if (hasMiniPlayer) 156.dp else 98.dp
    val activeVideoId = playerState.currentSong?.videoId

    val tabHomeLabel = stringResource(R.string.tab_home)
    val tabExploreLabel = stringResource(R.string.tab_explore)
    val tabMixesLabel = stringResource(R.string.tab_mixes)
    val tabLibraryLabel = stringResource(R.string.tab_library)
    val tabSearchLabel = stringResource(R.string.tab_search)
    val tabs = remember(tabHomeLabel, tabExploreLabel, tabMixesLabel, tabLibraryLabel, tabSearchLabel) {
        listOf(
            NavTabItem(tabHomeLabel, Icons.Rounded.Home),
            NavTabItem(tabExploreLabel, Icons.Rounded.GridView),
            NavTabItem(tabMixesLabel, Icons.Rounded.Sensors),
            NavTabItem(tabLibraryLabel, Icons.Rounded.LibraryMusic),
            NavTabItem(tabSearchLabel, Icons.Rounded.Search),
        )
    }

    CompositionLocalProvider(LocalLiquidGlassEnabled provides settings.frostedGlassEnabled) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
        ) {
            when {
                visibleRoute == AppRoute.YtLogin -> YtMusicLoginWebView(
                    onCookieCaptured = { cookie ->
                        vm.onGoogleCookieCaptured(cookie)
                        navigator.pop()
                    },
                    onDismiss = { navigator.pop() },
                )

                visibleRoute == AppRoute.Settings -> SettingsOverlayHost(
                    vm = vm,
                    settings = settings,
                    authSession = authSession,
                    audioSessionId = playerState.audioSessionId,
                    hazeState = hazeState,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                    onSignIn = { navigator.push(AppRoute.YtLogin) },
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenDownloads = { navigator.push(AppRoute.Downloads) },
                    onExportBackup = { exportBackupLauncher.launch("musica-backup.json") },
                    onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/plain")) },
                    onApplyLanguage = onApplyLanguage,
                )

                visibleRoute == AppRoute.History -> HistoryOverlayHost(
                    vm = vm,
                    activeVideoId = activeVideoId,
                    downloadedIds = downloadedIds,
                    hazeState = hazeState,
                    frostedGlassEnabled = settings.frostedGlassEnabled,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                    onSongMore = onOpenSongMore,
                )

                visibleRoute == AppRoute.Replay -> ReplayOverlayHost(
                    vm = vm,
                    hazeState = hazeState,
                    frostedGlassEnabled = settings.frostedGlassEnabled,
                    reduceAnimation = settings.reduceAnimation,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                )

                visibleRoute == AppRoute.Downloads -> DownloadsOverlayHost(
                    vm = vm,
                    settings = settings,
                    hazeState = hazeState,
                    frostedGlassEnabled = settings.frostedGlassEnabled,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                )

                topDetail != null -> {
                    val detailLoadingMore by vm.detailLoadingMore.collectAsStateWithLifecycle()
                    DetailScreen(
                        state = topDetail.state,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        subscribedArtistIds = subscribedArtistIds,
                        savedCollectionIds = savedCollectionIds,
                        isLoadingMore = detailLoadingMore,
                        hazeState = hazeState,
                        frostedGlassEnabled = settings.frostedGlassEnabled,
                        fullBleedCoverArt = settings.fullBleedCoverArt,
                        reduceAnimation = settings.reduceAnimation,
                        bottomPadding = chromeBottomPadding,
                        onBack = { navigator.pop() },
                        onRetry = vm::retryTopDetail,
                        onLoadMoreTracks = vm::loadMoreDetail,
                        onPlayTracks = vm::playTracks,
                        onDownloadAll = { songs, collectionId, collectionTitle ->
                            vm.downloadAll(songs, collectionId, collectionTitle)
                            Toast.makeText(context, resources.getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
                        },
                        onToggleSubscribeArtist = vm::toggleArtistSubscribed,
                        onToggleSaveCollection = { id, type, title, subtitle, thumb ->
                            val wasSaved = id in savedCollectionIds
                            vm.toggleSaveCollection(id, type, title, subtitle, thumb)
                            val msg = if (wasSaved) R.string.toast_removed_library else R.string.toast_saved_library
                            Toast.makeText(context, resources.getString(msg), Toast.LENGTH_SHORT).show()
                        },
                        onOpenArtist = { artistId -> vm.openDetail(artistId) },
                        onOpenSeeAll = { browseId, params -> vm.openDetail(browseId, params) },
                        onItemClick = { item -> vm.handleShelfItemClick(item) },
                        onBrowseLongClick = onOpenBrowseMore,
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                    )
                }

                else -> when (selectedTab) {
                    0 -> HomeTabHost(
                        vm = vm,
                        authSession = authSession,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        artworkMotionEnabled = appForeground && !nowPlayingExpanded && !hasActiveOverlay && !settings.reduceAnimation,
                        bottomPadding = chromeBottomPadding,
                        listState = homeListState,
                        onTopPickLongClick = onOpenTopPickMore,
                        onBrowseLongClick = onOpenBrowseMore,
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                        onOpenHistory = { navigator.push(AppRoute.History) },
                        onOpenDownloads = { navigator.push(AppRoute.Downloads) },
                        onOpenSettings = { navigator.push(AppRoute.Settings) },
                        onOpenLogin = { navigator.push(AppRoute.YtLogin) },
                    )

                    1 -> ExploreTabHost(
                        vm = vm,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = exploreListState,
                    )

                    2 -> MixesTabHost(
                        vm = vm,
                        isSignedIn = authSession?.isSignedIn == true,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = mixesListState,
                        onBrowseLongClick = onOpenBrowseMore,
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                        onSignIn = { navigator.push(AppRoute.YtLogin) },
                    )

                    3 -> LibraryTabHost(
                        vm = vm,
                        isSignedIn = authSession?.isSignedIn == true,
                        listenerName = authSession?.profile?.name,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = libraryListState,
                        onOpenReplay = { section ->
                            vm.prepareReplayLaunch(section)
                            navigator.push(AppRoute.Replay)
                        },
                        onOpenDownloads = { navigator.push(AppRoute.Downloads) },
                        onOpenHistory = { navigator.push(AppRoute.History) },
                        onOpenLogin = { navigator.push(AppRoute.YtLogin) },
                        onBrowseLongClick = onOpenBrowseMore,
                        onPlaylistLongClick = { pl ->
                            browseActionTarget = BrowseActionTarget.fromUserPlaylist(pl)
                        },
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                    )

                    else -> SearchTabHost(
                        vm = vm,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = searchListState,
                        onBrowseLongClick = onOpenBrowseMore,
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                    )
                }
            }
        }

        val hasModalOverlay = actionMenuSong != null ||
            browseActionTarget != null ||
            playlistPickerSong != null ||
            showSpeedSheet ||
            showSleepSheet ||
            showStatsDialog
        val activeOverlayHazeState = if (nowPlayingExpanded && playerState.currentSong != null) {
            playerHazeState
        } else {
            hazeState
        }

        // Fixed Full-Width Liquid Glass Bottom Chrome (stays permanently expanded while scrolling;
        // pauses live Haze backdrop capture while full-screen NowPlayingSheet or a modal sheet covers the screen)
        if (visibleRoute != AppRoute.YtLogin) {
            val bottomGlassActive = settings.frostedGlassEnabled && !nowPlayingExpanded && !hasModalOverlay
            BottomFadeScrim(
                hasMiniPlayer = hasMiniPlayer,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            FloatingBarsTapGuard(
                height = if (hasMiniPlayer) 132.dp else 76.dp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasMiniPlayer) {
                    MiniPlayer(
                        playerState = playerState,
                        hazeState = hazeState,
                        frostedGlassEnabled = bottomGlassActive,
                        onExpand = { nowPlayingExpanded = true },
                        onTogglePlayPause = { vm.container.playerController.togglePlayPause() },
                        onSkipNext = { vm.container.playerController.skipNext() },
                        onSkipPrevious = { vm.container.playerController.skipPrevious() },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                FloatingGlassNavBar(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    hazeState = hazeState,
                    frostedGlassEnabled = bottomGlassActive,
                    onSelectTab = { idx ->
                        navigator.clear()
                        selectedTab = idx
                    },
                    onActiveTabTap = { idx ->
                        if (hasActiveOverlay) {
                            navigator.clear()
                        } else {
                            scope.launch {
                                when (idx) {
                                    0 -> homeListState.animateScrollToItem(0)
                                    1 -> exploreListState.animateScrollToItem(0)
                                    2 -> mixesListState.animateScrollToItem(0)
                                    3 -> libraryListState.animateScrollToItem(0)
                                    else -> searchListState.animateScrollToItem(0)
                                }
                            }
                        }
                    },
                )
            }
        }

        // Full-Screen Expandable NowPlaying Sheet (only collects 250ms progress while visible;
        // pure vertical slide avoids full-screen alpha saveLayer stalls over Haze)
        AnimatedVisibility(
            visible = nowPlayingExpanded && playerState.currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            NowPlayingOverlayHost(
                vm = vm,
                playerState = playerState,
                likedIds = likedIds,
                settings = settings,
                playerHazeState = playerHazeState,
                onDismiss = { nowPlayingExpanded = false },
                onOpenMoreActions = onOpenSongMore,
                onOpenSpeedSheet = { showSpeedSheet = true },
                onOpenSleepTimerSheet = { showSleepSheet = true },
                onOpenEqualizer = { onOpenEqualizer(playerState.audioSessionId) },
                onOpenStatsForNerds = { showStatsDialog = true },
            )
        }

        // ---- In-Hierarchy Liquid Glass Modal Sheets & Dialogs ----
        // Rendered inside the root Box so live Haze refracts the page or NowPlaying album art behind them.

        actionMenuSong?.let { song ->
            SongActionsSheet(
                song = song,
                isLiked = song.videoId in likedIds,
                isDisliked = song.videoId in dislikedIds,
                isDownloaded = song.videoId in downloadedIds,
                hazeState = activeOverlayHazeState,
                onDismiss = { actionMenuSong = null },
                onPlayNext = { onPlayNextWithToast(song) },
                onAddToQueue = { onAddToQueueWithToast(song) },
                onStartRadio = {
                    vm.startRadio(song)
                    Toast.makeText(context, resources.getString(R.string.toast_radio_started), Toast.LENGTH_SHORT).show()
                },
                onToggleLike = { vm.toggleLike(song) },
                onToggleDislike = { vm.toggleDislike(song) },
                onAddToPlaylist = { playlistPickerSong = song },
                onToggleDownload = {
                    vm.toggleDownload(song)
                    if (song.videoId !in downloadedIds) {
                        Toast.makeText(context, resources.getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onGoToArtist = song.primaryArtistId?.let { artistId ->
                    {
                        nowPlayingExpanded = false
                        vm.openDetail(artistId)
                    }
                },
                onGoToAlbum = song.album?.browseId?.let { albumId ->
                    {
                        nowPlayingExpanded = false
                        vm.openDetail(albumId)
                    }
                },
                onShare = { onShareSong(song) },
                onShowStats = { showStatsDialog = true },
            )
        }

        browseActionTarget?.let { target ->
            val isSavedOrSubscribed = when (target.type) {
                BrowseType.ARTIST -> target.browseId in subscribedArtistIds
                else -> target.browseId in savedCollectionIds
            }
            BrowseActionsSheet(
                target = target,
                isSavedOrSubscribed = isSavedOrSubscribed,
                hazeState = activeOverlayHazeState,
                onDismiss = { browseActionTarget = null },
                onPlay = { vm.playBrowseTarget(target, shuffled = false) },
                onShuffle = { vm.playBrowseTarget(target, shuffled = true) },
                onPlayNext = {
                    vm.queueBrowseTarget(target, playNext = true)
                    Toast.makeText(context, resources.getString(R.string.toast_added_play_next), Toast.LENGTH_SHORT).show()
                },
                onAddToQueue = {
                    vm.queueBrowseTarget(target, playNext = false)
                    Toast.makeText(context, resources.getString(R.string.toast_added_queue), Toast.LENGTH_SHORT).show()
                },
                onToggleSaveOrSubscribe = {
                    if (target.type == BrowseType.ARTIST) {
                        vm.toggleArtistSubscribed(target.browseId, target.title, target.thumbnailUrl)
                    } else {
                        val wasSaved = target.browseId in savedCollectionIds
                        vm.toggleSaveCollection(
                            target.browseId,
                            target.type,
                            target.title,
                            target.subtitle,
                            target.thumbnailUrl,
                        )
                        val msg = if (wasSaved) R.string.toast_removed_library else R.string.toast_saved_library
                        Toast.makeText(context, resources.getString(msg), Toast.LENGTH_SHORT).show()
                    }
                },
                onDownloadAll = if (target.type != BrowseType.ARTIST) {
                    {
                        vm.downloadBrowseTarget(target)
                        Toast.makeText(context, resources.getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
                    }
                } else null,
                onShare = {
                    val isLocalPlaylist = target.userPlaylist?.playlistId?.startsWith("local_") == true
                    val shareText = if (isLocalPlaylist) {
                        target.title
                    } else {
                        val shareUrl = when {
                            target.type == BrowseType.PLAYLIST ->
                                "https://music.youtube.com/playlist?list=${target.browseId.removePrefix("VL")}"
                            target.type == BrowseType.ARTIST ->
                                "https://music.youtube.com/channel/${target.browseId}"
                            else ->
                                "https://music.youtube.com/browse/${target.browseId}"
                        }
                        "${target.title}\n$shareUrl"
                    }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, target.title))
                },
                onTogglePinPlaylist = target.userPlaylist?.let { pl ->
                    { vm.togglePlaylistPin(pl.playlistId) }
                },
                onRenamePlaylist = target.userPlaylist?.let { pl ->
                    { newName -> vm.renamePlaylist(pl.playlistId, newName) }
                },
                onDeletePlaylist = target.userPlaylist?.let { pl ->
                    {
                        vm.deletePlaylist(pl.playlistId)
                        Toast.makeText(context, resources.getString(R.string.toast_playlist_deleted), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        playlistPickerSong?.let { song ->
            val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
            PlaylistPickerSheet(
                playlists = localPlaylists,
                hazeState = activeOverlayHazeState,
                onSelectPlaylist = { playlist ->
                    vm.addSongToPlaylist(playlist.playlistId, song)
                    Toast.makeText(
                        context,
                        resources.getString(R.string.toast_added_playlist, playlist.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onCreateAndAdd = { name ->
                    vm.createPlaylistAndAddSong(name, song)
                    Toast.makeText(
                        context,
                        resources.getString(R.string.toast_added_playlist, name),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onDismiss = { playlistPickerSong = null },
            )
        }

        if (showSpeedSheet) {
            PlaybackSpeedSheet(
                currentSpeed = playerState.playbackSpeed,
                hazeState = activeOverlayHazeState,
                onSpeedChange = vm::setPlaybackSpeed,
                onDismiss = { showSpeedSheet = false },
            )
        }

        if (showSleepSheet) {
            SleepTimerSheet(
                remainingMs = playerState.sleepTimerRemainingMs ?: 0L,
                stopAfterTrack = playerState.sleepAfterTrack,
                fadeOutEnabled = settings.sleepTimerFadeOut,
                hazeState = activeOverlayHazeState,
                onToggleFadeOut = vm::setSleepTimerFadeOut,
                onSelectMinutes = { vm.container.sleepTimerManager.startMinutes(it) },
                onStopAfterTrack = { vm.container.sleepTimerManager.setStopAfterCurrentTrack() },
                onCancelTimer = { vm.container.sleepTimerManager.cancel() },
                onDismiss = { showSleepSheet = false },
            )
        }

        if (showStatsDialog) {
            val progress by vm.playbackProgress.collectAsStateWithLifecycle()
            val liveStats = remember(playerState.nerdStats, progress.bufferedMs, progress.positionMs) {
                playerState.nerdStats.copy(
                    bufferedMs = (progress.bufferedMs - progress.positionMs).coerceAtLeast(0L),
                )
            }
            StatsForNerdsDialog(
                stats = liveStats,
                hazeState = activeOverlayHazeState,
                onDismiss = { showStatsDialog = false },
            )
        }
    }
    }
}

// ---- Per-Screen Scoped State Collectors (isolates recomposition per screen) ----

@Composable
private fun HomeTabHost(
    vm: MainViewModel,
    authSession: GoogleSession?,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    settings: UserSettings,
    artworkMotionEnabled: Boolean,
    bottomPadding: Dp,
    listState: LazyListState,
    onTopPickLongClick: (TopPick) -> Unit,
    onBrowseLongClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val homeState by vm.homeState.collectAsStateWithLifecycle()
    val homeRefreshing by vm.homeRefreshing.collectAsStateWithLifecycle()
    val topPicks by vm.topPicks.collectAsStateWithLifecycle()
    val topPicksLoading by vm.topPicksLoading.collectAsStateWithLifecycle()
    val activeTopPickLoadingKey by vm.activeTopPickLoadingKey.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayedSongs.collectAsStateWithLifecycle()
    HomeScreen(
        state = homeState,
        topPicks = topPicks,
        topPicksLoading = topPicksLoading,
        activeTopPickLoadingKey = activeTopPickLoadingKey,
        hideTopPicks = settings.hideTopPicks,
        artworkMotionEnabled = artworkMotionEnabled,
        recentlyPlayed = recentlyPlayed,
        profile = authSession?.profile,
        isSignedIn = authSession?.isSignedIn == true,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        isRefreshing = homeRefreshing,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onRefresh = { vm.loadHome(isRefresh = true) },
        onLoadMore = vm::loadMoreHome,
        onTopPickClick = { pick, _ ->
            vm.openTopPick(pick) {
                Toast.makeText(context, resources.getString(R.string.top_pick_load_failed), Toast.LENGTH_SHORT).show()
            }
        },
        onTopPickLongClick = onTopPickLongClick,
        onItemClick = { vm.handleShelfItemClick(it) },
        onBrowseLongClick = onBrowseLongClick,
        onSongMore = onSongMore,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onOpenHistory = onOpenHistory,
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        onOpenLogin = onOpenLogin,
    )
}

@Composable
private fun ExploreTabHost(
    vm: MainViewModel,
    hazeState: HazeState,
    settings: UserSettings,
    bottomPadding: Dp,
    listState: LazyListState,
) {
    val exploreState by vm.exploreState.collectAsStateWithLifecycle()
    val exploreRefreshing by vm.exploreRefreshing.collectAsStateWithLifecycle()
    ExploreScreen(
        state = exploreState,
        isRefreshing = exploreRefreshing,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onRefresh = { vm.loadExplore(isRefresh = true) },
        onCategoryClick = { cat -> vm.openDetail(cat.browseId, cat.params) },
    )
}

@Composable
private fun MixesTabHost(
    vm: MainViewModel,
    isSignedIn: Boolean,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    settings: UserSettings,
    bottomPadding: Dp,
    listState: LazyListState,
    onBrowseLongClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onSignIn: () -> Unit,
) {
    val mixesState by vm.mixesState.collectAsStateWithLifecycle()
    val mixesRefreshing by vm.mixesRefreshing.collectAsStateWithLifecycle()
    MixesScreen(
        state = mixesState,
        isSignedIn = isSignedIn,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        isRefreshing = mixesRefreshing,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onRefresh = { vm.loadMixes(isRefresh = true) },
        onItemClick = { vm.handleShelfItemClick(it) },
        onBrowseLongClick = onBrowseLongClick,
        onSongMore = onSongMore,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onSignIn = onSignIn,
    )
}

@Composable
private fun LibraryTabHost(
    vm: MainViewModel,
    isSignedIn: Boolean,
    listenerName: String?,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    settings: UserSettings,
    bottomPadding: Dp,
    listState: LazyListState,
    onOpenReplay: (com.music.musica.data.repository.ReplayPassSection) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLogin: () -> Unit,
    onBrowseLongClick: (ShelfItem) -> Unit,
    onPlaylistLongClick: (com.music.musica.core.model.UserPlaylist) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val likedSongs by vm.likedSongs.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val remoteLibrary by vm.remoteLibrary.collectAsStateWithLifecycle()
    val replaySummary by vm.allTimeReplaySummary.collectAsStateWithLifecycle()
    val libraryRefreshing by vm.libraryRefreshing.collectAsStateWithLifecycle()
    val memberSinceMs = remember(replaySummary.firstPlayedAtMs, vm.appInstalledAtMs) {
        listOfNotNull(
            replaySummary.firstPlayedAtMs?.takeIf { it > 0L },
            vm.appInstalledAtMs.takeIf { it > 0L },
        ).minOrNull()
    }
    LibraryScreen(
        likedSongs = likedSongs,
        playlists = localPlaylists,
        remoteLibraryShelves = remoteLibrary,
        replaySummary = replaySummary,
        listenerName = listenerName,
        memberSinceMs = memberSinceMs,
        downloadedCount = downloadedIds.size,
        isSignedIn = isSignedIn,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        isRefreshing = libraryRefreshing,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onRefresh = vm::refreshLibrary,
        onPlayLiked = { idx -> vm.playTracks(likedSongs, idx, shuffled = false) },
        onOpenPlaylist = vm::openLocalPlaylist,
        onCreatePlaylist = vm::createPlaylist,
        onTogglePinPlaylist = vm::togglePlaylistPin,
        onPlaylistLongClick = onPlaylistLongClick,
        onOpenDownloads = onOpenDownloads,
        onOpenHistory = onOpenHistory,
        onOpenReplay = onOpenReplay,
        onOpenLogin = onOpenLogin,
        onItemClick = { vm.handleShelfItemClick(it) },
        onBrowseLongClick = onBrowseLongClick,
        onSongMore = onSongMore,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
    )
}

@Composable
private fun SearchTabHost(
    vm: MainViewModel,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    settings: UserSettings,
    bottomPadding: Dp,
    listState: LazyListState,
    onBrowseLongClick: (ShelfItem) -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchFilter by vm.searchFilter.collectAsStateWithLifecycle()
    val searchSuggestions by vm.searchSuggestions.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchHasMore by vm.searchHasMore.collectAsStateWithLifecycle()
    val searchLoadingMore by vm.searchLoadingMore.collectAsStateWithLifecycle()
    SearchScreen(
        query = searchQuery,
        filter = searchFilter,
        suggestions = searchSuggestions,
        recentQueries = recentSearches,
        results = searchResults,
        hasMore = searchHasMore,
        isLoadingMore = searchLoadingMore,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onQueryChange = vm::onSearchQueryChange,
        onSubmitQuery = { q -> vm.submitSearch(q) },
        onLoadMore = vm::loadMoreSearch,
        onFilterSelect = vm::selectSearchFilter,
        onClearRecent = vm::clearRecentSearches,
        onDeleteRecent = vm::deleteRecentSearch,
        onItemClick = { vm.handleShelfItemClick(it) },
        onBrowseLongClick = onBrowseLongClick,
        onSongMore = onSongMore,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
    )
}

@Composable
private fun SettingsOverlayHost(
    vm: MainViewModel,
    settings: UserSettings,
    authSession: GoogleSession?,
    audioSessionId: Int,
    hazeState: HazeState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenEqualizer: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onApplyLanguage: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val networkType by vm.networkType.collectAsStateWithLifecycle()
    val audioCacheBytes by vm.audioCacheBytes.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        profile = authSession?.profile,
        isSignedIn = authSession?.isSignedIn == true,
        networkType = networkType,
        audioCacheBytes = audioCacheBytes,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onSignIn = onSignIn,
        onSignOut = vm::signOut,
        onSetSyncYtmHistory = vm::setSyncPlaybackHistoryToYtm,
        onSetCrossfade = vm::setCrossfadeSeconds,
        onSetSkipSilence = vm::setSkipSilence,
        onSetAutoplay = vm::setAutoplay,
        onOpenEqualizer = { onOpenEqualizer(audioSessionId) },
        onSetWifiQuality = vm::setWifiQuality,
        onSetCellularQuality = vm::setCellularQuality,
        onSetDownloadQuality = vm::setDownloadQuality,
        onSetWifiOnlyDownloads = vm::setWifiOnlyDownloads,
        onSetTheme = vm::setThemeMode,
        onSetPlayerTheme = vm::setPlayerTheme,
        onSetFullBleedCover = vm::setFullBleedCoverArt,
        onSetShowVolumeSlider = vm::setShowVolumeSlider,
        onSetShowRemainingTime = vm::setShowRemainingTime,
        onSetFrostedGlass = vm::setFrostedGlass,
        onSetReduceAnimation = vm::setReduceAnimation,
        onSetHideTopPicks = vm::setHideTopPicks,
        onSetPreferWordLyrics = vm::setPreferWordLyrics,
        onSetBlurUnfocusedLyrics = vm::setBlurUnfocusedLyrics,
        onSetShowLyricsPreview = vm::setShowLyricsPreviewStrip,
        onToggleLyricsSource = vm::toggleLyricsSource,
        onMoveLyricsSourceUp = vm::moveLyricsSourceUp,
        onSetAudioCacheLimitMb = vm::setAudioCacheLimitMb,
        onOpenDownloads = onOpenDownloads,
        onClearAudioCache = {
            vm.clearAudioCache()
            Toast.makeText(context, resources.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
        },
        onClearImageCache = {
            vm.clearImageCache()
            Toast.makeText(context, resources.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
        },
        onExportBackup = onExportBackup,
        onImportBackup = onImportBackup,
        onSetLanguage = { lang ->
            vm.setAppLanguage(lang)
            onApplyLanguage(lang)
        },
    )
}

@Composable
private fun HistoryOverlayHost(
    vm: MainViewModel,
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onSongMore: (Song) -> Unit,
) {
    val recentHistory by vm.recentHistory.collectAsStateWithLifecycle()
    HistoryScreen(
        events = recentHistory,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        hazeState = hazeState,
        frostedGlassEnabled = frostedGlassEnabled,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onClearHistory = vm::clearHistory,
        onPlaySong = { vm.container.playerController.playSong(it) },
        onSongMore = onSongMore,
    )
}

@Composable
private fun ReplayOverlayHost(
    vm: MainViewModel,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    reduceAnimation: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
) {
    val replayPeriod by vm.replayPeriod.collectAsStateWithLifecycle()
    val replaySummary by vm.replaySummary.collectAsStateWithLifecycle()
    val landingSection by vm.replayLandingSection.collectAsStateWithLifecycle()
    ReplayScreen(
        period = replayPeriod,
        summary = replaySummary,
        landingSection = landingSection,
        hazeState = hazeState,
        frostedGlassEnabled = frostedGlassEnabled,
        reduceAnimation = reduceAnimation,
        bottomPadding = bottomPadding,
        onSelectPeriod = vm::selectReplayPeriod,
        onBack = onBack,
        onPlayTopSongs = { songs, idx -> vm.playTracks(songs, idx, shuffled = false) },
        onOpenArtist = { artistId -> vm.openDetail(artistId) },
        onOpenAlbum = { albumId -> vm.openDetail(albumId) },
    )
}

@Composable
private fun DownloadsOverlayHost(
    vm: MainViewModel,
    settings: UserSettings,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val networkType by vm.networkType.collectAsStateWithLifecycle()
    DownloadsScreen(
        downloads = downloads,
        wifiOnlyDownloads = settings.wifiOnlyDownloads,
        networkType = networkType,
        hazeState = hazeState,
        frostedGlassEnabled = frostedGlassEnabled,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onPlayTracks = { songs, idx -> vm.playTracks(songs, idx, shuffled = false) },
        onRetryDownload = vm::retryDownload,
        onCancelDownload = vm::cancelDownload,
        onRemoveDownload = vm::removeDownload,
    )
}

@Composable
private fun NowPlayingOverlayHost(
    vm: MainViewModel,
    playerState: PlayerUiState,
    likedIds: Set<String>,
    settings: UserSettings,
    playerHazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenMoreActions: (Song) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenSleepTimerSheet: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenStatsForNerds: () -> Unit,
) {
    val progress by vm.playbackProgress.collectAsStateWithLifecycle()
    val lyricsState by vm.lyricsState.collectAsStateWithLifecycle()
    val currentSong = playerState.currentSong

    val liveLyricPreview = if (settings.showLyricsPreviewStrip) {
        val synced = (lyricsState as? UiState.Success)?.data?.takeIf { it.isSynced && it.lines.isNotEmpty() }
        if (synced != null) {
            val idx = SyncedLyricsParser.activeLineIndex(synced.lines, progress.positionMs, synced.offsetMs)
            synced.lines.getOrNull(idx)?.text
        } else null
    } else null

    NowPlayingSheet(
        playerState = playerState,
        progress = progress,
        isLiked = currentSong != null && currentSong.videoId in likedIds,
        playerTheme = settings.playerTheme,
        fullBleedCoverArt = settings.fullBleedCoverArt,
        showVolumeSlider = settings.showVolumeSlider,
        showRemainingTime = settings.showRemainingTime,
        currentLyricPreview = liveLyricPreview,
        reduceAnimation = settings.reduceAnimation,
        playerHazeState = playerHazeState,
        lyricsContent = { mod ->
            SyncedLyricsPanel(
                lyricsState = lyricsState,
                playerPositionMs = progress.positionMs,
                isPlaying = playerState.isPlaying,
                playbackSpeed = playerState.playbackSpeed,
                blurUnfocusedLyrics = settings.blurUnfocusedLyrics,
                reduceAnimation = settings.reduceAnimation,
                onSeekTo = { vm.container.playerController.seekTo(it) },
                onChangeOffset = vm::updateLyricsOffset,
                onSwitchProvider = { src ->
                    currentSong?.let { vm.loadLyricsFor(it, forceSource = src) }
                },
                modifier = mod,
            )
        },
        onDismiss = onDismiss,
        onTogglePlayPause = { vm.container.playerController.togglePlayPause() },
        onSkipNext = { vm.container.playerController.skipNext() },
        onSkipPrevious = { vm.container.playerController.skipPrevious() },
        onSeekTo = { vm.container.playerController.seekTo(it) },
        onToggleShuffle = { vm.container.playerController.toggleShuffle() },
        onCycleRepeat = { vm.container.playerController.cycleRepeatMode() },
        onToggleLike = { currentSong?.let(vm::toggleLike) },
        onToggleAutoplay = vm::setAutoplay,
        onToggleRemainingTime = { vm.setShowRemainingTime(!settings.showRemainingTime) },
        onSelectQueueIndex = { vm.container.playerController.selectQueueIndex(it) },
        onMoveQueueItem = { from, to -> vm.container.playerController.moveQueueItem(from, to) },
        onRemoveQueueItem = { vm.container.playerController.removeQueueItem(it) },
        onClearUpcomingQueue = { vm.container.playerController.clearUpcomingQueue() },
        onGoToArtist = { artistId -> vm.openDetail(artistId) },
        onGoToAlbum = { albumId -> vm.openDetail(albumId) },
        onOpenMoreActions = onOpenMoreActions,
        onOpenSpeedSheet = onOpenSpeedSheet,
        onOpenSleepTimerSheet = onOpenSleepTimerSheet,
        onOpenEqualizer = onOpenEqualizer,
        onOpenStatsForNerds = onOpenStatsForNerds,
    )
}
