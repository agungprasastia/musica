package com.music.musica.data.repository

import com.music.musica.core.model.DetailPage
import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface ArtistPortraitLookup {
    suspend fun photoFor(artistName: String, catalogueTitles: suspend () -> Collection<String>): String?
    fun seed(photos: Map<String, String>)
    fun snapshot(): Map<String, String>
}

/**
 * Resolves high-resolution studio artist portraits via public iTunes search +
 * YouTube catalogue cross-verification (confirming the candidate shares at least
 * one track or release title to prevent homonym mismatches), then streams only the
 * first 64 KB of the matched page head to extract a verified portrait `og:image`.
 *
 * Callers combine this with a YouTube Music smart-crop fallback so local and indie
 * artists always display cleanly even when no studio portrait is available.
 */
class ArtistPortraitResolver(
    baseClient: OkHttpClient = HttpClients.okHttp,
) : ArtistPortraitLookup {

    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()

    private val known = ConcurrentHashMap<String, String>()

    override fun seed(photos: Map<String, String>) {
        photos.forEach { (key, url) ->
            if (key.startsWith(KEY_PREFIX) && (url.isEmpty() || isPortraitUrl(url))) {
                known.putIfAbsent(key, url)
            }
        }
    }

    override fun snapshot(): Map<String, String> = HashMap(known)

    override suspend fun photoFor(
        artistName: String,
        catalogueTitles: suspend () -> Collection<String>,
    ): String? {
        val nameKey = matchKey(artistName)
        if (nameKey.isEmpty()) return null
        val cacheKey = KEY_PREFIX + nameKey
        known[cacheKey]?.let { return it.ifEmpty { null } }

        val titleKeys = titleKeysOf(runCatching { catalogueTitles() }.getOrDefault(emptyList()))
        if (titleKeys.isEmpty()) return null

        val resolved = withContext(Dispatchers.IO) {
            runCatching { lookupVerifiedPortrait(artistName, nameKey, titleKeys) }
                .getOrElse { return@withContext null } // transient network failure: do not cache miss
                ?: ""
        } ?: return null

        known[cacheKey] = resolved
        return resolved.ifEmpty { null }
    }

    private fun lookupVerifiedPortrait(
        artistName: String,
        nameKey: String,
        titleKeys: Set<String>,
    ): String? {
        val encoded = URLEncoder.encode(artistName, StandardCharsets.UTF_8.name())
        val searchUrl = "https://itunes.apple.com/search?term=$encoded&entity=musicArtist&limit=5"
        val body = executeGetString(searchUrl) ?: return null
        val results = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        }.getOrNull() ?: return null

        return results.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { matchKey(it.str("artistName").orEmpty()) == nameKey }
            .filter { it.str("artistLinkUrl").orEmpty().startsWith("https://music.apple.com/") }
            .filter { candidate ->
                val artistId = candidate.str("artistId")?.toLongOrNull() ?: return@filter false
                sharesCatalogueTitles(artistId, titleKeys)
            }
            .mapNotNull { it.str("artistLinkUrl")?.substringBefore('?') }
            .take(MAX_CANDIDATES)
            .firstNotNullOfOrNull(::fetchHeadOgImage)
    }

    private fun sharesCatalogueTitles(artistId: Long, expectedKeys: Set<String>): Boolean {
        if (expectedKeys.isEmpty()) return false
        val body = executeGetString("https://itunes.apple.com/lookup?id=$artistId&entity=song&limit=200")
            ?: return false
        val rows = runCatching {
            HttpClients.json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        }.getOrNull() ?: return false
        return rows.any { elem ->
            val obj = elem as? JsonObject ?: return@any false
            val trackKey = obj.str("trackName")?.let(::titleKey)
            val collectionKey = obj.str("collectionName")?.let(::titleKey)
            (trackKey != null && trackKey in expectedKeys) ||
                (collectionKey != null && collectionKey in expectedKeys)
        }
    }

    private fun fetchHeadOgImage(pageUrl: String): String? {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body.source()
            val buffer = Buffer()
            val head = StringBuilder()
            while (head.length < HEAD_LIMIT_BYTES && source.read(buffer, 8_192L) != -1L) {
                head.append(buffer.readUtf8())
                OG_IMAGE_REGEX.find(head)?.let { match ->
                    return@use match.groupValues[1].takeIf(::isPortraitUrl)
                }
                if ("</head>" in head) return@use null
            }
            null
        }
    }

    private fun executeGetString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key]?.jsonPrimitive)?.contentOrNull

    companion object {
        private const val KEY_PREFIX = "v1|"
        private const val HEAD_LIMIT_BYTES = 64 * 1024
        private const val MAX_CANDIDATES = 2
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
        private val OG_IMAGE_REGEX = Regex("""property="og:image"\s+content="([^"]+)"""")

        fun matchKey(name: String): String =
            Normalizer.normalize(name.lowercase(Locale.ROOT).replace('$', 's'), Normalizer.Form.NFD)
                .filter { it.isLetterOrDigit() }

        fun titleKey(title: String): String? =
            matchKey(
                title.substringBefore(" (")
                    .substringBefore(" [")
                    .removeSuffix(" - Single")
                    .removeSuffix(" - EP"),
            ).takeIf { it.length >= 4 }

        fun titleKeysOf(titles: Collection<String>): Set<String> =
            titles.mapNotNullTo(HashSet(), ::titleKey)

        fun titlesOf(page: DetailPage): List<String> =
            page.tracks.map { it.title } +
                page.sections
                    .flatMap { it.items }
                    .filter { it.id.startsWith("MPRE") }
                    .map { it.title }

        fun isPortraitUrl(url: String): Boolean {
            val bucket = url.substringAfter("mzstatic.com/image/thumb/", "").substringBefore('/')
            return bucket.startsWith("Features") || bucket.startsWith("AMCArtistImages")
        }
    }
}
