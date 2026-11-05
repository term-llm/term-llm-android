package com.termllm.app.net

import com.termllm.app.data.ServerAddress
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Mirrors the iOS `HubAPIError`: HTTP status plus a human-readable message. */
open class ApiException(val status: Int, override val message: String) : IOException(message)

/** A source replied 401/403: private state for that source must be purged. */
class UnauthorizedException(status: Int, message: String, val loginUrl: String? = null) : ApiException(status, message)

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/**
 * In-memory cookie jar scoped to the configured host and mount path.
 * iOS keeps one isolated WKWebsiteDataStore per source and only forwards
 * cookies that match the exact host and mount, never a global jar. This is the
 * native equivalent: no ambient/global cookie store, and a cookie set at a
 * sibling path is not sent.
 */
class ScopedCookieJar(private val server: HttpUrl, private val mountPath: String) : CookieJar {

    private val stored = mutableListOf<Cookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(stored) {
            if (!ServerAddress.isWithinMount(url, server)) return emptyList()
            stored.filter { it.matches(url) }
        }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!ServerAddress.isWithinMount(url, server)) return
        synchronized(stored) {
            for (cookie in cookies) {
                stored.removeAll { it.name == cookie.name && it.path == cookie.path && it.domain == cookie.domain }
                if (cookie.expiresAt > System.currentTimeMillis()) stored.add(cookie)
            }
        }
    }

    fun cookie(name: String, path: String): Cookie? = synchronized(stored) {
        stored.firstOrNull { it.name == name && it.path == path }
    }

    fun all(): List<Cookie> = synchronized(stored) { stored.toList() }
}

/**
 * One authenticated HTTP surface for a saved source. Requests never follow
 * redirects (the iOS transport blocks them so a redirect cannot replay
 * credentials to another origin), never use a shared cookie cache, and apply a
 * bounded timeout.
 */
class SourceHttp(
    val server: HttpUrl,
    private val token: String?,
    private val onUnauthorized: () -> Unit,
) {
    val mountPath: String = ServerAddress.mountPath(server)
    val origin: String = ServerAddress.origin(server)
    val cookieJar: ScopedCookieJar = ScopedCookieJar(server, mountPath)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** Seed a session cookie the native passkey sign-in redeemed (scoped to this mount). */
    fun seedCookie(cookie: Cookie) {
        cookieJar.saveFromResponse(server, listOf(cookie))
    }

    /** URL for a path relative to the configured mount, e.g. "/api/nodes". */
    fun urlFor(relative: String): HttpUrl {
        val base = server.newBuilder().encodedPath(if (mountPath.isEmpty()) "/" else mountPath + "/").query(null).build()
        val cleaned = relative.removePrefix("/")
        return base.resolve(cleaned) ?: base
    }

    /** URL for a server-provided path that already contains the mount. */
    fun urlForAbsolute(pathWithMount: String): HttpUrl? = ServerAddress.absolute(server, pathWithMount)

    fun get(relative: String, query: String? = null): String {
        val url = if (query.isNullOrEmpty()) urlFor(relative) else urlFor(relative).newBuilder().encodedQuery(query).build()
        return execute(Request.Builder().url(url).get().build())
    }

    fun post(relative: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val payload = (body ?: "{}").toRequestBody(Wire.JSON_MEDIA)
        val builder = request(relative, payload, "POST").newBuilder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return execute(builder.build())
    }

    fun patch(relative: String, body: String): String =
        execute(request(relative, body.toRequestBody(Wire.JSON_MEDIA), "PATCH"))

    fun delete(relative: String): String =
        execute(request(relative, "{}".toRequestBody(Wire.JSON_MEDIA), "DELETE"))

    private fun request(relative: String, body: okhttp3.RequestBody, method: String): Request {
        val builder = Request.Builder().url(urlFor(relative)).method(method, body)
        // The real Origin is required on mutations, as in the iOS client.
        builder.header("Origin", origin)
        return builder.build()
    }

    private fun execute(request: Request): String {
        val builder = request.newBuilder()
            .header("Accept", "application/json")
            .header("X-Term-LLM-Client", "term-llm-android")
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer " + token)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val status = response.code
            if (status == 401 || status == 403) {
                onUnauthorized()
                throw UnauthorizedException(status, errorMessage(status, text), response.header("X-Term-LLM-Login-URL"))
            }
            if (status in 300..399) {
                throw ApiException(status, "The server redirected this request. Enter the final server address, including its path prefix. Request following is disabled.")
            }
            if (!response.isSuccessful) throw ApiException(status, errorMessage(status, text))
            return text
        }
    }

    private fun errorMessage(status: Int, body: String): String {
        val parsed = try {
            Wire.json.decodeFromString(com.termllm.app.model.ApiErrorBody.serializer(), body)
        } catch (e: Exception) {
            null
        }
        parsed?.let { bodyJson ->
            (bodyJson.message ?: bodyJson.error)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return when (status) {
            401, 403 -> "The server rejected this connection (HTTP $status). Sign in again for this source."
            404 -> "The server has no such endpoint (HTTP 404). Check the address and path prefix."
            else -> "The server returned HTTP $status."
        }
    }
}
