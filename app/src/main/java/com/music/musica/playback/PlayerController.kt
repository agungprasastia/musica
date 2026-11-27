package com.music.musica.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.musica.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Bridges UI composables and ViewModels with [MusicaPlaybackService] using Media3's official
 * [MediaController] and [SessionCommand] IPC transport.
 *
 * Avoids raw `startForegroundService()` calls and busy-wait polling loops so Android 8–15
 * handles background/foreground lifecycle cleanly and never throws
 * `ForegroundServiceDidNotStartInTimeException`.
 */
class PlayerController(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val state: StateFlow<PlayerUiState> = MusicaPlaybackService.stateFlow
    val progress: StateFlow<PlaybackProgress> = MusicaPlaybackService.progressFlow

    fun connect() {
        ensureControllerFuture()
    }

    @Synchronized
    private fun ensureControllerFuture(): ListenableFuture<MediaController> {
        val existing = controllerFuture
        if (existing != null && !existing.isCancelled) {
            return existing
        }
        val token = SessionToken(appContext, ComponentName(appContext, MusicaPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        return future
    }

    private fun dispatchCommand(
        action: String,
        args: Bundle = Bundle.EMPTY,
        connectIfNeeded: Boolean = false,
    ) {
        val future = if (connectIfNeeded) ensureControllerFuture() else controllerFuture
        if (future == null) return

        val command = SessionCommand(action, Bundle.EMPTY)
        if (future.isDone && !future.isCancelled) {
            val controller = runCatching { future.get() }.getOrNull()
            if (controller != null && controller.isConnected) {
                controller.sendCustomCommand(command, args)
                return
            }
            if (!connectIfNeeded) return
            synchronized(this) {
                if (controllerFuture === future) {
                    controllerFuture = null
                }
            }
            runCatching { MediaController.releaseFuture(future) }
            val freshFuture = ensureControllerFuture()
            scope.launch {
                runCatching { freshFuture.await() }
                    .getOrNull()
                    ?.takeIf { it.isConnected }
                    ?.sendCustomCommand(command, args)
            }
            return
        }

        scope.launch {
            runCatching { future.await() }
                .getOrNull()
                ?.takeIf { it.isConnected }
                ?.sendCustomCommand(command, args)
        }
    }

    private fun hasLoadedQueue(): Boolean = state.value.currentSong != null

    fun playQueue(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean? = null) {
        if (songs.isEmpty()) return
        val handoffId = MusicaSessionCommands.putQueuePayload(songs, startIndex, shuffled)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_PLAY_QUEUE,
            args = Bundle().apply { putString(MusicaSessionCommands.ARG_HANDOFF_ID, handoffId) },
            connectIfNeeded = true,
        )
    }

    fun playSong(song: Song) = playQueue(listOf(song), 0, shuffled = null)

    fun selectQueueIndex(index: Int) = dispatchCommand(
        action = MusicaSessionCommands.CMD_SELECT_INDEX,
        args = Bundle().apply { putInt(MusicaSessionCommands.ARG_INDEX, index) },
        connectIfNeeded = true,
    )

    fun playNext(song: Song) {
        val handoffId = MusicaSessionCommands.putSongPayload(song)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_PLAY_NEXT,
            args = Bundle().apply { putString(MusicaSessionCommands.ARG_HANDOFF_ID, handoffId) },
            connectIfNeeded = true,
        )
    }

    fun playNextAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        val handoffId = MusicaSessionCommands.putQueuePayload(songs, 0, null)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_PLAY_NEXT_LIST,
            args = Bundle().apply { putString(MusicaSessionCommands.ARG_HANDOFF_ID, handoffId) },
            connectIfNeeded = true,
        )
    }

    fun addToQueue(song: Song) {
        val handoffId = MusicaSessionCommands.putSongPayload(song)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_ADD_TO_QUEUE,
            args = Bundle().apply { putString(MusicaSessionCommands.ARG_HANDOFF_ID, handoffId) },
            connectIfNeeded = true,
        )
    }

    fun addAllToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val handoffId = MusicaSessionCommands.putQueuePayload(songs, 0, null)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_ADD_TO_QUEUE_LIST,
            args = Bundle().apply { putString(MusicaSessionCommands.ARG_HANDOFF_ID, handoffId) },
            connectIfNeeded = true,
        )
    }

    fun moveQueueItem(from: Int, to: Int) = dispatchCommand(
        action = MusicaSessionCommands.CMD_MOVE_ITEM,
        args = Bundle().apply {
            putInt(MusicaSessionCommands.ARG_FROM_INDEX, from)
            putInt(MusicaSessionCommands.ARG_TO_INDEX, to)
        },
        connectIfNeeded = hasLoadedQueue(),
    )

    fun removeQueueItem(index: Int) = dispatchCommand(
        action = MusicaSessionCommands.CMD_REMOVE_ITEM,
        args = Bundle().apply { putInt(MusicaSessionCommands.ARG_INDEX, index) },
        connectIfNeeded = hasLoadedQueue(),
    )

    fun clearUpcomingQueue() = dispatchCommand(
        action = MusicaSessionCommands.CMD_CLEAR_UPCOMING,
        connectIfNeeded = hasLoadedQueue(),
    )

    fun togglePlayPause() = dispatchCommand(
        action = MusicaSessionCommands.CMD_TOGGLE_PLAY_PAUSE,
        connectIfNeeded = true,
    )

    fun skipNext() = dispatchCommand(
        action = MusicaSessionCommands.CMD_SKIP_NEXT,
        connectIfNeeded = true,
    )

    fun skipPrevious() = dispatchCommand(
        action = MusicaSessionCommands.CMD_SKIP_PREVIOUS,
        connectIfNeeded = true,
    )

    fun seekTo(positionMs: Long) = dispatchCommand(
        action = MusicaSessionCommands.CMD_SEEK_TO,
        args = Bundle().apply { putLong(MusicaSessionCommands.ARG_POSITION_MS, positionMs) },
        connectIfNeeded = true,
    )

    fun toggleShuffle() = dispatchCommand(
        action = MusicaSessionCommands.CMD_TOGGLE_SHUFFLE,
        connectIfNeeded = true,
    )

    fun cycleRepeatMode() = dispatchCommand(
        action = MusicaSessionCommands.CMD_CYCLE_REPEAT,
        connectIfNeeded = true,
    )

    fun setPlaybackSpeed(speed: Float) = dispatchCommand(
        action = MusicaSessionCommands.CMD_SET_SPEED,
        args = Bundle().apply { putFloat(MusicaSessionCommands.ARG_SPEED, speed) },
        connectIfNeeded = hasLoadedQueue(),
    )

    fun setSkipSilence(enabled: Boolean) = dispatchCommand(
        action = MusicaSessionCommands.CMD_SET_SKIP_SILENCE,
        args = Bundle().apply { putBoolean(MusicaSessionCommands.ARG_ENABLED, enabled) },
        connectIfNeeded = hasLoadedQueue(),
    )

    fun pauseForSleepTimer() = dispatchCommand(
        action = MusicaSessionCommands.CMD_PAUSE_SLEEP_TIMER,
        connectIfNeeded = hasLoadedQueue(),
    )

    fun clearError() {
        MusicaPlaybackService.clearErrorState()
        dispatchCommand(
            action = MusicaSessionCommands.CMD_CLEAR_ERROR,
            connectIfNeeded = false,
        )
    }

    fun updateSleepTimerUi(remainingMs: Long?, afterTrack: Boolean) {
        MusicaPlaybackService.updateSleepTimerState(remainingMs, afterTrack)
        dispatchCommand(
            action = MusicaSessionCommands.CMD_UPDATE_SLEEP_UI,
            args = Bundle().apply {
                putBoolean(MusicaSessionCommands.ARG_HAS_REMAINING, remainingMs != null)
                putLong(MusicaSessionCommands.ARG_REMAINING_MS, remainingMs ?: 0L)
                putBoolean(MusicaSessionCommands.ARG_AFTER_TRACK, afterTrack)
            },
            connectIfNeeded = hasLoadedQueue(),
        )
    }
}
