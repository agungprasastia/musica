package com.music.musica.data.innertube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaybackTrackerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun CoroutineScope.awaitChildren() {
        coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    @Test
    fun emitsPlaybackAtrPeriodicWatchtimeAndFinalPingWithConsistentCpn() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val playerPayload = """
            {
              "playbackTracking": {
                "videostatsPlaybackUrl": { "baseUrl": "$base/api/stats/playback" },
                "atrUrl": { "baseUrl": "$base/api/stats/atr", "elapsedMediaTimeSeconds": 5 },
                "videostatsWatchtimeUrl": { "baseUrl": "$base/api/stats/watchtime" }
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(playerPayload))
        server.enqueue(MockResponse().setResponseCode(204)) // playback ping
        server.enqueue(MockResponse().setResponseCode(204)) // atr ping
        server.enqueue(MockResponse().setResponseCode(204)) // watchtime 30s
        server.enqueue(MockResponse().setResponseCode(204)) // final watchtime

        val trackerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val client = MusicaInnertubeClient(
                baseUrl = "$base/youtubei/v1",
                cookieProvider = { "SAPISID=test_sid; SID=abc" },
            )
            val tracker = PlaybackTracker(
                transport = InnertubeTrackingTransport(client),
                isSignedIn = { true },
                isOnline = { true },
                isSyncEnabled = { true },
                scope = trackerScope,
                cpnGenerator = { "FIXED_CPN_123456" },
            )

            val videoId = "dQw4w9WgXcQ"
            tracker.onPlaying(videoId)
            trackerScope.awaitChildren()

            tracker.onProgress(videoId, positionSeconds = 6L)
            trackerScope.awaitChildren()

            tracker.onProgress(videoId, positionSeconds = 32L)
            trackerScope.awaitChildren()

            tracker.onTrackEnded(positionSeconds = 95L)
            trackerScope.awaitChildren()

            val reqPlayer = server.takeRequest(2, TimeUnit.SECONDS)
            val reqPlayback = server.takeRequest(2, TimeUnit.SECONDS)
            val reqAtr = server.takeRequest(2, TimeUnit.SECONDS)
            val reqWatch30 = server.takeRequest(2, TimeUnit.SECONDS)
            val reqWatchFinal = server.takeRequest(2, TimeUnit.SECONDS)

            assertNotNull(reqPlayer)
            assertTrue(reqPlayer!!.path!!.startsWith("/youtubei/v1/player"))

            assertNotNull(reqPlayback)
            assertTrue(reqPlayback!!.path!!.startsWith("/api/stats/playback"))
            assertTrue(reqPlayback.path!!.contains("cpn=FIXED_CPN_123456"))

            assertNotNull(reqAtr)
            assertTrue(reqAtr!!.path!!.startsWith("/api/stats/atr"))
            assertTrue(reqAtr.path!!.contains("cpn=FIXED_CPN_123456"))

            assertNotNull(reqWatch30)
            assertTrue(reqWatch30!!.path!!.startsWith("/api/stats/watchtime"))
            assertTrue(reqWatch30.path!!.contains("cpn=FIXED_CPN_123456"))
            assertTrue(reqWatch30.path!!.contains("st=0"))
            assertTrue(reqWatch30.path!!.contains("et=32"))

            assertNotNull(reqWatchFinal)
            assertTrue(reqWatchFinal!!.path!!.startsWith("/api/stats/watchtime"))
            assertTrue(reqWatchFinal.path!!.contains("cpn=FIXED_CPN_123456"))
            assertTrue(reqWatchFinal.path!!.contains("st=32"))
            assertTrue(reqWatchFinal.path!!.contains("et=95"))
            assertTrue(reqWatchFinal.path!!.contains("final=1"))
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun guardsRejectInvalidIdsGuestsOfflineAndPrivacyOptOut() = runBlocking {
        var fetchCount = 0
        var pingCount = 0
        val spyTransport = object : PlaybackTrackingTransport {
            override suspend fun fetchTracking(videoId: String): PlaybackTrackingUrls {
                fetchCount++
                return PlaybackTrackingUrls("https://example/p", "https://example/a", "https://example/w")
            }

            override suspend fun ping(baseUrl: String, params: Map<String, String>): Int {
                pingCount++
                return 204
            }
        }

        val trackerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            // 1. Non-11-char ID (e.g. local/invalid)
            val tInvalid = PlaybackTracker(spyTransport, { true }, { true }, { true }, trackerScope)
            tInvalid.onPlaying("local_track_id_123")
            tInvalid.onPlaying("short")
            trackerScope.awaitChildren()
            assertEquals(0, fetchCount)

            // 2. Guest mode
            val tGuest = PlaybackTracker(spyTransport, { false }, { true }, { true }, trackerScope)
            tGuest.onPlaying("dQw4w9WgXcQ")
            trackerScope.awaitChildren()
            assertEquals(0, fetchCount)

            // 3. Offline mode
            val tOffline = PlaybackTracker(spyTransport, { true }, { false }, { true }, trackerScope)
            tOffline.onPlaying("dQw4w9WgXcQ")
            trackerScope.awaitChildren()
            assertEquals(0, fetchCount)

            // 4. User disabled privacy toggle
            val tOptOut = PlaybackTracker(spyTransport, { true }, { true }, { false }, trackerScope)
            tOptOut.onPlaying("dQw4w9WgXcQ")
            trackerScope.awaitChildren()
            assertEquals(0, fetchCount)
            assertEquals(0, pingCount)
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun rapidTrackChangeCancelsStaleOpenSession() = runBlocking {
        val pingedUrls = mutableListOf<String>()
        val slowTransport = object : PlaybackTrackingTransport {
            override suspend fun fetchTracking(videoId: String): PlaybackTrackingUrls {
                kotlinx.coroutines.delay(if (videoId == "FIRST111111") 120L else 10L)
                return PlaybackTrackingUrls(
                    playbackUrl = "https://example/playback/$videoId",
                    atrUrl = null,
                    watchtimeUrl = null,
                )
            }

            override suspend fun ping(baseUrl: String, params: Map<String, String>): Int {
                pingedUrls += baseUrl
                return 204
            }
        }

        val trackerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val tracker = PlaybackTracker(slowTransport, { true }, { true }, { true }, trackerScope)
            tracker.onPlaying("FIRST111111")
            tracker.onPlaying("SECOND22222")
            trackerScope.awaitChildren()

            assertEquals(listOf("https://example/playback/SECOND22222"), pingedUrls)
        } finally {
            trackerScope.cancel()
        }
    }
}
