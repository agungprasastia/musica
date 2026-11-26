package com.music.musica.playback

import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LastPlayedStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun song(index: Int) = Song(
        videoId = "vid_$index",
        title = "Track $index",
        artists = listOf(ArtistRef("Artist $index", "UC_$index")),
        durationMs = 180_000L + index * 1_000L,
    )

    @Test
    fun saveAndLoadRoundTripPreservesQueueIndexPositionAndModes() {
        val file = File(tempFolder.root, "last_played.json")
        val store = LastPlayedStore(file)

        val expected = LastPlayedSnapshot(
            queue = listOf(song(0), song(1), song(2)),
            currentIndex = 1,
            positionMs = 47_500L,
            shuffleEnabled = true,
            repeatMode = RepeatMode.ALL,
            savedAtMs = 1_700_000_000_000L,
        )
        store.save(expected)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(expected.queue, loaded?.queue)
        assertEquals(1, loaded?.currentIndex)
        assertEquals(47_500L, loaded?.positionMs)
        assertEquals(true, loaded?.shuffleEnabled)
        assertEquals(RepeatMode.ALL, loaded?.repeatMode)
    }

    @Test
    fun largeQueueIsCappedTo100TracksKeepingActiveTrackExact() {
        val file = File(tempFolder.root, "last_played_large.json")
        val store = LastPlayedStore(file)

        val largeQueue = (0 until 250).map(::song)
        store.save(
            LastPlayedSnapshot(
                queue = largeQueue,
                currentIndex = 140,
                positionMs = 12_345L,
            ),
        )

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(LastPlayedStore.MAX_PERSISTED_TRACKS, loaded!!.queue.size)
        assertEquals("vid_140", loaded.currentSong?.videoId)
        assertEquals(12_345L, loaded.positionMs)
        // Keeps up to 25 previous tracks before currentIndex
        assertEquals("vid_115", loaded.queue.first().videoId)
    }

    @Test
    fun corruptFileReturnsNullWithoutThrowing() {
        val file = File(tempFolder.root, "corrupt.json")
        file.writeText("{not valid json!!!")
        val store = LastPlayedStore(file)

        assertNull(store.load())
    }

    @Test
    fun emptyQueueClearsExistingSnapshot() {
        val file = File(tempFolder.root, "clearable.json")
        val store = LastPlayedStore(file)

        store.save(LastPlayedSnapshot(queue = listOf(song(0)), currentIndex = 0, positionMs = 5_000L))
        assertNotNull(store.load())

        store.save(LastPlayedSnapshot(queue = emptyList(), currentIndex = -1, positionMs = 0L))
        assertNull(store.load())
    }
}
