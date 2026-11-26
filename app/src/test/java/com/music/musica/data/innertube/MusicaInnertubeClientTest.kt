package com.music.musica.data.innertube

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicaInnertubeClientTest {

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

    @Test
    fun browseHome_sendsWebRemixContextLocaleAndSapisidAuthWhenSignedIn() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"responseContext":{"visitorData":"VIS_99"},"contents":{}}"""),
        )

        var capturedVisitor: String? = null
        val client = MusicaInnertubeClient(
            baseUrl = server.url("/youtubei/v1").toString(),
            cookieProvider = { "SAPISID=my_secret_sid; SID=123" },
            visitorDataProvider = { "OLD_VIS" },
            onVisitorDataUpdated = { capturedVisitor = it },
            languageProvider = { "id" },
            regionProvider = { "ID" },
        )

        client.browseHome()

        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected HTTP request to /youtubei/v1/browse", req)
        assertTrue(req!!.path!!.startsWith("/youtubei/v1/browse"))
        val auth = req.getHeader("Authorization")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("SAPISIDHASH "))
        assertEquals("https://music.youtube.com", req.getHeader("X-Origin"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"clientName\":\"WEB_REMIX\""))
        assertTrue(body.contains("\"hl\":\"id\""))
        assertTrue(body.contains("\"gl\":\"ID\""))
        assertEquals("VIS_99", capturedVisitor)
    }

    @Test
    fun browseHome_continuationSendsTokenDirectlyWithoutRedundantFirstRequest() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"continuationContents":{"sectionListContinuation":{"contents":[]}}}"""),
        )
        val client = MusicaInnertubeClient(
            baseUrl = server.url("/youtubei/v1").toString(),
        )

        client.browseHome(continuation = "NEXT_PAGE_TOKEN")

        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req)
        assertTrue(req!!.path!!.contains("continuation=NEXT_PAGE_TOKEN"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun remotePlaylistAndLibraryMutationsSendExpectedEndpointsAndPayloads() = runTest {
        repeat(6) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"status":"STATUS_SUCCEEDED"}"""),
            )
        }
        val client = MusicaInnertubeClient(
            baseUrl = server.url("/youtubei/v1").toString(),
            cookieProvider = { "SAPISID=my_sid; SID=abc" },
        )

        assertTrue(client.renamePlaylist("VLPL_123", "Renamed Mix", "Chill"))
        assertTrue(client.removeSongFromPlaylist("VLPL_123", "vid_1", "set_99"))
        assertTrue(client.movePlaylistItem("VLPL_123", "set_99", "set_01"))
        assertTrue(client.deletePlaylist("VLPL_123"))
        assertTrue(client.setSubscribed("UC_artist", subscribe = true))
        assertTrue(client.ratePlaylist("VLPL_456", like = false))

        val rRename = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(rRename.path!!.startsWith("/youtubei/v1/browse/edit_playlist"))
        val renameBody = rRename.body.readUtf8()
        assertTrue(renameBody.contains("\"playlistId\":\"PL_123\""))
        assertTrue(renameBody.contains("ACTION_SET_PLAYLIST_NAME"))

        val rRemove = server.takeRequest(2, TimeUnit.SECONDS)!!
        val removeBody = rRemove.body.readUtf8()
        assertTrue(removeBody.contains("ACTION_REMOVE_VIDEO"))
        assertTrue(removeBody.contains("set_99"))

        val rMove = server.takeRequest(2, TimeUnit.SECONDS)!!
        val moveBody = rMove.body.readUtf8()
        assertTrue(moveBody.contains("ACTION_MOVE_VIDEO_BEFORE"))
        assertTrue(moveBody.contains("set_01"))

        val rDelete = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(rDelete.path!!.startsWith("/youtubei/v1/playlist/delete"))
        assertTrue(rDelete.body.readUtf8().contains("\"playlistId\":\"PL_123\""))

        val rSub = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(rSub.path!!.startsWith("/youtubei/v1/subscription/subscribe"))
        assertTrue(rSub.body.readUtf8().contains("UC_artist"))

        val rRate = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertTrue(rRate.path!!.startsWith("/youtubei/v1/like/removelike"))
        assertTrue(rRate.body.readUtf8().contains("\"playlistId\":\"PL_456\""))
    }
}
