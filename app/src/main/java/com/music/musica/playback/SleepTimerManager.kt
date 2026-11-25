package com.music.musica.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SleepTimerState(
    val remainingMs: Long? = null,
    val stopAfterCurrentTrack: Boolean = false,
) {
    val isActive: Boolean get() = remainingMs != null || stopAfterCurrentTrack
}

class SleepTimerManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onTimerFinished: () -> Unit = {},
    private val onStateChanged: (SleepTimerState) -> Unit = {},
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null

    fun startMinutes(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        startDurationMs(minutes * 60_000L)
    }

    fun startDurationMs(durationMs: Long) {
        timerJob?.cancel()
        val endAt = clockMs() + durationMs
        publish(SleepTimerState(remainingMs = durationMs, stopAfterCurrentTrack = false))
        timerJob = scope.launch {
            while (isActive) {
                val left = endAt - clockMs()
                if (left <= 0L) {
                    publish(SleepTimerState())
                    onTimerFinished()
                    break
                }
                publish(SleepTimerState(remainingMs = left, stopAfterCurrentTrack = false))
                delay(1_000L.coerceAtMost(left))
            }
        }
    }

    fun setStopAfterCurrentTrack() {
        timerJob?.cancel()
        publish(SleepTimerState(remainingMs = null, stopAfterCurrentTrack = true))
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        publish(SleepTimerState())
    }

    private fun publish(newState: SleepTimerState) {
        _state.value = newState
        onStateChanged(newState)
    }
}
