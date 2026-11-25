package com.music.musica.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class CrossfadeCurveTest {

    @Test
    fun equalPowerGains_preservesConstantPowerAcrossFade() {
        val start = CrossfadeCurve.equalPowerGains(0f)
        assertEquals(1f, start.outgoingGain, 0.001f)
        assertEquals(0f, start.incomingGain, 0.001f)

        val mid = CrossfadeCurve.equalPowerGains(0.5f)
        val expectedMid = (sqrt(2.0) / 2.0).toFloat()
        assertEquals(expectedMid, mid.outgoingGain, 0.002f)
        assertEquals(expectedMid, mid.incomingGain, 0.002f)

        val end = CrossfadeCurve.equalPowerGains(1f)
        assertEquals(0f, end.outgoingGain, 0.001f)
        assertEquals(1f, end.incomingGain, 0.001f)
    }
}
