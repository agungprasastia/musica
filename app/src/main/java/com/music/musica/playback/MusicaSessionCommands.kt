package com.music.musica.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.music.musica.core.model.Song
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3 [SessionCommand] definitions and in-process queue payload handoff for
 * [PlayerController] <-> [MusicaPlaybackService] communication.
 *
 * Large song queues are handed off by UUID token instead of serializing thousands of tracks
 * over Binder, preventing `TransactionTooLargeException` while keeping command dispatch on
 * the official Media3 [androidx.media3.session.MediaController] session transport.
 */
internal object MusicaSessionCommands {
    const val CMD_PLAY_QUEUE = "com.music.musica.cmd.PLAY_QUEUE"
    const val CMD_SELECT_INDEX = "com.music.musica.cmd.SELECT_INDEX"
    const val CMD_PLAY_NEXT = "com.music.musica.cmd.PLAY_NEXT"
    const val CMD_ADD_TO_QUEUE = "com.music.musica.cmd.ADD_TO_QUEUE"
    const val CMD_MOVE_ITEM = "com.music.musica.cmd.MOVE_ITEM"
    const val CMD_REMOVE_ITEM = "com.music.musica.cmd.REMOVE_ITEM"
    const val CMD_CLEAR_UPCOMING = "com.music.musica.cmd.CLEAR_UPCOMING"
    const val CMD_TOGGLE_PLAY_PAUSE = "com.music.musica.cmd.TOGGLE_PLAY_PAUSE"
    const val CMD_SKIP_NEXT = "com.music.musica.cmd.SKIP_NEXT"
    const val CMD_SKIP_PREVIOUS = "com.music.musica.cmd.SKIP_PREVIOUS"
    const val CMD_SEEK_TO = "com.music.musica.cmd.SEEK_TO"
    const val CMD_TOGGLE_SHUFFLE = "com.music.musica.cmd.TOGGLE_SHUFFLE"
    const val CMD_CYCLE_REPEAT = "com.music.musica.cmd.CYCLE_REPEAT"
    const val CMD_SET_SPEED = "com.music.musica.cmd.SET_SPEED"
    const val CMD_SET_SKIP_SILENCE = "com.music.musica.cmd.SET_SKIP_SILENCE"
    const val CMD_PAUSE_SLEEP_TIMER = "com.music.musica.cmd.PAUSE_SLEEP_TIMER"
    const val CMD_CLEAR_ERROR = "com.music.musica.cmd.CLEAR_ERROR"
    const val CMD_UPDATE_SLEEP_UI = "com.music.musica.cmd.UPDATE_SLEEP_UI"

    const val ARG_HANDOFF_ID = "handoff_id"
    const val ARG_INDEX = "index"
    const val ARG_FROM_INDEX = "from_index"
    const val ARG_TO_INDEX = "to_index"
    const val ARG_POSITION_MS = "position_ms"
    const val ARG_SPEED = "speed"
    const val ARG_ENABLED = "enabled"
    const val ARG_REMAINING_MS = "remaining_ms"
    const val ARG_HAS_REMAINING = "has_remaining"
    const val ARG_AFTER_TRACK = "after_track"

    val ALL_COMMANDS: List<SessionCommand> = listOf(
        CMD_PLAY_QUEUE,
        CMD_SELECT_INDEX,
        CMD_PLAY_NEXT,
        CMD_ADD_TO_QUEUE,
        CMD_MOVE_ITEM,
        CMD_REMOVE_ITEM,
        CMD_CLEAR_UPCOMING,
        CMD_TOGGLE_PLAY_PAUSE,
        CMD_SKIP_NEXT,
        CMD_SKIP_PREVIOUS,
        CMD_SEEK_TO,
        CMD_TOGGLE_SHUFFLE,
        CMD_CYCLE_REPEAT,
        CMD_SET_SPEED,
        CMD_SET_SKIP_SILENCE,
        CMD_PAUSE_SLEEP_TIMER,
        CMD_CLEAR_ERROR,
        CMD_UPDATE_SLEEP_UI,
    ).map { SessionCommand(it, Bundle.EMPTY) }

    data class QueuePayload(
        val songs: List<Song>,
        val startIndex: Int = 0,
        val shuffled: Boolean? = null,
    )

    private val queuePayloads = ConcurrentHashMap<String, QueuePayload>()
    private val songPayloads = ConcurrentHashMap<String, Song>()

    fun putQueuePayload(songs: List<Song>, startIndex: Int, shuffled: Boolean?): String {
        val id = UUID.randomUUID().toString()
        queuePayloads[id] = QueuePayload(songs, startIndex, shuffled)
        if (queuePayloads.size > 16) {
            queuePayloads.keys.firstOrNull { it != id }?.let { queuePayloads.remove(it) }
        }
        return id
    }

    fun takeQueuePayload(id: String?): QueuePayload? = id?.let { queuePayloads.remove(it) }

    fun putSongPayload(song: Song): String {
        val id = UUID.randomUUID().toString()
        songPayloads[id] = song
        if (songPayloads.size > 32) {
            songPayloads.keys.firstOrNull { it != id }?.let { songPayloads.remove(it) }
        }
        return id
    }

    fun takeSongPayload(id: String?): Song? = id?.let { songPayloads.remove(it) }
}
