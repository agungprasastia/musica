package com.music.musica.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
 * Foreground Media3 MediaSessionService handling YouTube Music stream resolution,
 * completed-only offline/LRU caching, notification/lockscreen/Bluetooth ForwardingPlayer
 * routing, queue & autoplay, bounded retry on errors, and dual-ExoPlayer equal-power crossfade.
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

    // Accurate listening-time tracker (counts only actual playing time, not paused intervals)
    private var accumulatedPlayMs: Long = 0L
    private var playSegmentStartMs: Long = 0L

    // Bounded per-track retry guard (max 1 retry per videoId load)
    private var lastRetriedVideoId: String? = null
    private var retryCountForCurrent: Int = 0

    private val container by lazy { (application as MusicaApplication).container }

    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

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
            .setSessionActivity(sessionActivity)
            .build()

        instance = this
        observeSettings()
        startPositionTicker()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!activePlayer.playWhenReady || activePlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        recordCompletedListeningSession()
        cancelCrossfade()
        positionJob?.cancel()
        autoplayJob?.cancel()
        retryJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        activePlayer.release()
        standbyPlayer?.release()
        standbyPlayer = null
        serviceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Playback API invoked by PlayerController ----

    fun playQueue(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean? = null) {
        if (songs.isEmpty()) return
        recordCompletedListeningSession()
        cancelCrossfade()
        retryJob?.cancel()
        container.autoplayController.reset()
        queueManager.setQueue(songs, startIndex, shuffled)
        loadCurrentFromQueue(autoPlay = true)
        syncUiState()
        maybeFetchAutoplay()
    }

    fun selectQueueIndex(index: Int) {
        recordCompletedListeningSession()
        cancelCrossfade()
        retryJob?.cancel()
        if (queueManager.selectIndex(index) != null) {
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
            maybeFetchAutoplay()
        }
    }

    fun playNext(song: Song) {
        cancelCrossfade()
        queueManager.playNext(song)
        syncUiState()
    }

    fun addToQueue(song: Song) {
        queueManager.addToQueue(song)
        syncUiState()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        cancelCrossfade()
        queueManager.move(fromIndex, toIndex)
        syncUiState()
    }

    fun removeQueueItem(index: Int) {
        val removingCurrent = index == queueManager.currentIndex
        cancelCrossfade()
        queueManager.removeAt(index)
        if (removingCurrent) {
            retryJob?.cancel()
            if (queueManager.currentSong != null) {
                loadCurrentFromQueue(autoPlay = activePlayer.playWhenReady)
            } else {
                activePlayer.stop()
                activePlayer.clearMediaItems()
            }
        }
        syncUiState()
    }

    fun clearUpcomingQueue() {
        cancelCrossfade()
        val cur = queueManager.currentIndex
        if (cur >= 0) {
            while (queueManager.queue.size > cur + 1) {
                queueManager.removeAt(queueManager.queue.lastIndex)
            }
            syncUiState()
        }
    }

    fun togglePlayPause() {
        cancelCrossfade()
        if (activePlayer.isPlaying) {
            activePlayer.pause()
        } else {
            if (activePlayer.playbackState == Player.STATE_IDLE && queueManager.currentSong != null) {
                loadCurrentFromQueue(autoPlay = true)
            } else {
                activePlayer.play()
            }
        }
        syncUiState()
    }

    fun skipNext(manual: Boolean = true) {
        recordCompletedListeningSession()
        cancelCrossfade()
        retryJob?.cancel()
        val next = queueManager.nextIndex(manualSkip = manual)
        if (next != null) {
            queueManager.selectIndex(next)
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
            maybeFetchAutoplay()
        } else if (stateFlow.value.autoplayEnabled) {
            maybeFetchAutoplay(playWhenLoaded = true)
        }
    }

    fun skipPrevious() {
        cancelCrossfade()
        if (activePlayer.currentPosition > 3_500L) {
            activePlayer.seekTo(0L)
            return
        }
        recordCompletedListeningSession()
        retryJob?.cancel()
        val prev = queueManager.previousIndex()
        if (prev != null) {
            queueManager.selectIndex(prev)
            loadCurrentFromQueue(autoPlay = true)
            syncUiState()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelCrossfade()
        val target = positionMs.coerceAtLeast(0L)
        activePlayer.seekTo(target)
        _progress.update { it.copy(positionMs = target) }
        syncUiState()
    }

    fun toggleShuffle() {
        cancelCrossfade()
        queueManager.toggleShuffle()
        syncUiState()
    }

    fun cycleRepeatMode() {
        cancelCrossfade()
        queueManager.cycleRepeatMode()
        syncUiState()
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
        activePlayer.pause()
        syncUiState()
    }

    fun updateSleepTimerUi(remainingMs: Long?, afterTrack: Boolean) {
        _uiState.update { it.copy(sleepTimerRemainingMs = remainingMs, sleepAfterTrack = afterTrack) }
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

            override fun seekToNext() = skipNext(manual = true)
            override fun seekToNextMediaItem() = skipNext(manual = true)
            override fun seekToPrevious() = skipPrevious()
            override fun seekToPreviousMediaItem() = skipPrevious()
            override fun seekTo(positionMs: Long) {
                cancelCrossfade()
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
            container.audioCacheProvider.upstreamHttpFactory,
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
                playSegmentStartMs = System.currentTimeMillis()
            } else {
                flushPlaySegment()
                // Only cancel crossfade on genuine pause/focus loss, NOT when active track naturally ended!
                if (crossfadeStateMachine.status.phase == CrossfadePhase.FADING &&
                    activePlayer.playbackState != Player.STATE_ENDED
                ) {
                    cancelCrossfade()
                }
            }
            syncUiState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (_uiState.value.sleepAfterTrack) {
                    container.sleepTimerManager.cancel()
                    cancelCrossfade()
                    activePlayer.pause()
                    syncUiState()
                    return
                }
                val fadingTarget = crossfadeStateMachine.status.targetIndex
                if (crossfadeStateMachine.status.phase == CrossfadePhase.FADING && fadingTarget != null) {
                    // Outgoing finished slightly before fade timer ended -> hand over immediately without restarting!
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
                lastRetriedVideoId = song.videoId
                retryCountForCurrent = if (lastRetriedVideoId == song.videoId) retryCountForCurrent + 1 else 1
                retryJob?.cancel()
                retryJob = serviceScope.launch {
                    container.streamResolver.invalidate(song.videoId)
                    container.audioCacheProvider.evictStreamTrack(song.videoId)
                    val refreshed = container.streamResolver.resolve(song.videoId, forceRefresh = true)
                    // Ensure user hasn't skipped to a different track while resolving
                    if (queueManager.currentSong?.videoId != song.videoId) return@launch
                    if (refreshed.isSuccess) {
                        loadCurrentFromQueue(autoPlay = true, resumeAtMs = resumePosition, resetRetry = false)
                    } else {
                        _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Playback error") }
                    }
                }
            } else {
                _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Playback error") }
            }
        }
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
        val song = queueManager.currentSong ?: return
        if (resetRetry) {
            accumulatedPlayMs = 0L
            playSegmentStartMs = 0L
            lastRetriedVideoId = null
            retryCountForCurrent = 0
        }
        _uiState.update { it.copy(errorMessage = null) }
        val item = song.toMediaItem()
        activePlayer.volume = 1f
        activePlayer.setMediaItem(item, resumeAtMs)
        activePlayer.prepare()
        activePlayer.playWhenReady = autoPlay
        updateNerdStatsBase(song.videoId)
    }

    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (isActive) {
                val active = activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING
                if (active || queueManager.currentSong != null) {
                    val duration = activePlayer.duration.takeIf { it > 0L }
                        ?: queueManager.currentSong?.durationMs ?: 0L
                    val position = activePlayer.currentPosition.coerceAtLeast(0L)
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
            // Wait up to 2.5s for standby to actually start decoding audio before ramping volume down
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
        crossfadeStateMachine.resetAfterSwap()
        queueManager.currentSong?.let { updateNerdStatsBase(it.videoId) }
        syncUiState()
        maybeFetchAutoplay()
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
                    }
                    syncUiState()
                }
            }
        }
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
        const val MUSICA_SCHEME = "musica"

        @Volatile
        internal var instance: MusicaPlaybackService? = null

        private val _uiState = MutableStateFlow(PlayerUiState())
        val stateFlow: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        private val _progress = MutableStateFlow(PlaybackProgress())
        val progressFlow: StateFlow<PlaybackProgress> = _progress.asStateFlow()
    }
}
