package com.music.musica.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlayHistoryEntity::class,
        SearchHistoryEntity::class,
        CachedLyricsEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicaDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun build(context: Context): MusicaDatabase = Room.databaseBuilder(
            context.applicationContext,
            MusicaDatabase::class.java,
            "musica.db",
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
