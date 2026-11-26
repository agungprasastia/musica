package com.music.musica.data.backup

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.AudioQuality
import com.music.musica.core.model.ThemeMode
import com.music.musica.core.network.HttpClients
import com.music.musica.core.settings.UserSettings
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupSerializerTest {

    @Test
    fun roundTripsFullV2BackupIncludingSettingsLibraryPlaylistsHistoryAndCollections() {
        val original = MusicaBackupV2(
            exportedAtMs = 1_750_000_000_000L,
            settings = UserSettings(
                themeMode = ThemeMode.DARK,
                audioCacheLimitMb = 1024,
                downloadQuality = AudioQuality.MAX,
                wifiOnlyDownloads = true,
                syncPlaybackHistoryToYtm = false,
            ),
            songs = listOf(
                SongBackup(
                    videoId = "abc12345678",
                    title = "Midnight Echo",
                    artists = listOf(ArtistRef("Luna", "UC_luna")),
                    albumName = "After Hours",
                    albumBrowseId = "MPREb_1",
                    durationMs = 215_000L,
                    isLiked = true,
                    likedAtMs = 1_700_000_000_000L,
                ),
            ),
            playlists = listOf(
                PlaylistBackup(
                    playlistId = "local_001",
                    title = "Late Night Drive",
                    isPinned = true,
                    pinnedAtMs = 1_710_000_000_000L,
                    trackVideoIds = listOf("abc12345678", "xyz98765432"),
                ),
            ),
            playHistory = listOf(
                PlayHistoryBackup(videoId = "abc12345678", playedAtMs = 1_720_000_000_000L, listenedDurationMs = 180_000L),
            ),
            subscribedArtists = listOf(
                SubscribedArtistBackup(browseId = "UC_luna", name = "Luna", subscribedAtMs = 1_700_000_000_000L),
            ),
            savedCollections = listOf(
                SavedCollectionBackup(browseId = "MPREb_1", type = "ALBUM", title = "After Hours"),
            ),
        )

        val encoded = FullBackupSerializer.encodeV2(original)
        val parsed = FullBackupSerializer.parse(encoded)
        assertTrue(parsed is ParsedBackup.FullV2)

        val decoded = (parsed as ParsedBackup.FullV2).data
        assertEquals(MusicaBackupV2.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(ThemeMode.DARK, decoded.settings.themeMode)
        assertEquals(1024, decoded.settings.audioCacheLimitMb)
        assertEquals(AudioQuality.MAX, decoded.settings.downloadQuality)
        assertTrue(decoded.settings.wifiOnlyDownloads)
        assertEquals(false, decoded.settings.syncPlaybackHistoryToYtm)
        assertEquals(1, decoded.songs.size)
        assertTrue(decoded.songs[0].isLiked)
        assertEquals(1, decoded.playlists.size)
        assertTrue(decoded.playlists[0].isPinned)
        assertEquals(listOf("abc12345678", "xyz98765432"), decoded.playlists[0].trackVideoIds)
        assertEquals(1, decoded.playHistory.size)
        assertEquals(1, decoded.subscribedArtists.size)
        assertEquals(1, decoded.savedCollections.size)
    }

    @Test
    fun parsesLegacyV1SettingsOnlyBackupCleanly() {
        val v1Settings = UserSettings(
            themeMode = ThemeMode.LIGHT,
            crossfadeSeconds = 8,
            audioCacheLimitMb = 768,
            skipSilence = true,
        )
        val legacyJson = HttpClients.json.encodeToString(v1Settings)

        val parsed = FullBackupSerializer.parse(legacyJson)
        assertTrue(parsed is ParsedBackup.SettingsOnlyV1)
        val settings = (parsed as ParsedBackup.SettingsOnlyV1).settings
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
        assertEquals(8, settings.crossfadeSeconds)
        assertEquals(768, settings.audioCacheLimitMb)
        assertTrue(settings.skipSilence)
    }
}
