package com.music.musica.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        CachedLyricsEntity::class,
        DownloadEntity::class,
        SubscribedArtistEntity::class,
        SavedCollectionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MusicaDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun downloadDao(): DownloadDao
    abstract fun subscribedArtistDao(): SubscribedArtistDao
    abstract fun savedCollectionDao(): SavedCollectionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN pinnedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN isDisliked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN collectionId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN collectionTitle TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscribed_artists` (
                        `browseId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `subscribedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`browseId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_collections` (
                        `browseId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT,
                        `thumbnailUrl` TEXT,
                        `savedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`browseId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): MusicaDatabase = Room.databaseBuilder(
            context.applicationContext,
            MusicaDatabase::class.java,
            "musica.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
