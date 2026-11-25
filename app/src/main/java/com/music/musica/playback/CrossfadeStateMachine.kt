package com.music.musica.playback

enum class CrossfadePhase {
    IDLE,
    FADING,
    SWAPPED,
}

data class CrossfadeStatus(
    val phase: CrossfadePhase = CrossfadePhase.IDLE,
    val targetIndex: Int? = null,
    val progress: Float = 0f,
    val outgoingGain: Float = 1f,
    val incomingGain: Float = 0f,
)

/**
 * Deterministic state machine governing dual-ExoPlayer crossfade transitions,
 * ensuring interruptions (seek/skip/pause/focus loss) restore active player gain cleanly.
 */
class CrossfadeStateMachine {
    var status: CrossfadeStatus = CrossfadeStatus()
        private set

    fun canStart(
        crossfadeSeconds: Int,
        isPlaying: Boolean,
        repeatOne: Boolean,
        sleepAfterTrack: Boolean,
        durationMs: Long,
        positionMs: Long,
        nextIndex: Int?,
    ): Boolean {
        if (status.phase != CrossfadePhase.IDLE) return false
        if (crossfadeSeconds <= 0 || !isPlaying || repeatOne || sleepAfterTrack || nextIndex == null) {
            return false
        }
        val fadeMs = crossfadeSeconds * 1000L
        if (durationMs <= fadeMs + 3_000L) return false
        val remainingMs = durationMs - positionMs
        return remainingMs in 500L..fadeMs
    }

    fun begin(targetIndex: Int) {
        status = CrossfadeStatus(
            phase = CrossfadePhase.FADING,
            targetIndex = targetIndex,
            progress = 0f,
            outgoingGain = 1f,
            incomingGain = 0f,
        )
    }

    fun advance(progress: Float): Boolean {
        if (status.phase != CrossfadePhase.FADING) return false
        val clamped = progress.coerceIn(0f, 1f)
        val gains = CrossfadeCurve.equalPowerGains(clamped)
        if (clamped >= 1f) {
            status = status.copy(
                phase = CrossfadePhase.SWAPPED,
                progress = 1f,
                outgoingGain = 0f,
                incomingGain = 1f,
            )
            return true
        }
        status = status.copy(
            progress = clamped,
            outgoingGain = gains.outgoingGain,
            incomingGain = gains.incomingGain,
        )
        return false
    }

    fun resetAfterSwap() {
        status = CrossfadeStatus()
    }

    fun cancel() {
        status = CrossfadeStatus()
    }
}
