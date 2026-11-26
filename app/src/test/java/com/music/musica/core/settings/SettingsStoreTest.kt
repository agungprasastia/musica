package com.music.musica.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.AudioQuality
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }

    private fun createStore(): SettingsStore = SettingsStore(FakePreferencesDataStore())

    @Test
    fun freshStoreEmitsDefaultUserSettings() = runTest {
        val store = createStore()
        val defaults = store.settings.first()

        assertEquals(UserSettings(), defaults)
        assertFalse(defaults.onboardingCompleted)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(512, defaults.audioCacheLimitMb)
        assertEquals(1.0f, defaults.playbackSpeed, 0.001f)
        assertTrue(defaults.sleepTimerFadeOut)
        assertEquals(LyricsSourceId.entries.toSet(), defaults.enabledLyricsSources)
        assertEquals(LyricsSourceId.entries.toList(), defaults.lyricsSourceOrder)
    }

    @Test
    fun mutatorsPersistValuesAndClampOutOfRangeInputs() = runTest {
        val store = createStore()

        store.setOnboardingCompleted(true)
        store.setThemeMode(ThemeMode.DARK)
        store.setPlayerTheme(PlayerTheme.LEGACY)
        store.setFullBleedCoverArt(true)
        store.setSleepTimerFadeOut(false)
        store.setAudioCacheLimitMb(99_999) // clamped to 4096
        store.setCrossfadeSeconds(42)      // clamped to 12
        store.setPlaybackSpeed(0.1f)       // clamped to 0.5f
        store.setAppLanguage(AppLanguage.INDONESIAN)
        store.setWifiQuality(AudioQuality.HIGH)
        store.setCellularQuality(AudioQuality.LOW)

        val updated = store.settings.first()
        assertTrue(updated.onboardingCompleted)
        assertEquals(ThemeMode.DARK, updated.themeMode)
        assertEquals(PlayerTheme.LEGACY, updated.playerTheme)
        assertTrue(updated.fullBleedCoverArt)
        assertFalse(updated.sleepTimerFadeOut)
        assertEquals(4096, updated.audioCacheLimitMb)
        assertEquals(12, updated.crossfadeSeconds)
        assertEquals(0.5f, updated.playbackSpeed, 0.001f)
        assertEquals(AppLanguage.INDONESIAN, updated.appLanguage)
        assertEquals(AudioQuality.HIGH, updated.wifiQuality)
        assertEquals(AudioQuality.LOW, updated.cellularQuality)

        // Lower / upper bound clamping
        store.setAudioCacheLimitMb(10)
        store.setCrossfadeSeconds(-5)
        store.setPlaybackSpeed(5.0f)
        val clamped = store.settings.first()
        assertEquals(128, clamped.audioCacheLimitMb)
        assertEquals(0, clamped.crossfadeSeconds)
        assertEquals(2.0f, clamped.playbackSpeed, 0.001f)
    }

    @Test
    fun lyricsSourceToggleOrderAndRestoreFromWorkDeterministically() = runTest {
        val store = createStore()

        store.toggleLyricsSource(LyricsSourceId.YOUTUBE, enabled = false)
        store.setLyricsSourceOrder(listOf(LyricsSourceId.LRCLIB, LyricsSourceId.BETTER_LYRICS))

        val mid = store.settings.first()
        assertFalse(LyricsSourceId.YOUTUBE in mid.enabledLyricsSources)
        // Missing entries are appended in default order to keep full list intact
        assertEquals(LyricsSourceId.LRCLIB, mid.lyricsSourceOrder[0])
        assertEquals(LyricsSourceId.BETTER_LYRICS, mid.lyricsSourceOrder[1])
        assertEquals(LyricsSourceId.entries.size, mid.lyricsSourceOrder.size)

        val backup = UserSettings(
            themeMode = ThemeMode.LIGHT,
            audioCacheLimitMb = 1024,
            crossfadeSeconds = 6,
            playbackSpeed = 1.25f,
            frostedGlassEnabled = false,
            skipSilence = true,
            enabledLyricsSources = setOf(LyricsSourceId.LRCLIB),
            lyricsSourceOrder = listOf(
                LyricsSourceId.LRCLIB,
                LyricsSourceId.YOUTUBE,
                LyricsSourceId.BETTER_LYRICS,
                LyricsSourceId.LYRICS_PLUS,
            ),
        )
        store.restoreFrom(backup)

        val restored = store.settings.first()
        assertEquals(ThemeMode.LIGHT, restored.themeMode)
        assertEquals(1024, restored.audioCacheLimitMb)
        assertEquals(6, restored.crossfadeSeconds)
        assertEquals(1.25f, restored.playbackSpeed, 0.001f)
        assertFalse(restored.frostedGlassEnabled)
        assertTrue(restored.skipSilence)
        assertEquals(setOf(LyricsSourceId.LRCLIB), restored.enabledLyricsSources)
        assertEquals(backup.lyricsSourceOrder, restored.lyricsSourceOrder)
    }
}
