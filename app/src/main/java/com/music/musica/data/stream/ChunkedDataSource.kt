package com.music.musica.data.stream

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException

/**
 * Pure, framework-independent chunked byte-range reader backing [ChunkedDataSource].
 * Splits a stream of known total length into bounded `[position, position + chunkBytes - 1]`
 * range reads so servers do not pace continuous progressive transfers down to playback bitrate.
 */
@OptIn(UnstableApi::class)
internal class ChunkedRangeEngine(
    private val chunkBytes: Long,
    private val openRange: (position: Long, length: Long) -> Unit,
    private val readRange: (buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val closeRange: () -> Unit,
) {
    private var position: Long = 0L
    private var bytesRemaining: Long = 0L
    private var chunkRemaining: Long = 0L
    private var chunkOpen: Boolean = false

    fun open(startPosition: Long, requestedLength: Long?, totalContentLength: Long): Long {
        val safeStart = startPosition.coerceAtLeast(0L)
        position = safeStart
        val end = if (requestedLength == null || requestedLength < 0L) {
            totalContentLength
        } else {
            minOf(totalContentLength, safeStart + requestedLength)
        }
        bytesRemaining = (end - safeStart).coerceAtLeast(0L)
        chunkRemaining = 0L
        chunkOpen = false
        if (bytesRemaining > 0L) {
            openNextChunk()
        }
        return bytesRemaining
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0

        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining <= 0L) {
                closeCurrentChunk()
                openNextChunk()
            }
            val toRead = minOf(length.toLong(), chunkRemaining).toInt()
            val read = readRange(buffer, offset, toRead)
            if (read != C.RESULT_END_OF_INPUT && read >= 0) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    fun close() {
        closeCurrentChunk()
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    private fun openNextChunk() {
        val nextLength = minOf(bytesRemaining.coerceAtLeast(0L), chunkBytes.coerceAtLeast(1L))
        openRange(position, nextLength)
        chunkRemaining = nextLength
        chunkOpen = true
    }

    private fun closeCurrentChunk() {
        if (chunkOpen) {
            closeRange()
            chunkOpen = false
        }
    }

    companion object {
        private const val MAX_EMPTY_RANGES: Int = 3
    }
}

/**
 * Splits progressive googlevideo HTTP reads into bounded byte-range requests (default 2 MB).
 *
 * Unbounded GET requests to googlevideo are frequently paced down to roughly playback bitrate,
 * preventing ExoPlayer from building a healthy read-ahead buffer. Issuing bounded HTTP Range
 * requests serves each chunk at full connection speed while remaining completely transparent
 * to [androidx.media3.datasource.cache.CacheDataSource] and ExoPlayer above it.
 *
 * URLs without a `clen` query parameter (e.g., HLS manifests or third-party sources) are
 * passed straight through untouched.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val onHttpRefused: ((uri: Uri, responseCode: Int) -> Unit)? = null,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var passthrough: Boolean = false
    private var passthroughOpen: Boolean = false

    private val engine = ChunkedRangeEngine(
        chunkBytes = chunkBytes,
        openRange = { pos, len ->
            val spec = requireNotNull(baseSpec).buildUpon()
                .setPosition(pos)
                .setLength(len)
                .build()
            try {
                upstream.open(spec)
            } catch (e: Exception) {
                reportRefusal(spec, e)
                throw e
            }
        },
        readRange = { buf, off, len -> upstream.read(buf, off, len) },
        closeRange = { upstream.close() },
    )

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        val total = extractContentLength(dataSpec.uri)
        if (total == null || total <= 0L) {
            passthrough = true
            passthroughOpen = true
            return try {
                upstream.open(dataSpec)
            } catch (e: Exception) {
                passthroughOpen = false
                reportRefusal(dataSpec, e)
                throw e
            }
        }

        passthrough = false
        passthroughOpen = false
        val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else dataSpec.length
        return engine.open(
            startPosition = dataSpec.position,
            requestedLength = requested,
            totalContentLength = total,
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        return engine.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (passthrough) {
            if (passthroughOpen) {
                upstream.close()
                passthroughOpen = false
            }
        } else {
            engine.close()
        }
        baseSpec = null
    }

    private fun reportRefusal(spec: DataSpec, e: Exception) {
        if (e is InterruptedIOException) return
        if (e is HttpDataSource.InvalidResponseCodeException) {
            Log.w(TAG, "Stream refused (${e.responseCode}) for host=${spec.uri.host}")
            onHttpRefused?.invoke(spec.uri, e.responseCode)
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
        private val onHttpRefused: ((uri: Uri, responseCode: Int) -> Unit)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(
                upstream = upstream.createDataSource(),
                chunkBytes = chunkBytes,
                onHttpRefused = onHttpRefused,
            )
    }

    companion object {
        private const val TAG = "MusicaChunkedDS"
        const val DEFAULT_CHUNK_BYTES: Long = 2L * 1024L * 1024L
        private val CLEN_QUERY_REGEX = Regex("""(?:^|[?&])clen=(\d+)(?:&|$)""")

        internal fun extractContentLength(uri: Uri): Long? =
            runCatching { uri.getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
                ?: extractContentLengthFromUrl(uri.toString())

        internal fun extractContentLengthFromUrl(url: String): Long? {
            val queryStart = url.indexOf('?')
            if (queryStart < 0 || queryStart + 1 >= url.length) return null
            val query = url.substring(queryStart + 1)
            return CLEN_QUERY_REGEX.find(query)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
    }
}
