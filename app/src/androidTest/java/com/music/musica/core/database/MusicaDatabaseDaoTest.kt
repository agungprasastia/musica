package com.music.musica.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicaDatabaseDaoTest {

    private lateinit var database: MusicaDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var historyDao: HistoryDao
    private lateinit var searchHistoryDao: SearchHistoryDao
    private lateinit var lyricsCacheDao: LyricsCacheDao
    private lateinit var downloadDao: DownloadDao

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MusicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = database.songDao()
        playlistDao = database.playlistDao()
        historyDao = database.historyDao()
        searchHistoryDao = database.searchHistoryDao()
        lyricsCacheDao = database.lyricsCacheDao()
        downloadDao = database.downloadDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertSong_thenQueryById_returnsSongAndIgnoresDuplicate() = runTest {
        val song = sampleSong(
            videoId = "vid_001",
            title = "Starlight Drive",
            artist = "Neon Pulse",
            album = "Night Horizon",
            thumbnailUrl = "https://example.invalid/thumb1.jpg",
            isExplicit = true,
        )
        val firstRowId = songDao.insertIgnore(SongEntity.fromSong(song))
        val duplicateRowId = songDao.insertIgnore(
            SongEntity.fromSong(song.copy(title = "Should Not Overwrite")),
        )

        assertNotEquals(-1L, firstRowId)
        assertEquals(-1L, duplicateRowId)

        val stored = songDao.getById("vid_001")
        assertNotNull(stored)
        val restored = stored!!.toSong()
        assertEquals("vid_001", restored.videoId)
        assertEquals("Starlight Drive", restored.title)
        assertEquals("Neon Pulse", restored.artistText)
        assertEquals("Night Horizon", restored.album?.name)
        assertEquals("https://example.invalid/thumb1.jpg", restored.thumbnailUrl)
        assertTrue(restored.isExplicit)
    }

    @Test
    fun updateSongMetadata_updatesFieldsAndPreservesThumbnailWhenNull() = runTest {
        val original = SongEntity.fromSong(
            sampleSong(
                videoId = "vid_meta",
                title = "Original Title",
                thumbnailUrl = "https://example.invalid/original.jpg",
                isExplicit = false,
            ),
        )
        songDao.insertIgnore(original)

        songDao.updateMetadata(
            videoId = "vid_meta",
            title = "Updated Title",
            artistsJson = """[{"name":"Updated Artist","browseId":"UC_1"}]""",
            albumName = "New Album",
            albumBrowseId = "MPRE_1",
            durationMs = 245_000L,
            thumbnailUrl = null,
            isExplicit = true,
        )

        val afterNullThumb = songDao.getById("vid_meta")!!
        assertEquals("Updated Title", afterNullThumb.title)
        assertEquals("New Album", afterNullThumb.albumName)
        assertEquals(245_000L, afterNullThumb.durationMs)
        assertEquals("https://example.invalid/original.jpg", afterNullThumb.thumbnailUrl)
        assertTrue(afterNullThumb.isExplicit)

        songDao.updateMetadata(
            videoId = "vid_meta",
            title = "Updated Title",
            artistsJson = afterNullThumb.artistsJson,
            albumName = afterNullThumb.albumName,
            albumBrowseId = afterNullThumb.albumBrowseId,
            durationMs = afterNullThumb.durationMs,
            thumbnailUrl = "https://example.invalid/replaced.jpg",
            isExplicit = false,
        )

        val afterNewThumb = songDao.getById("vid_meta")!!
        assertEquals("https://example.invalid/replaced.jpg", afterNewThumb.thumbnailUrl)
        assertFalse(afterNewThumb.isExplicit)
    }

    @Test
    fun setLiked_ordersLikedSongsByTimestampDescendingAndUpdatesLikedIds() = runTest {
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s1", "First")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s2", "Second")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s3", "Third")))

        songDao.setLiked("s1", liked = true, likedAtMs = 1_000L)
        songDao.setLiked("s2", liked = true, likedAtMs = 3_000L)
        songDao.setLiked("s3", liked = true, likedAtMs = 2_000L)

        val likedSongs = songDao.observeLikedSongs().first()
        assertEquals(listOf("s2", "s3", "s1"), likedSongs.map { it.videoId })
        assertEquals(setOf("s1", "s2", "s3"), songDao.observeLikedIds().first().toSet())

        songDao.setLiked("s3", liked = false, likedAtMs = 0L)

        val afterUnlike = songDao.observeLikedSongs().first()
        assertEquals(listOf("s2", "s1"), afterUnlike.map { it.videoId })
        assertEquals(setOf("s1", "s2"), songDao.observeLikedIds().first().toSet())
    }

    @Test
    fun playlistDao_crudOrdersSongsByPositionOrdersPlaylistsByUpdatedAtAndCascadesDelete() = runTest {
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s1", "Track 1")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s2", "Track 2")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("s3", "Track 3")))

        playlistDao.upsertPlaylist(
            PlaylistEntity(playlistId = "pl_old", title = "Roadtrip", updatedAtMs = 1_000L),
        )
        playlistDao.upsertPlaylist(
            PlaylistEntity(playlistId = "pl_new", title = "Focus", updatedAtMs = 5_000L),
        )

        assertEquals(0, playlistDao.nextPosition("pl_old"))

        playlistDao.addSongToPlaylist(PlaylistSongCrossRef("pl_old", "s3", position = 2))
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef("pl_old", "s1", position = 0))
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef("pl_old", "s2", position = 1))

        assertEquals(3, playlistDao.nextPosition("pl_old"))
        assertEquals(
            listOf("s1", "s2", "s3"),
            playlistDao.getSongsInPlaylist("pl_old").map { it.videoId },
        )

        val playlistsWithCounts = playlistDao.observePlaylistsWithCounts().first()
        assertEquals(listOf("pl_new", "pl_old"), playlistsWithCounts.map { it.playlist.playlistId })
        assertEquals(0, playlistsWithCounts[0].songCount)
        assertEquals(3, playlistsWithCounts[1].songCount)

        // Update playlist metadata via upsert
        playlistDao.upsertPlaylist(
            PlaylistEntity(playlistId = "pl_old", title = "Roadtrip Deluxe", updatedAtMs = 9_000L),
        )
        assertEquals("Roadtrip Deluxe", playlistDao.getPlaylist("pl_old")?.title)
        assertEquals(
            listOf("pl_old", "pl_new"),
            playlistDao.observePlaylistsWithCounts().first().map { it.playlist.playlistId },
        )

        // Remove single song
        playlistDao.removeSongFromPlaylist("pl_old", "s2")
        assertEquals(listOf("s1", "s3"), playlistDao.getSongsInPlaylist("pl_old").map { it.videoId })

        // Delete playlist cascades cross-ref rows
        playlistDao.deletePlaylist("pl_old")
        assertNull(playlistDao.getPlaylist("pl_old"))
        assertTrue(playlistDao.getSongsInPlaylist("pl_old").isEmpty())
    }

    @Test
    fun historyDao_insertOrdersByPlayedAtDescendingRespectsLimitAndClears() = runTest {
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("h1", "Morning Song")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("h2", "Noon Song")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("h3", "Evening Song")))

        historyDao.insert(PlayHistoryEntity(videoId = "h1", playedAtMs = 1_000L, listenedDurationMs = 90_000L))
        historyDao.insert(PlayHistoryEntity(videoId = "h2", playedAtMs = 3_000L, listenedDurationMs = 120_000L))
        historyDao.insert(PlayHistoryEntity(videoId = "h3", playedAtMs = 2_000L, listenedDurationMs = 60_000L))

        val allHistory = historyDao.observeAllHistory().first()
        assertEquals(listOf("h2", "h3", "h1"), allHistory.map { it.history.videoId })
        assertEquals(listOf("Noon Song", "Evening Song", "Morning Song"), allHistory.map { it.song?.title })

        val topTwo = historyDao.observeRecentHistory(limit = 2).first()
        assertEquals(listOf("h2", "h3"), topTwo.map { it.history.videoId })

        historyDao.clearAll()
        assertTrue(historyDao.observeAllHistory().first().isEmpty())
    }

    @Test
    fun searchHistoryDao_upsertOrdersBySearchedAtDescendingAndDeletes() = runTest {
        searchHistoryDao.upsert(SearchHistoryEntity(query = "ambient", searchedAtMs = 1_000L))
        searchHistoryDao.upsert(SearchHistoryEntity(query = "jazz", searchedAtMs = 2_000L))
        searchHistoryDao.upsert(SearchHistoryEntity(query = "lofi", searchedAtMs = 3_000L))

        // Re-searching an existing query replaces its timestamp and moves it to the top
        searchHistoryDao.upsert(SearchHistoryEntity(query = "ambient", searchedAtMs = 4_000L))

        assertEquals(
            listOf("ambient", "lofi", "jazz"),
            searchHistoryDao.observeRecentQueries().first(),
        )

        searchHistoryDao.delete("lofi")
        assertEquals(listOf("ambient", "jazz"), searchHistoryDao.observeRecentQueries().first())

        searchHistoryDao.clearAll()
        assertTrue(searchHistoryDao.observeRecentQueries().first().isEmpty())
    }

    @Test
    fun lyricsCacheDao_upsertQueryAndUpdateOffset() = runTest {
        assertNull(lyricsCacheDao.get("lyr_1"))

        val entity = CachedLyricsEntity(
            videoId = "lyr_1",
            sourceName = "LRCLIB",
            isSynced = true,
            isWordSynced = false,
            payloadJson = """[{"timeMs":1000,"text":"Hello world"}]""",
            offsetMs = 0L,
            cachedAtMs = 10_000L,
        )
        lyricsCacheDao.upsert(entity)

        val stored = lyricsCacheDao.get("lyr_1")
        assertNotNull(stored)
        assertEquals("LRCLIB", stored!!.sourceName)
        assertTrue(stored.isSynced)
        assertEquals(0L, stored.offsetMs)

        lyricsCacheDao.updateOffset("lyr_1", -350L)
        assertEquals(-350L, lyricsCacheDao.get("lyr_1")?.offsetMs)
    }

    @Test
    fun downloadDao_ordersByUpdatedAtTracksStatusesMarksIncompleteFailedAndDeletes() = runTest {
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("d1", "Completed Track")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("d2", "Queued Track")))
        songDao.insertIgnore(SongEntity.fromSong(sampleSong("d3", "Downloading Track")))

        downloadDao.upsert(
            DownloadEntity(
                videoId = "d1",
                status = DownloadState.COMPLETED.name,
                progressPercent = 100,
                fileSizeBytes = 4_500_000L,
                qualityKbps = 256,
                updatedAtMs = 1_000L,
            ),
        )
        downloadDao.upsert(
            DownloadEntity(
                videoId = "d2",
                status = DownloadState.QUEUED.name,
                progressPercent = 0,
                updatedAtMs = 3_000L,
            ),
        )
        downloadDao.upsert(
            DownloadEntity(
                videoId = "d3",
                status = DownloadState.DOWNLOADING.name,
                progressPercent = 45,
                updatedAtMs = 2_000L,
            ),
        )

        val downloads = downloadDao.observeDownloads().first()
        assertEquals(listOf("d2", "d3", "d1"), downloads.map { it.download.videoId })
        assertEquals(
            listOf("Queued Track", "Downloading Track", "Completed Track"),
            downloads.map { it.song?.title },
        )
        assertEquals(listOf("d1"), downloadDao.observeCompletedIds().first())
        assertEquals(listOf("d1"), downloadDao.getCompletedIds())
        assertEquals(setOf("d2", "d3"), downloadDao.getIncompleteIds().toSet())

        downloadDao.markIncompleteAsFailed()
        assertTrue(downloadDao.getIncompleteIds().isEmpty())
        val afterMarkFailed = downloadDao.observeDownloads().first().associateBy { it.download.videoId }
        assertEquals(DownloadState.COMPLETED, afterMarkFailed["d1"]!!.download.downloadState)
        assertEquals(DownloadState.FAILED, afterMarkFailed["d2"]!!.download.downloadState)
        assertEquals(0, afterMarkFailed["d2"]!!.download.progressPercent)
        assertEquals(DownloadState.FAILED, afterMarkFailed["d3"]!!.download.downloadState)
        assertEquals(0, afterMarkFailed["d3"]!!.download.progressPercent)

        downloadDao.delete("d1")
        assertTrue(downloadDao.getCompletedIds().isEmpty())
    }

    private fun sampleSong(
        videoId: String,
        title: String,
        artist: String = "Test Artist",
        album: String? = "Test Album",
        thumbnailUrl: String? = null,
        isExplicit: Boolean = false,
    ): Song = Song(
        videoId = videoId,
        title = title,
        artists = listOf(ArtistRef(name = artist, browseId = "UC_$videoId")),
        album = album?.let { AlbumRef(name = it, browseId = "MPRE_$videoId") },
        durationMs = 180_000L,
        thumbnailUrl = thumbnailUrl,
        isExplicit = isExplicit,
    )
}
