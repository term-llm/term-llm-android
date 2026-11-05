package com.termllm.app.data

import okhttp3.HttpUrl
import java.net.URI

/**
 * Port of iOS `HubConnectionImport`: an untrusted pairing link
 * (`termllm://connect?server=https://…`) carries a server address, never a
 * bearer token. Import only pre-fills the connection editor; it never signs in
 * or changes the current source.
 */
object PairingLink {

    const val SCHEME = "termllm"
    const val CONNECT_HOST = "connect"
    const val VOICE_HOST = "voice"

    fun server(link: String): HttpUrl? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != SCHEME || uri.host != CONNECT_HOST) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) return null
        val query = uri.rawQuery ?: return null
        val items = query.split("&").filter { it.isNotEmpty() }
        if (items.size != 1) return null
        val index = items[0].indexOf('=')
        if (index < 0 || items[0].substring(0, index) != "server") return null
        val value = runCatching { java.net.URLDecoder.decode(items[0].substring(index + 1), "UTF-8") }.getOrNull() ?: return null
        val server = ServerAddress.parse(value) ?: return null
        if (server.scheme != "https") return null
        val host = server.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1" || host == "[::1]") return null
        return server
    }

    /** `termllm://voice`: the voice shortcut (iOS Action Button / App Intent). */
    fun isVoiceLaunch(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == SCHEME && uri.host == VOICE_HOST
    }
}
