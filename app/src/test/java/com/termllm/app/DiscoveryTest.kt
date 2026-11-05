package com.termllm.app

import com.termllm.app.model.ServerRole
import com.termllm.app.net.Discovery
import com.termllm.app.net.DiscoveryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Role classification ported from HubServerRole.classify. The payloads are the
 * exact bodies the repo's phone-hub protocol fixture serves on /healthz.
 */
class DiscoveryTest {

    @Test
    fun hubHealthIsHub() {
        val body = """{"status": "ok", "version": "protocol-fixture", "agent": "Work",
            "capabilities": ["attention"], "role": "hub"}"""
        assertEquals(ServerRole.HUB, Discovery.classify(body))
    }

    @Test
    fun nodeHealthWithoutRoleIsDirectNode() {
        val body = """{"status": "ok", "version": "protocol-fixture", "agent": "direct-node",
            "capabilities": ["attention"]}"""
        assertEquals(ServerRole.DIRECT_NODE, Discovery.classify(body))
    }

    @Test
    fun explicitNodeRoleIsDirectNode() {
        assertEquals(ServerRole.DIRECT_NODE, Discovery.classify("""{"status":"ok","role":"node"}"""))
    }

    @Test
    fun unhealthyOrUnknownServerIsNotGuessed() {
        assertThrows(DiscoveryException::class.java) { Discovery.classify("""{"status":"error","error":"down"}""") }
        assertThrows(DiscoveryException::class.java) { Discovery.classify("""{"status":"ok","role":"something"}""") }
        assertThrows(DiscoveryException::class.java) { Discovery.classify("<html>nope</html>") }
    }
}
