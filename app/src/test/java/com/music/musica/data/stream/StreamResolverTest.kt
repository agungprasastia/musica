package com.music.musica.data.stream

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResolverTest {

    private class FakeSource(
        override val name: String,
        private val handler: suspend (String, Int) -> ResolvedStream?,
    ) : StreamSource {
        var calls = 0
        override suspend fun resolve(videoId: String, maxKbps: Int): ResolvedStream? {
            calls++
            return handler(videoId, maxKbps)
        }
    }

    @Test
    fun resolve_usesCacheWithinTtlAndRespectsForceRefresh() = runTest {
        var now = 1_000L
        val primary = FakeSource("ITX") { id, kbps ->
            ResolvedStream(videoId = id, url = "https://cdn/$id-$kbps", bitrateKbps = kbps.coerceAtMost(256), sourceClient = "ITX", resolvedAtMs = now)
        }
        val prober = object : StreamProber {
            override suspend fun isPlayable(stream: ResolvedStream) = true
        }
        val resolver = StreamResolver(
            primary = primary,
            fallback = null,
            prober = prober,
            qualityKbpsProvider = { 128 },
            clockMs = { now },
            cacheTtlMs = 60_000L,
        )

        val first = resolver.resolve("vidA").getOrThrow()
        assertEquals(128, first.bitrateKbps)
        assertEquals(1, primary.calls)

        // Within TTL -> cache hit
        now += 10_000L
        val second = resolver.resolve("vidA").getOrThrow()
        assertEquals(first.url, second.url)
        assertEquals(1, primary.calls)

        // Force refresh -> bypasses cache
        val third = resolver.resolve("vidA", forceRefresh = true).getOrThrow()
        assertEquals(2, primary.calls)
        assertEquals(first.url, third.url)
    }

    @Test
    fun resolve_fallsBackWhenPrimaryFailsProbe() = runTest {
        val primary = FakeSource("ITX") { id, _ ->
            ResolvedStream(videoId = id, url = "https://dead/403", sourceClient = "ITX")
        }
        val fallback = FakeSource("NewPipe") { id, _ ->
            ResolvedStream(videoId = id, url = "https://alive/200", sourceClient = "NewPipe")
        }
        val prober = object : StreamProber {
            override suspend fun isPlayable(stream: ResolvedStream) = stream.url.contains("alive")
        }
        val resolver = StreamResolver(
            primary = primary,
            fallback = fallback,
            prober = prober,
            qualityKbpsProvider = { 256 },
        )

        val result = resolver.resolve("vidB").getOrThrow()
        assertEquals("https://alive/200", result.url)
        assertEquals("NewPipe", result.sourceClient)
        assertEquals(1, primary.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun resolve_usesSeparateCacheBucketWhenNetworkQualityChanges() = runTest {
        var ceiling = 128
        val primary = FakeSource("ITX") { id, kbps ->
            ResolvedStream(videoId = id, url = "https://cdn/$id-$kbps", bitrateKbps = kbps, sourceClient = "ITX")
        }
        val resolver = StreamResolver(
            primary = primary,
            prober = object : StreamProber { override suspend fun isPlayable(stream: ResolvedStream) = true },
            qualityKbpsProvider = { ceiling },
        )

        val mobile = resolver.resolve("vidQ").getOrThrow()
        assertEquals(128, mobile.bitrateKbps)

        ceiling = 256
        val wifi = resolver.resolve("vidQ").getOrThrow()
        assertEquals(256, wifi.bitrateKbps)
        assertEquals(2, primary.calls)
    }

    @Test
    fun resolve_returnsFailureWhenBothPrimaryAndFallbackFail() = runTest {
        val primary = FakeSource("ITX") { _, _ -> null }
        val fallback = FakeSource("NewPipe") { _, _ -> null }
        val resolver = StreamResolver(
            primary = primary,
            fallback = fallback,
            prober = object : StreamProber { override suspend fun isPlayable(stream: ResolvedStream) = true },
        )

        assertTrue(resolver.resolve("missing").isFailure)
    }

    @Test
    fun resolve_skipsBlockingProbeOnFirstResolveWhenDisabledAndProbesOnForceRefresh() = runTest {
        var probeCalls = 0
        val primary = FakeSource("ITX") { id, _ ->
            ResolvedStream(videoId = id, url = "https://primary/dead", sourceClient = "ITX")
        }
        val fallback = FakeSource("NewPipe") { id, _ ->
            ResolvedStream(videoId = id, url = "https://fallback/alive", sourceClient = "NewPipe")
        }
        val prober = object : StreamProber {
            override suspend fun isPlayable(stream: ResolvedStream): Boolean {
                probeCalls++
                return stream.url.contains("alive")
            }
        }
        val resolver = StreamResolver(
            primary = primary,
            fallback = fallback,
            prober = prober,
            probeOnFirstResolve = false,
        )

        // First play skips HTTP probe for fastest startup
        val first = resolver.resolve("vidFast").getOrThrow()
        assertEquals("https://primary/dead", first.url)
        assertEquals(0, probeCalls)

        // Retry with forceRefresh probes primary, rejects dead URL, and falls back to NewPipe
        val retried = resolver.resolve("vidFast", forceRefresh = true).getOrThrow()
        assertEquals("https://fallback/alive", retried.url)
        assertEquals(2, probeCalls)
    }
}
