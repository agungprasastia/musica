package com.music.musica.data.download

import com.music.musica.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRepositoryTest {

    @Test
    fun lifecycleTransitionsQueuedDownloadingCompletedAndRemove() {
        val coord = DownloadQueueCoordinator()
        coord.enqueue("v1")
        assertEquals(DownloadState.QUEUED, coord.records["v1"]?.state)
        assertFalse(coord.canPlayOffline("v1"))

        coord.onProgress("v1", 55)
        assertEquals(DownloadState.DOWNLOADING, coord.records["v1"]?.state)
        assertEquals(55, coord.records["v1"]?.progressPercent)

        coord.onCompleted("v1")
        assertEquals(DownloadState.COMPLETED, coord.records["v1"]?.state)
        assertEquals(100, coord.records["v1"]?.progressPercent)
        assertTrue(coord.canPlayOffline("v1"))

        coord.remove("v1")
        assertFalse(coord.canPlayOffline("v1"))
        assertFalse(coord.records.containsKey("v1"))
    }

    @Test
    fun failedDownloadIsNotMarkedOfflinePlayable() {
        val coord = DownloadQueueCoordinator()
        coord.enqueue("v2")
        coord.onFailed("v2")
        assertEquals(DownloadState.FAILED, coord.records["v2"]?.state)
        assertFalse(coord.canPlayOffline("v2"))
    }
}
