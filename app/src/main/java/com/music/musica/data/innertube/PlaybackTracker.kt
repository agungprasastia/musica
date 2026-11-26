package com.music.musica.data.innertube

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

data class PlaybackTrackingUrls(
    val playbackUrl: String,
    val atrUrl: String?,
    val watchtimeUrl: String?,
    val atrAfterSeconds: Long = 5L,
)

interface PlaybackTrackingTransport {
    suspend fun fetchTracking(videoId: String): PlaybackTrackingUrls?
    suspend fun ping(baseUrl: String, params: Map<String, String>): Int
}

class InnertubeTrackingTransport(
    private val client: MusicaInnertubeClient,
) : PlaybackTrackingTransport {
    override suspend fun fetchTracking(videoId: String): PlaybackTrackingUrls? =
        client.fetchPlaybackTracking(videoId)

    override suspend fun ping(baseUrl: String, params: Map<String, String>): Int =
        client.pingTrackingUrl(baseUrl, params)
}

/**
 * Best-effort YouTube Music play-history reporter implementing the 3-ping protocol:
 * 1. `videostatsPlaybackUrl` on audible start (registers play in account history)
 * 2. `atrUrl` after [PlaybackTrackingUrls.atrAfterSeconds] (~5s)
 * 3. `videostatsWatchtimeUrl` every 30s and on track completion/skip (`final=1`)
 *
 * Strictly gated by: signed-in session, valid 11-char YouTube videoId, active network,
 * and user privacy toggle (`settings.syncPlaybackHistoryToYtm`). Failures never impact playback.
 */
class PlaybackTracker(
    private val transport: PlaybackTrackingTransport,
    private val isSignedIn: () -> Boolean,
    private val isOnline: () -> Boolean,
    private val isSyncEnabled: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cpnGenerator: () -> String = ::generateCpn,
) {
    private class ActiveSession(
        val videoId: String,
        val cpn: String,
        val urls: PlaybackTrackingUrls,
        var reportedSeconds: Long = 0L,
        var flushingToSeconds: Long = 0L,
        var atrSent: Boolean = false,
    )

    private val lock = Mutex()

    @Volatile
    private var session: ActiveSession? = null

    @Volatile
    private var openingVideoId: String? = null

    fun onPlaying(videoId: String) {
        if (!canTrack(videoId)) return
        if (session?.videoId == videoId || openingVideoId == videoId) return
        openingVideoId = videoId
        scope.launch {
            try {
                openSession(videoId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Playback tracking open failed for $videoId: ${t.message}")
            } finally {
                if (openingVideoId == videoId) openingVideoId = null
            }
        }
    }

    fun onProgress(videoId: String, positionSeconds: Long) {
        if (!isSyncEnabled() || !isSignedIn() || !isOnline()) return
        val current = session ?: return
        if (current.videoId != videoId) return

        if (!current.atrSent && positionSeconds >= current.urls.atrAfterSeconds) {
            current.atrSent = true
            val atrUrl = current.urls.atrUrl
            if (!atrUrl.isNullOrBlank()) {
                scope.launch {
                    runCatching {
                        transport.ping(
                            baseUrl = atrUrl,
                            params = mapOf(
                                "cpn" to current.cpn,
                                "ver" to "2",
                                "c" to "WEB_REMIX",
                            ),
                        )
                    }.onFailure { Log.w(TAG, "ATR ping failed for $videoId: ${it.message}") }
                }
            }
        }

        val lastTracked = maxOf(current.reportedSeconds, current.flushingToSeconds)
        if (positionSeconds - lastTracked < WATCHTIME_INTERVAL_SECONDS) return
        current.flushingToSeconds = positionSeconds
        scope.launch {
            runCatching { flushWatchtime(current, positionSeconds, final = false) }
                .onFailure { Log.w(TAG, "Watchtime ping failed for $videoId: ${it.message}") }
        }
    }

    fun onTrackEnded(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        if (!isSyncEnabled() || !isSignedIn() || !isOnline()) return
        scope.launch {
            withContext(NonCancellable) {
                runCatching { flushWatchtime(closing, positionSeconds, final = true) }
                    .onFailure { Log.w(TAG, "Final watchtime ping failed for ${closing.videoId}: ${it.message}") }
            }
        }
    }

    fun reset() {
        session = null
        openingVideoId = null
    }

    private fun canTrack(videoId: String): Boolean =
        VIDEO_ID_REGEX.matches(videoId) &&
            isSignedIn() &&
            isOnline() &&
            isSyncEnabled()

    internal suspend fun openSession(videoId: String) = lock.withLock {
        if (!canTrack(videoId)) return@withLock
        val urls = transport.fetchTracking(videoId) ?: return@withLock
        val cpn = cpnGenerator()
        transport.ping(
            baseUrl = urls.playbackUrl,
            params = mapOf(
                "cpn" to cpn,
                "ver" to "2",
                "c" to "WEB_REMIX",
                "rt" to "0",
            ),
        )
        session = ActiveSession(videoId = videoId, cpn = cpn, urls = urls)
    }

    private suspend fun flushWatchtime(
        target: ActiveSession,
        positionSeconds: Long,
        final: Boolean,
    ) {
        val watchUrl = target.urls.watchtimeUrl ?: return
        if (!final && positionSeconds <= target.reportedSeconds) return
        lock.withLock {
            val st = target.reportedSeconds.coerceAtLeast(0L)
            val et = positionSeconds.coerceAtLeast(st)
            val params = buildMap {
                put("cpn", target.cpn)
                put("ver", "2")
                put("c", "WEB_REMIX")
                put("st", st.toString())
                put("et", et.toString())
                put("cmt", et.toString())
                if (final) put("final", "1")
            }
            transport.ping(baseUrl = watchUrl, params = params)
            target.reportedSeconds = maxOf(target.reportedSeconds, et)
        }
    }

    companion object {
        private const val TAG = "MusicaTracker"
        private const val WATCHTIME_INTERVAL_SECONDS = 30L
        private val VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11}$""")
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val random = SecureRandom()

        fun generateCpn(): String = buildString(16) {
            repeat(16) {
                append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)])
            }
        }
    }
}
