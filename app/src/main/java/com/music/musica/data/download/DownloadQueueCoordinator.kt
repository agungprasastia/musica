package com.music.musica.data.download

import com.music.musica.core.model.DownloadState
import java.util.concurrent.ConcurrentHashMap

data class DownloadRecord(
    val videoId: String,
    val state: DownloadState,
    val progressPercent: Int = 0,
)

class DownloadQueueCoordinator {
    private val _records = ConcurrentHashMap<String, DownloadRecord>()
    val records: Map<String, DownloadRecord> get() = _records.toMap()

    fun enqueue(videoId: String) {
        _records[videoId] = DownloadRecord(videoId, DownloadState.QUEUED, 0)
    }

    fun onProgress(videoId: String, percent: Int) {
        _records[videoId] = DownloadRecord(
            videoId = videoId,
            state = DownloadState.DOWNLOADING,
            progressPercent = percent.coerceIn(0, 100),
        )
    }

    fun onCompleted(videoId: String) {
        _records[videoId] = DownloadRecord(videoId, DownloadState.COMPLETED, 100)
    }

    fun onFailed(videoId: String) {
        _records[videoId] = DownloadRecord(videoId, DownloadState.FAILED, 0)
    }

    fun remove(videoId: String) {
        _records.remove(videoId)
    }

    fun canPlayOffline(videoId: String): Boolean =
        _records[videoId]?.state == DownloadState.COMPLETED
}
