package com.termllm.app.listen

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Authenticated media, scoped exactly like the iOS Listen transfer:
 * a source's credential is attached only to URLs inside that source's own mount,
 * a plain public URL never receives it, and any redirect that leaves the source
 * origin drops it before the follow-up request is sent.
 */
class MediaAuth(private val connections: () -> List<Connection>, private val tokenFor: (String) -> String?) {

    data class Credential(val header: String, val value: String, val origin: String)

    fun credentialFor(url: String, sourceId: String?): Credential? {
        val target = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        val candidates = connections().filter { it.id == sourceId }.ifEmpty { connections() }
        for (connection in candidates) {
            val server = runCatching { connection.url.toHttpUrl() }.getOrNull() ?: continue
            if (ServerAddress.isWithinMount(target, server)) {
                val token = tokenFor(connection.id) ?: continue
                val cookieName = if (connection.kind == com.termllm.app.data.ServerKind.HUB) {
                    "term_llm_hub_token"
                } else {
                    "term_llm_token"
                }
                val mount = ServerAddress.mountPath(server)
                val path = if (mount.isEmpty()) "/" else mount + "/"
                return Credential("Cookie", "$cookieName=$token; Path=$path", ServerAddress.origin(server))
            }
        }
        return null
    }

    /** Drops Cookie/Authorization as soon as a redirect leaves the original origin. */
    fun redirectGuard(originalOrigin: String) = Interceptor { chain ->
        val request = chain.request()
        if (ServerAddress.origin(request.url) == originalOrigin) {
            chain.proceed(request)
        } else {
            val cleaned = request.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Authorization")
                .build()
            chain.proceed(cleaned)
        }
    }

    companion object {
        const val HEADER_COOKIE = "Cookie"
        const val HEADER_AUTH = "Authorization"
    }
}
