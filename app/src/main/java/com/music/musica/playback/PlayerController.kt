package com.music.musica.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.musica.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bridges UI composables / ViewModels with [MusicaPlaybackService].
 * Uses Media3 SessionToken binding (not raw startForegroundService) so Android 8–15 never raises
 * ForegroundServiceDidNotStartInTimeException for non-playing commands.
 */
class PlayerController(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val state: StateFlow<PlayerUiState> = MusicaPlaybackService.stateFlow
    val progress: StateFlow<PlaybackProgress> = MusicaPlaybackService.progressFlow

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, MusicaPlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
    }

    private fun withService(startIfNeeded: Boolean = false, action: (MusicaPlaybackService) -> Unit) {
        val current = MusicaPlaybackService.instance
        if (current != null) {
            action(current)
            return
        }
        if (!startIfNeeded) return

        connect()
        runCatching {
            appContext.startService(Intent(appContext, MusicaPlaybackService::class.java))
        }
        scope.launch {
            repeat(60) {
                val svc = MusicaPlaybackService.instance
                if (svc != null) {
                    action(svc)
                    return@launch
                }
                delay(50L)
            }
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean? = null) =
        withService(startIfNeeded = true) { it.playQueue(songs, startIndex, shuffled) }

    fun playSong(song: Song) = playQueue(listOf(song), 0, shuffled = null)
    fun selectQueueIndex(index: Int) = withService(startIfNeeded = true) { it.selectQueueIndex(index) }
    fun playNext(song: Song) = withService(startIfNeeded = true) { it.playNext(song) }
    fun addToQueue(song: Song) = withService(startIfNeeded = true) { it.addToQueue(song) }
    fun moveQueueItem(from: Int, to: Int) = withService(startIfNeeded = false) { it.moveQueueItem(from, to) }
    fun removeQueueItem(index: Int) = withService(startIfNeeded = false) { it.removeQueueItem(index) }
    fun clearUpcomingQueue() = withService(startIfNeeded = false) { it.clearUpcomingQueue() }
    fun togglePlayPause() = withService(startIfNeeded = true) { it.togglePlayPause() }
    fun skipNext() = withService(startIfNeeded = false) { it.skipNext(manual = true) }
    fun skipPrevious() = withService(startIfNeeded = false) { it.skipPrevious() }
    fun seekTo(positionMs: Long) = withService(startIfNeeded = false) { it.seekTo(positionMs) }
    fun toggleShuffle() = withService(startIfNeeded = false) { it.toggleShuffle() }
    fun cycleRepeatMode() = withService(startIfNeeded = false) { it.cycleRepeatMode() }
    fun setPlaybackSpeed(speed: Float) = withService(startIfNeeded = false) { it.setPlaybackSpeed(speed) }
    fun setSkipSilence(enabled: Boolean) = withService(startIfNeeded = false) { it.setSkipSilence(enabled) }
    fun pauseForSleepTimer() = withService(startIfNeeded = false) { it.pauseForSleepTimer() }
    fun clearError() = MusicaPlaybackService.instance?.clearError()
    fun updateSleepTimerUi(remainingMs: Long?, afterTrack: Boolean) =
        MusicaPlaybackService.instance?.updateSleepTimerUi(remainingMs, afterTrack)
}
