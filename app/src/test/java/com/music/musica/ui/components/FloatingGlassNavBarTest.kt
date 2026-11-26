package com.music.musica.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingGlassNavBarTest {

    @Test
    fun scrubTracksRelativeDeltaSmoothlyAcrossTabBoundaries() {
        val pos = computeNavScrubPosition(
            dragStartTab = 1f,
            totalDragPx = 200f,
            tabWidthPx = 200f,
            tabCount = 5,
        )
        assertEquals(2.0f, pos, 0.0001f)
        assertEquals(2, computeNavLandedIndex(pos, 5))
    }

    @Test
    fun scrubHalfwaySnapsCleanlyToNearestTab() {
        val nearExplore = computeNavScrubPosition(
            dragStartTab = 1f,
            totalDragPx = 80f,
            tabWidthPx = 200f,
            tabCount = 5,
        )
        assertEquals(1.4f, nearExplore, 0.0001f)
        assertEquals(1, computeNavLandedIndex(nearExplore, 5))

        val nearMixes = computeNavScrubPosition(
            dragStartTab = 1f,
            totalDragPx = 120f,
            tabWidthPx = 200f,
            tabCount = 5,
        )
        assertEquals(1.6f, nearMixes, 0.0001f)
        assertEquals(2, computeNavLandedIndex(nearMixes, 5))
    }

    @Test
    fun scrubAppliesSoftRubberBandAtEdgesAndClampsLandedIndex() {
        val overLeft = computeNavScrubPosition(
            dragStartTab = 0f,
            totalDragPx = -500f,
            tabWidthPx = 200f,
            tabCount = 5,
        )
        assertTrue(overLeft < 0f && overLeft >= -0.18f)
        assertEquals(0, computeNavLandedIndex(overLeft, 5))

        val overRight = computeNavScrubPosition(
            dragStartTab = 4f,
            totalDragPx = 500f,
            tabWidthPx = 200f,
            tabCount = 5,
        )
        assertTrue(overRight > 4f && overRight <= 4.18f)
        assertEquals(4, computeNavLandedIndex(overRight, 5))
    }

    @Test
    fun degenerateTabCountsReturnSafeZero() {
        assertEquals(0f, computeNavScrubPosition(0f, 150f, 200f, 1), 0.0001f)
        assertEquals(0f, computeNavScrubPosition(0f, 150f, 0f, 5), 0.0001f)
        assertEquals(0, computeNavLandedIndex(3.2f, 0))
    }
}
