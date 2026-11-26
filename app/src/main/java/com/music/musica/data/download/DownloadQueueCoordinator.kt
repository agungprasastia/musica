package com.music.musica.data.download

import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import java.util.concurrent.ConcurrentHashMap

data class DownloadRecord(
    val videoId: String,
    val state: DownloadState,
    val progressPercent: Int = 0,
    val collectionId: String? = null,
    val collectionTitle: String? = null,
)

data class DownloadedCollectionGroup(
    val collectionId: String?,
    val title: String,
    val artworkUrl: String?,
    val items: List<DownloadedTrackItem>,
) {
    val completedSongs: List<Song>
        get() = items.filter { it.state == DownloadState.COMPLETED }.map { it.song }

    val totalSizeBytes: Long
        get() = items.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }

    val activeCount: Int
        get() = items.count { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }

    val failedCount: Int
        get() = items.count { it.state == DownloadState.FAILED }
}

class DownloadQueueCoordinator {
    private val _records = ConcurrentHashMap<String, DownloadRecord>()
    val records: Map<String, DownloadRecord> get() = _records.toMap()

    fun enqueue(
        videoId: String,
        collectionId: String? = null,
        collectionTitle: String? = null,
    ) {
        val existing = _records[videoId]
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.QUEUED,
            progressPercent = 0,
            collectionId = collectionId ?: existing?.collectionId,
            collectionTitle = collectionTitle ?: existing?.collectionTitle,
        )
    }

    fun retry(videoId: String) {
        val existing = _records[videoId]
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.QUEUED,
            progressPercent = 0,
            collectionId = existing?.collectionId,
            collectionTitle = existing?.collectionTitle,
        )
    }

    fun onProgress(videoId: String, percent: Int) {
        val existing = _records[videoId]
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.DOWNLOADING,
            progressPercent = percent.coerceIn(0, 100),
            collectionId = existing?.collectionId,
            collectionTitle = existing?.collectionTitle,
        )
    }

    fun onCompleted(videoId: String) {
        val existing = _records[videoId]
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.COMPLETED,
            progressPercent = 100,
            collectionId = existing?.collectionId,
            collectionTitle = existing?.collectionTitle,
        )
    }

    fun onFailed(videoId: String) {
        val existing = _records[videoId]
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.FAILED,
            progressPercent = 0,
            collectionId = existing?.collectionId,
            collectionTitle = existing?.collectionTitle,
        )
    }

    fun remove(videoId: String) {
        _records.remove(videoId)
    }

    fun canPlayOffline(videoId: String): Boolean =
        _records[videoId]?.state == DownloadState.COMPLETED

    companion object {
        fun groupByCollection(
            items: List<DownloadedTrackItem>,
            singlesTitle: String,
        ): List<DownloadedCollectionGroup> {
            if (items.isEmpty()) return emptyList()
            val grouped = linkedMapOf<String?, MutableList<DownloadedTrackItem>>()
            items.forEach { item ->
                val key = item.collectionId?.takeIf { it.isNotBlank() }
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }
            val named = grouped.entries
                .filter { it.key != null }
                .map { (id, list) ->
                    val title = list.firstNotNullOfOrNull { it.collectionTitle?.takeIf { t -> t.isNotBlank() } }
                        ?: singlesTitle
                    DownloadedCollectionGroup(
                        collectionId = id,
                        title = title,
                        artworkUrl = list.firstNotNullOfOrNull { it.song.thumbnailUrl },
                        items = list,
                    )
                }
            val singles = grouped[null]?.takeIf { it.isNotEmpty() }?.let { list ->
                DownloadedCollectionGroup(
                    collectionId = null,
                    title = singlesTitle,
                    artworkUrl = list.firstNotNullOfOrNull { it.song.thumbnailUrl },
                    items = list,
                )
            }
            return if (singles != null) named + singles else named
        }
    }
}
