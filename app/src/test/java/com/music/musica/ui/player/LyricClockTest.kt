package com.music.musica.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricClockTest {

    private val msToNs = 1_000_000L

    @Test
    fun advance_neverStepsBackwardWithoutAnnouncedDiscontinuity() {
        val clock = LyricClock()
        clock.observe(reportedMs = 10_000L, sampledAtNanos = 0L, isPlaying = true, discontinuity = 1L)
        var after500 = 10_000L
        for (step in 1..25) {
            after500 = clock.advance(step * 20L * msToNs)
        }
        assertTrue("Expected clock >= 10450 after 500ms, got $after500", after500 >= 10_450L)

        // Slightly stale backward report with same discontinuity must not pull display backward
        clock.observe(reportedMs = 10_300L, sampledAtNanos = 500L * msToNs, isPlaying = true, discontinuity = 1L)
        val next = clock.advance(516L * msToNs)
        assertTrue("Clock stepped backward: $next < $after500", next >= after500)
    }

    @Test
    fun observe_glidesShortAnnouncedJumpAndSnapsLongJump() {
        val clock = LyricClock()
        clock.observe(reportedMs = 10_000L, sampledAtNanos = 0L, isPlaying = true, discontinuity = 1L)
        // Short 1000ms jump -> smoothstep glides over 260ms
        clock.observe(reportedMs = 11_000L, sampledAtNanos = 100L * msToNs, isPlaying = true, discontinuity = 2L)
        val midGlide = clock.advance(230L * msToNs)
        assertTrue("Expected smooth mid-glide value, got $midGlide", midGlide in 10_200L..11_200L)
        val endGlide = clock.advance(400L * msToNs)
        assertTrue("Expected settled glide >= 11250, got $endGlide", endGlide >= 11_250L)

        // Long 30_000ms jump -> snaps immediately
        val snapped = clock.observe(reportedMs = 45_000L, sampledAtNanos = 500L * msToNs, isPlaying = true, discontinuity = 3L)
        assertEquals(45_000L, snapped)
    }

    @Test
    fun observe_learnsPlaybackRateAt150PercentSpeed() {
        val clock = LyricClock(initialRate = 1.0)
        clock.observe(reportedMs = 0L, sampledAtNanos = 0L, isPlaying = true, discontinuity = 1L)
        for (step in 1..6) {
            val wallMs = step * 400L
            val songMs = (wallMs * 1.5).toLong()
            clock.observe(reportedMs = songMs, sampledAtNanos = wallMs * msToNs, isPlaying = true, discontinuity = 1L)
        }
        assertTrue("Expected learned rate near 1.5, got ${clock.rate}", clock.rate in 1.40..1.60)
    }

    @Test
    fun observe_holdsBaselineAcrossPauseAndResumeWithoutDip() {
        val clock = LyricClock()
        clock.observe(reportedMs = 5_000L, sampledAtNanos = 0L, isPlaying = true, discontinuity = 1L)
        val beforePause = clock.advance(400L * msToNs)
        clock.observe(reportedMs = 5_350L, sampledAtNanos = 400L * msToNs, isPlaying = false, discontinuity = 1L)
        // Slightly behind report on resume must not jump backward
        val onResume = clock.observe(reportedMs = 5_200L, sampledAtNanos = 2_000L * msToNs, isPlaying = true, discontinuity = 1L)
        assertTrue("Clock dipped on resume: $onResume < $beforePause", onResume >= beforePause)
    }
}
