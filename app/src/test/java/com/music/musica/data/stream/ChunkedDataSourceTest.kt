package com.music.musica.data.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(UnstableApi::class)
class ChunkedDataSourceTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun extractContentLengthFromUrlParsesClenInAnyQueryPosition() {
        assertEquals(
            4_194_304L,
            ChunkedDataSource.extractContentLengthFromUrl(
                "https://rr1.googlevideo.com/videoplayback?expire=123&clen=4194304&mime=audio%2Fwebm",
            ),
        )
        assertEquals(
            2_500L,
            ChunkedDataSource.extractContentLengthFromUrl(
                "https://rr1.googlevideo.com/videoplayback?clen=2500",
            ),
        )
        assertNull(
            ChunkedDataSource.extractContentLengthFromUrl(
                "https://example.com/live/playlist.m3u8?token=abc",
            ),
        )
        assertNull(
            ChunkedDataSource.extractContentLengthFromUrl(
                "https://example.com/audio.mp3",
            ),
        )
    }

    @Test
    fun chunkedEngineIssuesBoundedRangeRequestsAndReassemblesExactPayload() {
        val payload = ByteArray(2_500) { idx -> (idx * 31 + 7).toByte() }
        val observedRanges = mutableListOf<String?>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                observedRanges.add(rangeHeader)
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val parts = rangeHeader.removePrefix("bytes=").split("-")
                    val start = parts[0].toInt()
                    val end = parts[1].toInt().coerceAtMost(payload.lastIndex)
                    val slice = payload.copyOfRange(start, end + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(payload))
            }
        }

        val url = server.url("/videoplayback?clen=${payload.size}").toString()
        val clen = ChunkedDataSource.extractContentLengthFromUrl(url)!!

        var currentStream: InputStream? = null
        val engine = ChunkedRangeEngine(
            chunkBytes = 1_024L,
            openRange = { pos, len ->
                val endInclusive = pos + len - 1
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$pos-$endInclusive")
                    .build()
                val resp = httpClient.newCall(req).execute()
                currentStream = resp.body.byteStream()
            },
            readRange = { buf, off, len ->
                val n = currentStream?.read(buf, off, len) ?: -1
                if (n < 0) C.RESULT_END_OF_INPUT else n
            },
            closeRange = {
                currentStream?.close()
                currentStream = null
            },
        )

        val totalBytes = engine.open(startPosition = 0L, requestedLength = null, totalContentLength = clen)
        assertEquals(2_500L, totalBytes)

        val out = ByteArrayOutputStream()
        val scratch = ByteArray(333) // Odd buffer size to exercise mid-chunk boundary crossings
        while (true) {
            val read = engine.read(scratch, 0, scratch.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(scratch, 0, read)
        }
        engine.close()

        assertArrayEquals(payload, out.toByteArray())
        assertEquals(
            listOf("bytes=0-1023", "bytes=1024-2047", "bytes=2048-2499"),
            observedRanges,
        )
    }

    @Test
    fun chunkedEngineRecoversFromTruncatedRangeResponse() {
        val payload = ByteArray(1_600) { idx -> (idx % 251).toByte() }
        var firstChunkTruncated = false
        val observedRanges = mutableListOf<String>()

        var offsetInSlice = 0
        var activeSlice = ByteArray(0)
        val engine = ChunkedRangeEngine(
            chunkBytes = 1_000L,
            openRange = { pos, len ->
                observedRanges.add("$pos-${pos + len - 1}")
                val actualLen = if (!firstChunkTruncated) {
                    firstChunkTruncated = true
                    400 // Simulate server ending range early after 400 bytes
                } else {
                    len.toInt()
                }
                activeSlice = payload.copyOfRange(pos.toInt(), (pos + actualLen).toInt())
                offsetInSlice = 0
            },
            readRange = { buf, off, len ->
                if (offsetInSlice >= activeSlice.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val count = minOf(len, activeSlice.size - offsetInSlice)
                    System.arraycopy(activeSlice, offsetInSlice, buf, off, count)
                    offsetInSlice += count
                    count
                }
            },
            closeRange = {
                activeSlice = ByteArray(0)
                offsetInSlice = 0
            },
        )

        assertEquals(1_600L, engine.open(startPosition = 0L, requestedLength = null, totalContentLength = 1_600L))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (true) {
            val read = engine.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, read)
        }
        engine.close()

        assertArrayEquals(payload, out.toByteArray())
        assertEquals(listOf("0-999", "400-1399", "1400-1599"), observedRanges)
    }
}
