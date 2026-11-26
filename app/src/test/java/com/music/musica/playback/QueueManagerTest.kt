package com.music.musica.playback

import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueManagerTest {

    private fun song(id: String) = Song(videoId = id, title = "Song $id")

    @Test
    fun setQueue_playNext_addToQueue_maintainOrderAndCurrentIndex() {
        val qm = QueueManager()
        qm.setQueue(listOf(song("1"), song("2"), song("3")), startIndex = 1)
        assertEquals("2", qm.currentSong?.videoId)

        qm.playNext(song("99"))
        assertEquals(listOf("1", "2", "99", "3"), qm.queue.map { it.videoId })
        assertEquals(1, qm.currentIndex)

        qm.addToQueue(song("100"))
        assertEquals(listOf("1", "2", "99", "3", "100"), qm.queue.map { it.videoId })
    }

    @Test
    fun moveAndRemove_adjustCurrentIndexCorrectly() {
        val qm = QueueManager()
        qm.setQueue(listOf(song("A"), song("B"), song("C"), song("D")), startIndex = 2)
        assertEquals("C", qm.currentSong?.videoId)

        qm.removeAt(0) // remove A before current
        assertEquals(1, qm.currentIndex)
        assertEquals("C", qm.currentSong?.videoId)

        qm.move(fromIndex = 2, toIndex = 0) // move D before current
        assertEquals(listOf("D", "B", "C"), qm.queue.map { it.videoId })
        assertEquals(2, qm.currentIndex)
        assertEquals("C", qm.currentSong?.videoId)
    }

    @Test
    fun shuffle_keepsCurrentSongAtIndexZeroAndRestoresOrderWhenDisabled() {
        val qm = QueueManager()
        val original = (1..10).map { song("$it") }
        qm.setQueue(original, startIndex = 4) // song 5
        assertEquals("5", qm.currentSong?.videoId)

        qm.toggleShuffle()
        assertTrue(qm.shuffleEnabled)
        assertEquals(0, qm.currentIndex)
        assertEquals("5", qm.currentSong?.videoId)
        assertEquals(10, qm.queue.size)

        qm.toggleShuffle()
        assertFalse(qm.shuffleEnabled)
        assertEquals(original.map { it.videoId }, qm.queue.map { it.videoId })
        assertEquals(4, qm.currentIndex)
    }

    @Test
    fun repeatModes_computeNextAndPreviousIndicesProperly() {
        val qm = QueueManager()
        qm.setQueue(listOf(song("1"), song("2")), startIndex = 1)

        assertNull(qm.nextIndex())
        assertEquals(0, qm.previousIndex())

        assertEquals(RepeatMode.ALL, qm.cycleRepeatMode())
        assertEquals(0, qm.nextIndex())

        assertEquals(RepeatMode.ONE, qm.cycleRepeatMode())
        assertEquals(1, qm.nextIndex())

        assertEquals(RepeatMode.OFF, qm.cycleRepeatMode())
    }

    @Test
    fun setQueue_atomicShuffledPicksShuffledModeAndExplicitFalseDisablesShuffle() {
        val qm = QueueManager(kotlin.random.Random(42))
        val tracks = (1..8).map { song("t$it") }

        qm.setQueue(tracks, startIndex = 0, shuffled = true)
        assertTrue(qm.shuffleEnabled)
        assertEquals(0, qm.currentIndex)
        assertEquals(8, qm.queue.size)

        qm.setQueue(tracks, startIndex = 2, shuffled = false)
        assertFalse(qm.shuffleEnabled)
        assertEquals(2, qm.currentIndex)
        assertEquals("t3", qm.currentSong?.videoId)
    }

    @Test
    fun playNextAllAndAddAllToQueue_preserveExactOrderOnEmptyAndActiveQueues() {
        val emptyNext = QueueManager()
        emptyNext.playNextAll(listOf(song("A"), song("B"), song("C")))
        assertEquals(listOf("A", "B", "C"), emptyNext.queue.map { it.videoId })
        assertEquals(0, emptyNext.currentIndex)

        val active = QueueManager()
        active.setQueue(listOf(song("1"), song("2")), startIndex = 0)
        active.playNextAll(listOf(song("A"), song("B")))
        assertEquals(listOf("1", "A", "B", "2"), active.queue.map { it.videoId })
        assertEquals(0, active.currentIndex)

        active.addAllToQueue(listOf(song("X"), song("Y")))
        assertEquals(listOf("1", "A", "B", "2", "X", "Y"), active.queue.map { it.videoId })
        assertEquals(0, active.currentIndex)
    }
}
