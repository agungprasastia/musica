package com.music.musica.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {

    @Test
    fun interleavedRoutesAndDetails_popInExactLifoOrder() {
        val details = mutableListOf<String>()
        val nav = AppNavigator(
            detailDepth = { details.size },
            popDetail = {
                if (details.isNotEmpty()) {
                    details.removeAt(details.lastIndex)
                    true
                } else false
            },
            clearDetails = { details.clear() },
        )

        details += "album_1"
        nav.push(AppRoute.Settings)
        assertEquals(AppRoute.Settings, nav.visibleRoute())

        details += "artist_2" // opened above Settings
        assertNull(nav.visibleRoute()) // detail sits on top of Settings

        assertTrue(nav.pop()) // closes artist_2
        assertEquals(1, details.size)
        assertEquals(AppRoute.Settings, nav.visibleRoute())

        assertTrue(nav.pop()) // closes Settings
        assertNull(nav.visibleRoute())
        assertEquals(1, details.size)

        assertTrue(nav.pop()) // closes album_1
        assertEquals(0, details.size)
        assertFalse(nav.pop()) // stack empty
    }
}
