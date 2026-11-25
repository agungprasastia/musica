package com.music.musica

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.musica.core.auth.GoogleSession
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.model.UiState
import com.music.musica.core.settings.UserSettings
import com.music.musica.data.lyrics.SyncedLyricsParser
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.MainViewModel
import com.music.musica.ui.components.BottomFadeScrim
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
import com.music.musica.ui.theme.MusicaTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional notification permission on Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestNotificationsIfNeeded()

        setContent {
            val vm: MainViewModel = viewModel()
            val loadedSettings by vm.loadedSettings.collectAsStateWithLifecycle()

            MusicaTheme(themeMode = loadedSettings?.themeMode ?: ThemeMode.SYSTEM) {
                if (loadedSettings == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    MusicaAppRoot(
                        vm = vm,
                        onOpenEqualizer = ::openSystemEqualizer,
                        onApplyLanguage = ::applyAppLanguage,
                        onShareSong = ::shareSong,
                    )
                }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openSystemEqualizer(audioSessionId: Int) {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.player_equalizer_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAppLanguage(language: AppLanguage) {
        val locales = if (language.tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun shareSong(song: Song) {
        val url = "https://music.youtube.com/watch?v=${song.videoId}"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artistText}\n$url")
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.menu_share)))
    }
}

@Composable
private fun MusicaAppRoot(
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
    val downloadedIds by vm.downloadedIds.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var nowPlayingExpanded by rememberSaveable { mutableStateOf(false) }
    var onboardingLoginOpen by remember { mutableStateOf(false) }

    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
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
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(vm.exportSettingsJson().toByteArray(Charsets.UTF_8))
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
                    vm.importSettingsJson(text)
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

    LaunchedEffect(playerState.errorMessage) {
        val msg = playerState.errorMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.container.playerController.clearError()
        }
    }

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
                onSignInWithGoogle = { onboardingLoginOpen = true },
                onContinueGuest = { vm.completeOnboarding() },
            )
        }
        return
    }

    val visibleRoute = navigator.visibleRoute(detailStack.size)
    val topDetail = if (visibleRoute == null) detailStack.lastOrNull() else null
    val hasActiveOverlay = visibleRoute != null || topDetail != null

    val showingDarkSurface = !settings.onboardingCompleted && !onboardingLoginOpen ||
        (nowPlayingExpanded && playerState.currentSong != null) ||
        visibleRoute == AppRoute.Replay
    DarkStatusBarEffect(enabled = showingDarkSurface)

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
                    hazeState = hazeState,
                    frostedGlassEnabled = settings.frostedGlassEnabled,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                )

                topDetail != null -> DetailScreen(
                    state = topDetail.state,
                    activeVideoId = activeVideoId,
                    downloadedIds = downloadedIds,
                    hazeState = hazeState,
                    frostedGlassEnabled = settings.frostedGlassEnabled,
                    fullBleedCoverArt = settings.fullBleedCoverArt,
                    reduceAnimation = settings.reduceAnimation,
                    bottomPadding = chromeBottomPadding,
                    onBack = { navigator.pop() },
                    onRetry = vm::retryTopDetail,
                    onPlayTracks = vm::playTracks,
                    onDownloadAll = { songs ->
                        vm.downloadAll(songs)
                        Toast.makeText(context, resources.getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
                    },
                    onItemClick = { item -> vm.handleShelfItemClick(item) },
                    onSongMore = onOpenSongMore,
                    onPlayNext = onPlayNextWithToast,
                    onAddToQueue = onAddToQueueWithToast,
                )

                else -> when (selectedTab) {
                    0 -> HomeTabHost(
                        vm = vm,
                        authSession = authSession,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = homeListState,
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
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                        onSignIn = { navigator.push(AppRoute.YtLogin) },
                    )

                    3 -> LibraryTabHost(
                        vm = vm,
                        isSignedIn = authSession?.isSignedIn == true,
                        activeVideoId = activeVideoId,
                        downloadedIds = downloadedIds,
                        hazeState = hazeState,
                        settings = settings,
                        bottomPadding = chromeBottomPadding,
                        listState = libraryListState,
                        onOpenReplay = { navigator.push(AppRoute.Replay) },
                        onOpenDownloads = { navigator.push(AppRoute.Downloads) },
                        onOpenHistory = { navigator.push(AppRoute.History) },
                        onOpenLogin = { navigator.push(AppRoute.YtLogin) },
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
                        onSongMore = onOpenSongMore,
                        onPlayNext = onPlayNextWithToast,
                        onAddToQueue = onAddToQueueWithToast,
                    )
                }
            }
        }

        val hasModalOverlay = actionMenuSong != null ||
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
                isDownloaded = song.videoId in downloadedIds,
                hazeState = activeOverlayHazeState,
                onDismiss = { actionMenuSong = null },
                onPlayNext = { onPlayNextWithToast(song) },
                onAddToQueue = { onAddToQueueWithToast(song) },
                onToggleLike = { vm.toggleLike(song) },
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
    bottomPadding: Dp,
    listState: LazyListState,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val homeState by vm.homeState.collectAsStateWithLifecycle()
    val homeRefreshing by vm.homeRefreshing.collectAsStateWithLifecycle()
    val recentlyPlayed by vm.recentlyPlayedSongs.collectAsStateWithLifecycle()
    HomeScreen(
        state = homeState,
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
        onItemClick = { vm.handleShelfItemClick(it) },
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
    activeVideoId: String?,
    downloadedIds: Set<String>,
    hazeState: HazeState,
    settings: UserSettings,
    bottomPadding: Dp,
    listState: LazyListState,
    onOpenReplay: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLogin: () -> Unit,
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val likedSongs by vm.likedSongs.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val remoteLibrary by vm.remoteLibrary.collectAsStateWithLifecycle()
    val replaySummary by vm.replaySummary.collectAsStateWithLifecycle()
    val libraryRefreshing by vm.libraryRefreshing.collectAsStateWithLifecycle()
    LibraryScreen(
        likedSongs = likedSongs,
        playlists = localPlaylists,
        remoteLibraryShelves = remoteLibrary,
        replaySummary = replaySummary,
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
        onOpenDownloads = onOpenDownloads,
        onOpenHistory = onOpenHistory,
        onOpenReplay = onOpenReplay,
        onOpenLogin = onOpenLogin,
        onItemClick = { vm.handleShelfItemClick(it) },
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
    onSongMore: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchFilter by vm.searchFilter.collectAsStateWithLifecycle()
    val searchSuggestions by vm.searchSuggestions.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    SearchScreen(
        query = searchQuery,
        filter = searchFilter,
        suggestions = searchSuggestions,
        recentQueries = recentSearches,
        results = searchResults,
        activeVideoId = activeVideoId,
        downloadedIds = downloadedIds,
        hazeState = hazeState,
        frostedGlassEnabled = settings.frostedGlassEnabled,
        reduceAnimation = settings.reduceAnimation,
        bottomPadding = bottomPadding,
        listState = listState,
        onQueryChange = vm::onSearchQueryChange,
        onSubmitQuery = { q -> vm.submitSearch(q) },
        onFilterSelect = vm::selectSearchFilter,
        onClearRecent = vm::clearRecentSearches,
        onDeleteRecent = vm::deleteRecentSearch,
        onItemClick = { vm.handleShelfItemClick(it) },
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
        onSetCrossfade = vm::setCrossfadeSeconds,
        onSetSkipSilence = vm::setSkipSilence,
        onSetAutoplay = vm::setAutoplay,
        onOpenEqualizer = { onOpenEqualizer(audioSessionId) },
        onSetWifiQuality = vm::setWifiQuality,
        onSetCellularQuality = vm::setCellularQuality,
        onSetTheme = vm::setThemeMode,
        onSetPlayerTheme = vm::setPlayerTheme,
        onSetFullBleedCover = vm::setFullBleedCoverArt,
        onSetShowVolumeSlider = vm::setShowVolumeSlider,
        onSetShowRemainingTime = vm::setShowRemainingTime,
        onSetFrostedGlass = vm::setFrostedGlass,
        onSetReduceAnimation = vm::setReduceAnimation,
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
    ReplayScreen(
        period = replayPeriod,
        summary = replaySummary,
        hazeState = hazeState,
        frostedGlassEnabled = frostedGlassEnabled,
        reduceAnimation = reduceAnimation,
        bottomPadding = bottomPadding,
        onSelectPeriod = vm::selectReplayPeriod,
        onBack = onBack,
        onPlayTopSongs = { songs, idx -> vm.playTracks(songs, idx, shuffled = false) },
        onOpenArtist = { artistId -> vm.openDetail(artistId) },
    )
}

@Composable
private fun DownloadsOverlayHost(
    vm: MainViewModel,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    DownloadsScreen(
        downloads = downloads,
        hazeState = hazeState,
        frostedGlassEnabled = frostedGlassEnabled,
        bottomPadding = bottomPadding,
        onBack = onBack,
        onPlayTracks = { songs, idx -> vm.playTracks(songs, idx, shuffled = false) },
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
