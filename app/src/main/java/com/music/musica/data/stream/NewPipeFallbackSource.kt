package com.music.musica.data.stream

import android.os.Build
import android.util.Log
import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Fallback YouTube stream source using NewPipeExtractor.
 * Guarded on API < 33 because NewPipeExtractor v0.26.3 references URLEncoder.encode(String, Charset)
 * introduced in Android 13 (API 33), avoiding NoSuchMethodError on older devices.
 */
class NewPipeFallbackSource(
    private val okHttp: OkHttpClient = HttpClients.okHttp,
) : StreamSource {

    override val name: String = "NewPipe"

    override suspend fun resolve(videoId: String, maxKbps: Int): ResolvedStream? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Skipping NewPipe fallback on API ${Build.VERSION.SDK_INT} (< 33)")
            return@withContext null
        }

        ensureInitialized(okHttp)

        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val streams = extractor.audioStreams
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.content.isNotBlank() &&
                    it.averageBitrate > 0
            }

        val best = streams
            .filter { it.averageBitrate <= maxKbps }
            .maxByOrNull { it.averageBitrate }
            ?: streams.minByOrNull { it.averageBitrate }
            ?: return@withContext null

        ResolvedStream(
            videoId = videoId,
            url = best.content,
            mimeType = best.format?.mimeType ?: "audio/mp4",
            codecs = best.codec.orEmpty(),
            bitrateKbps = best.averageBitrate.coerceAtLeast(64),
            sourceClient = "NewPipeFallback",
        )
    }

    private class MusicaNewPipeDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: Request): Response {
            if ("/youtubei/v1/next" in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_JSON, request.url())
            }
            val method = request.httpMethod().uppercase()
            val body = when (method) {
                "POST", "PUT", "PATCH" -> (request.dataToSend() ?: ByteArray(0)).toRequestBody()
                else -> null
            }
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(method, body)
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (key, values) ->
                if (key.equals("User-Agent", ignoreCase = true)) {
                    values.firstOrNull()?.let { builder.header("User-Agent", it) }
                } else {
                    values.forEach { value -> builder.addHeader(key, value) }
                }
            }
            client.newCall(builder.build()).execute().use { resp ->
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body.string(),
                    resp.request.url.toString(),
                )
            }
        }
    }

    companion object {
        private const val TAG = "MusicaNewPipe"
        @Volatile
        private var initialized = false
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val EMPTY_NEXT_JSON =
            """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

        @Synchronized
        private fun ensureInitialized(okHttp: OkHttpClient) {
            if (!initialized) {
                NewPipe.init(MusicaNewPipeDownloader(okHttp))
                initialized = true
            }
        }
    }
}
