package com.music.musica.core.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.AudioQuality
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "musica_settings")

@Immutable
@Serializable
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val playerTheme: PlayerTheme = PlayerTheme.DEFAULT,
    val fullBleedCoverArt: Boolean = false,
    val showVolumeSlider: Boolean = true,
    val showRemainingTime: Boolean = true,
    val blurUnfocusedLyrics: Boolean = true,
    val showLyricsPreviewStrip: Boolean = true,
    val sleepTimerFadeOut: Boolean = true,
    val audioCacheLimitMb: Int = 512,
    val frostedGlassEnabled: Boolean = true,
    val reduceAnimation: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val wifiQuality: AudioQuality = AudioQuality.MAX,
    val cellularQuality: AudioQuality = AudioQuality.MEDIUM,
    val crossfadeSeconds: Int = 0,
    val skipSilence: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val preferWordSyncedLyrics: Boolean = true,
    val enabledLyricsSources: Set<LyricsSourceId> = LyricsSourceId.entries.toSet(),
    val lyricsSourceOrder: List<LyricsSourceId> = LyricsSourceId.entries.toList(),
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PLAYER_THEME = stringPreferencesKey("player_theme")
        val FULL_BLEED_COVER = booleanPreferencesKey("full_bleed_cover")
        val SHOW_VOLUME_SLIDER = booleanPreferencesKey("show_volume_slider")
        val SHOW_REMAINING_TIME = booleanPreferencesKey("show_remaining_time")
        val BLUR_UNFOCUSED_LYRICS = booleanPreferencesKey("blur_unfocused_lyrics")
        val SHOW_LYRICS_PREVIEW = booleanPreferencesKey("show_lyrics_preview")
        val SLEEP_FADE_OUT = booleanPreferencesKey("sleep_fade_out")
        val AUDIO_CACHE_LIMIT_MB = intPreferencesKey("audio_cache_limit_mb")
        val FROSTED_GLASS = booleanPreferencesKey("frosted_glass")
        val REDUCE_ANIMATION = booleanPreferencesKey("reduce_animation")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val WIFI_QUALITY = stringPreferencesKey("wifi_quality")
        val CELLULAR_QUALITY = stringPreferencesKey("cellular_quality")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PREFER_WORD_LYRICS = booleanPreferencesKey("prefer_word_lyrics")
        val ENABLED_LYRICS = stringPreferencesKey("enabled_lyrics")
        val LYRICS_ORDER = stringPreferencesKey("lyrics_order")
    }

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val enabledRaw = prefs[Keys.ENABLED_LYRICS]
        val enabledLyrics = if (enabledRaw == null) {
            LyricsSourceId.entries.toSet()
        } else {
            enabledRaw.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { LyricsSourceId.valueOf(it) }.getOrNull() }
                .toSet()
        }

        val orderRaw = prefs[Keys.LYRICS_ORDER]
        val savedOrder = orderRaw?.split(",")
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { runCatching { LyricsSourceId.valueOf(it) }.getOrNull() }
            .orEmpty()
        val fullOrder = (savedOrder + LyricsSourceId.entries).distinct()

        UserSettings(
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            playerTheme = prefs[Keys.PLAYER_THEME]?.let { runCatching { PlayerTheme.valueOf(it) }.getOrNull() }
                ?: PlayerTheme.DEFAULT,
            fullBleedCoverArt = prefs[Keys.FULL_BLEED_COVER] ?: false,
            showVolumeSlider = prefs[Keys.SHOW_VOLUME_SLIDER] ?: true,
            showRemainingTime = prefs[Keys.SHOW_REMAINING_TIME] ?: true,
            blurUnfocusedLyrics = prefs[Keys.BLUR_UNFOCUSED_LYRICS] ?: true,
            showLyricsPreviewStrip = prefs[Keys.SHOW_LYRICS_PREVIEW] ?: true,
            sleepTimerFadeOut = prefs[Keys.SLEEP_FADE_OUT] ?: true,
            audioCacheLimitMb = (prefs[Keys.AUDIO_CACHE_LIMIT_MB] ?: 512).coerceIn(128, 4096),
            frostedGlassEnabled = prefs[Keys.FROSTED_GLASS] ?: true,
            reduceAnimation = prefs[Keys.REDUCE_ANIMATION] ?: false,
            appLanguage = prefs[Keys.APP_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            wifiQuality = prefs[Keys.WIFI_QUALITY]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
                ?: AudioQuality.MAX,
            cellularQuality = prefs[Keys.CELLULAR_QUALITY]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
                ?: AudioQuality.MEDIUM,
            crossfadeSeconds = (prefs[Keys.CROSSFADE_SECONDS] ?: 0).coerceIn(0, 12),
            skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
            autoplayEnabled = prefs[Keys.AUTOPLAY] ?: true,
            playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f),
            preferWordSyncedLyrics = prefs[Keys.PREFER_WORD_LYRICS] ?: true,
            enabledLyricsSources = enabledLyrics,
            lyricsSourceOrder = fullOrder,
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setPlayerTheme(theme: PlayerTheme) {
        context.settingsDataStore.edit { it[Keys.PLAYER_THEME] = theme.name }
    }

    suspend fun setFullBleedCoverArt(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FULL_BLEED_COVER] = enabled }
    }

    suspend fun setShowVolumeSlider(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_VOLUME_SLIDER] = enabled }
    }

    suspend fun setShowRemainingTime(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_REMAINING_TIME] = enabled }
    }

    suspend fun setBlurUnfocusedLyrics(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BLUR_UNFOCUSED_LYRICS] = enabled }
    }

    suspend fun setShowLyricsPreviewStrip(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_LYRICS_PREVIEW] = enabled }
    }

    suspend fun setSleepTimerFadeOut(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SLEEP_FADE_OUT] = enabled }
    }

    suspend fun setAudioCacheLimitMb(mb: Int) {
        context.settingsDataStore.edit { it[Keys.AUDIO_CACHE_LIMIT_MB] = mb.coerceIn(128, 4096) }
    }

    suspend fun setFrostedGlass(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FROSTED_GLASS] = enabled }
    }

    suspend fun setReduceAnimation(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.REDUCE_ANIMATION] = enabled }
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[Keys.APP_LANGUAGE] = language.name }
    }

    suspend fun setWifiQuality(quality: AudioQuality) {
        context.settingsDataStore.edit { it[Keys.WIFI_QUALITY] = quality.name }
    }

    suspend fun setCellularQuality(quality: AudioQuality) {
        context.settingsDataStore.edit { it[Keys.CELLULAR_QUALITY] = quality.name }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(0, 12) }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SKIP_SILENCE] = enabled }
    }

    suspend fun setAutoplay(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTOPLAY] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.settingsDataStore.edit { it[Keys.PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setPreferWordSyncedLyrics(prefer: Boolean) {
        context.settingsDataStore.edit { it[Keys.PREFER_WORD_LYRICS] = prefer }
    }

    suspend fun toggleLyricsSource(source: LyricsSourceId, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.ENABLED_LYRICS]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { runCatching { LyricsSourceId.valueOf(it) }.getOrNull() }
                ?.toMutableSet()
                ?: LyricsSourceId.entries.toMutableSet()
            if (enabled) current.add(source) else current.remove(source)
            prefs[Keys.ENABLED_LYRICS] = current.joinToString(",") { it.name }
        }
    }

    suspend fun setLyricsSourceOrder(order: List<LyricsSourceId>) {
        context.settingsDataStore.edit {
            it[Keys.LYRICS_ORDER] = order.distinct().joinToString(",") { src -> src.name }
        }
    }

    suspend fun restoreFrom(restored: UserSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = restored.themeMode.name
            prefs[Keys.PLAYER_THEME] = restored.playerTheme.name
            prefs[Keys.FULL_BLEED_COVER] = restored.fullBleedCoverArt
            prefs[Keys.SHOW_VOLUME_SLIDER] = restored.showVolumeSlider
            prefs[Keys.SHOW_REMAINING_TIME] = restored.showRemainingTime
            prefs[Keys.BLUR_UNFOCUSED_LYRICS] = restored.blurUnfocusedLyrics
            prefs[Keys.SHOW_LYRICS_PREVIEW] = restored.showLyricsPreviewStrip
            prefs[Keys.SLEEP_FADE_OUT] = restored.sleepTimerFadeOut
            prefs[Keys.AUDIO_CACHE_LIMIT_MB] = restored.audioCacheLimitMb.coerceIn(128, 4096)
            prefs[Keys.FROSTED_GLASS] = restored.frostedGlassEnabled
            prefs[Keys.REDUCE_ANIMATION] = restored.reduceAnimation
            prefs[Keys.WIFI_QUALITY] = restored.wifiQuality.name
            prefs[Keys.CELLULAR_QUALITY] = restored.cellularQuality.name
            prefs[Keys.CROSSFADE_SECONDS] = restored.crossfadeSeconds.coerceIn(0, 12)
            prefs[Keys.SKIP_SILENCE] = restored.skipSilence
            prefs[Keys.AUTOPLAY] = restored.autoplayEnabled
            prefs[Keys.PLAYBACK_SPEED] = restored.playbackSpeed.coerceIn(0.5f, 2.0f)
            prefs[Keys.PREFER_WORD_LYRICS] = restored.preferWordSyncedLyrics
            prefs[Keys.ENABLED_LYRICS] = restored.enabledLyricsSources.joinToString(",") { it.name }
            prefs[Keys.LYRICS_ORDER] = restored.lyricsSourceOrder.joinToString(",") { it.name }
        }
    }
}
