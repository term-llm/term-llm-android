package com.termllm.app.listen

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/**
 * Download credentials, bound to the item's own source.
 *
 * The queue item carries the connection it was added from ([ListenItem.sourceId]).
 * A download for that item may only use that connection's credential, and an item
 * with no source (a public podcast URL, an agent-added link) never receives one —
 * even when the URL happens to sit inside a signed-in mount. Redirects leaving the
 * source origin keep dropping the cookie through [MediaAuth.redirectGuard].
 */
class DownloadAuth(
    private val mediaAuth: MediaAuth?,
    private val connections: () -> List<Connection> = { emptyList() },
) {

    /** Credential to attach to `url`, or null when this exact source may not sign it. */
    fun credentialFor(url: String, sourceId: String?): Pair<String, String>? {
        val auth = mediaAuth ?: return null
        if (sourceId.isNullOrBlank()) return null
        val known = connections()
        if (known.isNotEmpty()) {
            // A source that no longer exists (removed, or signed out) must not fall
            // back to "whichever source contains the URL".
            val owner = known.firstOrNull { it.id == sourceId } ?: return null
            val ownerUrl = owner.url.toHttpUrlOrNull() ?: return null
            val target = url.toHttpUrlOrNull() ?: return null
            if (!ServerAddress.isWithinMount(target, ownerUrl)) return null
        }
        val credential = auth.credentialFor(url, sourceId) ?: return null
        if (credential.value.isEmpty()) return null
        return credential.header to credential.value
    }

    fun redirectGuard(origin: String): Interceptor? = mediaAuth?.redirectGuard(origin)

    /** Per-item credential supplier for [RangeTransfer], or null for public media. */
    fun provider(sourceId: String?): ((String) -> Pair<String, String>?)? {
        if (mediaAuth == null || sourceId.isNullOrBlank()) return null
        val scoped = sourceId
        return { url -> credentialFor(url, scoped) }
    }
}
