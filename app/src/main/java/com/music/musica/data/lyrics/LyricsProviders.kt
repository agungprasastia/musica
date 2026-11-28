package com.music.musica.data.lyrics

import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.network.HttpClients
import com.music.musica.data.innertube.MusicaInnertubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Per-lookup in-flight HTTP document deduplication so providers pointing to the same TTML URL
 * (e.g. `lrc.red` and `BiniLyrics`) share a single network GET.
 */
class SharedDocuments(
    private val fetcher: suspend (String) -> String?,
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

    suspend fun get(scope: CoroutineScope, url: String): String? =
        inFlight.computeIfAbsent(url) {
            scope.async(Dispatchers.IO) { runCatching { fetcher(url) }.getOrNull() }
        }.await()
}

internal object LyricsHttp {
    private const val MUSICA_UA = "Musica/1.0 (+https://github.com/agungprasastia/musica)"

    val standard: OkHttpClient = HttpClients.okHttp.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    val extended: OkHttpClient = HttpClients.okHttp.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun getString(
        url: String,
        client: OkHttpClient = standard,
        headers: Map<String, String> = emptyMap(),
    ): String? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MUSICA_UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        }.getOrNull()
    }

    fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private val ISRC_REGEX = Regex("""^[A-Z]{2}[A-Z0-9]{3}\d{7}$""")

// ---- 1. lrc.red (Direct ISRC + Strict Search TTML) --------------------------

class LrcRedProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.LRC_RED

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = fetchWithShared(query, shared = null)

    suspend fun fetchWithShared(query: LyricsQuery, shared: SharedDocuments?): LyricsResult? = coroutineScope {
        suspend fun loadDoc(url: String): String? =
            if (shared != null) shared.get(this, url) else httpFetch(url)

        val isrc = query.isrc?.uppercase(Locale.ROOT)?.takeIf { ISRC_REGEX.matches(it) }
        if (isrc != null) {
            val ttml = loadDoc("https://lrc.red/s/$isrc.ttml")
            if (!ttml.isNullOrBlank()) {
                val lines = SyncedLyricsParser.parseTtml(ttml)
                if (lines.isNotEmpty()) return@coroutineScope buildResult(sourceId, lines)
            }
        }

        val searchTerm = "${query.title} ${query.artist}".trim()
        if (searchTerm.isEmpty()) return@coroutineScope null
        val json = httpFetch("https://lrc.red/search.json?q=${LyricsHttp.enc(searchTerm)}")
            ?: return@coroutineScope null
        val rows = runCatching { HttpClients.json.parseToJsonElement(json).jsonArray }.getOrNull()
            ?: return@coroutineScope null

        val bestUrl = rows.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title") ?: obj.str("track_name") ?: return@mapNotNull null
            val artist = obj.str("artist") ?: obj.str("artist_name").orEmpty()
            val durMs = (obj.dbl("duration")?.times(1000.0))?.toLong() ?: 0L
            val url = obj.str("url") ?: obj.str("lyricsUrl") ?: obj.str("isrc")?.let { "https://lrc.red/s/$it.ttml" }
                ?: return@mapNotNull null
            if (!SyncedLyricsParser.matchesCatalogCandidate(query.title, query.artist, query.durationMs, title, artist, durMs)) {
                return@mapNotNull null
            }
            val delta = if (query.durationMs > 0L && durMs > 0L) abs(query.durationMs - durMs) else 0L
            delta to url
        }.minByOrNull { it.first }?.second ?: return@coroutineScope null

        val doc = loadDoc(bestUrl) ?: return@coroutineScope null
        val lines = SyncedLyricsParser.parseTtml(doc)
        if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

// ---- 2. BiniLyrics (ISRC Pre-Identify + TTML) -------------------------------

class BiniLyricsProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.BINI_LYRICS

    data class Hit(
        val isrc: String?,
        val lyricsUrl: String,
    )

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = fetchForHit(query, preIdentified = null, shared = null)

    suspend fun identify(query: LyricsQuery): Hit? {
        val isrc = query.isrc?.uppercase(Locale.ROOT)?.takeIf { ISRC_REGEX.matches(it) }
        val url = if (isrc != null) {
            "https://lyrics-api.binimum.org/?isrc=${LyricsHttp.enc(isrc)}"
        } else {
            buildString {
                append("https://lyrics-api.binimum.org/?track=").append(LyricsHttp.enc(query.title))
                append("&artist=").append(LyricsHttp.enc(query.artist))
                if (!query.album.isNullOrBlank()) append("&album=").append(LyricsHttp.enc(query.album))
                if (query.durationMs > 0L) append("&duration=").append(query.durationMs / 1000L)
            }
        }
        val body = httpFetch(url) ?: return null
        val results = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        }.getOrNull() ?: return null

        val best = results.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val lyricsUrl = obj.str("lyricsUrl")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val hitIsrc = obj.str("isrc")?.uppercase(Locale.ROOT)?.takeIf { ISRC_REGEX.matches(it) }
            if (isrc != null) {
                return@mapNotNull 0L to Hit(hitIsrc ?: isrc, lyricsUrl)
            }
            val title = obj.str("track_name") ?: return@mapNotNull null
            val artist = obj.str("artist_name").orEmpty()
            val durMs = (obj.dbl("duration")?.times(1000.0))?.toLong() ?: 0L
            if (!SyncedLyricsParser.matchesCatalogCandidate(query.title, query.artist, query.durationMs, title, artist, durMs)) {
                return@mapNotNull null
            }
            val delta = if (query.durationMs > 0L && durMs > 0L) abs(query.durationMs - durMs) else 0L
            delta to Hit(hitIsrc, lyricsUrl)
        }.minByOrNull { it.first }?.second
        return best
    }

    suspend fun fetchForHit(
        query: LyricsQuery,
        preIdentified: Hit?,
        shared: SharedDocuments?,
    ): LyricsResult? = coroutineScope {
        val hit = preIdentified ?: identify(query) ?: return@coroutineScope null
        val doc = if (shared != null) shared.get(this, hit.lyricsUrl) else httpFetch(hit.lyricsUrl)
        if (doc.isNullOrBlank()) return@coroutineScope null
        val lines = SyncedLyricsParser.parseProviderPayload(doc)
        if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

// ---- 3 & 4. BetterLyrics & BetterLyrics Portato (QQ Karaoke) ----------------

class BetterLyricsProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.BETTER_LYRICS

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val url = buildString {
            append("https://lyrics-api.boidu.dev/getLyrics?s=").append(LyricsHttp.enc(query.title))
            append("&a=").append(LyricsHttp.enc(query.artist))
            if (query.durationMs > 0L) append("&d=").append(query.durationMs / 1000L)
            if (!query.album.isNullOrBlank()) append("&al=").append(LyricsHttp.enc(query.album))
        }
        val body = httpFetch(url) ?: return null
        val lines = SyncedLyricsParser.parseProviderPayload(body)
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

class BetterLyricsPortatoProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.BETTER_LYRICS_PORTATO

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val url = buildString {
            append("https://lyrics-api.boidu.dev/qq/getLyrics?s=").append(LyricsHttp.enc(query.title))
            append("&a=").append(LyricsHttp.enc(query.artist))
            if (query.durationMs > 0L) append("&d=").append(query.durationMs / 1000L)
            if (!query.album.isNullOrBlank()) append("&al=").append(LyricsHttp.enc(query.album))
        }
        val body = httpFetch(url) ?: return null
        val lines = SyncedLyricsParser.parseProviderPayload(body)
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

// ---- 5. LyricsPlus (6-Mirror Parallel Race + Duet Agents) -------------------

class LyricsPlusProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.LYRICS_PLUS

    private val lastGoodMirror = AtomicReference<String?>(null)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = coroutineScope {
        val orderedMirrors = buildList {
            lastGoodMirror.get()?.let(::add)
            MIRRORS.forEach { if (it !in this) add(it) }
        }
        val path = buildString {
            append("/v2/lyrics/get?title=").append(LyricsHttp.enc(query.title))
            append("&artist=").append(LyricsHttp.enc(query.artist))
            if (query.durationMs > 0L) append("&duration=").append(query.durationMs / 1000L)
            if (!query.album.isNullOrBlank()) append("&album=").append(LyricsHttp.enc(query.album))
            if (!query.isrc.isNullOrBlank()) append("&isrc=").append(LyricsHttp.enc(query.isrc))
        }

        val tasks = orderedMirrors.map { host ->
            async(Dispatchers.IO) {
                val body = httpFetch("https://$host$path") ?: return@async null
                val parsed = parseLyricsPlusJson(body) ?: return@async null
                host to parsed
            }
        }
        val remaining = tasks.toMutableList()
        var winner: Pair<String, LyricsResult>? = null
        try {
            while (remaining.isNotEmpty() && winner == null) {
                val finished = select {
                    remaining.forEach { deferred ->
                        deferred.onAwait { res -> deferred to res }
                    }
                }
                remaining.remove(finished.first)
                if (finished.second != null) {
                    winner = finished.second
                }
            }
        } finally {
            tasks.forEach { it.cancel() }
        }
        winner?.let { (host, result) ->
            lastGoodMirror.set(host)
            result
        }
    }

    internal fun parseLyricsPlusJson(body: String): LyricsResult? {
        val root = runCatching { HttpClients.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val arr = root["lyrics"] as? JsonArray ?: return null

        val agentTypes = HashMap<String, String>()
        val metaAgents = (root["metadata"] as? JsonObject)?.get("agents") as? JsonObject
        metaAgents?.forEach { (id, elem) ->
            val type = (elem as? JsonObject)?.str("type")?.lowercase(Locale.ROOT) ?: "person"
            agentTypes[id] = type
        }

        val rawEntries = mutableListOf<Pair<String?, LyricLine>>()
        for (elem in arr) {
            val obj = elem as? JsonObject ?: continue
            val time = obj.lng("time") ?: continue
            val dur = obj.lng("duration") ?: 3_000L
            val text = obj.str("text")?.trim().orEmpty()
            val sylArr = obj["syllabus"] as? JsonArray
            val runs = sylArr?.mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val st = so.lng("time") ?: return@mapNotNull null
                val sd = so.lng("duration") ?: 250L
                val sw = so.str("text").orEmpty()
                if (sw.isEmpty()) null else SyncedLyricsParser.TimedRun(st, (st + sd).coerceAtLeast(st), sw)
            }.orEmpty()
            val words = SyncedLyricsParser.wordsFromRuns(runs, spacingIsExplicit = true)
            val finalText = if (words.isNotEmpty()) SyncedLyricsParser.buildWordText(words) else text
            if (finalText.isEmpty()) continue

            val elementField = obj["element"]
            val singerId = when (elementField) {
                is JsonObject -> elementField.str("singer")
                is JsonArray -> {
                    val tags = elementField.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT) }
                    if ("opposite" in tags || "right" in tags) "v2000" else "v1"
                }
                else -> null
            }

            val endMs = maxOf(time + dur.coerceAtLeast(1L), words.lastOrNull()?.endMs ?: time)
            rawEntries += singerId to LyricLine(
                startMs = time,
                endMs = endMs,
                text = finalText,
                words = words,
                sungUntilMs = endMs.takeIf { it > time },
            )
        }
        if (rawEntries.isEmpty()) return null
        val sorted = rawEntries.sortedBy { it.second.startMs }
        val alignments = SyncedLyricsParser.assignDuetAlignments(sorted.map { it.first }, agentTypes)
        val aligned = sorted.mapIndexed { idx, (_, line) ->
            line.copy(alignment = alignments.getOrElse(idx) { LyricAlignment.START })
        }
        return buildResult(sourceId, aligned)
    }

    companion object {
        private val MIRRORS = listOf(
            "lyricsplus.prjktla.my.id",
            "lyricsplus.atomix.one",
            "lyricsplus.binimum.org",
            "lyricsplus.prjktla.workers.dev",
            "lyricsplus-seven.vercel.app",
            "lyrics-plus-backend.vercel.app",
        )
    }
}

// ---- 6. Unison --------------------------------------------------------------

class UnisonProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.UNISON

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val url = buildString {
            append("https://unison.boidu.dev/lyrics?song=").append(LyricsHttp.enc(query.title))
            append("&artist=").append(LyricsHttp.enc(query.artist))
            if (!query.album.isNullOrBlank()) append("&album=").append(LyricsHttp.enc(query.album))
            if (query.durationMs > 0L) append("&duration=").append(query.durationMs / 1000L)
        }
        val body = httpFetch(url) ?: return null
        val root = runCatching { HttpClients.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val rawLyrics = data.str("lyrics")?.takeIf { it.isNotBlank() } ?: return null
        val format = data.str("format")?.lowercase(Locale.ROOT)
        val syncType = data.str("syncType")?.lowercase(Locale.ROOT)
        val lines = when {
            format == "ttml" -> SyncedLyricsParser.parseTtml(rawLyrics)
            syncType == "plain" -> SyncedLyricsParser.parsePlainText(rawLyrics)
            else -> SyncedLyricsParser.parseProviderPayload(rawLyrics)
        }
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

// ---- 7 & 8. YouTube Captions & YouTube Music --------------------------------

class YouTubeCaptionsProvider(
    private val client: MusicaInnertubeClient,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.YOUTUBE_CAPTIONS

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val cues = client.fetchYouTubeTranscript(query.videoId)
        if (cues.isEmpty()) return@runCatching null
        val lines = cues.mapIndexed { idx, cue ->
            val end = cue.endMs ?: cues.getOrNull(idx + 1)?.startMs ?: (cue.startMs + 4_000L)
            LyricLine(
                startMs = cue.startMs,
                endMs = end.coerceAtLeast(cue.startMs),
                text = cue.text,
                sungUntilMs = cue.endMs?.takeIf { it > cue.startMs },
            )
        }
        buildResult(sourceId, lines)
    }.getOrNull()
}

class YouTubeLyricsProvider(
    private val client: MusicaInnertubeClient,
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.YOUTUBE

    override suspend fun fetch(query: LyricsQuery): LyricsResult? = runCatching {
        val payload = client.fetchYouTubeLyrics(query.videoId) ?: return@runCatching null
        if (payload.timedLines.isNotEmpty()) {
            val sorted = payload.timedLines.sortedBy { it.startMs }
            val lines = sorted.mapIndexed { idx, cue ->
                val end = cue.endMs ?: sorted.getOrNull(idx + 1)?.startMs ?: (cue.startMs + 4_000L)
                LyricLine(
                    startMs = cue.startMs,
                    endMs = end.coerceAtLeast(cue.startMs),
                    text = cue.text,
                    sungUntilMs = cue.endMs?.takeIf { it > cue.startMs },
                )
            }
            return@runCatching buildResult(sourceId, lines)
        }
        val plain = payload.plainText ?: return@runCatching null
        val lines = SyncedLyricsParser.parsePlainText(plain)
        if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }.getOrNull()
}

// ---- 9. KuGou (3-Step Keyless Base64 LRC) -----------------------------------

class KuGouProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.KUGOU

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val keyword = "${query.artist} - ${query.title}".trim(' ', '-')
        if (keyword.isBlank()) return null

        val songSearch = httpFetch(
            "https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=${LyricsHttp.enc(keyword)}",
        )
        val matchedHash = songSearch?.let { body ->
            val info = runCatching {
                HttpClients.json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("info")?.jsonArray
            }.getOrNull()
            info?.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val hash = obj.str("hash")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val durMs = (obj.lng("duration") ?: 0L) * 1_000L
                val delta = if (query.durationMs > 0L && durMs > 0L) abs(query.durationMs - durMs) else 0L
                if (query.durationMs > 0L && durMs > 0L && delta > 8_000L) return@mapNotNull null
                delta to hash
            }?.minByOrNull { it.first }?.second
        }

        val lrcSearchUrl = if (matchedHash != null) {
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${LyricsHttp.enc(matchedHash)}"
        } else {
            buildString {
                append("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=").append(LyricsHttp.enc(keyword))
                if (query.durationMs > 0L) append("&duration=").append(query.durationMs)
            }
        }
        val lrcSearch = httpFetch(lrcSearchUrl) ?: return null
        val cand = runCatching {
            HttpClients.json.parseToJsonElement(lrcSearch).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return null
        val id = cand.str("id") ?: return null
        val accessKey = cand.str("accesskey") ?: return null

        val dlBody = httpFetch(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=${LyricsHttp.enc(id)}&accesskey=${LyricsHttp.enc(accessKey)}&fmt=lrc&charset=utf8",
        ) ?: return null
        val contentB64 = runCatching {
            HttpClients.json.parseToJsonElement(dlBody).jsonObject.str("content")
        }.getOrNull() ?: return null
        val decoded = runCatching {
            String(Base64.getDecoder().decode(contentB64), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val lines = SyncedLyricsParser.stripHeaderCredits(SyncedLyricsParser.parseLrc(decoded))
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }
}

// ---- 10. LRCLIB (Exact + Fuzzy Search) --------------------------------------

class LrclibProvider(
    private val httpFetch: suspend (String) -> String? = { LyricsHttp.getString(it) },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.LRCLIB

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val exactUrl = buildString {
            append("https://lrclib.net/api/get?track_name=").append(LyricsHttp.enc(query.title))
            append("&artist_name=").append(LyricsHttp.enc(query.artist))
            if (!query.album.isNullOrBlank()) append("&album_name=").append(LyricsHttp.enc(query.album))
            if (query.durationMs > 0L) append("&duration=").append(query.durationMs / 1000L)
        }
        httpFetch(exactUrl)?.let { body ->
            val obj = runCatching { HttpClients.json.parseToJsonElement(body).jsonObject }.getOrNull()
            obj?.let(::parseLrclibObject)?.let { return it }
        }

        // Fuzzy search fallback picking closest duration with non-blank synced lyrics
        val searchUrl = "https://lrclib.net/api/search?track_name=${LyricsHttp.enc(query.title)}&artist_name=${LyricsHttp.enc(query.artist)}"
        val searchBody = httpFetch(searchUrl) ?: return null
        val arr = runCatching { HttpClients.json.parseToJsonElement(searchBody).jsonArray }.getOrNull() ?: return null
        val candidates = arr.mapNotNull { it as? JsonObject }
        val targetSec = query.durationMs / 1000.0

        val bestSynced = candidates
            .filter { !it.str("syncedLyrics").isNullOrBlank() }
            .minByOrNull { obj ->
                val dur = obj.dbl("duration") ?: 0.0
                if (targetSec > 0.0 && dur > 0.0) abs(targetSec - dur) else 0.0
            }
        bestSynced?.let(::parseLrclibObject)?.let { return it }

        val bestPlain = candidates.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
        return bestPlain?.let(::parseLrclibObject)
    }

    private fun parseLrclibObject(obj: JsonObject): LyricsResult? {
        val synced = obj.str("syncedLyrics")
        if (!synced.isNullOrBlank()) {
            val lines = SyncedLyricsParser.parseLrc(synced)
            if (lines.isNotEmpty()) return buildResult(sourceId, lines)
        }
        val plain = obj.str("plainLyrics")
        if (!plain.isNullOrBlank()) {
            val lines = SyncedLyricsParser.parsePlainText(plain)
            if (lines.isNotEmpty()) return buildResult(sourceId, lines)
        }
        return null
    }
}

// ---- 11. Musixmatch (HMAC-SHA256 Signed RichSync -> Subtitle) ---------------

class MusixmatchProvider(
    private val httpFetch: suspend (String, Map<String, String>) -> String? = { url, hdr ->
        LyricsHttp.getString(url, client = LyricsHttp.extended, headers = hdr)
    },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.MUSIXMATCH

    private val cachedSecret = AtomicReference<String?>(null)
    private val cachedToken = AtomicReference<String?>(null)

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val token = ensureToken(forceRefresh = false) ?: return null
        val searchResult = searchTrack(query, token) ?: run {
            val refreshed = ensureToken(forceRefresh = true) ?: return null
            searchTrack(query, refreshed) ?: return null
        }

        val activeToken = cachedToken.get() ?: return null
        if (searchResult.hasRichSync) {
            val rich = fetchRichSync(searchResult.commonTrackId, activeToken)
            if (rich != null) return rich
        }
        return fetchSubtitle(searchResult.commonTrackId, activeToken)
    }

    private data class TrackHit(val commonTrackId: Long, val hasRichSync: Boolean)

    private suspend fun searchTrack(query: LyricsQuery, token: String): TrackHit? {
        val url = signedApiUrl(
            endpoint = "track.search",
            params = linkedMapOf(
                "usertoken" to token,
                "q_track" to query.title,
                "q_artist" to query.artist,
                "page_size" to "5",
                "page" to "1",
                "s_track_rating" to "desc",
                "f_has_lyrics" to "1",
            ),
        )
        val body = httpFetch(url, MXM_HEADERS) ?: return null
        val root = runCatching { HttpClients.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val status = statusCode(root)
        if (status == 401 || status == 402) {
            cachedToken.set(null)
            return null
        }
        val list = root["message"]?.jsonObject?.get("body")?.jsonObject?.get("track_list")?.jsonArray ?: return null
        val targetSec = query.durationMs / 1000L

        return list.mapNotNull { elem ->
            val track = (elem as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
            val cid = track.lng("commontrack_id") ?: return@mapNotNull null
            val lenSec = track.lng("track_length") ?: 0L
            val rich = (track.int("has_richsync") ?: 0) != 0
            val delta = if (targetSec > 0L && lenSec > 0L) abs(targetSec - lenSec) else 0L
            if (targetSec > 0L && lenSec > 0L && delta > 15L) return@mapNotNull null
            val score = delta - (if (rich) 2L else 0L)
            score to TrackHit(cid, rich)
        }.minByOrNull { it.first }?.second
    }

    private suspend fun fetchRichSync(commonTrackId: Long, token: String): LyricsResult? {
        val url = signedApiUrl(
            endpoint = "track.richsync.get",
            params = linkedMapOf(
                "usertoken" to token,
                "commontrack_id" to commonTrackId.toString(),
            ),
        )
        val body = httpFetch(url, MXM_HEADERS) ?: return null
        val richBody = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                ?.get("body")?.jsonObject?.get("richsync")?.jsonObject?.str("richsync_body")
        }.getOrNull() ?: return null
        val lines = parseRichSyncBody(richBody)
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }

    private suspend fun fetchSubtitle(commonTrackId: Long, token: String): LyricsResult? {
        val url = signedApiUrl(
            endpoint = "track.subtitle.get",
            params = linkedMapOf(
                "usertoken" to token,
                "commontrack_id" to commonTrackId.toString(),
                "subtitle_format" to "lrc",
            ),
        )
        val body = httpFetch(url, MXM_HEADERS) ?: return null
        val subBody = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                ?.get("body")?.jsonObject?.get("subtitle")?.jsonObject?.str("subtitle_body")
        }.getOrNull() ?: return null
        val lines = SyncedLyricsParser.parseLrc(subBody)
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }

    internal fun parseRichSyncBody(raw: String): List<LyricLine> {
        val arr = runCatching { HttpClients.json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        val lines = mutableListOf<LyricLine>()
        for (elem in arr) {
            val obj = elem as? JsonObject ?: continue
            val ts = ((obj.dbl("ts") ?: continue) * 1000.0).toLong()
            val te = ((obj.dbl("te") ?: (ts / 1000.0 + 3.0)) * 1000.0).toLong().coerceAtLeast(ts)
            val lArr = obj["l"] as? JsonArray ?: continue
            val runs = mutableListOf<SyncedLyricsParser.TimedRun>()
            for (i in lArr.indices) {
                val item = lArr[i] as? JsonObject ?: continue
                val text = item.str("c").orEmpty()
                val offset = ((item.dbl("o") ?: 0.0) * 1000.0).toLong()
                val start = ts + offset
                val nextStart = (lArr.getOrNull(i + 1) as? JsonObject)?.dbl("o")?.let { ts + (it * 1000.0).toLong() } ?: te
                if (text.isNotEmpty()) {
                    runs += SyncedLyricsParser.TimedRun(start, nextStart.coerceAtLeast(start), text)
                }
            }
            val words = SyncedLyricsParser.wordsFromRuns(runs, spacingIsExplicit = true)
            val lineText = if (words.isNotEmpty()) SyncedLyricsParser.buildWordText(words) else obj.str("x")?.trim().orEmpty()
            if (lineText.isNotEmpty()) {
                lines += LyricLine(
                    startMs = ts,
                    endMs = te,
                    text = lineText,
                    words = words,
                    sungUntilMs = te.takeIf { it > ts },
                )
            }
        }
        return lines.sortedBy { it.startMs }
    }

    private suspend fun ensureToken(forceRefresh: Boolean): String? {
        if (!forceRefresh) cachedToken.get()?.let { return it }
        if (forceRefresh) {
            cachedToken.set(null)
            cachedSecret.set(null)
        }
        ensureSecret()
        val url = signedApiUrl(endpoint = "token.get", params = linkedMapOf("user_language" to "en"))
        val body = httpFetch(url, MXM_HEADERS) ?: return null
        val token = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                ?.get("body")?.jsonObject?.str("user_token")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        cachedToken.set(token)
        return token
    }

    private suspend fun ensureSecret(): String {
        cachedSecret.get()?.let { return it }
        val scraped = runCatching {
            val html = httpFetch("https://www.musixmatch.com/search", emptyMap()) ?: return@runCatching null
            val appChunk = MXM_APP_CHUNK_REGEX.find(html)?.value ?: return@runCatching null
            val js = httpFetch(appChunk, emptyMap()) ?: return@runCatching null
            val match = MXM_SECRET_REGEX.find(js)?.groupValues?.get(1) ?: return@runCatching null
            String(Base64.getDecoder().decode(match.reversed()), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val secret = scraped ?: FALLBACK_SECRET
        cachedSecret.set(secret)
        return secret
    }

    private fun signedApiUrl(endpoint: String, params: LinkedHashMap<String, String>): String {
        val base = buildString {
            append("https://apic-desktop.musixmatch.com/ws/1.1/").append(endpoint)
            append("?app_id=web-desktop-app-v1.0&format=json")
            params.forEach { (k, v) ->
                append('&').append(k).append('=').append(LyricsHttp.enc(v))
            }
        }
        val secret = cachedSecret.get() ?: FALLBACK_SECRET
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val sig = Base64.getEncoder().encodeToString(mac.doFinal((base + dateStamp).toByteArray(Charsets.UTF_8)))
        return "$base&signature=${LyricsHttp.enc(sig)}&signature_protocol=sha256"
    }

    private fun statusCode(root: JsonObject): Int =
        root["message"]?.jsonObject?.get("header")?.jsonObject?.int("status_code") ?: 0

    companion object {
        private const val FALLBACK_SECRET = "96707058025921365445"
        private val MXM_APP_CHUNK_REGEX = Regex("""https://[^"'\s]+/_next/static/chunks/pages/_app-[^"'\s]+\.js""")
        private val MXM_SECRET_REGEX = Regex("""from\(\s*"([^"]+)"\s*\.split\(""\)\.reverse\(\)\.join\(""\)""")
        private val MXM_HEADERS = mapOf(
            "Cookie" to "AWSELBCORS=0; AWSELB=0",
            "Origin" to "https://www.musixmatch.com",
            "Referer" to "https://www.musixmatch.com/",
        )
    }
}

// ---- 12. Genius (Lazy Plain-Text Scraper with [Section] Headers) ------------

class GeniusProvider(
    private val httpFetch: suspend (String) -> String? = { url ->
        LyricsHttp.getString(url, client = LyricsHttp.extended)
    },
) : LyricsProvider {
    override val sourceId: LyricsSourceId = LyricsSourceId.GENIUS

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val searchQueries = listOf(
            "${query.title} ${query.artist}".trim(),
            query.title.trim(),
        ).distinct().filter { it.isNotBlank() }

        var songUrl: String? = null
        for (q in searchQueries) {
            val body = httpFetch("https://genius.com/api/search/multi?q=${LyricsHttp.enc(q)}") ?: continue
            songUrl = extractGeniusSongUrl(body, query)
            if (songUrl != null) break
        }
        val targetUrl = songUrl ?: return null
        val html = httpFetch(targetUrl) ?: return null
        val lines = parseGeniusHtml(html)
        return if (lines.isEmpty()) null else buildResult(sourceId, lines)
    }

    private fun extractGeniusSongUrl(body: String, query: LyricsQuery): String? {
        val sections = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("sections")?.jsonArray
        }.getOrNull() ?: return null
        val qCore = SyncedLyricsParser.normalizeCore(query.title)
        for (sec in sections) {
            val secObj = sec as? JsonObject ?: continue
            val hits = secObj["hits"] as? JsonArray ?: continue
            for (hit in hits) {
                val res = (hit as? JsonObject)?.get("result") as? JsonObject ?: continue
                val url = res.str("url")?.takeIf { it.startsWith("https://genius.com/") } ?: continue
                val title = res.str("title").orEmpty()
                if (qCore.isEmpty() || SyncedLyricsParser.normalizeCore(title).contains(qCore) || qCore.contains(SyncedLyricsParser.normalizeCore(title))) {
                    return url
                }
            }
        }
        return null
    }

    internal fun parseGeniusHtml(html: String): List<LyricLine> {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val containers = doc.select("div[data-lyrics-container=true]").ifEmpty { doc.select("div.lyrics") }
        if (containers.isEmpty()) return emptyList()

        val rawLines = mutableListOf<String>()
        containers.forEach { container ->
            container.select("div[data-exclude-from-selection=true], button, script, style").remove()
            container.select("br").append("\\n")
            val text = container.text().replace("\\n", "\n")
            text.lineSequence().forEach { raw ->
                val cleaned = raw
                    .replace(Regex("""\d*Embed$"""), "")
                    .replace("You might also like", "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    rawLines += cleaned
                }
            }
        }
        return rawLines.map { LyricLine(startMs = 0L, endMs = 0L, text = it) }
    }
}

// ---- Shared Result & JSON Helpers -------------------------------------------

private fun buildResult(source: LyricsSourceId, rawLines: List<LyricLine>): LyricsResult {
    val withBg = SyncedLyricsParser.withBackgroundVocals(rawLines)
    val isSynced = withBg.any { it.startMs > 0L || it.isWordSynced }
    val finalLines = if (isSynced) SyncedLyricsParser.withInstrumentalGaps(withBg) else withBg
    return LyricsResult(
        source = source,
        lines = finalLines,
        isSynced = isSynced,
        isWordSynced = finalLines.any { it.isWordSynced },
    )
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
