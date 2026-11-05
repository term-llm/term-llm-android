package com.termllm.app.auth

import com.termllm.app.data.ServerAddress
import com.termllm.app.data.ServerKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Port of the iOS `NativeLogin`: the passkey ceremony runs in the system browser
 * (a Custom Tab here, `ASWebAuthenticationSession` there), and only the newly
 * issued same-origin session cookie is imported. Passkey material never reaches
 * the app.
 *
 * Protocol (core `serve_native_handoff.go`, RFC 7636 S256):
 *  1. generate `code_verifier`, open `{mount}/auth/native/{b64url(sha256(verifier))}`;
 *  2. the browser signs in with a passkey and approves; it is sent to
 *     `termllm-auth://callback?code=…&state={challenge}`;
 *  3. the app POSTs `{code, code_verifier}` to `{mount}/api/auth/native/redeem`
 *     and receives its own browser session cookie (never a bearer).
 */
object NativeLogin {

    const val CALLBACK_SCHEME = "termllm-auth"
    const val CALLBACK_HOST = "callback"
    private const val TICKET_LIFETIME_MS = 5 * 60 * 1000L

    /** One in-flight sign-in; the verifier is kept in memory only. */
    data class Pending(
        val connectionId: String,
        val verifier: String,
        val challenge: String,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        fun expired(now: Long = System.currentTimeMillis()): Boolean = now - startedAt > TICKET_LIFETIME_MS
    }

    /** The imported session cookie, exactly as the server issued it. */
    @Serializable
    data class SessionCookie(
        val name: String,
        val value: String,
        val path: String,
        val expiresAt: Long? = null,
        val secure: Boolean = false,
    ) {
        fun expired(now: Long = System.currentTimeMillis()): Boolean = expiresAt != null && expiresAt <= now
    }

    class LoginException(message: String) : IOException(message)

    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun begin(connectionId: String, random: SecureRandom = SecureRandom()): Pending {
        val verifier = newVerifier(random)
        return Pending(connectionId, verifier, challenge(verifier))
    }

    /** `{server mount}/auth/native/{challenge}`, inside the saved mount only. */
    fun startUrl(server: HttpUrl, challenge: String): HttpUrl =
        endpoint(server, "auth/native/" + challenge)

    fun endpoint(server: HttpUrl, suffix: String): HttpUrl {
        val mount = ServerAddress.mountPath(server)
        val path = (if (mount.isEmpty()) "" else mount) + "/" + suffix
        return server.newBuilder().encodedPath(path).query(null).fragment(null).build()
    }

    /** Session cookie name the server issues for this kind of source. */
    fun cookieName(kind: ServerKind): String =
        if (kind == ServerKind.HUB) "term_llm_hub_session" else "term_llm_web_session"

    /** Cookie path the server scopes its session to: the mount, with a trailing slash. */
    fun cookiePath(server: HttpUrl): String {
        val mount = ServerAddress.mountPath(server)
        return if (mount.isEmpty()) "/" else mount + "/"
    }

    /**
     * Validates the browser callback exactly as iOS does: our scheme and host, no
     * path, exactly two query items, `state` equal to the challenge we issued and a
     * `code` that decodes to 32 bytes. Returns the code or null.
     */
    fun parseCallback(callback: String, expectedChallenge: String): String? {
        val uri = runCatching { URI(callback) }.getOrNull() ?: return null
        if (uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.rawFragment != null) return null
        val query = uri.rawQuery ?: return null
        val items = query.split("&").filter { it.isNotEmpty() }.map { item ->
            val index = item.indexOf('=')
            if (index < 0) item to "" else decode(item.substring(0, index)) to decode(item.substring(index + 1))
        }
        if (items.size != 2) return null
        val state = items.firstOrNull { it.first == "state" }?.second ?: return null
        val code = items.firstOrNull { it.first == "code" }?.second ?: return null
        if (state != expectedChallenge) return null
        val decoded = runCatching { Base64.getUrlDecoder().decode(code) }.getOrNull() ?: return null
        if (decoded.size != 32 || base64Url(decoded) != code) return null
        return code
    }

    /**
     * Redeems the one-use code. The request sends no cookies, follows no redirect,
     * and the reply must be a 200 `{ok:true}` from the exact redeem URL carrying one
     * HttpOnly session cookie scoped to this mount (Secure unless on a debug loopback host).
     */
    fun redeem(
        server: HttpUrl,
        kind: ServerKind,
        code: String,
        verifier: String,
        client: OkHttpClient = defaultClient,
    ): SessionCookie {
        val url = endpoint(server, "api/auth/native/redeem")
        val body = buildJsonObject { put("code", code); put("code_verifier", verifier) }.toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Origin", ServerAddress.origin(server))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code != 200 || response.request.url != url) {
                throw LoginException("The sign-in code expired or couldn't be redeemed. Retry sign-in.")
            }
            val ok = runCatching {
                Json.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
            }.getOrDefault(false)
            if (!ok) throw LoginException("The server returned an invalid native login response. Update term-llm and retry.")
            val expectedName = cookieName(kind)
            val expectedPath = cookiePath(server)
            val localHttp = server.scheme == "http" && ServerAddress.allowsLocalHttp(server.host)
            val cookie = Cookie.parseAll(url, response.headers).firstOrNull { cookie ->
                cookie.name == expectedName &&
                    cookie.domain.equals(server.host, ignoreCase = true) &&
                    cookie.httpOnly &&
                    cookie.path == expectedPath &&
                    (cookie.secure || localHttp)
            } ?: throw LoginException("The server returned an invalid native login response. Update term-llm and retry.")
            return SessionCookie(
                name = cookie.name,
                value = cookie.value,
                path = cookie.path,
                expiresAt = cookie.expiresAt.takeIf { cookie.persistent },
                secure = cookie.secure,
            )
        }
    }

    /** `Set-Cookie`-style string for a WebView cookie manager. */
    fun webViewCookie(cookie: SessionCookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        append("; Path=").append(cookie.path)
        append("; HttpOnly")
        if (cookie.secure) append("; Secure")
        cookie.expiresAt?.let { expires ->
            val seconds = ((expires - System.currentTimeMillis()) / 1000L).coerceAtLeast(1)
            append("; Max-Age=").append(seconds)
        }
    }

    /** OkHttp cookie for the native transport's scoped jar. */
    fun okHttpCookie(server: HttpUrl, cookie: SessionCookie): Cookie {
        val builder = Cookie.Builder()
            .name(cookie.name)
            .value(cookie.value)
            .domain(server.host)
            .path(cookie.path)
            .httpOnly()
        if (cookie.secure) builder.secure()
        cookie.expiresAt?.let { builder.expiresAt(it) }
        return builder.build()
    }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
