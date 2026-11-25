package com.music.musica.data.lyrics

import com.music.musica.core.model.LyricsSourceId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Queries all enabled lyrics providers in parallel while honouring user-configured priority order,
 * cancelling remaining slow providers immediately once a winning result is selected.
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val cacheRead: suspend (String) -> LyricsResult? = { null },
    private val cacheWrite: suspend (String, LyricsResult) -> Unit = { _, _ -> },
    private val offsetWrite: suspend (String, Long) -> Unit = { _, _ -> },
) {
    private val byId = providers.associateBy { it.sourceId }

    suspend fun fetchLyrics(
        query: LyricsQuery,
        enabledSources: Set<LyricsSourceId> = LyricsSourceId.entries.toSet(),
        priorityOrder: List<LyricsSourceId> = LyricsSourceId.entries.toList(),
        preferWordSynced: Boolean = true,
        forceSource: LyricsSourceId? = null,
    ): LyricsResult? {
        if (forceSource == null) {
            cacheRead(query.videoId)?.let { cached ->
                if (cached.source in enabledSources) {
                    return cached
                }
            }
        }

        val orderedIds = if (forceSource != null) {
            listOf(forceSource)
        } else {
            (priorityOrder + LyricsSourceId.entries).distinct().filter { it in enabledSources }
        }

        val activeProviders = orderedIds.mapNotNull { byId[it] }
        if (activeProviders.isEmpty()) return null

        val chosen = coroutineScope {
            val deferreds = activeProviders.map { provider ->
                provider.sourceId to async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching { provider.fetch(query) }.getOrNull()
                    }
                }
            }

            try {
                var bestSyncedFallback: LyricsResult? = null
                var bestPlainFallback: LyricsResult? = null

                for ((_, deferred) in deferreds) {
                    val res = deferred.await() ?: continue
                    if (res.lines.isEmpty()) continue

                    if (res.isWordSynced) {
                        deferreds.forEach { it.second.cancel() }
                        return@coroutineScope res
                    }
                    if (!preferWordSynced && res.isSynced) {
                        deferreds.forEach { it.second.cancel() }
                        return@coroutineScope res
                    }
                    if (res.isSynced && bestSyncedFallback == null) {
                        bestSyncedFallback = res
                    } else if (bestPlainFallback == null) {
                        bestPlainFallback = res
                    }
                }
                bestSyncedFallback ?: bestPlainFallback
            } finally {
                deferreds.forEach { it.second.cancel() }
            }
        }

        if (chosen != null) {
            runCatching { cacheWrite(query.videoId, chosen) }
        }
        return chosen
    }

    suspend fun saveOffset(videoId: String, offsetMs: Long) {
        runCatching { offsetWrite(videoId, offsetMs) }
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 10_000L
    }
}
