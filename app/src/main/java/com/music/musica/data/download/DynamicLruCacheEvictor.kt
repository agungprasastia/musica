package com.music.musica.data.download

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * LRU [CacheEvictor] whose ceiling can change at runtime without reopening [SimpleCache].
 * Also shields track opening spans (within [headBytes]) up to [headBudgetBytes] so normal
 * playback tails do not evict cached track headers prematurely.
 */
@UnstableApi
class DynamicLruCacheEvictor(
    @Volatile var maxBytes: Long,
    private val headBytes: Long = DEFAULT_HEAD_BYTES,
    private val headBudgetBytes: Long = DEFAULT_HEAD_BUDGET_BYTES,
) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compareSpans)
    private val protectedHeads = TreeSet<CacheSpan>(::compareSpans)
    private var protectedSize = 0L
    private var currentSize = 0L

    private fun isHead(span: CacheSpan): Boolean = span.position < headBytes

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        val effectiveHeadBudget = headBudgetBytes.coerceAtMost(maxBytes / 4L)
        if (isHead(span) && protectedSize + span.length <= effectiveHeadBudget) {
            protectedHeads.add(span)
            protectedSize += span.length
        } else {
            leastRecentlyUsed.add(span)
        }
        currentSize += span.length
        evictCache(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (protectedHeads.remove(span)) {
            protectedSize -= span.length
        } else {
            leastRecentlyUsed.remove(span)
        }
        currentSize = (currentSize - span.length).coerceAtLeast(0L)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Immediately trims the cache if [maxBytes] was lowered by the user. */
    fun applyNow(cache: Cache) {
        evictCache(cache, 0L)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        val limit = maxBytes.coerceAtLeast(0L)
        while (currentSize + requiredSpace > limit) {
            if (leastRecentlyUsed.isNotEmpty()) {
                cache.removeSpan(leastRecentlyUsed.first())
                continue
            }
            val oldestHead = protectedHeads.pollFirst() ?: return
            protectedSize = (protectedSize - oldestHead.length).coerceAtLeast(0L)
            leastRecentlyUsed.add(oldestHead)
        }
    }

    private companion object {
        const val DEFAULT_HEAD_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_HEAD_BUDGET_BYTES = 48L * 1024L * 1024L

        fun compareSpans(lhs: CacheSpan, rhs: CacheSpan): Int {
            val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
            return when {
                delta == 0L -> lhs.compareTo(rhs)
                delta < 0L -> -1
                else -> 1
            }
        }
    }
}
