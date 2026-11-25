package com.music.musica.data.lyrics

import com.music.musica.core.model.LyricsSourceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LyricsRepositoryTest {

    private class FakeLyricsProvider(
        override val sourceId: LyricsSourceId,
        private val delayMs: Long = 0L,
        private val result: LyricsResult?,
    ) : LyricsProvider {
        var calls = 0
        override suspend fun fetch(query: LyricsQuery): LyricsResult? {
            calls++
            if (delayMs > 0L) delay(delayMs)
            return result
        }
    }

    @Test
    fun fetchLyrics_respectsPriorityAndPrefersWordSyncedOverLineSynced() = runTest {
        val lineSynced = LyricsResult(
            source = LyricsSourceId.LRCLIB,
            lines = listOf(LyricLine(1000L, 2000L, "Line")),
            isSynced = true,
            isWordSynced = false,
        )
        val wordSynced = LyricsResult(
            source = LyricsSourceId.BETTER_LYRICS,
            lines = listOf(LyricLine(1000L, 2000L, "Word", words = listOf(LyricWord(1000L, 2000L, "Word")))),
            isSynced = true,
            isWordSynced = true,
        )

        val lrcProvider = FakeLyricsProvider(LyricsSourceId.LRCLIB, delayMs = 10L, result = lineSynced)
        val betterProvider = FakeLyricsProvider(LyricsSourceId.BETTER_LYRICS, delayMs = 80L, result = wordSynced)

        val repo = LyricsRepository(providers = listOf(lrcProvider, betterProvider))
        val res = repo.fetchLyrics(
            query = LyricsQuery("vid1", "Song", "Artist"),
            priorityOrder = listOf(LyricsSourceId.LRCLIB, LyricsSourceId.BETTER_LYRICS),
            preferWordSynced = true,
        )

        assertNotNull(res)
        assertEquals(LyricsSourceId.BETTER_LYRICS, res?.source)
        assertEquals(true, res?.isWordSynced)
    }

    @Test
    fun fetchLyrics_usesCacheBeforeContactingNetworkProviders() = runTest {
        val cached = LyricsResult(
            source = LyricsSourceId.LRCLIB,
            lines = listOf(LyricLine(0L, 1000L, "Cached")),
            isSynced = true,
            isWordSynced = false,
        )
        val provider = FakeLyricsProvider(LyricsSourceId.LRCLIB, result = cached)
        val repo = LyricsRepository(
            providers = listOf(provider),
            cacheRead = { cached },
        )

        val res = repo.fetchLyrics(LyricsQuery("vid1", "Song", "Artist"))
        assertEquals("Cached", res?.lines?.first()?.text)
        assertEquals(0, provider.calls)
    }

    @Test
    fun fetchLyrics_ignoresDisabledCachedSourceAndCancelsSlowProviders() = runTest {
        val wordSynced = LyricsResult(
            source = LyricsSourceId.BETTER_LYRICS,
            lines = listOf(LyricLine(0L, 1000L, "Fast", words = listOf(LyricWord(0L, 1000L, "Fast")))),
            isSynced = true,
            isWordSynced = true,
        )
        val fastProvider = FakeLyricsProvider(LyricsSourceId.BETTER_LYRICS, delayMs = 5L, result = wordSynced)
        val slowProvider = FakeLyricsProvider(LyricsSourceId.LRCLIB, delayMs = 9_000L, result = null)
        val disabledCache = LyricsResult(
            source = LyricsSourceId.YOUTUBE,
            lines = listOf(LyricLine(0L, 1000L, "Stale")),
            isSynced = true,
            isWordSynced = true,
        )

        val repo = LyricsRepository(
            providers = listOf(fastProvider, slowProvider),
            cacheRead = { disabledCache },
        )

        val res = repo.fetchLyrics(
            query = LyricsQuery("vid2", "Song", "Artist"),
            enabledSources = setOf(LyricsSourceId.BETTER_LYRICS, LyricsSourceId.LRCLIB),
            priorityOrder = listOf(LyricsSourceId.BETTER_LYRICS, LyricsSourceId.LRCLIB),
        )
        assertEquals(LyricsSourceId.BETTER_LYRICS, res?.source)
        assertEquals("Fast", res?.lines?.first()?.text)
    }
}
