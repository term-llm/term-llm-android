package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.AgentSessionPatch
import com.termllm.app.net.SourceApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cursor paging, per-agent search and session patching against a direct node.
 * Mirrors the iOS DirectNodeClient contract: `limit`/`cursor` query parameters,
 * `next_cursor` in the page payload, `q`+`limit` for search, and PATCH bodies
 * carrying only the fields the user changed.
 */
class AgentPagingTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SourceApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val connection = Connection(
            id = "test",
            name = "Test node",
            kind = ServerKind.DIRECT_NODE,
            url = server.url("/").toString(),
        )
        api = SourceApi(connection, "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun firstPageSendsLimitAndParsesCursor() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions":[{"id":"s1","short_title":"First","pinned":true,"unseen":true}],"next_cursor":"c2"}"""
            )
        )
        val page = kotlinx.coroutines.runBlocking { api.sessions(null, 1) }
        val recorded = server.takeRequest()
        assertEquals("/v1/sessions?scope=all&limit=1", recorded.path)
        assertEquals("c2", page.nextCursor)
        assertEquals(1, page.sessions.size)
        assertEquals(true, page.sessions[0].pinned)
    }

    @Test
    fun nextPageSendsEncodedCursorAndLastPageHasNullCursor() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions":[{"id":"s2","short_title":"Second"}],"next_cursor":null}"""
            )
        )
        val page = kotlinx.coroutines.runBlocking { api.sessions(null, 30, "c2/with space") }
        val recorded = server.takeRequest()
        assertEquals("/v1/sessions?scope=all&limit=30&cursor=c2%2Fwith%20space", recorded.path)
        assertNull(page.nextCursor)
        assertEquals("s2", page.sessions[0].id)
    }

    @Test
    fun searchEncodesQueryAndClampsLimit() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions":[{"id":"s1","short_title":"Hit","snippet":"read_file path"}]}"""
            )
        )
        val hits = kotlinx.coroutines.runBlocking { api.searchSessions(null, "read file & more", 500) }
        val recorded = server.takeRequest()
        assertEquals("/v1/sessions/search?q=read%20file%20%26%20more&limit=50", recorded.path)
        assertEquals("read_file path", hits.sessions[0].snippet)
    }

    @Test
    fun pinnedReorderSendsSessionIdsAndReadsRanks() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"pinned":[{"id":"s2","pin_order":1},{"id":"s1","pin_order":2}]}"""
            )
        )
        val order = kotlinx.coroutines.runBlocking { api.reorderPinnedSessions(null, listOf("s2", "s1")) }
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/v1/sessions/pinned-order", recorded.path)
        assertEquals("""{"session_ids":["s2","s1"]}""", recorded.body.readUtf8())
        assertEquals(listOf("s2", "s1"), order.pinned.map { it.id })
        assertEquals(1L, order.pinned[0].pinOrder)
    }

    @Test
    fun patchSendsOnlyChangedFields() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        kotlinx.coroutines.runBlocking { api.patchSession(null, "s1", AgentSessionPatch(pinned = true)) }
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/v1/sessions/s1", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"pinned\":true"))
        assertEquals(false, body.contains("archived"))
        assertEquals(false, body.contains("name"))
    }
}
