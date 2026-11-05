package com.termllm.app

import com.termllm.app.net.ApiException
import com.termllm.app.net.ScopedCookieJar
import com.termllm.app.net.SourceHttp
import com.termllm.app.net.UnauthorizedException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transport invariants ported from the iOS HubClient/DirectNodeClient transport:
 * bearer on every native request, mount-relative path joining, redirects blocked,
 * 401/403 revoking local state, and mount-scoped cookie isolation.
 */
class SourceHttpTest {

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

    private fun mountUrl(path: String = "/work/") =
        (server.url("/").toString().trimEnd('/') + path).toHttpUrl()

    @Test
    fun joinsPathsUnderTheConfiguredMountAndSendsBearer() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"nodes":[]}"""))
        val http = SourceHttp(mountUrl(), "secret-token") {}
        val body = http.get("/api/nodes")
        assertEquals("""{"nodes":[]}""", body)
        val recorded = server.takeRequest()
        assertEquals("/work/api/nodes", recorded.path)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Accept"))
    }

    @Test
    fun mutationsCarryOrigin() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cleared":1}"""))
        val http = SourceHttp(mountUrl(), "t") {}
        http.post("/api/attention/seen", """{"session_id":"s"}""")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(server.url("/").toString().trimEnd('/'), recorded.getHeader("Origin"))
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
        val http = SourceHttp(mountUrl(), "t") {}
        val error = runCatching { http.get("/api/nodes") }.exceptionOrNull()
        assertTrue(error is ApiException && error.status == 302)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorizedRevokesLocalState() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"no"}"""))
        var revoked = false
        val http = SourceHttp(mountUrl(), "t") { revoked = true }
        val error = runCatching { http.get("/api/nodes") }.exceptionOrNull()
        assertTrue(error is UnauthorizedException)
        assertTrue(revoked)
    }

    @Test
    fun cookiesAreScopedToTheMountAndSiblingPathsAreExcluded() {
        val url = mountUrl()
        val jar = ScopedCookieJar(url, "/work")
        val cookie = okhttp3.Cookie.parse(
            url.newBuilder().encodedPath("/work/").build(),
            "term_llm_hub_token=abc; Path=/work/; HttpOnly"
        )!!
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals(1, jar.loadForRequest(url.newBuilder().encodedPath("/work/node/shared/").build()).size)
        assertEquals(0, jar.loadForRequest(url.newBuilder().encodedPath("/other/").build()).size)
        assertEquals("abc", jar.cookie("term_llm_hub_token", "/work/")!!.value)
    }

    @Test
    fun errorBodiesSurfaceServerMessage() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Synthetic marker mismatch"}"""))
        val http = SourceHttp(mountUrl(), "t") {}
        val error = runCatching { http.post("/api/attention/seen", "{}") }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(409, (error as ApiException).status)
        assertEquals("Synthetic marker mismatch", error.message)
    }
}
