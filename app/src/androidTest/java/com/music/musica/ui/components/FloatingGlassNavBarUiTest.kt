package com.music.musica.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.musica.core.model.ThemeMode
import com.music.musica.ui.theme.MusicaTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingGlassNavBarUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleTabs = listOf(
        NavTabItem("Home", Icons.Rounded.Home),
        NavTabItem("Explore", Icons.Rounded.GridView),
        NavTabItem("Mixes", Icons.Rounded.Sensors),
        NavTabItem("Library", Icons.Rounded.LibraryMusic),
        NavTabItem("Search", Icons.Rounded.Search),
    )

    @Test
    fun floatingGlassNavBar_displaysAllTabItems() {
        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                FloatingGlassNavBar(
                    tabs = sampleTabs,
                    selectedIndex = 0,
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onSelectTab = {},
                )
            }
        }

        sampleTabs.forEach { tab ->
            composeRule.onNodeWithText(tab.label).assertIsDisplayed()
        }
    }

    @Test
    fun floatingGlassNavBar_clickUnselectedTab_invokesOnSelectTabCallback() {
        var clickedIndex = -1

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                FloatingGlassNavBar(
                    tabs = sampleTabs,
                    selectedIndex = 0,
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onSelectTab = { clickedIndex = it },
                )
            }
        }

        composeRule.onNodeWithText("Library").performClick()
        composeRule.waitForIdle()

        assertEquals(3, clickedIndex)
    }

    @Test
    fun floatingGlassNavBar_selectingTabUpdatesActiveSelectionAndRoutesRetapToOnActiveTabTap() {
        var selectedIndex by mutableIntStateOf(0)
        val selectEvents = mutableListOf<Int>()
        val activeTapEvents = mutableListOf<Int>()

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                FloatingGlassNavBar(
                    tabs = sampleTabs,
                    selectedIndex = selectedIndex,
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onSelectTab = { idx ->
                        selectEvents.add(idx)
                        selectedIndex = idx
                    },
                    onActiveTabTap = { idx ->
                        activeTapEvents.add(idx)
                    },
                )
            }
        }

        // First tap on unselected "Explore" selects index 1
        composeRule.onNodeWithText("Explore").performClick()
        composeRule.waitForIdle()
        assertEquals(1, selectedIndex)
        assertEquals(listOf(1), selectEvents)
        assertEquals(emptyList<Int>(), activeTapEvents)

        // Second tap on now-selected "Explore" invokes onActiveTabTap instead of onSelectTab
        composeRule.onNodeWithText("Explore").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(1), selectEvents)
        assertEquals(listOf(1), activeTapEvents)

        // Selecting another tab ("Search") updates selection to index 4
        composeRule.onNodeWithText("Search").performClick()
        composeRule.waitForIdle()
        assertEquals(4, selectedIndex)
        assertEquals(listOf(1, 4), selectEvents)

        composeRule.onNodeWithText("Search").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(1, 4), activeTapEvents)
    }
}
