package com.termllm.app.net

import com.termllm.app.model.HealthResponse
import com.termllm.app.model.ServerRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DiscoveryException(override val message: String) : Exception(message)

/**
 * Bounded, credential-free role discovery — a direct port of
 * `HubServerRole.detect` in TermLLM/HubConnection.swift.
 *
 * GET <mount>/healthz, Accept: application/json, no cookies, no redirects,
 * 15 second budget. `{"status":"ok","role":"hub"}` is a Hub; a node (or a
 * missing role) is a Direct Node. A timeout or auth failure never guesses a
 * role and never replaces an existing chat workspace.
 */
object Discovery {

    const val TIMEOUT_SECONDS = 15L

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun detect(server: HttpUrl): ServerRole = withContext(Dispatchers.IO) {
        val mount = server.encodedPath.trimEnd('/')
        val url = server.newBuilder()
            .encodedPath((if (mount.isEmpty()) "" else mount) + "/healthz")
            .query(null)
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.request.url != url) throw DiscoveryException(unrecognized())
                val status = response.code
                if (status != 200) throw DiscoveryException(httpMessage(status))
                val body = response.body?.string().orEmpty()
                return@withContext classify(body)
            }
        } catch (e: DiscoveryException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw DiscoveryException("Could not resolve the server name. Check the address and this device's network connection.")
        } catch (e: java.net.SocketTimeoutException) {
            throw DiscoveryException("The server did not respond within 15 seconds. Check that the server is online and reachable from this device.")
        } catch (e: javax.net.ssl.SSLException) {
            throw DiscoveryException("Could not establish a trusted HTTPS connection. Use the hostname covered by the server certificate, not its IP address.")
        } catch (e: java.io.IOException) {
            throw DiscoveryException("Could not reach the server. Check the server address and your network connection.")
        }
    }

    fun classify(body: String): ServerRole {
        val health = try {
            Wire.json.decodeFromString(HealthResponse.serializer(), body)
        } catch (e: Exception) {
            throw DiscoveryException(unrecognized())
        }
        if (health.status != "ok" || health.error != null) throw DiscoveryException(unrecognized())
        return when (health.role) {
            "hub" -> ServerRole.HUB
            null, "node" -> ServerRole.DIRECT_NODE
            else -> throw DiscoveryException(unrecognized())
        }
    }

    private fun unrecognized() =
        "Could not identify this server. Check that the address points to a term-llm Hub or Direct Node and includes its path prefix."

    private fun httpMessage(status: Int): String = when {
        status == 401 || status == 403 ->
            "The health check requires access (HTTP $status). Allow the public /healthz check at the configured mount so the app can identify the server before sign-in."
        status in 300..399 ->
            "The health check redirected (HTTP $status). Enter the final HTTPS server address, including its path prefix. Discovery does not follow redirects."
        else -> "The health check returned HTTP $status. Check the server address, path prefix and reverse-proxy configuration."
    }
}
