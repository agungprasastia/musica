package com.music.musica.data.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import com.music.musica.core.model.DownloadState
import com.music.musica.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class DownloadRepositoryTest {

    private fun song(id: String) = Song(videoId = id, title = "Song $id", thumbnailUrl = "https://img/$id.jpg")

    @Test
    fun lifecycleTransitionsQueuedDownloadingCompletedAndRemove() {
        val coord = DownloadQueueCoordinator()
        coord.enqueue("v1", collectionId = "MPREb_1", collectionTitle = "Midnight Album")
        assertEquals(DownloadState.QUEUED, coord.records["v1"]?.state)
        assertEquals("MPREb_1", coord.records["v1"]?.collectionId)
        assertEquals("Midnight Album", coord.records["v1"]?.collectionTitle)
        assertFalse(coord.canPlayOffline("v1"))

        coord.onProgress("v1", 55)
        assertEquals(DownloadState.DOWNLOADING, coord.records["v1"]?.state)
        assertEquals(55, coord.records["v1"]?.progressPercent)
        assertEquals("MPREb_1", coord.records["v1"]?.collectionId)

        coord.onCompleted("v1")
        assertEquals(DownloadState.COMPLETED, coord.records["v1"]?.state)
        assertEquals(100, coord.records["v1"]?.progressPercent)
        assertTrue(coord.canPlayOffline("v1"))

        coord.remove("v1")
        assertFalse(coord.canPlayOffline("v1"))
        assertFalse(coord.records.containsKey("v1"))
    }

    @Test
    fun failedDownloadIsNotMarkedOfflinePlayableAndRetryPreservesCollectionMetadata() {
        val coord = DownloadQueueCoordinator()
        coord.enqueue("v2", collectionId = "VL_PL1", collectionTitle = "Road Trip")
        coord.onFailed("v2")
        assertEquals(DownloadState.FAILED, coord.records["v2"]?.state)
        assertFalse(coord.canPlayOffline("v2"))

        coord.retry("v2")
        assertEquals(DownloadState.QUEUED, coord.records["v2"]?.state)
        assertEquals(0, coord.records["v2"]?.progressPercent)
        assertEquals("VL_PL1", coord.records["v2"]?.collectionId)
        assertEquals("Road Trip", coord.records["v2"]?.collectionTitle)
    }

    @Test
    fun wifiOnlyRequirementMapsToUnmeteredOrAnyNetwork() {
        val wifiOnly = MusicaDownloadService.requirementsForWifiOnly(true)
        assertTrue(wifiOnly.isUnmeteredNetworkRequired)

        val anyNetwork = MusicaDownloadService.requirementsForWifiOnly(false)
        assertTrue(anyNetwork.isNetworkRequired)
        assertFalse(anyNetwork.isUnmeteredNetworkRequired)
    }

    @Test
    fun media3DownloadStatesMapCleanlyToDomainStates() {
        assertEquals(DownloadState.QUEUED, DownloadRepository.mapMedia3State(Download.STATE_QUEUED))
        assertEquals(DownloadState.DOWNLOADING, DownloadRepository.mapMedia3State(Download.STATE_DOWNLOADING))
        assertEquals(DownloadState.DOWNLOADING, DownloadRepository.mapMedia3State(Download.STATE_RESTARTING))
        assertEquals(DownloadState.COMPLETED, DownloadRepository.mapMedia3State(Download.STATE_COMPLETED))
        assertEquals(DownloadState.FAILED, DownloadRepository.mapMedia3State(Download.STATE_FAILED))
        assertEquals(DownloadState.FAILED, DownloadRepository.mapMedia3State(Download.STATE_STOPPED))
        assertEquals(DownloadState.NOT_DOWNLOADED, DownloadRepository.mapMedia3State(Download.STATE_REMOVING))
    }

    @Test
    fun groupByCollectionSeparatesNamedCollectionsAndSingles() {
        val items = listOf(
            DownloadedTrackItem(song("a1"), DownloadState.COMPLETED, 100, 3_000_000L, "ALB1", "Album One"),
            DownloadedTrackItem(song("s1"), DownloadState.COMPLETED, 100, 2_000_000L, null, null),
            DownloadedTrackItem(song("a2"), DownloadState.FAILED, 0, 0L, "ALB1", "Album One"),
            DownloadedTrackItem(song("p1"), DownloadState.DOWNLOADING, 40, 1_500_000L, "PL1", "Workout"),
        )

        val groups = DownloadQueueCoordinator.groupByCollection(items, singlesTitle = "Individual Tracks")
        assertEquals(3, groups.size)

        assertEquals("ALB1", groups[0].collectionId)
        assertEquals("Album One", groups[0].title)
        assertEquals(2, groups[0].items.size)
        assertEquals(1, groups[0].completedSongs.size)
        assertEquals(1, groups[0].failedCount)
        assertEquals(3_000_000L, groups[0].totalSizeBytes)

        assertEquals("PL1", groups[1].collectionId)
        assertEquals(1, groups[1].activeCount)

        assertNull(groups[2].collectionId)
        assertEquals("Individual Tracks", groups[2].title)
        assertEquals(1, groups[2].completedSongs.size)
    }
}
