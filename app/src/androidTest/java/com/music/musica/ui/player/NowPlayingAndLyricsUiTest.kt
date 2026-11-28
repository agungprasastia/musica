package com.music.musica.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.music.musica.R
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.Song
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.model.UiState
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricWord
import com.music.musica.data.lyrics.LyricsDisplayMode
import com.music.musica.data.lyrics.LyricsProviderStatus
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.playback.AudioOutputDevice
import com.music.musica.playback.AudioOutputKind
import com.music.musica.playback.AudioOutputRouter
import com.music.musica.playback.PlaybackProgress
import com.music.musica.playback.PlayerUiState
import com.music.musica.ui.theme.MusicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NowPlayingAndLyricsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sampleSong = Song(
        videoId = "let_down_vid",
        title = "Let Down",
        artists = listOf(ArtistRef("Radiohead", "UC_radiohead")),
        album = AlbumRef("OK Computer", "MPREb_okc"),
        durationMs = 299_000L,
        thumbnailUrl = null,
    )

    private val sampleLyrics = LyricsResult(
        source = LyricsSourceId.LRC_RED,
        isSynced = true,
        isWordSynced = true,
        lines = listOf(
            LyricLine(
                startMs = 5_000L,
                endMs = 12_000L,
                text = "Transport, motorways and tramlines",
                words = listOf(
                    LyricWord(5_000L, 6_800L, "Transport,"),
                    LyricWord(6_800L, 8_600L, "motorways"),
                    LyricWord(8_600L, 9_500L, "and"),
                    LyricWord(9_500L, 11_800L, "tramlines"),
                ),
            ),
            LyricLine(
                startMs = 12_500L,
                endMs = 19_000L,
                text = "Starting and then stopping",
            ),
            LyricLine(
                startMs = 20_000L,
                endMs = 26_000L,
                text = "A".repeat(240),
            ),
        ),
    )

    @Test
    fun fullBleedNowPlaying_showsOriginStarStripAndMorphsCenterDockForQueue() {
        var likeClicks = 0
        var audioOutputClicks = 0
        var shuffleClicks = 0
        var lyricsState by mutableStateOf<UiState<LyricsResult?>>(UiState.Loading)
        var progress by mutableStateOf(PlaybackProgress(positionMs = 7_500L, durationMs = 299_000L))

        val playerState = PlayerUiState(
            currentSong = sampleSong,
            queue = listOf(sampleSong),
            currentIndex = 0,
            isPlaying = false,
            playbackSourceLabel = "OK Computer",
            playbackSourceBrowseId = "MPREb_okc",
        )
        val phoneDevice = AudioOutputDevice(
            id = 2,
            name = context.getString(R.string.player_this_phone),
            kind = AudioOutputKind.PHONE,
            deviceType = AudioOutputRouter.TYPE_BUILTIN_SPEAKER,
            isBuiltIn = true,
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                NowPlayingSheet(
                    playerState = playerState,
                    progress = progress,
                    isLiked = false,
                    lyricsState = lyricsState,
                    activeAudioOutput = phoneDevice,
                    fullBleedCoverArt = true,
                    reduceAnimation = true,
                    lyricsContent = { mod, _ ->
                        SyncedLyricsPanel(
                            song = sampleSong,
                            lyricsState = lyricsState,
                            progress = progress,
                            isPlaying = false,
                            playbackSpeed = 1f,
                            reduceAnimation = true,
                            providerStatuses = mapOf(LyricsSourceId.LRC_RED to LyricsProviderStatus.FOUND),
                            providerOrder = LyricsSourceId.entries,
                            displayMode = LyricsDisplayMode.ORIGINAL,
                            subLyricsLines = null,
                            translationLoading = false,
                            translationNotice = null,
                            showLyricsCardLogo = true,
                            showInlineChips = false,
                            onSeekTo = {},
                            onChangeOffset = {},
                            onSelectProvider = {},
                            onToggleRomanize = {},
                            onToggleTranslate = {},
                            onSetShowLyricsCardLogo = {},
                            modifier = mod,
                        )
                    },
                    onDismiss = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeekTo = {},
                    onToggleShuffle = { shuffleClicks++ },
                    onCycleRepeat = {},
                    onToggleLike = { likeClicks++ },
                    onToggleAutoplay = {},
                    onSelectQueueIndex = {},
                    onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {},
                    onOpenMoreActions = {},
                    onOpenAudioOutput = { audioOutputClicks++ },
                    onOpenStatsForNerds = {},
                )
            }
        }

        // Playing from origin + title/artist + Star favorite + Audio Output label
        composeRule.onNodeWithText(context.getString(R.string.player_playing_from, "OK Computer")).assertIsDisplayed()
        composeRule.onNodeWithText("Let Down").assertIsDisplayed()
        composeRule.onNodeWithText("Radiohead").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.menu_like)).performClick()
        assertEquals(1, likeClicks)

        // Audio output slot shows "This phone" and opens picker
        composeRule.onNodeWithText(context.getString(R.string.player_this_phone)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.player_this_phone)).performClick()
        assertEquals(1, audioOutputClicks)

        // Loading strip transitions cleanly to live active synced lyric line
        composeRule.runOnIdle {
            lyricsState = UiState.Success(sampleLyrics)
            progress = PlaybackProgress(positionMs = 7_500L, durationMs = 299_000L)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Transport, motorways and tramlines").assertIsDisplayed()

        // Tapping Queue dock morphs center slot into Shuffle | Repeat | Autoplay
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_queue)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_shuffle)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_shuffle)).performClick()
        assertEquals(1, shuffleClicks)
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_repeat)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_autoplay)).assertIsDisplayed()
    }

    @Test
    fun lyricsPanel_opensProviderLinkTogglesTranslateRomanizeAndEnforces250CharPicker() {
        var providerSheetOpened = false
        var romanizeToggled = false
        var translateToggled = false
        var pickingActive by mutableStateOf(false)

        val playerState = PlayerUiState(
            currentSong = sampleSong,
            queue = listOf(sampleSong),
            currentIndex = 0,
            isPlaying = false,
            playbackSourceLabel = "OK Computer",
        )
        val progress = PlaybackProgress(positionMs = 7_500L, durationMs = 299_000L)

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                NowPlayingSheet(
                    playerState = playerState,
                    progress = progress,
                    isLiked = true,
                    lyricsState = UiState.Success(sampleLyrics),
                    fullBleedCoverArt = true,
                    reduceAnimation = true,
                    lyricsPickingActive = pickingActive,
                    onLyricsPickingChange = { pickingActive = it },
                    lyricsContent = { mod, captureBackdrop ->
                        SyncedLyricsPanel(
                            song = sampleSong,
                            lyricsState = UiState.Success(sampleLyrics),
                            progress = progress,
                            isPlaying = false,
                            playbackSpeed = 1f,
                            reduceAnimation = true,
                            providerStatuses = mapOf(LyricsSourceId.LRC_RED to LyricsProviderStatus.FOUND),
                            providerOrder = LyricsSourceId.entries,
                            displayMode = LyricsDisplayMode.ORIGINAL,
                            subLyricsLines = null,
                            translationLoading = false,
                            translationNotice = null,
                            showLyricsCardLogo = true,
                            showInlineChips = false,
                            externalPickingActive = pickingActive,
                            onPickingActiveChange = { pickingActive = it },
                            captureBackdropBitmap = captureBackdrop,
                            onRequestOpenProviderSheet = { providerSheetOpened = true },
                            onSeekTo = {},
                            onChangeOffset = {},
                            onSelectProvider = {},
                            onToggleRomanize = { romanizeToggled = true },
                            onToggleTranslate = { translateToggled = true },
                            onSetShowLyricsCardLogo = {},
                            modifier = mod,
                        )
                    },
                    onDismiss = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeekTo = {},
                    onToggleShuffle = {},
                    onCycleRepeat = {},
                    onToggleLike = {},
                    onToggleAutoplay = {},
                    onSelectQueueIndex = {},
                    onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {},
                    onOpenMoreActions = {},
                    onOpenLyricsProviders = { providerSheetOpened = true },
                    onOpenStatsForNerds = {},
                )
            }
        }

        // Open Lyrics tab via the live lyric preview strip
        composeRule.onNodeWithText("Transport, motorways and tramlines").performClick()
        composeRule.waitForIdle()

        // Underlined "Lyrics by lrc.red" status link opens provider sheet
        composeRule.onNodeWithText(context.getString(R.string.player_lyrics_by, "lrc.red")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.player_lyrics_by, "lrc.red")).performClick()
        assertTrue(providerSheetOpened)

        // Floating Romanize & Translate controls trigger callbacks
        composeRule.onNodeWithContentDescription(context.getString(R.string.lyrics_romanize)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.lyrics_translate)).performClick()
        assertTrue(romanizeToggled)
        assertTrue(translateToggled)

        // Long-press activates multi-line picking bar & Close button in GlassDiscPair
        composeRule.onNodeWithText("Starting and then stopping").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTrue(pickingActive)
        composeRule.onNodeWithText(context.getString(R.string.menu_share)).assertIsDisplayed()
        // Exceeding 250-char budget keeps picking bar alive cleanly without crashing
        composeRule.onNodeWithText("A".repeat(240)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()
        assertTrue(!pickingActive)
    }

    @Test
    fun audioOutputSheet_rendersDevicesAndInvokesSelection() {
        var selectedId: Int? = -1
        val devices = listOf(
            AudioOutputDevice(12, "AirPods Pro", AudioOutputKind.BUDS, AudioOutputRouter.TYPE_BLUETOOTH_A2DP, isBuiltIn = false),
            AudioOutputDevice(2, "This phone", AudioOutputKind.PHONE, AudioOutputRouter.TYPE_BUILTIN_SPEAKER, isBuiltIn = true),
        )

        composeRule.setContent {
            MusicaTheme(themeMode = ThemeMode.DARK) {
                AudioOutputSheet(
                    devices = devices,
                    activeDeviceId = 12,
                    needsBluetoothPermission = false,
                    hazeState = null,
                    onSelectDevice = { selectedId = it },
                    onRequestBluetoothPermission = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("AirPods Pro").assertIsDisplayed()
        composeRule.onNodeWithText("This phone").assertIsDisplayed()
        composeRule.onNodeWithText("This phone").performClick()
        assertEquals(2, selectedId)
    }
}
