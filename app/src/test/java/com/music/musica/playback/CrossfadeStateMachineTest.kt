package com.music.musica.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeStateMachineTest {

    @Test
    fun normalFadeCompletesAndSwaps() {
        val sm = CrossfadeStateMachine()
        assertTrue(
            sm.canStart(
                crossfadeSeconds = 6,
                isPlaying = true,
                repeatOne = false,
                sleepAfterTrack = false,
                durationMs = 180_000L,
                positionMs = 175_000L,
                nextIndex = 2,
            ),
        )
        sm.begin(targetIndex = 2)
        assertEquals(CrossfadePhase.FADING, sm.status.phase)
        assertEquals(2, sm.status.targetIndex)

        assertFalse(sm.advance(0.5f))
        assertTrue(sm.status.outgoingGain > 0.6f && sm.status.incomingGain > 0.6f)

        assertTrue(sm.advance(1.0f))
        assertEquals(CrossfadePhase.SWAPPED, sm.status.phase)
    }

    @Test
    fun interruptionCancelsFadeAndRestoresActiveGain() {
        val sm = CrossfadeStateMachine()
        sm.begin(targetIndex = 1)
        sm.advance(0.4f)
        sm.cancel()

        assertEquals(CrossfadePhase.IDLE, sm.status.phase)
        assertNull(sm.status.targetIndex)
        assertEquals(1f, sm.status.outgoingGain, 0.001f)
        assertEquals(0f, sm.status.incomingGain, 0.001f)
    }

    @Test
    fun blocksStartDuringRepeatOneSleepAfterTrackOrDisabled() {
        val sm = CrossfadeStateMachine()
        assertFalse(sm.canStart(0, true, false, false, 180_000L, 178_000L, 1))
        assertFalse(sm.canStart(6, true, true, false, 180_000L, 175_000L, 1))
        assertFalse(sm.canStart(6, true, false, true, 180_000L, 175_000L, 1))
        assertFalse(sm.canStart(6, false, false, false, 180_000L, 175_000L, 1))
        assertFalse(sm.canStart(6, true, false, false, 180_000L, 175_000L, null))
    }
}
