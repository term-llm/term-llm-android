package com.termllm.app

import com.termllm.app.data.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of TermLLMTests/ServerAddressTests.swift. */
class ServerAddressTest {

    @Test
    fun bareHostBecomesHTTPSWithRootPath() {
        val url = ServerAddress.parse("hub.example.com", debug = false)
        assertEquals("https://hub.example.com/", url.toString())
    }

    @Test
    fun explicitMountPrefixIsPreserved() {
        val url = ServerAddress.parse("https://hub.example.com/work/", debug = false)
        assertEquals("/work", ServerAddress.mountPath(url!!))
        assertEquals("https://hub.example.com", ServerAddress.origin(url))
    }

    @Test
    fun plainHTTPIsRejectedOutsideDebugLoopback() {
        assertNull(ServerAddress.parse("http://hub.example.com/", debug = false))
        assertNull(ServerAddress.parse("http://localhost:8766/work/", debug = false))
    }

    @Test
    fun plainHTTPIsAllowedForDebugLoopbackOnly() {
        assertEquals(
            "http://localhost:8766/work/",
            ServerAddress.parse("http://localhost:8766/work/", debug = true).toString()
        )
        // A scheme-less host still defaults to HTTPS, matching ServerAddress.parse.
        assertEquals(
            "https://localhost:8766/work/",
            ServerAddress.parse("localhost:8766/work/", debug = true).toString()
        )
        assertEquals(
            "http://10.0.2.2:8766/work/",
            ServerAddress.parse("http://10.0.2.2:8766/work/", debug = true).toString()
        )
        assertNull(ServerAddress.parse("http://example.com:8766/work/", debug = true))
    }

    @Test
    fun credentialsQueryAndFragmentAreRejected() {
        assertNull(ServerAddress.parse("https://user:pass@hub.example.com/", debug = false))
        assertNull(ServerAddress.parse("https://hub.example.com/?token=abc", debug = false))
        assertNull(ServerAddress.parse("https://hub.example.com/#frag", debug = false))
        assertNull(ServerAddress.parse("   ", debug = false))
    }

    @Test
    fun mountScopingDoesNotLeakToSiblings() {
        val server = ServerAddress.parse("https://hub.example.com/foo", debug = false)!!
        assertTrue(ServerAddress.isWithinMount(ServerAddress.parse("https://hub.example.com/foo/node/a", debug = false)!!, server))
        assertTrue(ServerAddress.isWithinMount(ServerAddress.parse("https://hub.example.com/foo", debug = false)!!, server))
        assertFalse(ServerAddress.isWithinMount(ServerAddress.parse("https://hub.example.com/foobar/node/a", debug = false)!!, server))
        assertFalse(ServerAddress.isWithinMount(ServerAddress.parse("https://other.example.com/foo/node/a", debug = false)!!, server))
    }

    @Test
    fun absoluteServerPathsResolveUnderOrigin() {
        val server = ServerAddress.parse("https://hub.example.com/work", debug = false)!!
        val chat = ServerAddress.absolute(server, "/work/node/shared/chat/2")
        assertEquals("https://hub.example.com/work/node/shared/chat/2", chat.toString())
        assertNull(ServerAddress.absolute(server, "work/node/shared/chat/2"))
    }
}
