package com.music.musica.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import com.music.musica.data.lyrics.smoothstep
import kotlin.math.abs

/**
 * Adaptive 60fps monotonic playhead clock for word- and syllable-synced lyrics:
 * - Never steps backward without an announced discontinuity (seek or lyrics offset change)
 * - Holds visual baseline cleanly across pauses and resumes without jitter
 * - Rejects outlier player position reports (buffers up to 2 suspect readings)
 * - Learns actual playback rate across a 4s window (handles 0.5x–2.0x speed seamlessly)
 * - Smoothstep-glides short jumps (`<= 2s`) over 260ms and steers ordinary drift asymptotically
 *   within `[0.5x .. 1.25x]` so words never stall or stutter.
 */
class LyricClock(
    initialRate: Double = 1.0,
) {
    private var anchorMs: Double = 0.0
    private var anchorNanos: Long = 0L
    private var displayedMs: Double = 0.0
    private var lastFrameNanos: Long = 0L
    private var initialized: Boolean = false
    private var playing: Boolean = false
    private var lastDiscontinuity: Long = Long.MIN_VALUE

    // Pause hold memory
    private var heldAtMs: Double = 0.0
    private var heldDiscontinuity: Long = Long.MIN_VALUE

    // Outlier guard
    private var suspectMs: Double = Double.NaN
    private var suspectNanos: Long = 0L
    private var rejections: Int = 0

    // Adaptive playback rate history (16-entry circular buffer)
    private val histMs = DoubleArray(HISTORY_SIZE)
    private val histNanos = LongArray(HISTORY_SIZE)
    private var histCount = 0
    private var histHead = 0
    var rate: Double = initialRate.coerceIn(MIN_RATE, MAX_RATE)
        private set
    private var rateSettled = false

    // Smoothstep glide state for short jumps / catch-up
    private var glideStartNanos: Long = 0L
    private var glideOffsetMs: Double = 0.0
    private var gliding: Boolean = false

    val currentMs: Long get() = displayedMs.toLong().coerceAtLeast(0L)

    fun observe(
        reportedMs: Long,
        sampledAtNanos: Long,
        isPlaying: Boolean,
        discontinuity: Long,
        playbackSpeedHint: Float = 1.0f,
        active: Boolean = true,
    ): Long {
        val reported = reportedMs.coerceAtLeast(0L).toDouble()
        val sameDiscontinuity = initialized && discontinuity == lastDiscontinuity

        if (!initialized) {
            initialized = true
            anchorMs = reported
            anchorNanos = sampledAtNanos
            displayedMs = reported
            lastFrameNanos = sampledAtNanos
            playing = isPlaying
            lastDiscontinuity = discontinuity
            rate = playbackSpeedHint.toDouble().coerceIn(MIN_RATE, MAX_RATE)
            resetHistory(reported, sampledAtNanos)
            return currentMs
        }

        if (!sameDiscontinuity) {
            // Announced discontinuity (seek, track change, or lyrics offset adjustment)
            lastDiscontinuity = discontinuity
            playing = isPlaying
            rate = playbackSpeedHint.toDouble().coerceIn(MIN_RATE, MAX_RATE)
            rateSettled = false
            rejections = 0
            suspectMs = Double.NaN
            anchorMs = reported
            anchorNanos = sampledAtNanos
            resetHistory(reported, sampledAtNanos)
            jumpTo(reported, sampledAtNanos, allowGlide = active)
            return currentMs
        }

        val wasPlaying = playing
        playing = isPlaying

        if (!isPlaying) {
            // Hold visual baseline on pause so text never jumps backward on pause/resume
            if (wasPlaying) {
                heldAtMs = displayedMs
                heldDiscontinuity = discontinuity
            }
            if (!active || abs(reported - displayedMs) > OUTLIER_MS) {
                displayedMs = reported
                anchorMs = reported
                anchorNanos = sampledAtNanos
            }
            gliding = false
            return currentMs
        }

        // Resuming from pause with same discontinuity: ignore slightly stale initial report
        if (!wasPlaying && heldDiscontinuity == discontinuity) {
            if (reported < heldAtMs && (heldAtMs - reported) <= 1_500.0) {
                anchorMs = heldAtMs
                anchorNanos = sampledAtNanos
                lastFrameNanos = sampledAtNanos
                return currentMs
            }
        }

        // Outlier rejection
        val elapsedMs = ((sampledAtNanos - anchorNanos) / 1_000_000.0).coerceAtLeast(0.0)
        val expected = anchorMs + elapsedMs * rate
        val tolerance = OUTLIER_MS + elapsedMs * (if (rateSettled) 0.2 else 0.75)
        if (abs(reported - expected) <= tolerance || rejections >= MAX_REJECTIONS) {
            acceptReading(reported, sampledAtNanos)
        } else if (!suspectMs.isNaN() && abs(reported - (suspectMs + ((sampledAtNanos - suspectNanos) / 1_000_000.0) * rate)) <= OUTLIER_MS) {
            acceptReading(reported, sampledAtNanos)
        } else {
            suspectMs = reported
            suspectNanos = sampledAtNanos
            rejections++
        }

        return currentMs
    }

    fun advance(nowNanos: Long): Long {
        if (!initialized) return 0L
        if (!playing) {
            lastFrameNanos = nowNanos
            return currentMs
        }
        val dtMs = ((nowNanos - lastFrameNanos) / 1_000_000.0).coerceIn(0.0, 250.0)
        lastFrameNanos = nowNanos
        val target = anchorMs + ((nowNanos - anchorNanos) / 1_000_000.0).coerceAtLeast(0.0) * rate

        if (gliding) {
            val progress = (((nowNanos - glideStartNanos) / 1_000_000.0) / GLIDE_MS).toFloat()
            if (progress >= 1f) {
                gliding = false
                displayedMs = target
            } else {
                val eased = smoothstep(progress)
                displayedMs = target + glideOffsetMs * (1.0 - eased)
            }
            return currentMs
        }

        val nominalStep = dtMs * rate
        val projected = displayedMs + nominalStep
        val error = target - projected

        when {
            abs(error) >= LOST_MS -> {
                displayedMs = target
            }
            error >= GLIDE_FROM_MS -> {
                gliding = true
                glideStartNanos = nowNanos
                glideOffsetMs = displayedMs - target
            }
            else -> {
                val closing = error * (dtMs / (dtMs + CORRECTION_MS))
                val minAdjust = -MAX_SLOW * nominalStep
                val maxAdjust = MAX_SLEW * nominalStep
                val step = (nominalStep + closing.coerceIn(minAdjust, maxAdjust)).coerceAtLeast(0.0)
                displayedMs += step
            }
        }
        return currentMs
    }

    private fun jumpTo(targetMs: Double, nowNanos: Long, allowGlide: Boolean) {
        val offset = displayedMs - targetMs
        if (!allowGlide || abs(offset) > GLIDE_MAX_MS || abs(offset) < 12.0) {
            gliding = false
            displayedMs = targetMs
            lastFrameNanos = nowNanos
        } else {
            gliding = true
            glideStartNanos = nowNanos
            glideOffsetMs = offset
            lastFrameNanos = nowNanos
        }
    }

    private fun acceptReading(reportedMs: Double, sampledAtNanos: Long) {
        rejections = 0
        suspectMs = Double.NaN
        if (gliding && reportedMs < displayedMs) {
            gliding = false
        }
        anchorMs = reportedMs
        anchorNanos = sampledAtNanos
        recordAndLearnRate(reportedMs, sampledAtNanos)
    }

    private fun resetHistory(ms: Double, nanos: Long) {
        histCount = 1
        histHead = 0
        histMs[0] = ms
        histNanos[0] = nanos
    }

    private fun recordAndLearnRate(ms: Double, nanos: Long) {
        val prevIdx = histHead
        val prevNanos = histNanos[prevIdx]
        val prevMs = histMs[prevIdx]
        if (nanos <= prevNanos) return

        histHead = (histHead + 1) % HISTORY_SIZE
        histMs[histHead] = ms
        histNanos[histHead] = nanos
        if (histCount < HISTORY_SIZE) histCount++

        // Find oldest reading within RATE_WINDOW_MS
        var oldestIdx = -1
        for (offset in 1 until histCount) {
            val idx = (histHead - offset + HISTORY_SIZE) % HISTORY_SIZE
            val spanMs = (nanos - histNanos[idx]) / 1_000_000.0
            if (spanMs <= RATE_WINDOW_MS) {
                oldestIdx = idx
            } else {
                break
            }
        }
        if (oldestIdx >= 0) {
            val spanMs = (nanos - histNanos[oldestIdx]) / 1_000_000.0
            if (spanMs >= MIN_RATE_SPAN_MS) {
                val measured = ((ms - histMs[oldestIdx]) / spanMs).coerceIn(MIN_RATE, MAX_RATE)
                rate = measured
                rateSettled = true
                return
            }
        }
        val pairSpan = (nanos - prevNanos) / 1_000_000.0
        if (pairSpan >= 300.0) {
            val pairRate = ((ms - prevMs) / pairSpan).coerceIn(MIN_RATE, MAX_RATE)
            rate = rate * (1.0 - PAIR_LEARNING) + pairRate * PAIR_LEARNING
        }
    }

    companion object {
        private const val HISTORY_SIZE = 16
        private const val MIN_RATE = 0.25
        private const val MAX_RATE = 4.0
        private const val OUTLIER_MS = 250.0
        private const val MAX_REJECTIONS = 2
        private const val RATE_WINDOW_MS = 4_000.0
        private const val MIN_RATE_SPAN_MS = 900.0
        private const val PAIR_LEARNING = 0.5
        private const val LOST_MS = 3_000.0
        private const val GLIDE_FROM_MS = 300.0
        private const val GLIDE_MAX_MS = 2_000.0
        private const val GLIDE_MS = 260.0
        private const val CORRECTION_MS = 600.0
        private const val MAX_SLEW = 0.25
        private const val MAX_SLOW = 0.5
        private const val SAME_CLOCK_NANOS = 250_000_000L

        internal fun packDiscontinuity(seekGeneration: Int, offsetMs: Long): Long =
            (seekGeneration.toLong() shl 32) or (offsetMs and 0xFFFF_FFFFL)

        internal fun frameNowNanos(frameNanos: Long): Long {
            val sys = System.nanoTime()
            return if (abs(sys - frameNanos) < SAME_CLOCK_NANOS) frameNanos else sys
        }
    }
}

/**
 * Returns a [MutableLongState] holding the smoothly steered effective lyrics position
 * (`playerPositionMs + offsetMs`). Read ONLY inside draw lambdas or `derivedStateOf` so outer
 * composables never recompose at 60fps.
 */
@Composable
fun rememberLyricClock(
    trackKey: Any?,
    positionMs: Long,
    sampledAtNanos: Long,
    seekGeneration: Int,
    offsetMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    active: Boolean,
): MutableLongState {
    val engine = remember(trackKey) { LyricClock(initialRate = playbackSpeed.toDouble()) }
    val clockState = remember(trackKey) { mutableLongStateOf((positionMs + offsetMs).coerceAtLeast(0L)) }

    val effectiveReported = (positionMs + offsetMs).coerceAtLeast(0L)
    val discontinuity = LyricClock.packDiscontinuity(seekGeneration, offsetMs)

    LaunchedEffect(trackKey, effectiveReported, sampledAtNanos, isPlaying, discontinuity, playbackSpeed, active) {
        val next = engine.observe(
            reportedMs = effectiveReported,
            sampledAtNanos = sampledAtNanos,
            isPlaying = isPlaying,
            discontinuity = discontinuity,
            playbackSpeedHint = playbackSpeed,
            active = active,
        )
        clockState.longValue = next
    }

    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(trackKey, isPlaying, active) {
        if (!isPlaying || !active) return@LaunchedEffect
        while (currentActive) {
            withFrameNanos { frameNanos ->
                val now = LyricClock.frameNowNanos(frameNanos)
                clockState.longValue = engine.advance(now)
            }
        }
    }

    return clockState
}
