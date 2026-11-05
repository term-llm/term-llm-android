package com.termllm.app.push

import com.termllm.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Relay endpoint policy.
 *
 * The relay is the one component that sees every notification body, so the URL it
 * listens on is a trust decision, not a convenience: a release build may only talk
 * to an HTTPS relay the user (or the page) configured. Debug builds additionally
 * accept plain HTTP to loopback and the emulator host alias, because that is where
 * the container-side test relay lives — and only there, mirroring the narrow
 * `ServerAddress.allowsLocalHttp` rule for sources.
 *
 * There is deliberately **no built-in production relay**: shipping one would mean
 * every install sends its alerts to a host nobody chose. [defaultRelay] returns a
 * value only in debug builds.
 */
object PushRelay {

    /**
     * The loopback/emulator relay used by the container test fixtures. Only ever
     * consulted when [BuildConfig.DEBUG] is true; release builds have no default.
     */
    const val DEBUG_DEFAULT: String = "http://10.0.2.2:8081"

    private val localHttpHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    /** The relay a debug build starts from; release builds have none. */
    fun defaultRelay(debug: Boolean = BuildConfig.DEBUG): String? = if (debug) DEBUG_DEFAULT else null

    fun allowsCleartext(host: String, debug: Boolean): Boolean =
        debug && localHttpHosts.contains(host.lowercase())

    /**
     * Normalize operator/page input into a relay base URL (no trailing slash), or
     * null when it must not be used. Credentials, queries and fragments are refused
     * so a relay URL can never smuggle a secret into logs or a stored preference.
     */
    fun normalize(input: String?, debug: Boolean = BuildConfig.DEBUG): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://" + trimmed
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.scheme == "http" && !allowsCleartext(url.host, debug)) return null
        return url.toString().trimEnd('/')
    }

    /** Why [normalize] refused [input]; used for honest UI copy instead of "invalid". */
    fun refusal(input: String?, debug: Boolean = BuildConfig.DEBUG): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return "Enter the relay's address."
        val candidate = if (trimmed.contains("://")) trimmed else "https://" + trimmed
        val url = candidate.toHttpUrlOrNull() ?: return "That is not a URL."
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "A relay URL cannot carry a username or password."
        if (url.scheme == "http" && !allowsCleartext(url.host, debug)) {
            return "Plain HTTP is only allowed for a local relay in a debug build. Use HTTPS."
        }
        if (url.scheme != "http" && url.scheme != "https") return "Use an https:// relay address."
        return "That relay address cannot be used."
    }

    fun isHttps(base: String): Boolean = base.startsWith("https://", ignoreCase = true)
}
