package com.music.musica.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.musica.R
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.theme.MusicaTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testSong = Song(
        videoId = "vid_mini_1",
        title = "Midnight Echoes",
        artists = listOf(ArtistRef("Aurora Synth")),
        durationMs = 210_000L,
        thumbnailUrl = null,
    )

    @Test
    fun miniPlayer_displaysCurrentSongTitleAndArtist() {
        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                MiniPlayer(
                    playerState = PlayerUiState(currentSong = testSong, isPlaying = false),
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onExpand = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                )
            }
        }

        composeRule.onNodeWithText("Midnight Echoes").assertIsDisplayed()
        composeRule.onNodeWithText("Aurora Synth").assertIsDisplayed()
    }

    @Test
    fun miniPlayer_playPauseButton_invokesCallbackAndUpdatesIconDescription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playDesc = context.getString(R.string.action_play)
        val pauseDesc = context.getString(R.string.action_pause)

        var isPlaying by mutableStateOf(false)
        var toggleCount = 0

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                MiniPlayer(
                    playerState = PlayerUiState(currentSong = testSong, isPlaying = isPlaying),
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onExpand = {},
                    onTogglePlayPause = {
                        toggleCount++
                        isPlaying = !isPlaying
                    },
                    onSkipNext = {},
                    onSkipPrevious = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(playDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, toggleCount)
        assertEquals(true, isPlaying)

        composeRule.onNodeWithContentDescription(pauseDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(2, toggleCount)
        assertEquals(false, isPlaying)
        composeRule.onNodeWithContentDescription(playDesc).assertIsDisplayed()
    }

    @Test
    fun miniPlayer_skipNextAndBodyClick_invokeRespectiveCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nextDesc = context.getString(R.string.action_next)

        var skipNextCount = 0
        var expandCount = 0

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                MiniPlayer(
                    playerState = PlayerUiState(currentSong = testSong, isPlaying = true),
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onExpand = { expandCount++ },
                    onTogglePlayPause = {},
                    onSkipNext = { skipNextCount++ },
                    onSkipPrevious = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(nextDesc).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, skipNextCount)
        assertEquals(0, expandCount)

        composeRule.onNodeWithText("Midnight Echoes").performClick()
        composeRule.waitForIdle()
        assertEquals(1, expandCount)
    }

    @Test
    fun miniPlayer_whenCurrentSongIsNull_rendersNothing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playDesc = context.getString(R.string.action_play)
        val pauseDesc = context.getString(R.string.action_pause)

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                val hazeState = remember { HazeState() }
                MiniPlayer(
                    playerState = PlayerUiState(currentSong = null, isPlaying = false),
                    hazeState = hazeState,
                    frostedGlassEnabled = false,
                    onExpand = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(playDesc).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(pauseDesc).assertDoesNotExist()
    }
}
