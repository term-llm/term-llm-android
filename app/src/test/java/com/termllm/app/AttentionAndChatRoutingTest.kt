package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.AttentionItem
import com.termllm.app.net.SourceApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the acknowledgement gate fixed during the emulator run
 * and for chat-route containment (iOS HubNavigation/DirectNodeClient.resume_path).
 */
class AttentionAndChatRoutingTest {

    private fun item(store: String?, seq: Int) = AttentionItem(
        connectionId = "c1",
        connectionName = "Work",
        nodeId = "shared",
        nodeName = "Work agent",
        sessionId = "session-shared",
        sessionNumber = 1,
        storeInstanceId = store,
        title = "Work result",
        outcome = "completed",
        kind = "unseen",
        attentionSeq = seq,
        resumePath = "/work/node/shared/chat/1",
    )

    @Test
    fun hubInputRequiredRowsWithoutStoreIdentityAreNotAcknowledgeable() {
        // Hub input-required payloads carry no store_instance_id / attention_seq; the
        // iOS HubAggregateStore only builds a seen request when the store id exists.
        assertFalse(item(store = null, seq = 0).canAcknowledge)
        assertFalse(item(store = "", seq = 5).canAcknowledge)
    }

    @Test
    fun inboxAndDirectRowsWithStoreIdentityAreAcknowledgeable() {
        assertTrue(item(store = "store-work", seq = 1).canAcknowledge)
        assertTrue(item(store = "store-direct", seq = 2).canAcknowledge)
    }

    @Test
    fun directNodeResumePathMatchesTheIosBuilder() {
        val direct = Connection(
            id = "d", name = "Laptop", kind = ServerKind.DIRECT_NODE,
            url = "https://node.example/direct/",
        )
        val api = SourceApi(direct, "token") {}
        assertEquals("/direct/chat/2", api.resumePath("session-shared", 2))
        assertEquals("/direct/chat/session-shared", api.resumePath("session-shared", null))
    }

    @Test
    fun chatUrlRejectsPathsOutsideTheConfiguredMount() {
        val hub = Connection(
            id = "h", name = "Work", kind = ServerKind.HUB,
            url = "https://hub.example/foo/",
        )
        val api = SourceApi(hub, "token") {}
        val inside = api.chatUrl("/foo/node/shared/chat/2")
        assertNotNull(inside)
        assertEquals("https://hub.example/foo/node/shared/chat/2", inside.toString())
        // A sibling mount, a different host and a relative path are all rejected.
        assertNull(api.chatUrl("/foobar/node/shared/chat/2"))
        assertNull(api.chatUrl("/other/node/shared/chat/2"))
        assertNull(api.chatUrl("foo/node/shared/chat/2"))
    }
}
