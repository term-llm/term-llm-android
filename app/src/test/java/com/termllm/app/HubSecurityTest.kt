package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.net.SourceApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hub security wire contract, ported from the passkey handler in the core
 * (`/api/auth/session`, `/api/auth/credentials`, `/api/auth/logout`,
 * `/api/auth/sessions/revoke-others`). Mutations must carry Origin.
 */
class HubSecurityTest {

    private lateinit var server: MockWebServer
    private lateinit var hub: SourceApi
    private lateinit var direct: SourceApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        hub = SourceApi(Connection(id = "hub", name = "Hub", kind = ServerKind.HUB, url = base), "tok")
        direct = SourceApi(Connection(id = "node", name = "Node", kind = ServerKind.DIRECT_NODE, url = base), "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sessionReportsAdministratorAndCounts() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"administrator":"Ada","session":{"session_id":"s-1","last_seen_at":"2026-11-04T10:00:00Z","peer":"10.0.0.2"},"recently_authenticated":true,"active_sessions":3}"""
        ))
        val state = runBlocking { hub.hubSecurity() }
        assertEquals("GET", server.takeRequest().method)
        assertEquals("Ada", state.administrator)
        assertEquals(3, state.activeSessions)
        assertTrue(state.recentlyAuthenticated)
        assertEquals("s-1", state.session?.sessionId)
        assertEquals("10.0.0.2", state.session?.peer)
    }

    @Test
    fun credentialsDecodeThePasskeyList() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"credentials":[{"record_id":"r1","display_name":"Laptop","transports":["internal"],"last_used_at":"2026-11-01T00:00:00Z"}]}"""
        ))
        val list = runBlocking { hub.hubCredentials() }
        assertEquals(1, list.size)
        assertEquals("Laptop", list[0].displayName)
        assertEquals(listOf("internal"), list[0].transports)
    }

    @Test
    fun logoutPostsJsonWithOrigin() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        runBlocking { hub.hubLogout() }
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/logout", recorded.path)
        assertEquals(server.url("/").toString().trimEnd('/'), recorded.getHeader("Origin"))
    }

    @Test
    fun revokeOthersReportsHowManySessionsWent() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked":2}"""))
        val revoked = runBlocking { hub.hubRevokeOtherSessions() }
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/sessions/revoke-others", recorded.path)
        assertEquals(2, revoked)
    }

    @Test
    fun directNodesRefuseSecurityRoutesWithoutTouchingTheNetwork() {
        val failure = runCatching { runBlocking { direct.hubSecurity() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, server.requestCount)
        assertFalse(direct.isHub)
    }
}
