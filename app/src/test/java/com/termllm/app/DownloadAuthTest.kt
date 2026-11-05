package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.listen.DownloadAuth
import com.termllm.app.listen.MediaAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Download credentials are bound to the item's own source: a public URL never
 * inherits a signed-in source's cookie, and a source that is gone never falls
 * back to whichever other source happens to contain the URL.
 */
class DownloadAuthTest {

    private val hub = Connection(
        id = "hub-1", name = "Work", kind = ServerKind.HUB, url = "https://hub.example/foo/",
    )
    private val other = Connection(
        id = "hub-2", name = "Home", kind = ServerKind.HUB, url = "https://hub.example/bar/",
    )
    private val mediaAuth = MediaAuth(
        { listOf(hub, other) },
        { id -> if (id == "hub-1") "tok-123" else if (id == "hub-2") "tok-456" else null },
    )
    private val auth = DownloadAuth(mediaAuth, { listOf(hub, other) })

    private val insideMount = "https://hub.example/foo/media/episode.mp3"

    @Test
    fun anItemWithoutASourceNeverGetsACredential() {
        assertNull(
            "a public or agent-added URL must not inherit a source cookie",
            auth.credentialFor(insideMount, null),
        )
        assertNull(auth.credentialFor(insideMount, ""))
    }

    @Test
    fun theOwningSourceSignsItsOwnMedia() {
        val credential = auth.credentialFor(insideMount, "hub-1") ?: error("hub-1 must sign its own mount")
        assertEquals("Cookie", credential.first)
        assertEquals("term_llm_hub_token=tok-123; Path=/foo/", credential.second)
    }

    @Test
    fun anotherSourceNeverSignsSomebodyElsesMount() {
        assertNull(
            "hub-2 must not sign a hub-1 URL",
            auth.credentialFor(insideMount, "hub-2"),
        )
    }

    @Test
    fun aRemovedSourceDoesNotFallBackToAnyOtherSource() {
        assertNull(
            "a stale source id must not borrow a different source's credential",
            DownloadAuth(mediaAuth, { listOf(hub, other) }).credentialFor(insideMount, "hub-removed"),
        )
    }

    @Test
    fun publicHostsAreNeverSigned() {
        assertNull(auth.credentialFor("https://cdn.public.example/episode.mp3", "hub-1"))
    }

    @Test
    fun providerIsOnlyCreatedForSourcedItems() {
        assertNull(auth.provider(null))
        assertNull(auth.provider(""))
        val provider = auth.provider("hub-1") ?: error("a sourced item must get a provider")
        assertEquals("Cookie", provider.invoke(insideMount)!!.first)
        assertNull(provider.invoke("https://cdn.public.example/episode.mp3"))
    }

    @Test
    fun noMediaAuthMeansNoCredentialAtAll() {
        val none = DownloadAuth(null, { listOf(hub) })
        assertNull(none.credentialFor(insideMount, "hub-1"))
        assertNull(none.provider("hub-1"))
        assertNull(none.redirectGuard("https://hub.example"))
    }
}
