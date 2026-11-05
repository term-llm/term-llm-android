package com.termllm.app.data

import com.termllm.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Port of TermLLM/ServerAddress.swift.
 *
 * Only HTTPS is accepted outside local development. The iOS app permits plain
 * HTTP to `localhost` in simulator builds (`allowsLocalHTTP`); Android mirrors
 * that narrow rule for loopback and the emulator host alias in debug builds so
 * the protocol fixture can be exercised. Release builds never allow cleartext.
 */
object ServerAddress {

    private val localHttpHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun allowsLocalHttp(host: String, debug: Boolean = BuildConfig.DEBUG): Boolean =
        debug && localHttpHosts.contains(host.lowercase())

    /** Lenient user input -> canonical absolute URL, or null when unacceptable. */
    fun parse(input: String, debug: Boolean = BuildConfig.DEBUG): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://" + trimmed
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (url.scheme == "http" && !allowsLocalHttp(url.host, debug)) return null
        if (url.scheme != "http" && url.scheme != "https") return null
        // Normalize bare origins; preserve explicit prefixes and routes as entered.
        return if (url.encodedPath.isEmpty()) url.newBuilder().encodedPath("/").build() else url
    }

    /** Scheme + host + non-default port, matching the iOS `origin` used for Origin headers. */
    fun origin(url: HttpUrl): String = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

    /** Configured mount path without a trailing slash (empty for a root server). */
    fun mountPath(url: HttpUrl): String = url.encodedPath.trimEnd('/')

    fun isWithinMount(candidate: HttpUrl, server: HttpUrl): Boolean {
        if (candidate.username.isNotEmpty() || candidate.password.isNotEmpty()) return false
        // Reject ambiguous encoded path separators and nested traversal before trust checks.
        var decoded = candidate.encodedPath
        repeat(4) {
            if (Regex("(?i)%2f|%5c|%00").containsMatchIn(decoded) || '\\' in decoded) return false
            if (decoded.split('/').any { it == "." || it == ".." }) return false
            val next = runCatching { java.net.URLDecoder.decode(decoded.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return false
            if (next == decoded) return@repeat
            decoded = next
        }
        if (candidate.host != server.host || candidate.scheme != server.scheme || candidate.port != server.port) return false
        val mount = mountPath(server)
        if (mount.isEmpty()) return true
        val path = candidate.encodedPath
        return path == mount || path.startsWith(mount + "/")
    }

    /** Absolute URL for a server-provided path that already includes the mount. */
    fun absolute(server: HttpUrl, pathWithMount: String): HttpUrl? {
        if (!pathWithMount.startsWith("/")) return null
        return server.newBuilder().encodedPath("/").query(null).build().resolve(pathWithMount)
    }
}
