package com.music.musica.data.lyrics

import com.music.musica.core.model.LyricsSourceId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Multi-provider lyrics orchestrator featuring:
 * - Query cleaning (preserves version tags like Remix/Live/Acoustic; strips feat./Official Video)
 * - 100-entry LRU ISRC memory cache + 2.5s BiniLyrics pre-identify for direct `lrc.red` & `LyricsPlus` hits
 * - Per-lookup [SharedDocuments] deduplication so `lrc.red` & `BiniLyrics` never double-download TTML
 * - Parallel IO race (lazy [LyricsSourceId.GENIUS]) with strict user-priority selection
 * - Live per-provider status callbacks (`FETCHING`, `FOUND`, `NOT_FOUND`, `NOT_FETCHED`) for instant switching
 * - Local Room cache read/write + offset persistence
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val cacheRead: suspend (String) -> LyricsResult? = { null },
    private val cacheWrite: suspend (String, LyricsResult) -> Unit = { _, _ -> },
    private val offsetWrite: suspend (String, Long) -> Unit = { _, _ -> },
    private val documentFetcher: suspend (String) -> String? = { LyricsHttp.getString(it) },
) {
    private val byId = providers.associateBy { it.sourceId }

    private val isrcCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(ISRC_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > ISRC_CACHE_SIZE
        },
    )

    suspend fun fetchLyrics(
        query: LyricsQuery,
        enabledSources: Set<LyricsSourceId> = LyricsSourceId.entries.toSet(),
        priorityOrder: List<LyricsSourceId> = LyricsSourceId.entries.toList(),
        preferWordSynced: Boolean = true,
        forceSource: LyricsSourceId? = null,
        onProviderStatus: ((LyricsSourceId, LyricsProviderStatus, LyricsResult?) -> Unit)? = null,
    ): LyricsResult? {
        if (forceSource == null) {
            cacheRead(query.videoId)?.let { cached ->
                if (cached.source in enabledSources && cached.lines.isNotEmpty()) {
                    onProviderStatus?.invoke(cached.source, LyricsProviderStatus.FOUND, cached)
                    return cached
                }
            }
        }

        val cleanedQuery = query.copy(
            title = SyncedLyricsParser.cleanTitleForLyrics(query.title).ifBlank { query.title },
            artist = SyncedLyricsParser.cleanArtistForLyrics(query.artist).ifBlank { query.artist },
            isrc = query.isrc ?: isrcCache[query.videoId],
        )

        val orderedIds = if (forceSource != null) {
            listOf(forceSource)
        } else {
            LyricsSourceId.mergeOrder(priorityOrder).filter { it in enabledSources }
        }
        val activeProviders = orderedIds.mapNotNull { byId[it] }
        if (activeProviders.isEmpty()) return null

        val sharedDocs = SharedDocuments(documentFetcher)

        // Pre-identify ISRC via BiniLyrics (capped at 2.5s) when enabled & ISRC unknown
        val biniProvider = activeProviders.firstOrNull { it is BiniLyricsProvider } as? BiniLyricsProvider
        val biniHit = if (cleanedQuery.isrc == null && biniProvider != null && forceSource == null) {
            withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching { biniProvider.identify(cleanedQuery) }.getOrNull()
            }
        } else {
            null
        }
        val resolvedIsrc = cleanedQuery.isrc ?: biniHit?.isrc
        if (!resolvedIsrc.isNullOrBlank()) {
            isrcCache[query.videoId] = resolvedIsrc
        }
        val enrichedQuery = cleanedQuery.copy(isrc = resolvedIsrc)

        val chosen = coroutineScope {
            val jobs = activeProviders.map { provider ->
                val isLazy = forceSource == null && provider.sourceId == LyricsSourceId.GENIUS
                if (isLazy) {
                    onProviderStatus?.invoke(provider.sourceId, LyricsProviderStatus.NOT_FETCHED, null)
                } else {
                    onProviderStatus?.invoke(provider.sourceId, LyricsProviderStatus.FETCHING, null)
                }
                val start = if (isLazy) CoroutineStart.LAZY else CoroutineStart.DEFAULT
                provider.sourceId to async(Dispatchers.IO, start = start) {
                    if (isLazy) {
                        onProviderStatus?.invoke(provider.sourceId, LyricsProviderStatus.FETCHING, null)
                    }
                    val res = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching {
                            when (provider) {
                                is LrcRedProvider -> provider.fetchWithShared(enrichedQuery, sharedDocs)
                                is BiniLyricsProvider -> provider.fetchForHit(enrichedQuery, biniHit, sharedDocs)
                                else -> provider.fetch(enrichedQuery)
                            }
                        }.getOrNull()
                    }?.takeUnless { it.lines.isEmpty() || SyncedLyricsParser.isInstrumentalNotice(it.lines) }

                    onProviderStatus?.invoke(
                        provider.sourceId,
                        if (res != null) LyricsProviderStatus.FOUND else LyricsProviderStatus.NOT_FOUND,
                        res,
                    )
                    res
                }
            }

            try {
                var bestSyncedFallback: LyricsResult? = null
                var bestPlainFallback: LyricsResult? = null
                var winner: LyricsResult? = null

                for ((srcId, deferred) in jobs) {
                    if (srcId == LyricsSourceId.GENIUS && forceSource == null && (bestSyncedFallback != null || bestPlainFallback != null)) {
                        continue
                    }
                    val res = deferred.await() ?: continue
                    if (res.lines.isEmpty()) continue

                    if (res.isWordSynced) {
                        winner = res
                        break
                    }
                    if (!preferWordSynced && res.isSynced) {
                        winner = res
                        break
                    }
                    if (res.isSynced && bestSyncedFallback == null) {
                        bestSyncedFallback = res
                    } else if (!res.isSynced && bestPlainFallback == null) {
                        bestPlainFallback = res
                    }
                }
                winner ?: bestSyncedFallback ?: bestPlainFallback
            } finally {
                jobs.forEach { (srcId, deferred) ->
                    val wasActive = deferred.isActive
                    if (!deferred.isCompleted) {
                        deferred.cancel()
                        if (wasActive) {
                            onProviderStatus?.invoke(srcId, LyricsProviderStatus.NOT_FETCHED, null)
                        }
                    }
                }
            }
        }

        if (chosen != null) {
            runCatching { cacheWrite(query.videoId, chosen) }
        }
        return chosen
    }

    /**
     * Fetches a single provider on demand (used when tapping a `NOT_FETCHED` or `NOT_FOUND`
     * provider row inside `LyricsProviderSheet`).
     */
    suspend fun fetchSingleProvider(
        query: LyricsQuery,
        source: LyricsSourceId,
    ): LyricsResult? = fetchLyrics(
        query = query,
        enabledSources = setOf(source),
        priorityOrder = listOf(source),
        preferWordSynced = false,
        forceSource = source,
    )

    suspend fun saveOffset(videoId: String, offsetMs: Long) {
        runCatching { offsetWrite(videoId, offsetMs) }
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 10_000L
        private const val IDENTIFY_TIMEOUT_MS = 2_500L
        private const val ISRC_CACHE_SIZE = 100
    }
}
