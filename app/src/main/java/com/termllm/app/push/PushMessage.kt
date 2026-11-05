package com.termllm.app.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One push payload, as the node sends it (`serve_completion_push.go` / `notify`):
 * title, body, url, session_id, outcome, event_id plus the v2 metadata.
 *
 * `url` must be a same-origin absolute path inside the route's mount; anything
 * else is refused so a push can never navigate the app to another host.
 */
@Serializable
data class PushPayload(
    val title: String = "",
    val body: String = "",
    val url: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val outcome: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("kind") val kind: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

data class DecodedPush(
    val payload: PushPayload,
    val authSecretIndex: Int,
    /** Which route's auth secret opened this record (null for the legacy list form). */
    val routeKey: String? = null,
)

object PushMessageDecoder {

    private val json = Json { ignoreUnknownKeys = true }

    class Undecodable(message: String) : Exception(message)

    /**
     * Try each route auth secret in turn, the way `PushMessage.decode` does: the
     * first secret that authenticates the record wins.
     */
    fun decode(
        record: ByteArray,
        privateKey: java.security.PrivateKey,
        publicKeyBytes: ByteArray,
        authSecrets: List<ByteArray>,
    ): DecodedPush = decodeForRoutes(
        record,
        privateKey,
        publicKeyBytes,
        authSecrets.withIndex().associate { (index, secret) -> index.toString() to secret },
    )

    /**
     * Decode with a route-keyed secret table and report *which* route's secret
     * opened the record. Delivery uses that answer rather than the endpoint the
     * message arrived on, so a record encrypted for source A can never be shown as
     * source B's alert with B's tap target.
     */
    fun decodeForRoutes(
        record: ByteArray,
        privateKey: java.security.PrivateKey,
        publicKeyBytes: ByteArray,
        authSecrets: Map<String, ByteArray>,
    ): DecodedPush {
        if (authSecrets.isEmpty()) throw Undecodable("no auth secret is enrolled")
        var lastError: String? = null
        authSecrets.entries.forEachIndexed { index, entry ->
            try {
                val plain = PushCrypto.decrypt(record, privateKey, publicKeyBytes, entry.value)
                val payload = runCatching { json.decodeFromString(PushPayload.serializer(), plain.toString(Charsets.UTF_8)) }
                    .getOrElse { throw Undecodable("payload is not the expected JSON") }
                if (payload.title.isBlank() && payload.body.isBlank()) throw Undecodable("payload has no text")
                return DecodedPush(payload = payload, authSecretIndex = index, routeKey = entry.key)
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        throw Undecodable("record did not match any enrolled secret: " + (lastError ?: "unknown"))
    }

    /**
     * A push may only open a same-origin path inside [mountPrefix]. Returns the
     * absolute URL, or null when the path is not acceptable.
     */
    fun resolve(payload: PushPayload, origin: String, mountPrefix: String): String? {
        val originUrl = origin.let { okhttp3.HttpUrl.Companion.run { it.toHttpUrlOrNull() } } ?: return null
        val server = originUrl.newBuilder().encodedPath(PushRouting.mountPrefix(mountPrefix)).query(null).fragment(null).build()
        val raw = payload.url.trim()
        val target = when {
            raw.isEmpty() -> server
            raw.startsWith("//") -> return null
            raw.startsWith("/") -> server.resolve(raw)
            else -> okhttp3.HttpUrl.Companion.run { raw.toHttpUrlOrNull() }
        } ?: return null
        if (!com.termllm.app.data.ServerAddress.isWithinMount(target, server)) return null
        return target.toString()
    }
}

/** Encoding side of the payload, used by the self-test harness. */
object PushMessageJson {
    private val json = Json { encodeDefaults = false }

    fun encode(payload: PushPayload): String = json.encodeToString(PushPayload.serializer(), payload)
}
