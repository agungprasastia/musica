package com.music.musica.data.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.music.musica.core.database.DownloadEntity
import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import com.music.musica.data.repository.LibraryRepository
import com.music.musica.data.stream.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadedTrackItem(
    val song: Song,
    val state: DownloadState,
    val progressPercent: Int,
    val fileSizeBytes: Long,
)

/**
 * Coordinates bounded-concurrency offline downloads into Media3's [AudioCacheProvider.downloadCache].
 * Supports cancellation on removal, avoids DB write races, and recovers incomplete rows on startup.
 */
@OptIn(UnstableApi::class)
class DownloadRepository(
    @Suppress("unused") private val appContext: Context,
    private val db: MusicaDatabase,
    private val libraryRepository: LibraryRepository,
    private val streamResolver: StreamResolver,
    private val cacheProvider: AudioCacheProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = DownloadQueueCoordinator()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeJobs = ConcurrentHashMap<String, Pair<Job, AtomicBoolean>>()

    val downloads: Flow<List<DownloadedTrackItem>> = db.downloadDao().observeDownloads().map { rows ->
        rows.mapNotNull { row ->
            val song = row.song?.toSong() ?: return@mapNotNull null
            DownloadedTrackItem(
                song = song,
                state = row.download.downloadState,
                progressPercent = row.download.progressPercent,
                fileSizeBytes = row.download.fileSizeBytes,
            )
        }
    }

    val downloadedIds: Flow<Set<String>> = db.downloadDao().observeCompletedIds().map { ids ->
        cacheProvider.syncCompletedIds(ids)
        ids.toSet()
    }

    init {
        scope.launch {
            runCatching {
                val incomplete = db.downloadDao().getIncompleteIds()
                incomplete.forEach { cacheProvider.removeDownload(it) }
                if (incomplete.isNotEmpty()) db.downloadDao().markIncompleteAsFailed()
                cacheProvider.syncCompletedIds(db.downloadDao().getCompletedIds())
            }
        }
    }

    fun downloadSong(song: Song) {
        if (activeJobs.containsKey(song.videoId)) return
        val cancelled = AtomicBoolean(false)

        val job = scope.launch {
            try {
                libraryRepository.upsertSongMetadata(song)
                coordinator.enqueue(song.videoId)
                db.downloadDao().upsert(
                    DownloadEntity(
                        videoId = song.videoId,
                        status = DownloadState.QUEUED.name,
                        progressPercent = 0,
                    ),
                )

                downloadSemaphore.withPermit {
                    if (cancelled.get()) return@withPermit

                    val resolved = streamResolver.resolve(song.videoId).getOrNull()
                    if (resolved == null || cancelled.get()) {
                        if (!cancelled.get()) markFailed(song.videoId)
                        return@withPermit
                    }

                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cacheProvider.downloadCache)
                        .setUpstreamDataSourceFactory(cacheProvider.upstreamHttpFactory)
                        .createDataSource()

                    val dataSpec = DataSpec.Builder()
                        .setUri(resolved.url.toUri())
                        .setKey(song.videoId)
                        .setHttpRequestHeaders(resolved.headers)
                        .build()

                    var lastReported = -1
                    var totalBytes = 0L
                    val writer = CacheWriter(
                        cacheDataSource,
                        dataSpec,
                        null,
                    ) { requestLength, bytesCached, _ ->
                        if (cancelled.get()) throw CancellationException("Download removed")
                        totalBytes = if (requestLength > 0L) requestLength else bytesCached
                        val pct = if (requestLength > 0L) {
                            ((bytesCached * 100L) / requestLength).toInt().coerceIn(0, 99)
                        } else 50
                        if (pct - lastReported >= 8 || lastReported < 0) {
                            lastReported = pct
                            coordinator.onProgress(song.videoId, pct)
                            // Synchronous write on the IO download thread avoids out-of-order DB races.
                            runBlocking {
                                if (!cancelled.get()) {
                                    db.downloadDao().upsert(
                                        DownloadEntity(
                                            videoId = song.videoId,
                                            status = DownloadState.DOWNLOADING.name,
                                            progressPercent = pct,
                                            fileSizeBytes = totalBytes,
                                            qualityKbps = resolved.bitrateKbps,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    writer.cache()

                    if (cancelled.get()) {
                        cacheProvider.removeDownload(song.videoId)
                        return@withPermit
                    }

                    coordinator.onCompleted(song.videoId)
                    cacheProvider.markCompleted(song.videoId)
                    db.downloadDao().upsert(
                        DownloadEntity(
                            videoId = song.videoId,
                            status = DownloadState.COMPLETED.name,
                            progressPercent = 100,
                            fileSizeBytes = totalBytes,
                            qualityKbps = resolved.bitrateKbps,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                cacheProvider.removeDownload(song.videoId)
                throw ce
            } catch (_: Throwable) {
                if (!cancelled.get()) markFailed(song.videoId)
            } finally {
                activeJobs.remove(song.videoId)
            }
        }

        activeJobs[song.videoId] = job to cancelled
    }

    fun downloadSongs(songs: List<Song>) {
        songs.forEach { downloadSong(it) }
    }

    fun removeDownload(videoId: String) {
        activeJobs.remove(videoId)?.let { (job, cancelled) ->
            cancelled.set(true)
            job.cancel()
        }
        scope.launch {
            coordinator.remove(videoId)
            cacheProvider.removeDownload(videoId)
            db.downloadDao().delete(videoId)
        }
    }

    private suspend fun markFailed(videoId: String) {
        coordinator.onFailed(videoId)
        cacheProvider.removeDownload(videoId)
        db.downloadDao().upsert(
            DownloadEntity(
                videoId = videoId,
                status = DownloadState.FAILED.name,
                progressPercent = 0,
            ),
        )
    }

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }
}
