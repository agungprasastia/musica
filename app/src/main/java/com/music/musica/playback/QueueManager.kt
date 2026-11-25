package com.music.musica.playback

import com.music.musica.core.model.RepeatMode
import com.music.musica.core.model.Song
import kotlin.random.Random

/**
 * Pure, deterministic queue manager supporting Play Next, Add to Queue, drag reorder,
 * remove, shuffle (preserving the current track at index 0 and restoring original order on unshuffle),
 * and Repeat OFF / ALL / ONE.
 */
class QueueManager(
    private val random: Random = Random.Default,
) {
    private val _queue = mutableListOf<Song>()
    private var unshuffledOrder: List<Song> = emptyList()

    val queue: List<Song> get() = _queue.toList()

    var currentIndex: Int = -1
        private set

    val currentSong: Song?
        get() = _queue.getOrNull(currentIndex)

    var shuffleEnabled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    fun setQueue(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean? = null) {
        _queue.clear()
        _queue.addAll(songs)
        unshuffledOrder = songs.toList()
        if (shuffled != null) {
            shuffleEnabled = shuffled
        }
        currentIndex = when {
            songs.isEmpty() -> -1
            shuffled == true && songs.size > 1 -> random.nextInt(songs.size)
            else -> startIndex.coerceIn(0, songs.lastIndex)
        }
        if (shuffleEnabled && songs.size > 1) {
            applyShuffleKeepingCurrentFirst()
        }
    }

    fun playNext(song: Song) {
        if (_queue.isEmpty()) {
            setQueue(listOf(song), 0)
            return
        }
        val insertAt = (currentIndex + 1).coerceIn(0, _queue.size)
        _queue.add(insertAt, song)
        unshuffledOrder = unshuffledOrder + song
    }

    fun addToQueue(song: Song) {
        if (_queue.isEmpty()) {
            setQueue(listOf(song), 0)
            return
        }
        _queue.add(song)
        unshuffledOrder = unshuffledOrder + song
    }

    fun appendSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val existingIds = _queue.map { it.videoId }.toSet()
        val fresh = songs.filter { it.videoId !in existingIds }
        if (fresh.isEmpty()) return
        if (_queue.isEmpty()) {
            setQueue(fresh, 0)
        } else {
            _queue.addAll(fresh)
            unshuffledOrder = unshuffledOrder + fresh
        }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _queue.indices || toIndex !in _queue.indices || fromIndex == toIndex) return
        val item = _queue.removeAt(fromIndex)
        _queue.add(toIndex, item)
        currentIndex = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        if (!shuffleEnabled) unshuffledOrder = _queue.toList()
    }

    fun removeAt(index: Int) {
        if (index !in _queue.indices) return
        val removed = _queue.removeAt(index)
        unshuffledOrder = unshuffledOrder.filterNot { it.videoId == removed.videoId }
        currentIndex = when {
            _queue.isEmpty() -> -1
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> currentIndex.coerceAtMost(_queue.lastIndex)
            else -> currentIndex
        }
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        if (_queue.size <= 1) return
        if (shuffleEnabled) {
            unshuffledOrder = _queue.toList()
            applyShuffleKeepingCurrentFirst()
        } else {
            val active = currentSong
            _queue.clear()
            _queue.addAll(unshuffledOrder)
            currentIndex = active?.let { s ->
                _queue.indexOfFirst { it.videoId == s.videoId }.takeIf { it >= 0 }
            } ?: 0
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun cycleRepeatMode(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    fun nextIndex(manualSkip: Boolean = false): Int? {
        if (_queue.isEmpty() || currentIndex !in _queue.indices) return null
        if (repeatMode == RepeatMode.ONE && !manualSkip) return currentIndex
        return when {
            currentIndex + 1 < _queue.size -> currentIndex + 1
            repeatMode == RepeatMode.ALL -> 0
            else -> null
        }
    }

    fun previousIndex(): Int? {
        if (_queue.isEmpty() || currentIndex !in _queue.indices) return null
        return when {
            currentIndex - 1 >= 0 -> currentIndex - 1
            repeatMode == RepeatMode.ALL -> _queue.lastIndex
            else -> 0
        }
    }

    fun selectIndex(index: Int): Song? {
        if (index !in _queue.indices) return null
        currentIndex = index
        return _queue[index]
    }

    private fun applyShuffleKeepingCurrentFirst() {
        val current = currentSong ?: return
        val rest = _queue.toMutableList().apply { removeAt(currentIndex) }
        rest.shuffle(random)
        _queue.clear()
        _queue.add(current)
        _queue.addAll(rest)
        currentIndex = 0
    }
}
