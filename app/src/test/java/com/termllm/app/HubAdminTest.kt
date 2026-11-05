package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.NodeFormData
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
 * Hub administration wire contract, ported from `HubClient`'s `HubServing`
 * conformance: `/api/nodes/test`, `POST /api/nodes`, `DELETE /api/nodes/{id}`,
 * `PATCH /api/nodes/order`, `GET /api/delegations`. Mutations must carry Origin.
 */
class HubAdminTest {

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
    fun testNodePostsTheFormAndReadsStatus() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"status":{"reachable":true,"state":"ok","latency_ms":12,"version":"0.9.93"}}"""
        ))
        val status = runBlocking { hub.testHubNode(NodeFormData("Alpha", "https://a.example/", "secret")) }.status
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/nodes/test", recorded.path)
        assertEquals("""{"name":"Alpha","url":"https://a.example/","token":"secret"}""", recorded.body.readUtf8())
        assertEquals(server.url("/").toString().trimEnd('/'), recorded.getHeader("Origin"))
        assertTrue(status!!.reachable)
        assertEquals("0.9.93", status.version)
    }

    @Test
    fun addNodeReturnsTheNewIdAndWarning() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"node-9","warning":"reachability unchecked"}"""))
        val added = runBlocking { hub.addHubNode(NodeFormData("Beta", "https://b.example/", "")) }
        assertEquals("/api/nodes", server.takeRequest().path)
        assertEquals("node-9", added.id)
        assertEquals("reachability unchecked", added.warning)
    }

    @Test
    fun removeNodeUsesDeleteAndEncodesTheId() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"removed":"node 9/x"}"""))
        val removed = runBlocking { hub.removeHubNode("node 9/x") }
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/nodes/node%209%2Fx", recorded.path)
        assertEquals("node 9/x", removed.removed)
    }

    @Test
    fun reorderNodesSendsNodeIdsAndReturnsTheOrder() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"node_ids":["b","a"]}"""))
        val order = runBlocking { hub.reorderHubNodes(listOf("b", "a")) }
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/nodes/order", recorded.path)
        assertEquals("""{"node_ids":["b","a"]}""", recorded.body.readUtf8())
        assertEquals(listOf("b", "a"), order.nodeIds)
    }

    @Test
    fun delegationsDecodeTheReadOnlyList() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"delegations":[{"id":"d1","origin_node":"a","target_node":"b","status":"completed","depth":1,"created_at":"2026-11-04T08:00:00Z","updated_at":"2026-11-04T08:01:00Z","agent_name":"helper","prompt":"do it"}]}"""
        ))
        val list = runBlocking { hub.delegations() }.delegations
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, list.size)
        assertEquals("helper", list[0].agentName)
        assertEquals("completed", list[0].status)
        assertEquals(1, list[0].depth)
    }

    @Test
    fun directNodesRefuseAdminRoutesWithoutTouchingTheNetwork() {
        val failure = runCatching { runBlocking { direct.delegations() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, server.requestCount)
        assertFalse(direct.isHub)
    }
}
