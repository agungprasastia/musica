package com.music.musica.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerManagerTest {

    @Test
    fun startDuration_countsDownAndTriggersFinishCallback() = runTest {
        var finished = false
        val timer = SleepTimerManager(
            scope = this,
            clockMs = { testScheduler.currentTime },
            onTimerFinished = { finished = true },
        )

        timer.startDurationMs(3_000L)
        assertTrue(timer.state.value.isActive)

        advanceTimeBy(2_000L)
        runCurrent()
        assertFalse(finished)

        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(finished)
        assertFalse(timer.state.value.isActive)
        assertNull(timer.state.value.remainingMs)
    }

    @Test
    fun stopAfterTrack_andCancel_updateStateProperly() = runTest {
        val timer = SleepTimerManager(scope = this, clockMs = { testScheduler.currentTime })
        timer.setStopAfterCurrentTrack()
        assertTrue(timer.state.value.stopAfterCurrentTrack)
        assertTrue(timer.state.value.isActive)

        timer.cancel()
        assertFalse(timer.state.value.isActive)
        assertFalse(timer.state.value.stopAfterCurrentTrack)
    }
}
