package com.termllm.app

import com.termllm.app.model.AgentSessionPage
import com.termllm.app.model.AttentionResponse
import com.termllm.app.model.DirectAttentionPage
import com.termllm.app.model.DirectNodeSessionsStatus
import com.termllm.app.model.NodesResponse
import com.termllm.app.net.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the wire bodies the repository's protocol fixture serves, using the
 * field names the iOS Codable models declare (core 2496b5fd).
 */
class WireDecodeTest {

    @Test
    fun decodesHubNodeDashboard() {
        val json = """
        {"nodes": [{"id": "shared", "name": "Work agent", "source": "local", "connection": "reverse",
          "url": "https://private-node.invalid/", "base_path": "/private/",
          "proxy_path": "/work/node/shared/", "new_session_path": "/work/node/shared/?new=1",
          "has_token": true,
          "status": {"reachable": true, "state": "ok", "latency_ms": 12, "version": "protocol-fixture",
                     "agent": "fixture", "capabilities": ["attention"]},
          "sessions": {"count_label": "2 sessions", "active_count": 1, "input_required_count": 1,
                       "unseen_count": 1, "attention_capability": "supported",
                       "active": [{"id": "active-shared", "number": 2, "short_title": "Work needs input",
                                   "long_title": "", "pinned": false, "active_run": true,
                                   "interaction_required": true, "pending_interaction_count": 1,
                                   "pending_interaction_kinds": ["approval"],
                                   "last_message_at": 1792735200000, "message_count": 4,
                                   "resume_path": "/work/node/shared/chat/2"}],
                       "recent": []}, "diagnostics": []}],
         "resolver_error": null}
        """.trimIndent()
        val response = Wire.json.decodeFromString(NodesResponse.serializer(), json)
        val node = response.nodes.single()
        assertEquals("shared", node.id)
        assertTrue(node.status!!.reachable)
        assertEquals(12, node.status!!.latencyMs)
        assertEquals(1, node.sessions!!.inputRequiredCount)
        assertEquals("/work/node/shared/chat/2", node.sessions!!.active.single().resumePath)
        assertTrue(node.sessions!!.active.single().interactionRequired!!)
        assertNull(response.resolverError)
    }

    @Test
    fun decodesHubAttentionPage() {
        val json = """
        {"total_running": 1, "total_input_required": 1, "total_unseen": 1,
         "nodes": [{"node_id": "shared", "node_name": "Work agent", "capability_state": "supported",
                    "stale": false, "last_success_at": "2026-10-23T06:00:00Z", "running_count": 1,
                    "input_required_count": 1, "unseen_count": 1, "has_green_indicator": true}],
         "input_required": [{"node_id": "shared", "node_name": "Work agent", "session_id": "active-shared",
                             "session_number": 2, "title": "Work needs input", "pending_interaction_count": 1,
                             "pending_interaction_kinds": ["approval"], "required_since": "2026-10-23T06:00:00Z",
                             "resume_path": "/work/node/shared/chat/2", "stale": false}],
         "inbox": [{"node_id": "shared", "node_name": "Work agent", "store_instance_id": "store-work",
                    "session_id": "session-shared", "session_number": 1, "title": "Work result",
                    "outcome": "completed", "terminal_at": "2026-10-23T06:00:00Z", "attention_seq": 1,
                    "resume_path": "/work/node/shared/chat/1"}],
         "has_more": false}
        """.trimIndent()
        val response = Wire.json.decodeFromString(AttentionResponse.serializer(), json)
        assertEquals(1, response.totalInputRequired)
        assertEquals("store-work", response.inbox.single().storeInstanceId)
        assertEquals(1, response.inbox.single().attentionSeq)
        assertFalse(response.hasMore)
    }

    @Test
    fun decodesDirectNodeSessionsAndAttention() {
        val statusJson = """
        {"sessions": [{"id": "active-shared", "number": 2, "short_title": "Laptop needs input",
                       "long_title": "", "pinned": false, "active_run": true,
                       "interaction_required": true, "pending_interaction_count": 1,
                       "pending_interaction_kinds": ["approval"], "last_message_at": 1792735200000,
                       "message_count": 4, "attention_store_instance_id": "store-direct",
                       "attention_seq": 1, "attention_unseen": true}]}
        """.trimIndent()
        val status = Wire.json.decodeFromString(DirectNodeSessionsStatus.serializer(), statusJson)
        val entry = status.sessions.single()
        assertEquals("store-direct", entry.attentionStoreInstanceId)
        assertEquals(1L, entry.attentionSeq)
        assertTrue(entry.interactionRequired!!)

        val attentionJson = """
        {"protocol_version": 1, "store_instance_id": "store-direct", "snapshot_version": 3,
         "items": [{"session_id": "active-shared", "session_number": 2, "kind": "input_required",
                    "attention_seq": 2, "short_title": "Laptop needs input", "outcome": "running",
                    "pending_interaction_count": 1, "pending_interaction_kinds": ["approval"]}],
         "next_cursor": null, "has_more": false}
        """.trimIndent()
        val page = Wire.json.decodeFromString(DirectAttentionPage.serializer(), attentionJson)
        assertEquals("store-direct", page.storeInstanceId)
        assertEquals(2L, page.items.single().attentionSeq)
    }

    @Test
    fun decodesAgentSessionListingToleratingUnknownFields() {
        val json = """
        {"sessions": [{"id": "s1", "number": 3, "name": "Work Kyoto day plan", "short_title": "Kyoto",
                       "project_name": "Home projects", "pinned": true, "pin_order": 1,
                       "last_message_at": 1792735200000, "attention_unseen": false,
                       "some_future_field": {"a": 1}}],
         "next_cursor": "abc"}
        """.trimIndent()
        val page = Wire.json.decodeFromString(AgentSessionPage.serializer(), json)
        assertEquals("s1", page.sessions.single().id)
        assertEquals("abc", page.nextCursor)
        assertEquals(3L, page.sessions.single().number)
    }
}
