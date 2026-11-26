package com.music.musica.data.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

@OptIn(UnstableApi::class)
class DynamicLruCacheEvictorTest {

    private fun span(
        key: String,
        position: Long,
        length: Long,
        touchedAt: Long,
    ): CacheSpan = CacheSpan(key, position, length, touchedAt, File("$key-$position"))

    private fun fakeCache(evictor: DynamicLruCacheEvictor, removed: MutableList<CacheSpan>): Cache {
        lateinit var proxy: Cache
        proxy = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, args ->
            when (method.name) {
                "removeSpan" -> {
                    val s = args[0] as CacheSpan
                    removed.add(s)
                    evictor.onSpanRemoved(proxy, s)
                    null
                }
                else -> throw UnsupportedOperationException("Unexpected Cache method: ${method.name}")
            }
        } as Cache
        return proxy
    }

    @Test
    fun loweringMaxBytesAndCallingApplyNowEvictsOldestNonHeadSpansFirst() {
        val evictor = DynamicLruCacheEvictor(
            maxBytes = 100L,
            headBytes = 10L,
            headBudgetBytes = 25L,
        )
        val removed = mutableListOf<CacheSpan>()
        val cache = fakeCache(evictor, removed)

        val head1 = span("trackA", position = 0L, length = 20L, touchedAt = 1L)
        val tail1 = span("trackA", position = 20L, length = 30L, touchedAt = 2L)
        val tail2 = span("trackB", position = 20L, length = 30L, touchedAt = 3L)

        evictor.onSpanAdded(cache, head1)
        evictor.onSpanAdded(cache, tail1)
        evictor.onSpanAdded(cache, tail2)
        assertTrue(removed.isEmpty())

        // Lower ceiling from 100 -> 50: must evict oldest tail (tail1) while keeping protected head1
        evictor.maxBytes = 50L
        evictor.applyNow(cache)

        assertEquals(listOf(tail1), removed)
        assertFalse(head1 in removed)
    }

    @Test
    fun loweringMaxBytesBelowProtectedHeadsReleasesOldestProtectedHead() {
        val evictor = DynamicLruCacheEvictor(
            maxBytes = 100L,
            headBytes = 10L,
            headBudgetBytes = 25L,
        )
        val removed = mutableListOf<CacheSpan>()
        val cache = fakeCache(evictor, removed)

        val headOld = span("old", position = 0L, length = 12L, touchedAt = 10L)
        val headNew = span("new", position = 0L, length = 12L, touchedAt = 20L)

        evictor.onSpanAdded(cache, headOld)
        evictor.onSpanAdded(cache, headNew)
        assertTrue(removed.isEmpty())

        // Lower ceiling below combined protected heads -> oldest protected head must be evicted
        evictor.maxBytes = 12L
        evictor.applyNow(cache)

        assertEquals(listOf(headOld), removed)
    }

    @Test
    fun onStartFileEvictsRoomForIncomingWrite() {
        val evictor = DynamicLruCacheEvictor(
            maxBytes = 50L,
            headBytes = 0L,
            headBudgetBytes = 0L,
        )
        val removed = mutableListOf<CacheSpan>()
        val cache = fakeCache(evictor, removed)

        val s1 = span("a", position = 0L, length = 20L, touchedAt = 1L)
        val s2 = span("b", position = 0L, length = 20L, touchedAt = 2L)
        evictor.onSpanAdded(cache, s1)
        evictor.onSpanAdded(cache, s2)

        evictor.onStartFile(cache, "c", 0L, 25L)

        assertEquals(listOf(s1), removed)
    }

    @Test
    fun evictCacheTerminatesWhenCacheDoesNotRemoveSpan() {
        val evictor = DynamicLruCacheEvictor(
            maxBytes = 10L,
            headBytes = 0L,
            headBudgetBytes = 0L,
        )
        var calls = 0
        val stubbornCache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, _ ->
            if (method.name == "removeSpan") {
                calls++
                null // intentionally does NOT invoke evictor.onSpanRemoved
            } else {
                throw UnsupportedOperationException(method.name)
            }
        } as Cache

        evictor.onSpanAdded(stubbornCache, span("stuck", position = 0L, length = 30L, touchedAt = 1L))
        assertEquals(1, calls)
    }
}
