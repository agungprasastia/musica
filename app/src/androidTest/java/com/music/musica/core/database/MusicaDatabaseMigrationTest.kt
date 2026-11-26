package com.music.musica.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MusicaDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "musica_migration_test_${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_preservesExistingDataAndEnablesNewColumnsAndTables() = runTest {
        // 1. Seed a genuine v1 SQLite database matching MusicaDatabase schema v1
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV1Schema(db)
                            db.execSQL(
                                """
                                INSERT INTO songs (videoId, title, artistsJson, albumName, albumBrowseId, durationMs, thumbnailUrl, isExplicit, isLiked, likedAtMs)
                                VALUES ('vid_mig_1', 'Migrated Song', '[{"name":"Artist M"}]', 'Album M', 'MPRE_M', 200000, 'https://img/m.jpg', 0, 1, 5000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO playlists (playlistId, title, description, thumbnailUrl, isRemoteYtm, updatedAtMs)
                                VALUES ('pl_mig_1', 'My Roadtrip', 'Desc', NULL, 0, 7000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO playlist_songs (playlistId, videoId, position, setVideoId, addedAtMs)
                                VALUES ('pl_mig_1', 'vid_mig_1', 0, NULL, 7100)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO play_history (videoId, playedAtMs, listenedDurationMs)
                                VALUES ('vid_mig_1', 8000, 180000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO downloads (videoId, status, progressPercent, fileSizeBytes, qualityKbps, updatedAtMs)
                                VALUES ('vid_mig_1', 'COMPLETED', 100, 3500000, 256, 9000)
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        v1Helper.writableDatabase.close()
        v1Helper.close()

        // 2. Open with Room at version 2 using MIGRATION_1_2 (Room validates full schema equality!)
        val migratedDb = Room.databaseBuilder(context, MusicaDatabase::class.java, dbName)
            .addMigrations(MusicaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            // Verify preserved v1 data + default values on newly added columns
            val song = migratedDb.songDao().getById("vid_mig_1")
            assertNotNull(song)
            assertEquals("Migrated Song", song!!.title)
            assertTrue(song.isLiked)
            assertFalse(song.isDisliked)

            val playlist = migratedDb.playlistDao().getPlaylist("pl_mig_1")
            assertNotNull(playlist)
            assertEquals("My Roadtrip", playlist!!.title)
            assertFalse(playlist.isPinned)
            assertEquals(0L, playlist.pinnedAtMs)
            assertEquals(listOf("vid_mig_1"), migratedDb.playlistDao().getSongsInPlaylist("pl_mig_1").map { it.videoId })

            val history = migratedDb.historyDao().observeAllHistory().first()
            assertEquals(1, history.size)
            assertEquals("vid_mig_1", history.first().history.videoId)

            val completedDownloads = migratedDb.downloadDao().getCompletedIds()
            assertEquals(listOf("vid_mig_1"), completedDownloads)

            // Verify new v2 operations work cleanly on migrated database
            migratedDb.playlistDao().setPinned("pl_mig_1", pinned = true, pinnedAtMs = 12_000L)
            assertTrue(migratedDb.playlistDao().getPlaylist("pl_mig_1")!!.isPinned)

            migratedDb.subscribedArtistDao().upsert(
                SubscribedArtistEntity(browseId = "UC_artist_1", name = "Artist M", subscribedAtMs = 13_000L),
            )
            assertTrue(migratedDb.subscribedArtistDao().isSubscribed("UC_artist_1"))

            migratedDb.savedCollectionDao().upsert(
                SavedCollectionEntity(
                    browseId = "MPRE_M",
                    type = "ALBUM",
                    title = "Album M",
                    savedAtMs = 14_000L,
                ),
            )
            assertEquals(listOf("MPRE_M"), migratedDb.savedCollectionDao().observeIds().first())
        } finally {
            migratedDb.close()
        }
    }

    private fun createV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `songs` (
                `videoId` TEXT NOT NULL, `title` TEXT NOT NULL, `artistsJson` TEXT NOT NULL,
                `albumName` TEXT, `albumBrowseId` TEXT, `durationMs` INTEGER NOT NULL,
                `thumbnailUrl` TEXT, `isExplicit` INTEGER NOT NULL, `isLiked` INTEGER NOT NULL,
                `likedAtMs` INTEGER NOT NULL, PRIMARY KEY(`videoId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlists` (
                `playlistId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL,
                `thumbnailUrl` TEXT, `isRemoteYtm` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`playlistId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_songs` (
                `playlistId` TEXT NOT NULL, `videoId` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `setVideoId` TEXT, `addedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`playlistId`, `videoId`),
                FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`playlistId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_videoId` ON `playlist_songs` (`videoId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `play_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `videoId` TEXT NOT NULL,
                `playedAtMs` INTEGER NOT NULL, `listenedDurationMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_videoId` ON `play_history` (`videoId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAtMs` ON `play_history` (`playedAtMs`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_history` (
                `query` TEXT NOT NULL, `searchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`query`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_lyrics` (
                `videoId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `isSynced` INTEGER NOT NULL,
                `isWordSynced` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL,
                `offsetMs` INTEGER NOT NULL, `cachedAtMs` INTEGER NOT NULL, PRIMARY KEY(`videoId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `downloads` (
                `videoId` TEXT NOT NULL, `status` TEXT NOT NULL, `progressPercent` INTEGER NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL, `qualityKbps` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`videoId`)
            )
            """.trimIndent(),
        )
    }
}
