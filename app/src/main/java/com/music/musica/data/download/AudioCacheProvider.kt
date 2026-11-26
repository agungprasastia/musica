package com.music.musica.data.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.musica.core.network.HttpClients
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides two isolated Media3 SimpleCaches:
 * 1. [downloadCache] (NoOpCacheEvictor): persists completed offline tracks until deleted.
 * 2. [streamCache] (LRU 256 MB): speeds up replays and seek operations during normal streaming.
 */
@OptIn(UnstableApi::class)
class AudioCacheProvider(context: Context) {

    val databaseProvider: StandaloneDatabaseProvider = StandaloneDatabaseProvider(context)

    private val downloadDir = File(context.filesDir, "musica_downloads").apply { mkdirs() }
    private val streamCacheDir = File(context.cacheDir, "musica_audio_cache").apply { mkdirs() }

    /** Only videoIds verified as fully completed in Room are treated as offline-playable. */
    private val completedDownloadIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val downloadCache: SimpleCache by lazy {
        SimpleCache(downloadDir, NoOpCacheEvictor(), databaseProvider)
    }

    private val streamCacheEvictor = DynamicLruCacheEvictor(DEFAULT_STREAM_CACHE_BYTES)

    val streamCache: SimpleCache by lazy {
        SimpleCache(streamCacheDir, streamCacheEvictor, databaseProvider)
    }

    fun updateStreamCacheLimitMb(limitMb: Int) {
        val clampedBytes = limitMb.coerceIn(128, 4096).toLong() * 1024L * 1024L
        streamCacheEvictor.maxBytes = clampedBytes
        runCatching { streamCacheEvictor.applyNow(streamCache) }
    }

    val upstreamHttpFactory: OkHttpDataSource.Factory by lazy {
        OkHttpDataSource.Factory(HttpClients.okHttp)
            .setUserAgent(HTTP_USER_AGENT)
    }

    fun buildPlaybackDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        val streamCached = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamCached)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun syncCompletedIds(ids: Collection<String>) {
        completedDownloadIds.clear()
        completedDownloadIds.addAll(ids)
    }

    fun markCompleted(videoId: String) {
        completedDownloadIds.add(videoId)
    }

    fun unmarkCompleted(videoId: String) {
        completedDownloadIds.remove(videoId)
    }

    fun isDownloadCompleted(videoId: String): Boolean =
        videoId in completedDownloadIds &&
            runCatching { downloadCache.getCachedSpans(videoId).isNotEmpty() }.getOrDefault(false)

    fun isStreamCached(videoId: String): Boolean =
        runCatching { streamCache.getCachedSpans(videoId).isNotEmpty() }.getOrDefault(false)

    fun evictStreamTrack(videoId: String) {
        runCatching { streamCache.removeResource(videoId) }
    }

    fun streamCacheSizeBytes(): Long =
        runCatching { streamCache.cacheSpace }.getOrDefault(0L)

    fun clearStreamCache() {
        runCatching {
            streamCache.keys.toList().forEach { key ->
                streamCache.removeResource(key)
            }
        }
    }

    fun removeDownload(videoId: String) {
        completedDownloadIds.remove(videoId)
        runCatching { downloadCache.removeResource(videoId) }
    }

    companion object {
        private const val DEFAULT_STREAM_CACHE_BYTES = 512L * 1024L * 1024L
        private const val HTTP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
