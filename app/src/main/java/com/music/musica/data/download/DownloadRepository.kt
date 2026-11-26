package com.music.musica.data.download

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.music.musica.core.database.DownloadEntity
import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import com.music.musica.data.repository.LibraryRepository
import com.music.musica.data.stream.StreamResolver
import com.music.musica.playback.MusicaPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class DownloadedTrackItem(
    val song: Song,
    val state: DownloadState,
    val progressPercent: Int,
    val fileSizeBytes: Long,
    val collectionId: String? = null,
    val collectionTitle: String? = null,
)

/**
 * Coordinates durable foreground offline downloads through Media3's [MusicaDownloadService]
 * and [DownloadManager], mirroring live state transitions and byte progress into Room.
 */
@OptIn(UnstableApi::class)
class DownloadRepository(
    private val appContext: Context,
    private val db: MusicaDatabase,
    private val libraryRepository: LibraryRepository,
    private val streamResolver: StreamResolver,
    private val cacheProvider: AudioCacheProvider,
    private val downloadManagerProvider: () -> DownloadManager,
    private val downloadQualityKbpsProvider: () -> Int = { 256 },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator = DownloadQueueCoordinator()
    private val collectionMeta = ConcurrentHashMap<String, Pair<String?, String?>>()

    @Volatile
    private var managerAttached = false
    private var progressTickerJob: Job? = null

    val downloads: Flow<List<DownloadedTrackItem>> = db.downloadDao().observeDownloads().map { rows ->
        rows.mapNotNull { row ->
            val song = row.song?.toSong() ?: return@mapNotNull null
            if (!row.download.collectionId.isNullOrBlank()) {
                collectionMeta[row.download.videoId] = row.download.collectionId to row.download.collectionTitle
            }
            DownloadedTrackItem(
                song = song,
                state = row.download.downloadState,
                progressPercent = row.download.progressPercent,
                fileSizeBytes = row.download.fileSizeBytes,
                collectionId = row.download.collectionId,
                collectionTitle = row.download.collectionTitle,
            )
        }
    }

    val downloadedIds: Flow<Set<String>> = db.downloadDao().observeCompletedIds().map { ids ->
        cacheProvider.syncCompletedIds(ids)
        ids.toSet()
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (finalException != null) {
                Log.w(TAG, "Download error for ${download.request.id}: ${finalException.message}")
            }
            mirrorDownloadState(download)
            ensureProgressTicker(downloadManager)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            val videoId = download.request.id
            coordinator.remove(videoId)
            collectionMeta.remove(videoId)
            cacheProvider.removeDownload(videoId)
            scope.launch {
                runCatching { db.downloadDao().delete(videoId) }
            }
            ensureProgressTicker(downloadManager)
        }
    }

    init {
        scope.launch {
            runCatching {
                cacheProvider.syncCompletedIds(db.downloadDao().getCompletedIds())
            }
            mainScope.launch {
                attachDownloadManagerIfNeeded()
            }
        }
    }

    fun applyWifiOnlyRequirement(wifiOnly: Boolean) {
        mainScope.launch {
            val manager = attachDownloadManagerIfNeeded() ?: return@launch
            manager.requirements = MusicaDownloadService.requirementsForWifiOnly(wifiOnly)
        }
    }

    fun downloadSong(
        song: Song,
        collectionId: String? = null,
        collectionTitle: String? = null,
    ) {
        if (!collectionId.isNullOrBlank()) {
            collectionMeta[song.videoId] = collectionId to collectionTitle
        }
        scope.launch {
            runCatching {
                libraryRepository.upsertSongMetadata(song)
                val existing = db.downloadDao().get(song.videoId)
                val resolvedColId = collectionId ?: existing?.collectionId ?: collectionMeta[song.videoId]?.first
                val resolvedColTitle = collectionTitle ?: existing?.collectionTitle ?: collectionMeta[song.videoId]?.second
                if (!resolvedColId.isNullOrBlank()) {
                    collectionMeta[song.videoId] = resolvedColId to resolvedColTitle
                }

                coordinator.enqueue(song.videoId, resolvedColId, resolvedColTitle)
                db.downloadDao().upsert(
                    DownloadEntity(
                        videoId = song.videoId,
                        status = DownloadState.QUEUED.name,
                        progressPercent = 0,
                        qualityKbps = downloadQualityKbpsProvider().coerceAtMost(320),
                        collectionId = resolvedColId,
                        collectionTitle = resolvedColTitle,
                    ),
                )

                val request = DownloadRequest.Builder(
                    song.videoId,
                    "${MusicaPlaybackService.MUSICA_SCHEME}://track/${song.videoId}".toUri(),
                )
                    .setCustomCacheKey(song.videoId)
                    .build()

                mainScope.launch {
                    val manager = attachDownloadManagerIfNeeded()
                    runCatching {
                        DownloadService.sendAddDownload(
                            appContext,
                            MusicaDownloadService::class.java,
                            request,
                            /* foreground = */ false,
                        )
                    }.onFailure { err ->
                        Log.w(TAG, "Service add fallback for ${song.videoId}: ${err.message}")
                        manager?.addDownload(request)
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to queue download ${song.videoId}: ${err.message}")
            }
        }
    }

    fun downloadSongs(
        songs: List<Song>,
        collectionId: String? = null,
        collectionTitle: String? = null,
    ) {
        songs.forEach { downloadSong(it, collectionId, collectionTitle) }
    }

    fun retryDownload(song: Song) {
        scope.launch {
            val existing = runCatching { db.downloadDao().get(song.videoId) }.getOrNull()
            val colId = existing?.collectionId ?: collectionMeta[song.videoId]?.first
            val colTitle = existing?.collectionTitle ?: collectionMeta[song.videoId]?.second
            streamResolver.invalidate(song.videoId)
            cacheProvider.removeDownload(song.videoId)
            coordinator.retry(song.videoId)

            mainScope.launch {
                val manager = attachDownloadManagerIfNeeded()
                runCatching { manager?.removeDownload(song.videoId) }
                downloadSong(song, colId, colTitle)
            }
        }
    }

    fun cancelDownload(videoId: String) = removeDownload(videoId)

    fun removeDownload(videoId: String) {
        coordinator.remove(videoId)
        collectionMeta.remove(videoId)
        mainScope.launch {
            val manager = attachDownloadManagerIfNeeded()
            runCatching {
                DownloadService.sendRemoveDownload(
                    appContext,
                    MusicaDownloadService::class.java,
                    videoId,
                    /* foreground = */ false,
                )
            }.onFailure {
                manager?.removeDownload(videoId)
            }
        }
        scope.launch {
            cacheProvider.removeDownload(videoId)
            runCatching { db.downloadDao().delete(videoId) }
        }
    }

    private fun attachDownloadManagerIfNeeded(): DownloadManager? {
        val manager = runCatching { downloadManagerProvider() }.getOrNull() ?: return null
        if (!managerAttached) {
            managerAttached = true
            manager.addListener(downloadListener)
            reconcileFromManager(manager)
            ensureProgressTicker(manager)
        }
        return manager
    }

    private fun reconcileFromManager(manager: DownloadManager) {
        val activeIds = HashSet<String>()
        manager.currentDownloads.forEach { dl ->
            activeIds.add(dl.request.id)
            mirrorDownloadState(dl)
        }
        scope.launch {
            runCatching {
                val incomplete = db.downloadDao().getIncompleteIds()
                val orphaned = incomplete.filter { it !in activeIds }
                if (orphaned.isNotEmpty()) {
                    orphaned.forEach { id ->
                        cacheProvider.removeDownload(id)
                        val existing = db.downloadDao().get(id)
                        if (existing != null) {
                            db.downloadDao().upsert(
                                existing.copy(
                                    status = DownloadState.FAILED.name,
                                    progressPercent = 0,
                                ),
                            )
                        }
                    }
                }
                cacheProvider.syncCompletedIds(db.downloadDao().getCompletedIds())
            }
        }
    }

    private fun ensureProgressTicker(manager: DownloadManager) {
        val hasDownloading = manager.currentDownloads.any {
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
        }
        if (!hasDownloading) {
            progressTickerJob?.cancel()
            progressTickerJob = null
            return
        }
        if (progressTickerJob?.isActive == true) return
        progressTickerJob = mainScope.launch {
            while (isActive) {
                val active = manager.currentDownloads.filter {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
                }
                if (active.isEmpty()) break
                active.forEach { dl -> mirrorDownloadState(dl) }
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
            progressTickerJob = null
        }
    }

    private fun mirrorDownloadState(download: Download) {
        val videoId = download.request.id
        val state = mapMedia3State(download.state)
        val pct = when (state) {
            DownloadState.COMPLETED -> 100
            DownloadState.DOWNLOADING -> {
                val raw = download.percentDownloaded
                if (raw >= 0f) raw.roundToInt().coerceIn(0, 99) else 0
            }
            else -> 0
        }
        val sizeBytes = when {
            download.contentLength > 0L -> download.contentLength
            download.bytesDownloaded > 0L -> download.bytesDownloaded
            else -> 0L
        }

        when (state) {
            DownloadState.QUEUED -> coordinator.enqueue(videoId)
            DownloadState.DOWNLOADING -> coordinator.onProgress(videoId, pct)
            DownloadState.COMPLETED -> {
                coordinator.onCompleted(videoId)
                cacheProvider.markCompleted(videoId)
            }
            DownloadState.FAILED -> {
                coordinator.onFailed(videoId)
                cacheProvider.unmarkCompleted(videoId)
            }
            DownloadState.NOT_DOWNLOADED -> {
                coordinator.remove(videoId)
                cacheProvider.removeDownload(videoId)
                return
            }
        }

        scope.launch {
            runCatching {
                val existing = db.downloadDao().get(videoId)
                val meta = collectionMeta[videoId]
                db.downloadDao().upsert(
                    DownloadEntity(
                        videoId = videoId,
                        status = state.name,
                        progressPercent = pct,
                        fileSizeBytes = if (sizeBytes > 0L) sizeBytes else existing?.fileSizeBytes ?: 0L,
                        qualityKbps = existing?.qualityKbps ?: downloadQualityKbpsProvider().coerceAtMost(320),
                        updatedAtMs = if (state == DownloadState.COMPLETED) {
                            System.currentTimeMillis()
                        } else {
                            existing?.updatedAtMs ?: System.currentTimeMillis()
                        },
                        collectionId = existing?.collectionId ?: meta?.first,
                        collectionTitle = existing?.collectionTitle ?: meta?.second,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "MusicaDownloads"
        private const val PROGRESS_POLL_INTERVAL_MS = 700L

        internal fun mapMedia3State(media3State: Int): DownloadState = when (media3State) {
            Download.STATE_QUEUED -> DownloadState.QUEUED
            Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> DownloadState.DOWNLOADING
            Download.STATE_COMPLETED -> DownloadState.COMPLETED
            Download.STATE_FAILED, Download.STATE_STOPPED -> DownloadState.FAILED
            else -> DownloadState.NOT_DOWNLOADED
        }
    }
}
