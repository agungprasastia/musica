package com.music.musica.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.musica.BuildConfig
import com.music.musica.R
import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.AppLanguage
import com.music.musica.core.model.AudioQuality
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.PlayerTheme
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.network.NetworkType
import com.music.musica.core.settings.UserSettings
import com.music.musica.ui.components.FrostedCircleButton
import com.music.musica.ui.components.GlassDialogButton
import com.music.musica.ui.components.IosSwitch
import com.music.musica.ui.components.LiquidGlassDialog
import com.music.musica.ui.components.LiquidGlassMenuRow
import com.music.musica.ui.components.PillSegmentedControl
import com.music.musica.ui.components.ThinSlider
import com.music.musica.ui.components.glassCard
import com.music.musica.ui.components.glassContentColor
import com.music.musica.ui.components.lightweightGlass
import com.music.musica.ui.theme.MusicaAccentRed
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

/**
 * Musica Settings screen featuring:
 * - Live in-Settings search bar filtering groups and rows dynamically
 * - Leading 22dp icons on every row + 52dp inset hairline dividers + 14dp grouped cards
 * - Inline PillSegmentedControls (Theme, Player Backdrop, Language) replacing heavy AlertDialogs
 * - "IN USE" network badge on active Wi-Fi / Mobile Data quality
 * - Bare Apple ThinSlider for Crossfade & Audio Cache Limit
 * - Local JSON Export/Import backup
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    profile: AccountProfile?,
    isSignedIn: Boolean,
    networkType: NetworkType,
    audioCacheBytes: Long,
    hazeState: HazeState,
    frostedGlassEnabled: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSetSyncYtmHistory: (Boolean) -> Unit = {},
    onSetCrossfade: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetAutoplay: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSetWifiQuality: (AudioQuality) -> Unit,
    onSetCellularQuality: (AudioQuality) -> Unit,
    onSetDownloadQuality: (AudioQuality) -> Unit = {},
    onSetWifiOnlyDownloads: (Boolean) -> Unit = {},
    onSetTheme: (ThemeMode) -> Unit,
    onSetPlayerTheme: (PlayerTheme) -> Unit,
    onSetFullBleedCover: (Boolean) -> Unit,
    onSetShowVolumeSlider: (Boolean) -> Unit,
    onSetShowRemainingTime: (Boolean) -> Unit,
    onSetFrostedGlass: (Boolean) -> Unit,
    onSetReduceAnimation: (Boolean) -> Unit,
    onSetPreferWordLyrics: (Boolean) -> Unit,
    onSetBlurUnfocusedLyrics: (Boolean) -> Unit,
    onSetShowLyricsPreview: (Boolean) -> Unit,
    onToggleLyricsSource: (LyricsSourceId, Boolean) -> Unit,
    onMoveLyricsSourceUp: (LyricsSourceId) -> Unit,
    onSetAudioCacheLimitMb: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onClearAudioCache: () -> Unit,
    onClearImageCache: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var confirmSignOut by remember { mutableStateOf(false) }
    var pickingWifiQuality by remember { mutableStateOf(false) }
    var pickingCellQuality by remember { mutableStateOf(false) }
    val screenHazeState = rememberHazeState()

    val q = searchQuery.trim().lowercase()
    fun matches(vararg keywords: String): Boolean =
        q.isBlank() || keywords.any { it.contains(q, ignoreCase = true) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(screenHazeState)
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Header with circular frosted back button + display title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FrostedCircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                hazeState = hazeState,
                frostedGlassEnabled = frostedGlassEnabled,
                onClick = onBack,
            )
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Live Settings Liquid Glass Capsule SearchField
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(46.dp)
                .lightweightGlass(RoundedCornerShape(percent = 50))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPadding + 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Account
            if (matches("account", "akun", "sign in", "masuk", "google", "guest", "tamu", "login", "history", "riwayat", "sync", "sinkron", "privacy", "privasi")) {
                item(key = "sec_account") {
                    SettingsGroup(title = stringResource(R.string.settings_section_account)) {
                        SettingsValueRow(
                            icon = Icons.Rounded.Person,
                            title = if (isSignedIn) {
                                stringResource(R.string.auth_signed_in_as, profile?.name ?: "YouTube Music")
                            } else {
                                stringResource(R.string.auth_guest_mode)
                            },
                            value = stringResource(if (isSignedIn) R.string.auth_sign_out else R.string.auth_sign_in),
                            destructive = isSignedIn,
                            onClick = { if (isSignedIn) confirmSignOut = true else onSignIn() },
                        )
                        if (isSignedIn) {
                            GroupDivider()
                            SettingsSwitchRow(
                                icon = Icons.Rounded.History,
                                title = stringResource(R.string.settings_sync_ytm_history),
                                subtitle = stringResource(R.string.settings_sync_ytm_history_desc),
                                checked = settings.syncPlaybackHistoryToYtm,
                                onCheckedChange = onSetSyncYtmHistory,
                            )
                        }
                    }
                }
            }

            // 2. Playback
            if (matches("playback", "pemutaran", "crossfade", "silence", "hening", "autoplay", "equalizer", "eq")) {
                item(key = "sec_playback") {
                    SettingsGroup(title = stringResource(R.string.settings_section_playback)) {
                        var draftCrossfade by remember(settings.crossfadeSeconds) {
                            mutableIntStateOf(settings.crossfadeSeconds)
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(stringResource(R.string.settings_crossfade), style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    text = if (draftCrossfade == 0) {
                                        stringResource(R.string.settings_crossfade_off)
                                    } else {
                                        stringResource(R.string.settings_crossfade_seconds, draftCrossfade)
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            ThinSlider(
                                value = draftCrossfade.toFloat(),
                                onValueChange = { draftCrossfade = it.roundToInt().coerceIn(0, 12) },
                                onValueChangeFinished = {
                                    if (draftCrossfade != settings.crossfadeSeconds) {
                                        onSetCrossfade(draftCrossfade)
                                    }
                                },
                                valueRange = 0f..12f,
                                activeColor = MaterialTheme.colorScheme.primary,
                                inactiveColor = MaterialTheme.colorScheme.outline,
                            )
                        }
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.FastForward,
                            title = stringResource(R.string.settings_skip_silence),
                            subtitle = stringResource(R.string.settings_skip_silence_desc),
                            checked = settings.skipSilence,
                            onCheckedChange = onSetSkipSilence,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.AllInclusive,
                            title = stringResource(R.string.settings_autoplay),
                            subtitle = stringResource(R.string.settings_autoplay_desc),
                            checked = settings.autoplayEnabled,
                            onCheckedChange = onSetAutoplay,
                        )
                        GroupDivider()
                        SettingsValueRow(
                            icon = Icons.Rounded.Equalizer,
                            title = stringResource(R.string.settings_equalizer),
                            subtitle = stringResource(R.string.settings_equalizer_desc),
                            value = "",
                            showChevron = true,
                            onClick = onOpenEqualizer,
                        )
                    }
                }
            }

            // 3. Audio Quality with live IN USE badge
            if (matches("quality", "kualitas", "audio", "wifi", "wi-fi", "cellular", "seluler", "bitrate", "kbps")) {
                item(key = "sec_quality") {
                    SettingsGroup(title = stringResource(R.string.settings_section_audio_quality)) {
                        SettingsValueRow(
                            icon = Icons.Rounded.Wifi,
                            title = stringResource(R.string.settings_wifi_quality),
                            badge = if (networkType == NetworkType.WIFI) stringResource(R.string.settings_in_use) else null,
                            value = qualityLabel(settings.wifiQuality),
                            showChevron = true,
                            onClick = { pickingWifiQuality = true },
                        )
                        GroupDivider()
                        SettingsValueRow(
                            icon = Icons.Rounded.SignalCellularAlt,
                            title = stringResource(R.string.settings_cellular_quality),
                            badge = if (networkType == NetworkType.CELLULAR) stringResource(R.string.settings_in_use) else null,
                            value = qualityLabel(settings.cellularQuality),
                            showChevron = true,
                            onClick = { pickingCellQuality = true },
                        )
                    }
                }
            }

            // 4. Appearance & Player Craft (inline PillSegmentedControls!)
            if (matches("appearance", "tampilan", "theme", "tema", "dark", "light", "blur", "glass", "cover", "sampul", "volume", "remaining", "waktu", "animation", "animasi", "mesh")) {
                item(key = "sec_appearance") {
                    SettingsGroup(title = stringResource(R.string.settings_section_appearance)) {
                        SettingsSegmentedRow(
                            icon = Icons.Rounded.Palette,
                            title = stringResource(R.string.settings_theme),
                            options = listOf(
                                stringResource(R.string.theme_system),
                                stringResource(R.string.theme_light),
                                stringResource(R.string.theme_dark),
                            ),
                            selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
                            onSelect = { onSetTheme(ThemeMode.entries[it]) },
                        )
                        GroupDivider()
                        SettingsSegmentedRow(
                            icon = Icons.Rounded.BlurOn,
                            title = stringResource(R.string.settings_player_theme),
                            options = listOf(
                                stringResource(R.string.player_theme_default),
                                stringResource(R.string.player_theme_legacy),
                                stringResource(R.string.player_theme_simple),
                            ),
                            selectedIndex = PlayerTheme.entries.indexOf(settings.playerTheme),
                            onSelect = { onSetPlayerTheme(PlayerTheme.entries[it]) },
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Fullscreen,
                            title = stringResource(R.string.settings_full_bleed_cover),
                            subtitle = stringResource(R.string.settings_full_bleed_cover_desc),
                            checked = settings.fullBleedCoverArt,
                            onCheckedChange = onSetFullBleedCover,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            title = stringResource(R.string.settings_show_volume),
                            subtitle = stringResource(R.string.settings_show_volume_desc),
                            checked = settings.showVolumeSlider,
                            onCheckedChange = onSetShowVolumeSlider,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Timer,
                            title = stringResource(R.string.settings_show_remaining),
                            subtitle = stringResource(R.string.settings_show_remaining_desc),
                            checked = settings.showRemainingTime,
                            onCheckedChange = onSetShowRemainingTime,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.BlurOn,
                            title = stringResource(R.string.settings_frosted_glass),
                            subtitle = stringResource(R.string.settings_frosted_glass_desc),
                            checked = settings.frostedGlassEnabled,
                            onCheckedChange = onSetFrostedGlass,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Animation,
                            title = stringResource(R.string.settings_reduce_animation),
                            subtitle = stringResource(R.string.settings_reduce_animation_desc),
                            checked = settings.reduceAnimation,
                            onCheckedChange = onSetReduceAnimation,
                        )
                    }
                }
            }

            // 5. Lyrics
            if (matches("lyrics", "lirik", "betterlyrics", "lrclib", "blur", "preview", "pratinjau", "word", "kata")) {
                item(key = "sec_lyrics") {
                    SettingsGroup(title = stringResource(R.string.settings_section_lyrics)) {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Lyrics,
                            title = stringResource(R.string.settings_lyrics_prefer_word),
                            subtitle = stringResource(R.string.settings_lyrics_prefer_word_desc),
                            checked = settings.preferWordSyncedLyrics,
                            onCheckedChange = onSetPreferWordLyrics,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.BlurOn,
                            title = stringResource(R.string.settings_blur_lyrics),
                            subtitle = stringResource(R.string.settings_blur_lyrics_desc),
                            checked = settings.blurUnfocusedLyrics,
                            onCheckedChange = onSetBlurUnfocusedLyrics,
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Subtitles,
                            title = stringResource(R.string.settings_lyrics_preview),
                            subtitle = stringResource(R.string.settings_lyrics_preview_desc),
                            checked = settings.showLyricsPreviewStrip,
                            onCheckedChange = onSetShowLyricsPreview,
                        )
                        settings.lyricsSourceOrder.forEachIndexed { idx, source ->
                            GroupDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = source.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (idx > 0) {
                                    IconButton(
                                        onClick = { onMoveLyricsSourceUp(source) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowUpward,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                IosSwitch(
                                    checked = source in settings.enabledLyricsSources,
                                    onCheckedChange = { onToggleLyricsSource(source, it) },
                                )
                            }
                        }
                    }
                }
            }

            // 6. Storage & Downloads
            if (matches("storage", "penyimpanan", "download", "unduhan", "cache", "limit", "batas", "wifi", "wi-fi", "quality", "kualitas")) {
                item(key = "sec_storage") {
                    SettingsGroup(title = stringResource(R.string.settings_section_storage)) {
                        SettingsValueRow(
                            icon = Icons.Rounded.DownloadDone,
                            title = stringResource(R.string.downloads_title),
                            value = "",
                            showChevron = true,
                            onClick = onOpenDownloads,
                        )
                        GroupDivider()
                        SettingsSegmentedRow(
                            icon = Icons.Rounded.HighQuality,
                            title = stringResource(R.string.settings_download_quality),
                            options = listOf(
                                stringResource(R.string.quality_short_low),
                                stringResource(R.string.quality_short_medium),
                                stringResource(R.string.quality_short_high),
                                stringResource(R.string.quality_short_max),
                            ),
                            selectedIndex = AudioQuality.entries.indexOf(settings.downloadQuality),
                            onSelect = { onSetDownloadQuality(AudioQuality.entries[it]) },
                        )
                        GroupDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Wifi,
                            title = stringResource(R.string.settings_wifi_only_downloads),
                            subtitle = stringResource(R.string.settings_wifi_only_downloads_desc),
                            checked = settings.wifiOnlyDownloads,
                            onCheckedChange = onSetWifiOnlyDownloads,
                        )
                        GroupDivider()
                        var draftCacheLimitMb by remember(settings.audioCacheLimitMb) {
                            mutableIntStateOf(settings.audioCacheLimitMb)
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Storage,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Text(stringResource(R.string.settings_cache_limit), style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    text = if (draftCacheLimitMb >= 1024) {
                                        "%.1f GB".format(draftCacheLimitMb / 1024f)
                                    } else {
                                        "$draftCacheLimitMb MB"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            ThinSlider(
                                value = draftCacheLimitMb.toFloat(),
                                onValueChange = { raw ->
                                    draftCacheLimitMb = ((raw / 128f).roundToInt() * 128).coerceIn(128, 4096)
                                },
                                onValueChangeFinished = {
                                    if (draftCacheLimitMb != settings.audioCacheLimitMb) {
                                        onSetAudioCacheLimitMb(draftCacheLimitMb)
                                    }
                                },
                                valueRange = 128f..4096f,
                                activeColor = MaterialTheme.colorScheme.primary,
                                inactiveColor = MaterialTheme.colorScheme.outline,
                            )
                        }
                        GroupDivider()
                        SettingsValueRow(
                            icon = Icons.Rounded.DeleteSweep,
                            title = stringResource(R.string.settings_clear_audio_cache),
                            value = "%.1f MB".format(audioCacheBytes / (1024.0 * 1024.0)),
                            onClick = onClearAudioCache,
                        )
                        GroupDivider()
                        SettingsValueRow(
                            icon = Icons.Rounded.Image,
                            title = stringResource(R.string.settings_clear_image_cache),
                            value = stringResource(R.string.action_clear),
                            onClick = onClearImageCache,
                        )
                    }
                }
            }

            // 7. Your Data (Local JSON Export/Import Backup)
            if (matches("data", "backup", "cadangan", "export", "ekspor", "import", "impor", "json")) {
                item(key = "sec_data") {
                    SettingsGroup(title = stringResource(R.string.settings_section_data)) {
                        SettingsValueRow(
                            icon = Icons.Rounded.FileUpload,
                            title = stringResource(R.string.settings_export_backup),
                            subtitle = stringResource(R.string.settings_export_backup_desc),
                            value = "",
                            showChevron = true,
                            onClick = onExportBackup,
                        )
                        GroupDivider()
                        SettingsValueRow(
                            icon = Icons.Rounded.FileDownload,
                            title = stringResource(R.string.settings_import_backup),
                            subtitle = stringResource(R.string.settings_import_backup_desc),
                            value = "",
                            showChevron = true,
                            onClick = onImportBackup,
                        )
                    }
                }
            }

            // 8. Language (inline PillSegmentedControl!)
            if (matches("language", "bahasa", "english", "indonesia")) {
                item(key = "sec_language") {
                    SettingsGroup(title = stringResource(R.string.settings_section_language)) {
                        SettingsSegmentedRow(
                            icon = Icons.Rounded.Language,
                            title = stringResource(R.string.settings_app_language),
                            options = listOf(
                                stringResource(R.string.theme_system),
                                stringResource(R.string.language_english),
                                "Indonesia",
                            ),
                            selectedIndex = AppLanguage.entries.indexOf(settings.appLanguage),
                            onSelect = { onSetLanguage(AppLanguage.entries[it]) },
                        )
                    }
                }
            }

            // 9. About
            if (matches("about", "tentang", "version", "versi", "musica")) {
                item(key = "sec_about") {
                    SettingsGroup(title = stringResource(R.string.settings_section_about)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.settings_about_disclaimer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        }
    }

    if (confirmSignOut) {
        LiquidGlassDialog(
            hazeState = screenHazeState,
            onDismiss = { confirmSignOut = false },
            title = stringResource(R.string.auth_sign_out),
            actions = {
                GlassDialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmSignOut = false },
                )
                GlassDialogButton(
                    text = stringResource(R.string.auth_sign_out),
                    destructive = true,
                    onClick = { confirmSignOut = false; onSignOut() },
                )
            },
        ) {
            Text(
                text = stringResource(R.string.auth_sign_out_confirm),
                style = MaterialTheme.typography.bodyMedium,
                color = glassContentColor().copy(alpha = 0.80f),
            )
        }
    }

    if (pickingWifiQuality) {
        ChoiceDialog(
            title = stringResource(R.string.settings_wifi_quality),
            hazeState = screenHazeState,
            items = AudioQuality.entries.map { it to qualityLabel(it) },
            selected = settings.wifiQuality,
            onSelect = { pickingWifiQuality = false; onSetWifiQuality(it) },
            onDismiss = { pickingWifiQuality = false },
        )
    }

    if (pickingCellQuality) {
        ChoiceDialog(
            title = stringResource(R.string.settings_cellular_quality),
            hazeState = screenHazeState,
            items = AudioQuality.entries.map { it to qualityLabel(it) },
            selected = settings.cellularQuality,
            onSelect = { pickingCellQuality = false; onSetCellularQuality(it) },
            onDismiss = { pickingCellQuality = false },
        )
    }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(RoundedCornerShape(18.dp)),
            content = content,
        )
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    )
}

@Composable
private fun SettingsSegmentedRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(10.dp))
        PillSegmentedControl(
            options = options,
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelect = onSelect,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    value: String,
    destructive: Boolean = false,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!badge.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MusicaAccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    hazeState: HazeState,
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    LiquidGlassDialog(
        hazeState = hazeState,
        onDismiss = onDismiss,
        title = title,
        actions = {
            GlassDialogButton(
                text = stringResource(R.string.action_close),
                primary = true,
                onClick = onDismiss,
            )
        },
    ) {
        Column {
            items.forEach { (item, label) ->
                LiquidGlassMenuRow(
                    label = label,
                    selected = item == selected,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun qualityLabel(quality: AudioQuality): String = when (quality) {
    AudioQuality.LOW -> stringResource(R.string.quality_low)
    AudioQuality.MEDIUM -> stringResource(R.string.quality_medium)
    AudioQuality.HIGH -> stringResource(R.string.quality_high)
    AudioQuality.MAX -> stringResource(R.string.quality_max)
}
