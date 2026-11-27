package com.music.musica.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.data.repository.RankedAlbum
import com.music.musica.data.repository.RankedArtist
import com.music.musica.data.repository.RankedSong
import com.music.musica.data.repository.ReplayPassSection
import com.music.musica.data.repository.ReplaySummary
import com.music.musica.ui.theme.MusicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Calendar

class LibraryReplayPassCarouselUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersMinutesCardWithHolderAndMemberSinceAndFiresCallback() {
        var clickedSection: ReplayPassSection? = null
        val nov2026 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
        }.timeInMillis

        val summary = ReplaySummary(
            totalTracksPlayed = 51,
            totalListenedMs = 210L * 60_000L,
            topSongs = listOf(
                RankedSong(
                    song = Song(
                        videoId = "v1",
                        title = "Flower In A Hurricane",
                        artists = listOf(ArtistRef("Justice")),
                        album = AlbumRef("Hyperdrama", "MPREb_1"),
                    ),
                    playCount = 12,
                    totalListenedMs = 42L * 60_000L,
                ),
            ),
            topArtists = listOf(
                RankedArtist(
                    name = "BUMP OF CHICKEN",
                    browseId = "UC_bump",
                    playCount = 6,
                    totalListenedMs = 27L * 60_000L,
                ),
            ),
            topAlbums = listOf(
                RankedAlbum(
                    name = "Hyperdrama",
                    browseId = "MPREb_1",
                    artistName = "Justice",
                    thumbnailUrl = null,
                    playCount = 12,
                    totalListenedMs = 42L * 60_000L,
                ),
            ),
            firstPlayedAtMs = nov2026,
            distinctSongs = 18,
            distinctArtists = 7,
            distinctAlbums = 5,
            activeDaysCount = 4,
            peakHour = 21,
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                LibraryReplayPassCarousel(
                    summary = summary,
                    listenerName = "Agung",
                    memberSinceMs = nov2026,
                    reduceAnimation = true,
                    onOpenReplay = { clickedSection = it },
                )
            }
        }

        composeRule.onNodeWithText("210").assertIsDisplayed()
        composeRule.onAllNodesWithText("AGUNG").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("11/26").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("210").performClick()

        assertEquals(ReplayPassSection.MINUTES, clickedSection)

        composeRule.onNodeWithText("210").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Flower In A Hurricane").assertIsDisplayed()
        composeRule.onNodeWithText("Flower In A Hurricane").performClick()

        assertEquals(ReplayPassSection.TOP_SONG, clickedSection)
    }

    @Test
    fun rendersEmptySingleCardGracefully() {
        var clickedSection: ReplayPassSection? = null
        val nov2026 = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
        }.timeInMillis

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.LIGHT) {
                LibraryReplayPassCarousel(
                    summary = ReplaySummary(),
                    listenerName = null,
                    memberSinceMs = nov2026,
                    reduceAnimation = true,
                    onOpenReplay = { clickedSection = it },
                )
            }
        }

        composeRule.onNodeWithText("0").assertIsDisplayed()
        composeRule.onNodeWithText("11/26").assertIsDisplayed()
        composeRule.onNodeWithText("0").performClick()

        assertEquals(ReplayPassSection.MINUTES, clickedSection)
    }
}
