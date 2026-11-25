package com.music.musica.data.stream

import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String = "audio/webm",
    val codecs: String = "opus",
    val bitrateKbps: Int = 128,
    val sampleRateHz: Int = 48_000,
    val channelCount: Int = 2,
    val loudnessDb: Double? = null,
    val sourceClient: String = "",
    val headers: Map<String, String> = emptyMap(),
    val resolvedAtMs: Long = System.currentTimeMillis(),
)

interface StreamSource {
    val name: String
    suspend fun resolve(videoId: String, maxKbps: Int): ResolvedStream?
}

interface StreamProber {
    suspend fun isPlayable(stream: ResolvedStream): Boolean
}

class OkHttpStreamProber(
    private val okHttp: OkHttpClient = HttpClients.okHttp,
) : StreamProber {
    override suspend fun isPlayable(stream: ResolvedStream): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(stream.url)
                .header("Range", "bytes=0-1")
                .apply {
                    stream.headers.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()
            okHttp.newCall(req).execute().use { resp ->
                resp.code in listOf(200, 206)
            }
        }.getOrDefault(false)
    }
}

/**
 * Resolves a YouTube videoId to a verified playable audio stream URL.
 * Order: TTL cache (keyed by videoId + bitrate ceiling) -> primary source -> fallback source,
 * probing each candidate before returning or caching.
 */
class StreamResolver(
    private val primary: StreamSource,
    private val fallback: StreamSource? = null,
    private val prober: StreamProber = OkHttpStreamProber(),
    private val qualityKbpsProvider: () -> Int = { Int.MAX_VALUE },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) {
    private data class CacheKey(val videoId: String, val maxKbps: Int)
    private data class CacheEntry(val stream: ResolvedStream, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): Result<ResolvedStream> {
        val ceilingKbps = qualityKbpsProvider()
        val key = CacheKey(videoId, ceilingKbps)
        val now = clockMs()

        if (!forceRefresh) {
            cache[key]?.takeIf { it.expiresAtMs > now }?.let { cached ->
                return Result.success(cached.stream)
            }
        } else {
            invalidate(videoId)
        }

        val candidates = listOfNotNull(primary, fallback)
        var lastError: Throwable? = null

        for (source in candidates) {
            val resolved = runCatching { source.resolve(videoId, ceilingKbps) }
                .onFailure {
                    if (it is CancellationException) throw it
                    lastError = it
                }
                .getOrNull()
                ?: continue

            if (prober.isPlayable(resolved)) {
                val stamped = resolved.copy(resolvedAtMs = now)
                cache[key] = CacheEntry(stamped, now + cacheTtlMs)
                return Result.success(stamped)
            }
        }

        return Result.failure(
            lastError ?: IOException("Unable to resolve playable audio stream for $videoId"),
        )
    }

    fun invalidate(videoId: String) {
        cache.keys.removeAll { it.videoId == videoId }
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 45 * 60 * 1000L
    }
}
