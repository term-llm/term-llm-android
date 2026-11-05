package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.listen.MediaAuth
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Media credentials are source-bound: attached only inside the owning mount,
 * never to a public host, and dropped when a redirect leaves that origin.
 */
class MediaAuthTest {

    private val hub = Connection(
        id = "hub-1", name = "Work", kind = ServerKind.HUB, url = "https://hub.example/foo/",
    )
    private val auth = MediaAuth({ listOf(hub) }, { id -> if (id == "hub-1") "tok-123" else null })

    private lateinit var server: MockWebServer
    private lateinit var otherServer: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        otherServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        otherServer.shutdown()
    }

    @Test
    fun credentialIsAttachedInsideTheOwningMount() {
        val credential = auth.credentialFor("https://hub.example/foo/media/episode.mp3", "hub-1")
        assertEquals("Cookie", credential!!.header)
        assertEquals("term_llm_hub_token=tok-123; Path=/foo/", credential.value)
        assertEquals("https://hub.example", credential.origin)
    }

    @Test
    fun publicHostNeverReceivesTheCredential() {
        assertNull(auth.credentialFor("https://cdn.public.example/episode.mp3", "hub-1"))
        assertNull(auth.credentialFor("https://hub.example/other/episode.mp3", "hub-1"))
        assertNull(auth.credentialFor("https://hub.example/foobar/episode.mp3", "hub-1"))
    }

    @Test
    fun redirectLeavingTheOriginDropsTheCookie() {
        val serverOrigin = "http://" + server.hostName + ":" + server.port
        val guard = auth.redirectGuard(serverOrigin)!!
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/x.mp3"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(guard)
            .build()
        // Same-origin request keeps the credential.
        client.newCall(
            Request.Builder().url(server.url("/media/episode.mp3"))
                .header("Cookie", "term_llm_hub_token=tok-123")
                .build()
        ).execute().close()
        assertEquals("term_llm_hub_token=tok-123", server.takeRequest().getHeader("Cookie"))

        // Cross-origin request (as produced by a redirect) has it stripped.
        val sameOrigin = "http://" + otherServer.hostName + ":" + otherServer.port
        val crossGuard = auth.redirectGuard("http://some-other-origin.example")!!
        val crossClient = OkHttpClient.Builder().followRedirects(false).addInterceptor(crossGuard).build()
        otherServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        assertEquals("sanity: origin string matches", sameOrigin, "http://" + otherServer.hostName + ":" + otherServer.port)
        crossClient.newCall(
            Request.Builder().url(otherServer.url("/x.mp3"))
                .header("Cookie", "term_llm_hub_token=tok-123")
                .header("Authorization", "Bearer tok-123")
                .build()
        ).execute().close()
        val recorded = otherServer.takeRequest()
        assertNull("cookie leaked across origins", recorded.getHeader("Cookie"))
        assertNull("authorization leaked across origins", recorded.getHeader("Authorization"))
    }
}
