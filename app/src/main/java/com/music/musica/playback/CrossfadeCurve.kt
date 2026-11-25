package com.music.musica.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class CrossfadeGains(
    val outgoingGain: Float,
    val incomingGain: Float,
)

/**
 * Equal-power crossfade curve so perceived loudness stays constant across track overlaps.
 */
object CrossfadeCurve {
    fun equalPowerGains(progress: Float): CrossfadeGains {
        val t = progress.coerceIn(0f, 1f).toDouble()
        val angle = t * (PI / 2.0)
        return CrossfadeGains(
            outgoingGain = cos(angle).toFloat().coerceIn(0f, 1f),
            incomingGain = sin(angle).toFloat().coerceIn(0f, 1f),
        )
    }
}
