package com.music.musica.data.lyrics

import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.network.HttpClients
import com.music.musica.data.innertube.MusicaInnertubeClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class BetterLyricsProvider(
    private val http: HttpClient = HttpClients.ktor,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.BETTER_LYRICS

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val resp = http.get("https://lyrics-api.boidu.dev/getLyrics") {
            parameter("s", query.title)
            parameter("a", query.artist)
            if (query.durationMs > 0L) parameter("d", query.durationMs / 1000L)
            if (!query.album.isNullOrBlank()) parameter("al", query.album)
        }
        if (resp.status.value !in 200..299) return@runCatching null
        val obj = HttpClients.json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val ttml = (obj["ttml"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
        val lines = SyncedLyricsParser.parseTtml(ttml)
        if (lines.isEmpty()) return@runCatching null
        LyricsResult(
            source = sourceId,
            lines = lines,
            isSynced = true,
            isWordSynced = lines.any { it.isWordSynced },
        )
    }.getOrNull()
}

class LyricsPlusProvider(
    private val http: HttpClient = HttpClients.ktor,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.LYRICS_PLUS

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val resp = http.get("https://lyricsplus.atomix.one/v2/lyrics/get") {
            parameter("title", query.title)
            parameter("artist", query.artist)
            if (query.durationMs > 0L) parameter("duration", query.durationMs / 1000L)
            if (!query.album.isNullOrBlank()) parameter("album", query.album)
        }
        if (resp.status.value !in 200..299) return@runCatching null
        val root = HttpClients.json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val arr = root["lyrics"] as? JsonArray ?: return@runCatching null
        val lines = arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val time = (obj["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val dur = (obj["duration"] as? JsonPrimitive)?.longOrNull ?: 3_000L
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val sylArr = obj["syllabus"] as? JsonArray
            val words = sylArr?.mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val st = (so["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val sd = (so["duration"] as? JsonPrimitive)?.longOrNull ?: 250L
                val sw = (so["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (sw.isEmpty()) null else LyricWord(st, st + sd.coerceAtLeast(1L), sw)
            }.orEmpty()
            val finalText = text.ifBlank { words.joinToString(" ") { it.text } }
            if (finalText.isEmpty()) null
            else LyricLine(startMs = time, endMs = time + dur.coerceAtLeast(1L), text = finalText, words = words)
        }.sortedBy { it.startMs }
        if (lines.isEmpty()) return@runCatching null
        LyricsResult(
            source = sourceId,
            lines = lines,
            isSynced = true,
            isWordSynced = lines.any { it.isWordSynced },
        )
    }.getOrNull()
}

class LrclibProvider(
    private val http: HttpClient = HttpClients.ktor,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.LRCLIB

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val resp = http.get("https://lrclib.net/api/get") {
            parameter("track_name", query.title)
            parameter("artist_name", query.artist)
            if (!query.album.isNullOrBlank()) parameter("album_name", query.album)
            if (query.durationMs > 0L) parameter("duration", query.durationMs / 1000L)
        }
        if (resp.status.value !in 200..299) return@runCatching null
        val obj = HttpClients.json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val synced = (obj["syncedLyrics"] as? JsonPrimitive)?.contentOrNull
        if (!synced.isNullOrBlank()) {
            val lines = SyncedLyricsParser.parseLrc(synced)
            if (lines.isNotEmpty()) {
                return@runCatching LyricsResult(
                    source = sourceId,
                    lines = lines,
                    isSynced = true,
                    isWordSynced = lines.any { it.isWordSynced },
                )
            }
        }
        val plain = (obj["plainLyrics"] as? JsonPrimitive)?.contentOrNull
        if (!plain.isNullOrBlank()) {
            val lines = SyncedLyricsParser.parsePlainText(plain)
            if (lines.isNotEmpty()) {
                return@runCatching LyricsResult(
                    source = sourceId,
                    lines = lines,
                    isSynced = false,
                    isWordSynced = false,
                )
            }
        }
        null
    }.getOrNull()
}

class YouTubeLyricsProvider(
    private val client: MusicaInnertubeClient,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.YOUTUBE

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val payload = client.fetchYouTubeLyrics(query.videoId) ?: return@runCatching null
        if (payload.timedLines.isNotEmpty()) {
            val sortedTimed = payload.timedLines.sortedBy { it.first }
            val lines = sortedTimed.mapIndexed { idx, (start, text) ->
                val end = sortedTimed.getOrNull(idx + 1)?.first ?: (start + 4_000L)
                LyricLine(startMs = start, endMs = end.coerceAtLeast(start), text = text)
            }
            return@runCatching LyricsResult(
                source = sourceId,
                lines = lines,
                isSynced = true,
                isWordSynced = false,
            )
        }
        val plain = payload.plainText ?: return@runCatching null
        val lines = SyncedLyricsParser.parsePlainText(plain)
        if (lines.isEmpty()) null
        else LyricsResult(source = sourceId, lines = lines, isSynced = false, isWordSynced = false)
    }.getOrNull()
}
