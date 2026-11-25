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
}
