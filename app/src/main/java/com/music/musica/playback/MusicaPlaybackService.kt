package com.music.musica.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.musica.MainActivity
import com.music.musica.MusicaApplication
import com.music.musica.core.model.NerdStats
import com.music.musica.core.model.PlaybackSourceMode
import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import com.music.musica.data.stream.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
)

@Immutable
data class PlayerUiState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1.0f,
    val skipSilence: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val crossfadeSeconds: Int = 0,
    val sleepTimerRemainingMs: Long? = null,
    val sleepAfterTrack: Boolean = false,
    val audioSessionId: Int = 0,
    val nerdStats: NerdStats = NerdStats(),
    val errorMessage: String? = null,
)

/**
 * Foreground Media3 MediaSessionService featuring:
 * - Multi-item gapless ExoPlayer Timeline with proactive next-track stream pre-resolution
 * - Bounded-range ChunkedDataSource anti-throttling beneath completed-only offline/LRU caching
 * - Dual-ExoPlayer equal-power crossfade & sleep-timer cosine fade-out
 * - Bounded per-track retry + auto-skip resilience past consecutive unplayable tracks
 * - Official Media3 SessionCommand IPC and paused LastPlayed session restoration
 */
@OptIn(UnstableApi::class)
class MusicaPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var activePlayer: ExoPlayer
    private var standbyPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val queueManager = QueueManager()
    private val crossfadeStateMachine = CrossfadeStateMachine()
    private val resolvedMetadata = ConcurrentHashMap<String, ResolvedStream>()

    private var positionJob: Job? = null
    private var crossfadeJob: Job? = null
    private var autoplayJob: Job? = null
    private var retryJob: Job? = null
    private var sleepFadeJob: Job? = null
    private var prefetchJob: Job? = null
    private var saveLastPlayedJob: Job? = null

    // Accurate listening-time tracker (counts only actual playing time, not paused intervals)
    private var accumulatedPlayMs: Long = 0L
    private var playSegmentStartMs: Long = 0L

    // Bounded per-track retry & consecutive bad-track auto-skip guards
    private var lastRetriedVideoId: String? = null
    private var retryCountForCurrent: Int = 0
    private var consecutiveAutoSkips: Int = 0

    // Cold-start restored resume position (applied lazily when user first taps Play)
    private var pendingResumePositionMs: Long = 0L

    // Guards against reacting to our own setMediaItems() calls in onMediaItemTransition
    private var suppressNextTransition: Boolean = false

    private val container by lazy { (application as MusicaApplication).container }

    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val customCommands = base.availableSessionCommands.buildUpon().apply {
                MusicaSessionCommands.ALL_COMMANDS.forEach { add(it) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(customCommands)
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            handleSessionCommand(customCommand.customAction, args)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        activePlayer = createExoPlayer(handleAudioFocus = true)
        standbyPlayer = createExoPlayer(handleAudioFocus = false)
        attachPlayerListeners(activePlayer)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, createSessionForwardingPlayer(activePlayer))
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()

        instance = this
        hydrateFromRestoredSnapshotIfNeeded()
        observeSettings()
        startPositionTicker()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistLastPlayedNow()
        if (!activePlayer.playWhenReady || activePlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistLastPlayedNow()
        container.playbackTracker.onTrackEnded(activePlayer.currentPosition.coerceAtLeast(0L) / 1_000L)
        recordCompletedListeningSession()
        cancelCrossfade()
        cancelSleepFade()
        positionJob?.cancel()
        autoplayJob?.cancel()
        retryJob?.cancel()
        prefetchJob?.cancel()
        saveLastPlayedJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        activePlayer.release()
        standbyPlayer?.release()
        standbyPlayer = null
        serviceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun hydrateFromRestoredSnapshotIfNeeded() {
        val pending = consumePendingSnapshot()
        if (pending != null) {
            applyRestoredSnapshot(pending)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val loaded = container.lastPlayedStore.load() ?: return@launch
            launch(Dispatchers.Main.immediate) {
                if (queueManager.queue.isEmpty()) {
                    applyRestoredSnapshot(loaded)
                }
            }
        }
    }

    private fun applyRestoredSnapshot(restored: LastPlayedSnapshot) {
        if (queueManager.queue.isNotEmpty()) return
        queueManager.restore(
            songs = restored.queue,
            currentIndex = restored.currentIndex,
            shuffleEnabled = restored.shuffleEnabled,
            repeatMode = restored.repeatMode,
        )
        pendingResumePositionMs = restored.positionMs.coerceAtLeast(0L)
        val song = queueManager.currentSong
        if (song != null) {
            _progress.value = PlaybackProgress(
                positionMs = pendingResumePositionMs,
                durationMs = song.durationMs,
                bufferedMs = 0L,
            )
            updateNerdStatsBase(song.videoId)
        }
        syncUiState()
    }

    private fun handleSessionCommand(action: String, args: Bundle) {
        when (action) {
            MusicaSessionCommands.CMD_PLAY_QUEUE -> {
                val payload = MusicaSessionCommands.takeQueuePayload(
                    args.getString(MusicaSessionCommands.ARG_HANDOFF_ID),
                ) ?: return
                playQueue(payload.songs, payload.startIndex, payload.shuffled)
            }

            MusicaSessionCommands.CMD_SELECT_INDEX -> {
                selectQueueIndex(args.getInt(MusicaSessionCommands.ARG_INDEX, 0))
            }

            MusicaSessionCommands.CMD_PLAY_NEXT -> {
                val song = MusicaSessionCommands.takeSongPayload(
                    args.getString(MusicaSessionCommands.ARG_HANDOFF_ID),
                ) ?: return
                playNext(song)
            }

            MusicaSessionCommands.CMD_ADD_TO_QUEUE -> {
                val song = MusicaSessionCommands.takeSongPayload(
                    args.getString(MusicaSessionCommands.ARG_HANDOFF_ID),
                ) ?: return
                addToQueue(song)
            }

            MusicaSessionCommands.CMD_MOVE_ITEM -> {
                moveQueueItem(
                    args.getInt(MusicaSessionCommands.ARG_FROM_INDEX, -1),
                    args.getInt(MusicaSessionCommands.ARG_TO_INDEX, -1),
                )
            }

            MusicaSessionCommands.CMD_REMOVE_ITEM -> {
                removeQueueItem(args.getInt(MusicaSessionCommands.ARG_INDEX, -1))
            }

            MusicaSessionCommands.CMD_CLEAR_UPCOMING -> clearUpcomingQueue()
            MusicaSessionCommands.CMD_TOGGLE_PLAY_PAUSE -> togglePlayPause()
            MusicaSessionCommands.CMD_SKIP_NEXT -> skipNext(manual = true)
            MusicaSessionCommands.CMD_SKIP_PREVIOUS -> skipPrevious()
            MusicaSessionCommands.CMD_SEEK_TO -> {
                seekTo(args.getLong(MusicaSessionCommands.ARG_POSITION_MS, 0L))
            }

            MusicaSessionCommands.CMD_TOGGLE_SHUFFLE -> toggleShuffle()
            MusicaSessionCommands.CMD_CYCLE_REPEAT -> cycleRepeatMode()
            MusicaSessionCommands.CMD_SET_SPEED -> {
                setPlaybackSpeed(args.getFloat(MusicaSessionCommands.ARG_SPEED, 1.0f))
            }

            MusicaSessionCommands.CMD_SET_SKIP_SILENCE -> {
                setSkipSilence(args.getBoolean(MusicaSessionCommands.ARG_ENABLED, false))
            }

            MusicaSessionCommands.CMD_PAUSE_SLEEP_TIMER -> pauseForSleepTimer()
            MusicaSessionCommands.CMD_CLEAR_ERROR -> clearError()
            MusicaSessionCommands.CMD_UPDATE_SLEEP_UI -> {
                val hasRem = args.getBoolean(MusicaSessionCommands.ARG_HAS_REMAINING, false)
                val rem = if (hasRem) args.getLong(MusicaSessionCommands.ARG_REMAINING_MS, 0L) else null
                val afterTrack = args.getBoolean(MusicaSessionCommands.ARG_AFTER_TRACK, false)
                updateSleepTimerUi(rem, afterTrack)
            }
        }
    }

    // ---- Playback API ----

    fun playQueue(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean? = null) {
        if (songs.isEmpty()) return
        recordCompletedListeningSession()
        cancelCrossfade()
        cancelSleepFade()
        retryJob?.cancel()
        pendingResumePositionMs = 0L
        consecutiveAutoSkips = 0
        container.autoplayController.reset()
        queueManager.setQueue(songs, startIndex, shuffled)
        loadCurrentFromQueue(autoPlay = true)
        syncUiState()
        maybeFetchAutoplay()
        scheduleSaveLastPlayed(immediate = true)
    }

    fun selectQueueIndex(index: Int) {
        recordCompletedListeningSession()
        cancelCrossfade()
        cancelSleepFade()
        retryJob?.cancel()
        pendingResumePositionMs = 0L
        consecutiveAutoSkips = 0
        if (queueManager.selectIndex(index) != null) {
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
            maybeFetchAutoplay()
            scheduleSaveLastPlayed(immediate = true)
        }
    }

    fun playNext(song: Song) {
        cancelCrossfade()
        val wasEmpty = queueManager.queue.isEmpty()
        queueManager.playNext(song)
        if (!wasEmpty) {
            syncUpcomingTimelineItems()
            prefetchNextTrack()
        }
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun addToQueue(song: Song) {
        val wasEmpty = queueManager.queue.isEmpty()
        queueManager.addToQueue(song)
        if (!wasEmpty) {
            syncUpcomingTimelineItems()
            prefetchNextTrack()
        }
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        cancelCrossfade()
        queueManager.move(fromIndex, toIndex)
        syncUpcomingTimelineItems()
        prefetchNextTrack()
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun removeQueueItem(index: Int) {
        val removingCurrent = index == queueManager.currentIndex
        cancelCrossfade()
        queueManager.removeAt(index)
        if (removingCurrent) {
            retryJob?.cancel()
            pendingResumePositionMs = 0L
            if (queueManager.currentSong != null) {
                loadCurrentFromQueue(autoPlay = activePlayer.playWhenReady)
            } else {
                activePlayer.stop()
                activePlayer.clearMediaItems()
                container.lastPlayedStore.clear()
            }
        } else {
            syncUpcomingTimelineItems()
            prefetchNextTrack()
        }
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun clearUpcomingQueue() {
        cancelCrossfade()
        val cur = queueManager.currentIndex
        if (cur >= 0) {
            while (queueManager.queue.size > cur + 1) {
                queueManager.removeAt(queueManager.queue.lastIndex)
            }
            syncUpcomingTimelineItems()
            syncUiState()
            scheduleSaveLastPlayed()
        }
    }

    fun togglePlayPause() {
        cancelCrossfade()
        cancelSleepFade()
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            scheduleSaveLastPlayed(immediate = true)
        } else {
            if (activePlayer.playbackState == Player.STATE_IDLE && queueManager.currentSong != null) {
                val resumeMs = pendingResumePositionMs
                pendingResumePositionMs = 0L
                consecutiveAutoSkips = 0
                loadCurrentFromQueue(autoPlay = true, resumeAtMs = resumeMs)
            } else {
                activePlayer.play()
            }
        }
        syncUiState()
    }

    fun skipNext(manual: Boolean = true, resetAutoSkip: Boolean = true) {
        recordCompletedListeningSession()
        cancelCrossfade()
        cancelSleepFade()
        retryJob?.cancel()
        pendingResumePositionMs = 0L
        if (resetAutoSkip) consecutiveAutoSkips = 0
        val next = queueManager.nextIndex(manualSkip = manual)
        if (next != null) {
            queueManager.selectIndex(next)
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
            maybeFetchAutoplay()
            scheduleSaveLastPlayed(immediate = true)
        } else if (stateFlow.value.autoplayEnabled) {
            maybeFetchAutoplay(playWhenLoaded = true)
        }
    }

    fun skipPrevious() {
        cancelCrossfade()
        cancelSleepFade()
        consecutiveAutoSkips = 0
        val currentPos = if (activePlayer.playbackState == Player.STATE_IDLE) {
            pendingResumePositionMs
        } else {
            activePlayer.currentPosition
        }
        if (currentPos > 3_500L) {
            if (activePlayer.playbackState == Player.STATE_IDLE) {
                pendingResumePositionMs = 0L
                _progress.update { it.copy(positionMs = 0L) }
                scheduleSaveLastPlayed()
            } else {
                activePlayer.seekTo(0L)
            }
            return
        }
        recordCompletedListeningSession()
        retryJob?.cancel()
        pendingResumePositionMs = 0L
        val prev = queueManager.previousIndex()
        if (prev != null) {
            queueManager.selectIndex(prev)
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
            scheduleSaveLastPlayed(immediate = true)
        }
    }

    fun seekTo(positionMs: Long) {
        cancelCrossfade()
        cancelSleepFade()
        val target = positionMs.coerceAtLeast(0L)
        if (activePlayer.playbackState == Player.STATE_IDLE && queueManager.currentSong != null) {
            pendingResumePositionMs = target
        } else {
            activePlayer.seekTo(target)
        }
        _progress.update { it.copy(positionMs = target) }
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun toggleShuffle() {
        cancelCrossfade()
        queueManager.toggleShuffle()
        syncUpcomingTimelineItems()
        prefetchNextTrack()
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun cycleRepeatMode() {
        cancelCrossfade()
        queueManager.cycleRepeatMode()
        syncUpcomingTimelineItems()
        prefetchNextTrack()
        syncUiState()
        scheduleSaveLastPlayed()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        activePlayer.playbackParameters = PlaybackParameters(clamped)
        standbyPlayer?.playbackParameters = PlaybackParameters(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
    }

    fun setSkipSilence(enabled: Boolean) {
        activePlayer.skipSilenceEnabled = enabled
        standbyPlayer?.skipSilenceEnabled = enabled
        _uiState.update { it.copy(skipSilence = enabled) }
    }

    fun pauseForSleepTimer() {
        cancelCrossfade()
        cancelSleepFade()
        val fadeEnabled = container.settingsState.value.sleepTimerFadeOut
        if (!fadeEnabled || !activePlayer.isPlaying) {
            activePlayer.pause()
            activePlayer.volume = 1f
            syncUiState()
            scheduleSaveLastPlayed(immediate = true)
            return
        }
        val targetPlayer = activePlayer
        sleepFadeJob = serviceScope.launch {
            val startAt = System.currentTimeMillis()
            while (isActive && targetPlayer === activePlayer && targetPlayer.isPlaying) {
                val elapsed = System.currentTimeMillis() - startAt
                val progress = (elapsed.toFloat() / SLEEP_FADE_DURATION_MS).coerceIn(0f, 1f)
                val gain = kotlin.math.cos(progress * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                targetPlayer.volume = gain
                if (progress >= 1f) break
                delay(40L)
            }
            if (targetPlayer === activePlayer) {
                targetPlayer.pause()
                targetPlayer.volume = 1f
                syncUiState()
                scheduleSaveLastPlayed(immediate = true)
            }
            sleepFadeJob = null
        }
    }

    private fun cancelSleepFade() {
        if (sleepFadeJob != null) {
            sleepFadeJob?.cancel()
            sleepFadeJob = null
            if (crossfadeStateMachine.status.phase == CrossfadePhase.IDLE) {
                activePlayer.volume = 1f
            }
        }
    }

    fun updateSleepTimerUi(remainingMs: Long?, afterTrack: Boolean) {
        val prevAfterTrack = _uiState.value.sleepAfterTrack
        _uiState.update { it.copy(sleepTimerRemainingMs = remainingMs, sleepAfterTrack = afterTrack) }
        if (remainingMs == null && !afterTrack) {
            cancelSleepFade()
        }
        if (prevAfterTrack != afterTrack) {
            syncUpcomingTimelineItems()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ---- ForwardingPlayer for Notification / Lockscreen / Bluetooth ----

    private fun createSessionForwardingPlayer(delegate: ExoPlayer): ForwardingPlayer {
        return object : ForwardingPlayer(delegate) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> queueManager.queue.isNotEmpty()
                else -> super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean = queueManager.nextIndex(manualSkip = true) != null
            override fun hasPreviousMediaItem(): Boolean = queueManager.queue.isNotEmpty()

            override fun play() {
                if (activePlayer.playbackState == Player.STATE_IDLE && queueManager.currentSong != null) {
                    togglePlayPause()
                } else {
                    cancelSleepFade()
                    super.play()
                }
            }

            override fun seekToNext() = skipNext(manual = true)
            override fun seekToNextMediaItem() = skipNext(manual = true)
            override fun seekToPrevious() = skipPrevious()
            override fun seekToPreviousMediaItem() = skipPrevious()
            override fun seekTo(positionMs: Long) {
                cancelCrossfade()
                cancelSleepFade()
                super.seekTo(positionMs)
            }
        }
    }

    // ---- Internals ----

    private fun observeSettings() {
        serviceScope.launch {
            container.settingsStore.settings.collect { settings ->
                if (settings.crossfadeSeconds <= 0 && crossfadeStateMachine.status.phase == CrossfadePhase.FADING) {
                    cancelCrossfade()
                }
                activePlayer.playbackParameters = PlaybackParameters(settings.playbackSpeed)
                standbyPlayer?.playbackParameters = PlaybackParameters(settings.playbackSpeed)
                activePlayer.skipSilenceEnabled = settings.skipSilence
                standbyPlayer?.skipSilenceEnabled = settings.skipSilence
                _uiState.update {
                    it.copy(
                        playbackSpeed = settings.playbackSpeed,
                        skipSilence = settings.skipSilence,
                        autoplayEnabled = settings.autoplayEnabled,
                        crossfadeSeconds = settings.crossfadeSeconds,
                    )
                }
            }
        }
    }

    private fun createExoPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val resolvingFactory = ResolvingDataSource.Factory(
            container.audioCacheProvider.chunkedHttpFactory,
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val uri = dataSpec.uri
                    if (uri.scheme != MUSICA_SCHEME) return dataSpec
                    val videoId = uri.lastPathSegment
                        ?: throw IOException("Missing videoId in URI: $uri")

                    // Only verified-complete downloads bypass network resolution.
                    if (container.audioCacheProvider.isDownloadCompleted(videoId)) {
                        return dataSpec.buildUpon()
                            .setKey(videoId)
                            .build()
                    }

                    val resolved = runBlocking {
                        container.streamResolver.resolve(videoId)
                    }.getOrElse { err ->
                        throw IOException("Failed to resolve stream for $videoId", err)
                    }

                    resolvedMetadata[videoId] = resolved
                    val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                        putAll(resolved.headers)
                    }
                    return dataSpec.buildUpon()
                        .setUri(resolved.url.toUri())
                        .setKey(videoId)
                        .setHttpRequestHeaders(headers)
                        .build()
                }
            },
        )

        val cachedDataSourceFactory: DataSource.Factory =
            container.audioCacheProvider.buildPlaybackDataSourceFactory(resolvingFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(mediaAudioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun attachPlayerListeners(player: ExoPlayer) {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
    }

    private fun detachPlayerListeners(player: ExoPlayer) {
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveAutoSkips = 0
                playSegmentStartMs = System.currentTimeMillis()
                queueManager.currentSong?.let { song ->
                    container.playbackTracker.onPlaying(song.videoId)
                }
            } else {
                flushPlaySegment()
                if (crossfadeStateMachine.status.phase == CrossfadePhase.FADING &&
                    activePlayer.playbackState != Player.STATE_ENDED
                ) {
                    cancelCrossfade()
                }
                scheduleSaveLastPlayed(immediate = true)
            }
            syncUiState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (suppressNextTransition) {
                suppressNextTransition = false
                return
            }
            if (mediaItem == null) return

            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    recordCompletedListeningSession()
                    accumulatedPlayMs = 0L
                    playSegmentStartMs = if (activePlayer.isPlaying) System.currentTimeMillis() else 0L
                    syncUiState()
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    if (_uiState.value.sleepAfterTrack) {
                        container.sleepTimerManager.cancel()
                        cancelCrossfade()
                        activePlayer.pause()
                        syncUiState()
                        scheduleSaveLastPlayed(immediate = true)
                        return
                    }
                    // Gapless 0ms transition to the next item already prepared in ExoPlayer's Timeline!
                    recordCompletedListeningSession()
                    val transitionedId = mediaItem.mediaId
                    val targetIdx = resolveTransitionedQueueIndex(transitionedId)
                    if (targetIdx != null) {
                        queueManager.selectIndex(targetIdx)
                    }
                    accumulatedPlayMs = 0L
                    playSegmentStartMs = if (activePlayer.isPlaying) System.currentTimeMillis() else 0L
                    lastRetriedVideoId = null
                    retryCountForCurrent = 0
                    consecutiveAutoSkips = 0

                    queueManager.currentSong?.let { updateNerdStatsBase(it.videoId) }
                    val remainingInTimeline = activePlayer.mediaItemCount - 1 - activePlayer.currentMediaItemIndex
                    if (remainingInTimeline <= TIMELINE_TOP_UP_THRESHOLD) {
                        syncUpcomingTimelineItems()
                    }
                    syncUiState()
                    prefetchNextTrack()
                    maybeFetchAutoplay()
                    scheduleSaveLastPlayed(immediate = true)
                }

                else -> Unit
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveAutoSkips = 0
                prefetchNextTrack()
            } else if (playbackState == Player.STATE_ENDED) {
                if (_uiState.value.sleepAfterTrack) {
                    container.sleepTimerManager.cancel()
                    cancelCrossfade()
                    activePlayer.pause()
                    syncUiState()
                    scheduleSaveLastPlayed(immediate = true)
                    return
                }
                val fadingTarget = crossfadeStateMachine.status.targetIndex
                if (crossfadeStateMachine.status.phase == CrossfadePhase.FADING && fadingTarget != null) {
                    crossfadeJob?.cancel()
                    crossfadeJob = null
                    completeCrossfadeSwap(fadingTarget)
                    return
                }
                if (crossfadeStateMachine.status.phase == CrossfadePhase.IDLE) {
                    skipNext(manual = false)
                }
            }
            syncUiState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val song = queueManager.currentSong ?: return
            val resumePosition = activePlayer.currentPosition.coerceAtLeast(0L)
            Log.w(TAG, "ExoPlayer error for ${song.videoId}: ${error.errorCodeName}", error)

            val canRetry = !container.audioCacheProvider.isDownloadCompleted(song.videoId) &&
                (lastRetriedVideoId != song.videoId || retryCountForCurrent < MAX_RETRIES_PER_TRACK)

            if (canRetry) {
                val isSameTrack = lastRetriedVideoId == song.videoId
                lastRetriedVideoId = song.videoId
                retryCountForCurrent = if (isSameTrack) retryCountForCurrent + 1 else 1
                retryJob?.cancel()
                retryJob = serviceScope.launch {
                    container.streamResolver.invalidate(song.videoId)
                    container.audioCacheProvider.evictStreamTrack(song.videoId)
                    val refreshed = container.streamResolver.resolve(song.videoId, forceRefresh = true)
                    if (queueManager.currentSong?.videoId != song.videoId) return@launch
                    if (refreshed.isSuccess) {
                        loadCurrentFromQueue(autoPlay = true, resumeAtMs = resumePosition, resetRetry = false)
                    } else {
                        handleUnrecoverableTrackError(song, error)
                    }
                }
            } else {
                handleUnrecoverableTrackError(song, error)
            }
        }
    }

    private fun handleUnrecoverableTrackError(song: Song, error: PlaybackException) {
        val nextIdx = queueManager.nextIndex(manualSkip = false)
        val hasDifferentNext = nextIdx != null && nextIdx != queueManager.currentIndex
        if (hasDifferentNext && consecutiveAutoSkips < MAX_CONSECUTIVE_AUTO_SKIPS) {
            consecutiveAutoSkips++
            Log.w(
                TAG,
                "Auto-skipping unplayable track ${song.videoId} ($consecutiveAutoSkips/$MAX_CONSECUTIVE_AUTO_SKIPS)",
            )
            _uiState.update { it.copy(errorMessage = "Skipped unavailable track: ${song.title}") }
            skipNext(manual = false, resetAutoSkip = false)
        } else {
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Playback error") }
        }
    }

    private fun resolveTransitionedQueueIndex(videoId: String): Int? {
        val queue = queueManager.queue
        val expected = queueManager.nextIndex(manualSkip = false)
        if (expected != null && queue.getOrNull(expected)?.videoId == videoId) {
            return expected
        }
        val cur = queueManager.currentIndex
        for (i in (cur + 1) until queue.size) {
            if (queue[i].videoId == videoId) return i
        }
        return queue.indexOfFirst { it.videoId == videoId }.takeIf { it >= 0 }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            updateNerdStatsFromFormat(format)
        }
    }

    private fun loadCurrentFromQueue(
        autoPlay: Boolean,
        resumeAtMs: Long = 0L,
        resetRetry: Boolean = true,
    ) {
        cancelSleepFade()
        val song = queueManager.currentSong ?: return
        if (resetRetry) {
            accumulatedPlayMs = 0L
            playSegmentStartMs = 0L
            lastRetriedVideoId = null
            retryCountForCurrent = 0
        }
        _uiState.update { it.copy(errorMessage = null) }

        val timelineItems = buildTimelineWindowFromCurrent()
        activePlayer.volume = 1f
        suppressNextTransition = true
        activePlayer.setMediaItems(timelineItems, /* startIndex = */ 0, resumeAtMs)
        applyExoRepeatMode()
        activePlayer.prepare()
        activePlayer.playWhenReady = autoPlay
        updateNerdStatsBase(song.videoId)
        prefetchNextTrack()
    }

    private fun buildTimelineWindowFromCurrent(): List<MediaItem> {
        val queue = queueManager.queue
        val cur = queueManager.currentIndex
        if (cur !in queue.indices) return emptyList()
        if (_uiState.value.sleepAfterTrack) {
            return listOf(queue[cur].toMediaItem())
        }
        val endExclusive = minOf(queue.size, cur + 1 + UPCOMING_TIMELINE_COUNT)
        val items = ArrayList<MediaItem>(endExclusive - cur)
        for (i in cur until endExclusive) {
            items.add(queue[i].toMediaItem())
        }
        // When Repeat ALL is active and the queue wraps within our window, append head tracks seamlessly
        if (queueManager.repeatMode == RepeatMode.ALL && queue.size > 1 && endExclusive == queue.size) {
            val wrapCount = minOf(cur, UPCOMING_TIMELINE_COUNT - (items.size - 1)).coerceAtLeast(0)
            for (i in 0 until wrapCount) {
                items.add(queue[i].toMediaItem())
            }
        }
        return items
    }

    /**
     * Synchronizes upcoming ExoPlayer Timeline items after the currently playing index
     * without disturbing or rebuffering the active track.
     */
    private fun syncUpcomingTimelineItems() {
        if (activePlayer.mediaItemCount == 0 || queueManager.currentIndex !in queueManager.queue.indices) {
            return
        }
        val curWindow = activePlayer.currentMediaItemIndex
        if (curWindow < 0 || curWindow >= activePlayer.mediaItemCount) return

        // Trim any stale items before curWindow to keep the timeline compact without resetting playback
        if (curWindow > MAX_PAST_TIMELINE_ITEMS) {
            activePlayer.removeMediaItems(0, curWindow - MAX_PAST_TIMELINE_ITEMS)
        }
        val updatedCur = activePlayer.currentMediaItemIndex
        if (activePlayer.mediaItemCount > updatedCur + 1) {
            activePlayer.removeMediaItems(updatedCur + 1, activePlayer.mediaItemCount)
        }

        if (!_uiState.value.sleepAfterTrack) {
            val queue = queueManager.queue
            val cur = queueManager.currentIndex
            val endExclusive = minOf(queue.size, cur + 1 + UPCOMING_TIMELINE_COUNT)
            val upcoming = ArrayList<MediaItem>()
            for (i in (cur + 1) until endExclusive) {
                upcoming.add(queue[i].toMediaItem())
            }
            if (queueManager.repeatMode == RepeatMode.ALL && queue.size > 1 && endExclusive == queue.size) {
                val wrapCount = minOf(cur, UPCOMING_TIMELINE_COUNT - upcoming.size).coerceAtLeast(0)
                for (i in 0 until wrapCount) {
                    upcoming.add(queue[i].toMediaItem())
                }
            }
            if (upcoming.isNotEmpty()) {
                activePlayer.addMediaItems(upcoming)
            }
        }
        applyExoRepeatMode()
    }

    private fun applyExoRepeatMode() {
        activePlayer.repeatMode = when (queueManager.repeatMode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> if (queueManager.queue.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Proactively resolves the upcoming track's stream URL in the background so gapless
     * ExoPlayer transitions and crossfade standby preparation hit memory cache with 0ms latency.
     */
    private fun prefetchNextTrack() {
        val nextIdx = queueManager.nextIndex(manualSkip = false) ?: return
        if (nextIdx == queueManager.currentIndex) return
        val nextSong = queueManager.queue.getOrNull(nextIdx) ?: return
        if (container.audioCacheProvider.isDownloadCompleted(nextSong.videoId)) return

        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                container.streamResolver.resolve(nextSong.videoId)
            }.onSuccess { result ->
                result.getOrNull()?.let { resolved ->
                    resolvedMetadata[nextSong.videoId] = resolved
                }
            }
        }
    }

    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (isActive) {
                val active = activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING
                if (active || queueManager.currentSong != null) {
                    val isIdleRestored = activePlayer.playbackState == Player.STATE_IDLE && pendingResumePositionMs > 0L
                    val duration = activePlayer.duration.takeIf { it > 0L }
                        ?: queueManager.currentSong?.durationMs ?: 0L
                    val position = if (isIdleRestored) {
                        pendingResumePositionMs
                    } else {
                        activePlayer.currentPosition.coerceAtLeast(0L)
                    }
                    val buffered = activePlayer.bufferedPosition.coerceAtLeast(0L)

                    val nextProgress = PlaybackProgress(
                        positionMs = position,
                        durationMs = duration,
                        bufferedMs = buffered,
                    )
                    if (_progress.value != nextProgress) {
                        _progress.value = nextProgress
                    }
                    if (active) {
                        maybeTriggerCrossfade(position, duration)
                        if (activePlayer.isPlaying) {
                            queueManager.currentSong?.let { song ->
                                container.playbackTracker.onProgress(song.videoId, position / 1_000L)
                            }
                        }
                    }
                }
                delay(if (active) 250L else 900L)
            }
        }
    }

    private fun maybeTriggerCrossfade(positionMs: Long, durationMs: Long) {
        val fadeSec = _uiState.value.crossfadeSeconds
        val nextIdx = queueManager.nextIndex(manualSkip = false)
        val canStart = crossfadeStateMachine.canStart(
            crossfadeSeconds = fadeSec,
            isPlaying = activePlayer.isPlaying,
            repeatOne = queueManager.repeatMode == RepeatMode.ONE,
            sleepAfterTrack = _uiState.value.sleepAfterTrack,
            durationMs = durationMs,
            positionMs = positionMs,
            nextIndex = nextIdx,
        )
        if (!canStart || nextIdx == null) return
        val nextSong = queueManager.queue.getOrNull(nextIdx) ?: return
        val standby = standbyPlayer ?: return
        val speed = _uiState.value.playbackSpeed.coerceAtLeast(0.5f)
        val wallRemainingMs = ((durationMs - positionMs) / speed).toLong().coerceAtLeast(500L)
        val fadeMs = (fadeSec * 1000L).coerceAtMost((wallRemainingMs - 150L).coerceAtLeast(500L))
        startDualPlayerCrossfade(standby, nextIdx, nextSong, fadeMs)
    }

    private fun startDualPlayerCrossfade(
        standby: ExoPlayer,
        nextIdx: Int,
        nextSong: Song,
        fadeDurationMs: Long,
    ) {
        crossfadeStateMachine.begin(nextIdx)
        standby.volume = 0f
        standby.setMediaItem(nextSong.toMediaItem())
        standby.prepare()
        standby.playWhenReady = true

        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            var waitedMs = 0L
            while (isActive && standby.playbackState != Player.STATE_READY && waitedMs < 2_500L) {
                if (standby.playerError != null) {
                    cancelCrossfade()
                    return@launch
                }
                delay(50L)
                waitedMs += 50L
            }
            if (standby.playerError != null) {
                cancelCrossfade()
                return@launch
            }

            val startAt = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startAt
                val progress = (elapsed.toFloat() / fadeDurationMs.coerceAtLeast(500L)).coerceIn(0f, 1f)
                val completed = crossfadeStateMachine.advance(progress)
                val st = crossfadeStateMachine.status
                activePlayer.volume = st.outgoingGain
                standby.volume = st.incomingGain

                if (completed) {
                    completeCrossfadeSwap(nextIdx)
                    break
                }
                delay(40L)
            }
        }
    }

    private fun completeCrossfadeSwap(nextIdx: Int) {
        recordCompletedListeningSession()
        val outgoing = activePlayer
        val incoming = standbyPlayer ?: return

        detachPlayerListeners(outgoing)
        outgoing.setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ false)
        outgoing.setHandleAudioBecomingNoisy(false)
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f

        incoming.volume = 1f
        incoming.setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ true)
        incoming.setHandleAudioBecomingNoisy(true)
        attachPlayerListeners(incoming)

        activePlayer = incoming
        standbyPlayer = outgoing
        mediaSession?.player = createSessionForwardingPlayer(activePlayer)

        queueManager.selectIndex(nextIdx)
        accumulatedPlayMs = 0L
        playSegmentStartMs = if (activePlayer.isPlaying) System.currentTimeMillis() else 0L
        lastRetriedVideoId = null
        retryCountForCurrent = 0
        consecutiveAutoSkips = 0
        crossfadeStateMachine.resetAfterSwap()
        syncUpcomingTimelineItems()
        queueManager.currentSong?.let { updateNerdStatsBase(it.videoId) }
        syncUiState()
        prefetchNextTrack()
        maybeFetchAutoplay()
        scheduleSaveLastPlayed(immediate = true)
    }

    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeStateMachine.cancel()
        activePlayer.volume = 1f
        standbyPlayer?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
    }

    private fun maybeFetchAutoplay(playWhenLoaded: Boolean = false) {
        if (!_uiState.value.autoplayEnabled) return
        val current = queueManager.currentSong ?: return
        val remaining = queueManager.queue.size - 1 - queueManager.currentIndex
        if (remaining > 2 && !playWhenLoaded) return

        autoplayJob?.cancel()
        autoplayJob = serviceScope.launch(Dispatchers.IO) {
            val more = runCatching {
                container.autoplayController.fetchNextSuggestions(current, queueManager.queue)
            }.getOrDefault(emptyList())
            if (more.isNotEmpty()) {
                launch(Dispatchers.Main.immediate) {
                    val wasAtEnd = queueManager.currentIndex == queueManager.queue.lastIndex
                    queueManager.appendSongs(more)
                    if (playWhenLoaded && wasAtEnd && queueManager.currentIndex + 1 < queueManager.queue.size) {
                        queueManager.selectIndex(queueManager.currentIndex + 1)
                        loadCurrentFromQueue(autoPlay = true)
                    } else {
                        syncUpcomingTimelineItems()
                        prefetchNextTrack()
                    }
                    syncUiState()
                    scheduleSaveLastPlayed()
                }
            }
        }
    }

    private fun scheduleSaveLastPlayed(immediate: Boolean = false) {
        saveLastPlayedJob?.cancel()
        if (immediate) {
            val snapshot = buildLastPlayedSnapshot() ?: return
            serviceScope.launch(Dispatchers.IO) {
                container.lastPlayedStore.save(snapshot)
            }
            return
        }
        saveLastPlayedJob = serviceScope.launch {
            delay(1_500L)
            val snapshot = buildLastPlayedSnapshot() ?: return@launch
            launch(Dispatchers.IO) {
                container.lastPlayedStore.save(snapshot)
            }
        }
    }

    private fun persistLastPlayedNow() {
        val snapshot = buildLastPlayedSnapshot() ?: return
        container.appScope.launch {
            container.lastPlayedStore.save(snapshot)
        }
    }

    private fun buildLastPlayedSnapshot(): LastPlayedSnapshot? {
        val queue = queueManager.queue
        val idx = queueManager.currentIndex
        if (queue.isEmpty() || idx !in queue.indices) return null
        val position = if (activePlayer.playbackState == Player.STATE_IDLE) {
            pendingResumePositionMs
        } else {
            activePlayer.currentPosition.coerceAtLeast(0L)
        }
        return LastPlayedSnapshot(
            queue = queue,
            currentIndex = idx,
            positionMs = position,
            shuffleEnabled = queueManager.shuffleEnabled,
            repeatMode = queueManager.repeatMode,
            savedAtMs = System.currentTimeMillis(),
        )
    }

    private fun flushPlaySegment() {
        if (playSegmentStartMs > 0L) {
            accumulatedPlayMs += (System.currentTimeMillis() - playSegmentStartMs).coerceAtLeast(0L)
            playSegmentStartMs = 0L
        }
    }

    private fun recordCompletedListeningSession() {
        flushPlaySegment()
        val song = queueManager.currentSong ?: return
        val listenedMs = accumulatedPlayMs
        accumulatedPlayMs = 0L
        container.playbackTracker.onTrackEnded(
            (activePlayer.currentPosition.takeIf { it > 0L } ?: listenedMs) / 1_000L,
        )
        if (listenedMs < 5_000L) return
        container.onTrackPlayed(song, listenedMs)
    }

    private fun updateNerdStatsBase(videoId: String) {
        val stream = resolvedMetadata[videoId]
        val mode = when {
            container.audioCacheProvider.isDownloadCompleted(videoId) -> PlaybackSourceMode.OFFLINE
            container.audioCacheProvider.isStreamCached(videoId) -> PlaybackSourceMode.CACHED
            else -> PlaybackSourceMode.STREAMING
        }
        _uiState.update {
            it.copy(
                nerdStats = NerdStats(
                    videoId = videoId,
                    mimeType = stream?.mimeType.orEmpty(),
                    codecs = stream?.codecs.orEmpty(),
                    bitrateKbps = stream?.bitrateKbps ?: 0,
                    sampleRateHz = stream?.sampleRateHz ?: 0,
                    channelCount = stream?.channelCount ?: 2,
                    loudnessDb = stream?.loudnessDb,
                    sourceClient = stream?.sourceClient.orEmpty().ifBlank { if (mode == PlaybackSourceMode.OFFLINE) "Offline" else "YouTube" },
                    sourceMode = mode,
                ),
            )
        }
    }

    private fun updateNerdStatsFromFormat(format: Format) {
        val song = queueManager.currentSong ?: return
        val resolved = resolvedMetadata[song.videoId]
        val mode = when {
            container.audioCacheProvider.isDownloadCompleted(song.videoId) -> PlaybackSourceMode.OFFLINE
            container.audioCacheProvider.isStreamCached(song.videoId) -> PlaybackSourceMode.CACHED
            else -> PlaybackSourceMode.STREAMING
        }
        _uiState.update { state ->
            state.copy(
                nerdStats = state.nerdStats.copy(
                    videoId = song.videoId,
                    mimeType = format.sampleMimeType ?: format.containerMimeType ?: resolved?.mimeType ?: state.nerdStats.mimeType,
                    codecs = format.codecs ?: resolved?.codecs ?: state.nerdStats.codecs,
                    bitrateKbps = (format.bitrate.takeIf { it > 0 }?.div(1000)) ?: resolved?.bitrateKbps ?: state.nerdStats.bitrateKbps,
                    sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: state.nerdStats.sampleRateHz,
                    channelCount = format.channelCount.takeIf { it > 0 } ?: state.nerdStats.channelCount,
                    loudnessDb = resolved?.loudnessDb ?: state.nerdStats.loudnessDb,
                    sourceClient = resolved?.sourceClient?.takeIf { it.isNotBlank() } ?: state.nerdStats.sourceClient,
                    sourceMode = mode,
                ),
            )
        }
    }

    private fun syncUiState() {
        _uiState.update {
            it.copy(
                currentSong = queueManager.currentSong,
                queue = queueManager.queue,
                currentIndex = queueManager.currentIndex,
                isPlaying = activePlayer.isPlaying,
                isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING,
                shuffleEnabled = queueManager.shuffleEnabled,
                repeatMode = queueManager.repeatMode,
                audioSessionId = activePlayer.audioSessionId.takeIf { id -> id != C.AUDIO_SESSION_ID_UNSET } ?: 0,
            )
        }
    }

    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(videoId)
        .setUri("$MUSICA_SCHEME://track/$videoId")
        .setCustomCacheKey(videoId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistText)
                .setAlbumTitle(album?.name)
                .setArtworkUri(artworkAt(544)?.let(Uri::parse))
                .build(),
        )
        .build()

    companion object {
        private const val TAG = "MusicaPlayback"
        private const val MAX_RETRIES_PER_TRACK = 1
        private const val MAX_CONSECUTIVE_AUTO_SKIPS = 3
        private const val SLEEP_FADE_DURATION_MS = 3_000L
        private const val UPCOMING_TIMELINE_COUNT = 25
        private const val TIMELINE_TOP_UP_THRESHOLD = 5
        private const val MAX_PAST_TIMELINE_ITEMS = 3
        const val MUSICA_SCHEME = "musica"

        @Volatile
        internal var instance: MusicaPlaybackService? = null

        @Volatile
        private var pendingRestoredSnapshot: LastPlayedSnapshot? = null

        private val _uiState = MutableStateFlow(PlayerUiState())
        val stateFlow: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        private val _progress = MutableStateFlow(PlaybackProgress())
        val progressFlow: StateFlow<PlaybackProgress> = _progress.asStateFlow()

        /**
         * Seeds UI state with the last persisted playback session in PAUSED state on cold start.
         * Never starts ExoPlayer or triggers network fetches until the user explicitly taps Play.
         */
        fun seedRestoredSnapshot(snapshot: LastPlayedSnapshot) {
            val song = snapshot.currentSong ?: return
            if (_uiState.value.currentSong != null || instance != null) return
            pendingRestoredSnapshot = snapshot
            _uiState.update { state ->
                if (state.currentSong != null) return@update state
                state.copy(
                    currentSong = song,
                    queue = snapshot.queue,
                    currentIndex = snapshot.currentIndex,
                    isPlaying = false,
                    isBuffering = false,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    repeatMode = snapshot.repeatMode,
                )
            }
            _progress.value = PlaybackProgress(
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = song.durationMs,
                bufferedMs = 0L,
            )
        }

        private fun consumePendingSnapshot(): LastPlayedSnapshot? {
            val snapshot = pendingRestoredSnapshot
            pendingRestoredSnapshot = null
            return snapshot
        }

        internal fun clearErrorState() {
            _uiState.update { it.copy(errorMessage = null) }
        }

        internal fun updateSleepTimerState(remainingMs: Long?, afterTrack: Boolean) {
            _uiState.update { it.copy(sleepTimerRemainingMs = remainingMs, sleepAfterTrack = afterTrack) }
        }
    }
}
