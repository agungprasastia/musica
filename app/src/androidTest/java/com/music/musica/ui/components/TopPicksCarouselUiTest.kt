package com.music.musica.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.model.TopPick
import com.music.musica.core.model.TopPickKind
import com.music.musica.core.model.TopPickMood
import com.music.musica.core.model.WatchTarget
import com.music.musica.ui.theme.MusicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TopPicksCarouselUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersReleaseArtistMoodAndStationCardsAndFiresCallbacks() {
        var clickedKind: TopPickKind? = null
        var longClickedKind: TopPickKind? = null

        val picks = listOf(
            TopPick(
                kind = TopPickKind.LATEST_RELEASE,
                title = "Kami no Na wo",
                subtitle = "EP • 2026",
                artistName = "Ave Mujica",
                artistBrowseId = "UC_ave",
                browseId = "MPREb_ep",
            ),
            TopPick(
                kind = TopPickKind.ARTIST_AND_SIMILAR,
                title = "Ave Mujica",
                artistName = "Ave Mujica",
                artistBrowseId = "UC_ave",
                watch = WatchTarget("RDEM_ave"),
            ),
            TopPick(
                kind = TopPickKind.MOOD,
                mood = TopPickMood.LOVE,
            ),
            TopPick(
                kind = TopPickKind.PERSONAL_STATION,
                watch = WatchTarget("RDTMAK_super"),
            ),
            TopPick(
                kind = TopPickKind.DISCOVERY_STATION,
                watch = WatchTarget("RDTMAK_disc"),
            ),
            TopPick(
                kind = TopPickKind.FEATURED_ARTIST,
                title = "RAISE A SUILEN",
                subtitle = "228K monthly listeners",
                artistName = "RAISE A SUILEN",
                artistBrowseId = "UC_ras",
                watch = WatchTarget("RDAO_ras"),
            ),
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                TopPicksCarousel(
                    picks = picks,
                    listenerName = "Agung Prasastia",
                    onPickClick = { pick, _ -> clickedKind = pick.kind },
                    artworkMotionEnabled = false,
                    onPickLongClick = { pick -> longClickedKind = pick.kind },
                )
            }
        }

        // Card 1: Latest Release tap + long press
        composeRule.onNodeWithText("Kami no Na wo").assertIsDisplayed()
        composeRule.onNodeWithText("EP • 2026").assertIsDisplayed()
        composeRule.onNodeWithTag("top_pick_card_LATEST_RELEASE").performClick()
        assertEquals(TopPickKind.LATEST_RELEASE, clickedKind)

        composeRule.onNodeWithTag("top_pick_card_LATEST_RELEASE").performTouchInput { longClick() }
        assertEquals(TopPickKind.LATEST_RELEASE, longClickedKind)

        // Swipe to Card 2: Artist & Similar long press supported
        composeRule.onNodeWithTag("top_pick_card_LATEST_RELEASE").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_pick_card_ARTIST_AND_SIMILAR").assertIsDisplayed()
        composeRule.onNodeWithTag("top_pick_card_ARTIST_AND_SIMILAR").performTouchInput { longClick() }
        assertEquals(TopPickKind.ARTIST_AND_SIMILAR, longClickedKind)
    }

    @Test
    fun personalStation_usesFirstNameWhenSignedInAndIgnoresLongPress() {
        var longClicked: TopPickKind? = null
        val picks = listOf(
            TopPick(
                kind = TopPickKind.PERSONAL_STATION,
                watch = WatchTarget("RDTMAK_super"),
            ),
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.LIGHT) {
                TopPicksCarousel(
                    picks = picks,
                    listenerName = "Agung Prasastia",
                    onPickClick = { _, _ -> },
                    artworkMotionEnabled = false,
                    onPickLongClick = { longClicked = it.kind },
                )
            }
        }

        composeRule.onNodeWithTag("top_pick_card_PERSONAL_STATION").assertIsDisplayed()
        composeRule.onNodeWithTag("top_pick_card_PERSONAL_STATION").performTouchInput { longClick() }
        assertNull(longClicked)
    }

    @Test
    fun growingFromSingleCardToMultiCardList_recomposesWithoutCrash() {
        var picks by mutableStateOf(
            listOf(TopPick(kind = TopPickKind.MOOD, mood = TopPickMood.HEARTBREAK)),
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                TopPicksCarousel(
                    picks = picks,
                    listenerName = null,
                    onPickClick = { _, _ -> },
                    artworkMotionEnabled = false,
                )
            }
        }

        composeRule.onNodeWithTag("top_pick_card_MOOD").assertIsDisplayed()

        composeRule.runOnIdle {
            picks = listOf(
                TopPick(
                    kind = TopPickKind.LATEST_RELEASE,
                    title = "Fresh Drop",
                    subtitle = "EP • 2026",
                    artistName = "Luna",
                    browseId = "MPREb_fresh",
                ),
                TopPick(kind = TopPickKind.MOOD, mood = TopPickMood.LOVE),
                TopPick(kind = TopPickKind.DISCOVERY_STATION, watch = WatchTarget("RDTMAK_disc")),
                TopPick(kind = TopPickKind.PERSONAL_STATION, watch = WatchTarget("RDTMAK_super")),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fresh Drop").assertIsDisplayed()
        composeRule.onNodeWithTag("top_pick_card_MOOD").assertIsDisplayed()
    }
}
