package com.termllm.app.linked

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Port of iOS `LinkedDevicesProtocol.swift`: term-llm's FROZEN pairing wire format
 * v2 (`internal/pairing/WIRE.md`). Identity-only pairing over a one-use invitation,
 * then a signed transport handshake binding the WebRTC SDP. Binary values are
 * canonical unpadded base64url; keys are Ed25519; integer times are Unix seconds.
 * The deterministic vectors (`vectors_v2.json`) must be reproduced byte for byte.
 */
object LinkedDevicesWire {
    const val VERSION = 2
    const val MAX_LINK_BYTES = 4096
    const val MAX_MESSAGE_BYTES = 256 * 1024
    const val MAX_BROKER_FRAME_BYTES = MAX_MESSAGE_BYTES + 512
    const val MAX_SDP_BYTES = 64 * 1024
    const val INVITATION_ID_BYTES = 16
    const val DEVICE_ID_BYTES = 16
    const val NONCE_BYTES = 32
    const val SECRET_BYTES = 32
    const val KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val MAX_LABEL_BYTES = 64
    const val MAX_CAPABILITY_BYTES = 128
    const val DOMAIN = "term-llm/pairing/v2"
    const val MAC_KEY_LABEL = "term-llm/pairing/v2/mac-key"
}

sealed class LinkedDevicesProtocolError(message: String) : Exception(message) {
    object InvalidLink : LinkedDevicesProtocolError("This pairing link is not valid.")
    object Expired : LinkedDevicesProtocolError("This invitation has expired. Create a new one on the host.")
    class Invalid(val detail: String) : LinkedDevicesProtocolError("The host returned invalid Linked devices data ($detail).")
    object BadMac : LinkedDevicesProtocolError("The host identity could not be authenticated. Pairing was stopped.")
    object BadSignature : LinkedDevicesProtocolError("The host identity could not be authenticated. Pairing was stopped.")
    object HostIdentityChanged : LinkedDevicesProtocolError("The host identity changed. The connection was stopped.")
    class Rejected(val reason: String) : LinkedDevicesProtocolError("The host did not approve this device.")
    object Revoked : LinkedDevicesProtocolError("Access was revoked or the shared target was reset.")
}

/** Canonical unpadded base64url. Decoding rejects padding, non-canonical and oversized input. */
object B64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String, maximumBytes: Int): ByteArray? {
        if (value.isEmpty() || value.length > ((maximumBytes + 2) / 3) * 4) return null
        if (value.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
        if (decoded.size > maximumBytes || encode(decoded) != value) return null
        return decoded
    }
}

object LinkedDevicesCrypto {
    private val random = SecureRandom()

    fun random(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    fun constantTimeEqual(lhs: ByteArray, rhs: ByteArray): Boolean {
        var difference = if (lhs.size == rhs.size) 0 else 1
        for (index in 0 until maxOf(lhs.size, rhs.size)) {
            val l = if (index < lhs.size) lhs[index].toInt() else 0
            val r = if (index < rhs.size) rhs[index].toInt() else 0
            difference = difference or (l xor r)
        }
        return difference == 0
    }

    fun publicKey(seed: ByteArray): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey

    fun sign(seed: ByteArray, transcript: ByteArray): ByteArray = Ed25519Sign(seed).sign(transcript)

    fun verify(signature: ByteArray, key: ByteArray, transcript: ByteArray): Boolean {
        if (signature.size != LinkedDevicesWire.SIGNATURE_BYTES || key.size != LinkedDevicesWire.KEY_BYTES) return false
        return runCatching { Ed25519Verify(key).verify(signature, transcript); true }.getOrDefault(false)
    }

    fun validBasePath(path: String): Boolean =
        path.startsWith("/") && !path.contains('?') && !path.contains('#') && !path.contains('\\') &&
            !path.contains('\r') && !path.contains('\n') && !path.contains("..")

    /** The single consistent `a=fingerprint:` value of an SDP, normalised `algo AA:BB:…`. */
    fun fingerprint(sdp: String): String {
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        if (sdp.isEmpty() || bytes.size > LinkedDevicesWire.MAX_SDP_BYTES || bytes.any { it == 0.toByte() }) {
            throw LinkedDevicesProtocolError.Invalid("SDP size")
        }
        val byteCounts = mapOf("sha-256" to 32, "sha-384" to 48, "sha-512" to 64)
        var found: String? = null
        for (raw in sdp.split("\n")) {
            val line = raw.removeSuffix("\r")
            if (!line.startsWith("a=fingerprint:")) continue
            val value = line.removePrefix("a=fingerprint:").trim(' ', '\t')
            val split = value.indexOf(' ')
            if (split < 0) throw LinkedDevicesProtocolError.Invalid("SDP fingerprint")
            val algorithm = value.substring(0, split).lowercase()
            val hex = value.substring(split + 1).trim(' ', '\t')
            val count = byteCounts[algorithm] ?: throw LinkedDevicesProtocolError.Invalid("SDP fingerprint")
            val pieces = hex.split(":")
            if (pieces.size != count || pieces.any { p -> p.length != 2 || p.any { c -> !c.isLetterOrDigit() || c.lowercaseChar() !in "0123456789abcdef" } }) {
                throw LinkedDevicesProtocolError.Invalid("SDP fingerprint")
            }
            val normalized = algorithm + " " + pieces.joinToString(":") { it.uppercase() }
            if (found != null && found != normalized) throw LinkedDevicesProtocolError.Invalid("SDP fingerprints disagree")
            found = normalized
        }
        return found ?: throw LinkedDevicesProtocolError.Invalid("SDP fingerprint missing")
    }
}

/** A parsed, validated `termllm://pair` link. Secrets live only in memory. */
data class LinkedDevicesPairingLink(
    val broker: String,
    val hostKey: ByteArray,
    val invitationId: ByteArray,
    val expiresAtSeconds: Long,
    val hostLabel: String?,
    val pairingSecret: ByteArray,
    val invitationCapability: String?,
) {
    val hostId: String get() = B64Url.encode(LinkedDevicesCrypto.sha256(hostKey))
    val invitationIdString: String get() = B64Url.encode(invitationId)
    val hostFingerprint: String
        get() = LinkedDevicesCrypto.sha256(hostKey).take(8).joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = nowSeconds >= expiresAtSeconds

    /** Never logs secrets. */
    fun redacted(): String = "termllm://pair?v=2&b=" + broker + "&h=" + B64Url.encode(hostKey) + "&i=" + invitationIdString + "&e=" + expiresAtSeconds + "#s=<redacted>"

    companion object {
        fun parse(raw: String): LinkedDevicesPairingLink {
            if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > LinkedDevicesWire.MAX_LINK_BYTES) throw LinkedDevicesProtocolError.InvalidLink
            val uri = runCatching { URI(raw) }.getOrNull() ?: throw LinkedDevicesProtocolError.InvalidLink
            if (uri.scheme != "termllm" || uri.host != "pair" || uri.rawUserInfo != null || uri.port != -1 ||
                !uri.rawPath.isNullOrEmpty()
            ) throw LinkedDevicesProtocolError.InvalidLink
            val encodedQuery = uri.rawQuery ?: throw LinkedDevicesProtocolError.InvalidLink
            val encodedFragment = uri.rawFragment ?: throw LinkedDevicesProtocolError.InvalidLink
            val query = strict(encodedQuery, required = setOf("v", "b", "h", "i", "e"), optional = setOf("n"))
            if (query["v"] != "2") throw LinkedDevicesProtocolError.InvalidLink
            val secrets = strict(encodedFragment, required = setOf("s"), optional = setOf("c"))
            val broker = validateBroker(query.getValue("b")) ?: throw LinkedDevicesProtocolError.InvalidLink
            val host = B64Url.decode(query.getValue("h"), 32)?.takeIf { it.size == 32 } ?: throw LinkedDevicesProtocolError.InvalidLink
            val invitation = B64Url.decode(query.getValue("i"), 16)?.takeIf { it.size == 16 } ?: throw LinkedDevicesProtocolError.InvalidLink
            val secret = B64Url.decode(secrets.getValue("s"), 32)?.takeIf { it.size == 32 } ?: throw LinkedDevicesProtocolError.InvalidLink
            val expiryRaw = query.getValue("e")
            if (expiryRaw.startsWith("+") || expiryRaw.startsWith("0")) throw LinkedDevicesProtocolError.InvalidLink
            val expiry = expiryRaw.toLongOrNull()?.takeIf { it > 0 && it.toString() == expiryRaw } ?: throw LinkedDevicesProtocolError.InvalidLink
            query["n"]?.let { validateLabel(it) }
            secrets["c"]?.let { capability ->
                val bytes = capability.toByteArray(Charsets.UTF_8)
                if (bytes.size > LinkedDevicesWire.MAX_CAPABILITY_BYTES ||
                    bytes.any { b -> !((b in 0x41..0x5A) || (b in 0x61..0x7A) || (b in 0x30..0x39) || b == 0x2D.toByte() || b == 0x5F.toByte()) }
                ) throw LinkedDevicesProtocolError.InvalidLink
            }
            return LinkedDevicesPairingLink(broker, host, invitation, expiry, query["n"], secret, secrets["c"])
        }

        private fun strict(raw: String, required: Set<String>, optional: Set<String>): Map<String, String> {
            if (raw.isEmpty()) throw LinkedDevicesProtocolError.InvalidLink
            val values = HashMap<String, String>()
            for (field in raw.split("&")) {
                val index = field.indexOf('=')
                if (index < 0) throw LinkedDevicesProtocolError.InvalidLink
                val name = formDecode(field.substring(0, index)) ?: throw LinkedDevicesProtocolError.InvalidLink
                val value = formDecode(field.substring(index + 1)) ?: throw LinkedDevicesProtocolError.InvalidLink
                if (!(name in required || name in optional) || values.containsKey(name) || value.isEmpty()) throw LinkedDevicesProtocolError.InvalidLink
                values[name] = value
            }
            if (!values.keys.containsAll(required)) throw LinkedDevicesProtocolError.InvalidLink
            return values
        }

        /** Go's QueryUnescape contract: `+` is a space, a literal plus must be `%2B`. */
        private fun formDecode(value: String): String? =
            runCatching { URLDecoder.decode(value.replace("+", "%20"), "UTF-8") }.getOrNull()

        private fun validateBroker(raw: String): String? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (!(scheme == "https" || (scheme == "http" && isLoopback(host)))) return null
            var path = uri.rawPath ?: ""
            while (path.endsWith("/") && path != "/") path = path.dropLast(1)
            val port = if (uri.port == -1) "" else ":" + uri.port
            return scheme + "://" + host + port + (if (path == "/") "" else path)
        }

        fun isLoopback(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1" || host == "[::1]" || host.startsWith("127.")

        fun validateLabel(value: String) {
            if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > LinkedDevicesWire.MAX_LABEL_BYTES ||
                value.any { c -> Character.getType(c) == Character.CONTROL.toInt() || Character.getType(c) == Character.FORMAT.toInt() }
            ) throw LinkedDevicesProtocolError.InvalidLink
        }
    }
}

// ---- messages (field names are the wire names) ----

data class PairRequest(
    val invitationId: ByteArray, val deviceKey: ByteArray, val deviceNonce: ByteArray, val deviceName: String,
    val mac: ByteArray, val sig: ByteArray,
)

data class PairAnswer(
    val v: Int, val type: String, val invitationId: ByteArray, val hostKey: ByteArray, val deviceKey: ByteArray,
    val deviceNonce: ByteArray, val requestHash: ByteArray, val deviceId: ByteArray, val reconnectCapability: ByteArray,
    val scope: String, val basePath: String, val hostLabel: String, val mac: ByteArray, val sig: ByteArray,
)

data class PairReject(val invitationId: ByteArray, val reason: String)

data class TransportHello(
    val deviceKey: ByteArray, val deviceId: ByteArray, val reconnectCapability: ByteArray, val deviceNonce: ByteArray, val sig: ByteArray,
)

data class TransportChallenge(
    val v: Int, val type: String, val challengeId: ByteArray, val hostKey: ByteArray, val hostNonce: ByteArray,
    val deviceKey: ByteArray, val deviceId: ByteArray, val expiresAt: Long, val sig: ByteArray,
)

data class TransportRequest(
    val challengeId: ByteArray, val hostNonce: ByteArray, val deviceKey: ByteArray, val deviceNonce: ByteArray,
    val deviceId: ByteArray, val offerSdp: String, val sig: ByteArray,
)

data class TransportAnswer(
    val v: Int, val type: String, val challengeId: ByteArray, val hostKey: ByteArray, val hostNonce: ByteArray,
    val deviceKey: ByteArray, val deviceNonce: ByteArray, val requestHash: ByteArray, val deviceId: ByteArray,
    val answerSdp: String, val sig: ByteArray,
)

/** Transcripts: `field(x) = uint32_be(len) || x`, prefixed by the domain and the label. */
object LinkedDevicesTranscript {
    private fun field(out: java.io.ByteArrayOutputStream, bytes: ByteArray) {
        val n = bytes.size
        out.write((n ushr 24) and 0xff); out.write((n ushr 16) and 0xff); out.write((n ushr 8) and 0xff); out.write(n and 0xff)
        out.write(bytes)
    }

    private fun s(value: String) = value.toByteArray(Charsets.UTF_8)

    fun build(label: String, fields: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        field(out, s(LinkedDevicesWire.DOMAIN))
        field(out, s(label))
        fields.forEach { field(out, it) }
        return out.toByteArray()
    }

    fun macKey(secret: ByteArray): ByteArray = LinkedDevicesCrypto.hmac(secret, s(LinkedDevicesWire.MAC_KEY_LABEL))
    fun mac(secret: ByteArray, transcript: ByteArray): ByteArray = LinkedDevicesCrypto.hmac(macKey(secret), transcript)
    fun hash(transcript: ByteArray): ByteArray = LinkedDevicesCrypto.sha256(transcript)

    fun pairRequest(r: PairRequest, hostKey: ByteArray) =
        build("pair-request", listOf(s("device"), r.invitationId, hostKey, r.deviceKey, r.deviceNonce, s(r.deviceName)))

    fun pairAnswer(a: PairAnswer) =
        build("pair-answer", listOf(s("host"), a.invitationId, a.hostKey, a.deviceKey, a.deviceNonce, a.requestHash,
            a.deviceId, a.reconnectCapability, s(a.scope), s(a.basePath), s(a.hostLabel)))

    fun transportHello(h: TransportHello, hostKey: ByteArray) =
        build("transport-hello", listOf(s("device"), hostKey, h.deviceKey, h.deviceId, h.reconnectCapability, h.deviceNonce))

    fun transportChallenge(c: TransportChallenge) =
        build("transport-challenge", listOf(s("host"), c.challengeId, c.hostKey, c.hostNonce, c.deviceKey, c.deviceId, s(c.expiresAt.toString())))

    fun transportRequest(r: TransportRequest, hostKey: ByteArray) =
        build("transport-request", listOf(s("device"), r.challengeId, hostKey, r.hostNonce, r.deviceKey, r.deviceNonce, r.deviceId, s(r.offerSdp)))

    fun transportAnswer(a: TransportAnswer) =
        build("transport-answer", listOf(s("host"), a.challengeId, a.hostKey, a.hostNonce, a.deviceKey, a.deviceNonce, a.requestHash, a.deviceId, s(a.answerSdp)))

    fun sas(secret: ByteArray, invitationId: ByteArray, hostKey: ByteArray, deviceKey: ByteArray): String {
        if (secret.size != 32 || invitationId.size != 16 || hostKey.size != 32 || deviceKey.size != 32) throw LinkedDevicesProtocolError.Invalid("SAS inputs")
        val bytes = mac(secret, build("pair-sas", listOf(invitationId, hostKey, deviceKey)))
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }
}

/**
 * JSON in the wire's documented field order (what core emits and the frozen vectors
 * record), no escaped slashes, exact key sets on decode (iOS `LinkedDevicesMessageCodec`).
 */
object LinkedDevicesCodec {
    private val json = Json { encodeDefaults = true }

    private fun obj(vararg pairs: Pair<String, JsonElement>): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(linkedMapOf(*pairs)))

    private fun b(bytes: ByteArray) = JsonPrimitive(B64Url.encode(bytes))
    private fun t(text: String) = JsonPrimitive(text)
    private fun n(value: Long) = JsonPrimitive(value)

    fun encode(r: PairRequest): String = check(obj("v" to n(2), "type" to t("pair_request"), "invitation_id" to b(r.invitationId),
        "device_key" to b(r.deviceKey), "device_nonce" to b(r.deviceNonce), "device_name" to t(r.deviceName), "mac" to b(r.mac), "sig" to b(r.sig)))

    fun encode(h: TransportHello): String = check(obj("v" to n(2), "type" to t("transport_hello"), "device_key" to b(h.deviceKey),
        "device_id" to b(h.deviceId), "reconnect_capability" to b(h.reconnectCapability), "device_nonce" to b(h.deviceNonce), "sig" to b(h.sig)))

    fun encode(r: TransportRequest): String = check(obj("v" to n(2), "type" to t("transport_request"), "challenge_id" to b(r.challengeId),
        "host_nonce" to b(r.hostNonce), "device_key" to b(r.deviceKey), "device_nonce" to b(r.deviceNonce), "device_id" to b(r.deviceId),
        "offer_sdp" to t(r.offerSdp), "sig" to b(r.sig)))

    private fun check(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size > LinkedDevicesWire.MAX_MESSAGE_BYTES) throw LinkedDevicesProtocolError.Invalid("message size")
        return text
    }

    /** Parses one pairing message and returns its exact-shape object, or throws. */
    fun parse(data: String, expectedType: String, fields: Set<String>): JsonObject {
        if (data.toByteArray(Charsets.UTF_8).size > LinkedDevicesWire.MAX_MESSAGE_BYTES) throw LinkedDevicesProtocolError.Invalid("message shape")
        val element = runCatching { Json.parseToJsonElement(data) }.getOrNull() ?: throw LinkedDevicesProtocolError.Invalid("message shape")
        val obj = element as? JsonObject ?: throw LinkedDevicesProtocolError.Invalid("message shape")
        if (obj.keys != fields || obj["v"]?.jsonPrimitive?.intOrNull != 2 || obj["type"]?.jsonPrimitive?.content != expectedType) {
            throw LinkedDevicesProtocolError.Invalid("message shape")
        }
        return obj
    }

    /** The message type of a broker signal body, or null when it is not a v2 object. */
    fun typeOf(data: String): String? = runCatching {
        val obj = Json.parseToJsonElement(data).jsonObject
        if (obj["v"]?.jsonPrimitive?.intOrNull != 2) null else obj["type"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun JsonObject.bytes(key: String): ByteArray =
        B64Url.decode(this[key]?.jsonPrimitive?.content ?: throw LinkedDevicesProtocolError.Invalid("message fields"), LinkedDevicesWire.MAX_MESSAGE_BYTES)
            ?: throw LinkedDevicesProtocolError.Invalid("message fields")

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw LinkedDevicesProtocolError.Invalid("message fields")

    fun decodePairAnswer(data: String): PairAnswer {
        val o = parse(data, "pair_answer", setOf("v", "type", "invitation_id", "host_key", "device_key", "device_nonce", "request_hash",
            "device_id", "reconnect_capability", "scope", "base_path", "host_label", "mac", "sig"))
        return PairAnswer(2, "pair_answer", o.bytes("invitation_id"), o.bytes("host_key"), o.bytes("device_key"), o.bytes("device_nonce"),
            o.bytes("request_hash"), o.bytes("device_id"), o.bytes("reconnect_capability"), o.text("scope"), o.text("base_path"),
            o.text("host_label"), o.bytes("mac"), o.bytes("sig"))
    }

    fun decodePairReject(data: String): PairReject {
        val o = parse(data, "pair_reject", setOf("v", "type", "invitation_id", "reason"))
        return PairReject(o.bytes("invitation_id"), o.text("reason"))
    }

    fun decodeTransportChallenge(data: String): TransportChallenge {
        val o = parse(data, "transport_challenge", setOf("v", "type", "challenge_id", "host_key", "host_nonce", "device_key", "device_id", "expires_at", "sig"))
        val expires = o["expires_at"]?.jsonPrimitive?.longOrNull ?: throw LinkedDevicesProtocolError.Invalid("message fields")
        return TransportChallenge(2, "transport_challenge", o.bytes("challenge_id"), o.bytes("host_key"), o.bytes("host_nonce"),
            o.bytes("device_key"), o.bytes("device_id"), expires, o.bytes("sig"))
    }

    fun decodeTransportAnswer(data: String): TransportAnswer {
        val o = parse(data, "transport_answer", setOf("v", "type", "challenge_id", "host_key", "host_nonce", "device_key", "device_nonce",
            "request_hash", "device_id", "answer_sdp", "sig"))
        return TransportAnswer(2, "transport_answer", o.bytes("challenge_id"), o.bytes("host_key"), o.bytes("host_nonce"), o.bytes("device_key"),
            o.bytes("device_nonce"), o.bytes("request_hash"), o.bytes("device_id"), o.text("answer_sdp"), o.bytes("sig"))
    }
}

/** Per-host device identity + grant (iOS `LinkedDevicesCredential`). Stored encrypted by the caller. */
data class LinkedDevicesCredential(
    val hostKey: ByteArray,
    val deviceSeed: ByteArray,
    val deviceId: String? = null,
    val reconnectCapability: ByteArray? = null,
    val brokerUrl: String,
    val scope: String? = null,
    val basePath: String? = null,
    val hostLabel: String? = null,
    val pairedAtSeconds: Long? = null,
) {
    val devicePublicKey: ByteArray get() = LinkedDevicesCrypto.publicKey(deviceSeed)
    val hostId: String get() = B64Url.encode(LinkedDevicesCrypto.sha256(hostKey))
    val paired: Boolean get() = deviceId != null && reconnectCapability != null
}

/** Builds the signed/MACed `pair_request`, derives the SAS, verifies the `pair_answer`. */
class LinkedDevicesPairingAttempt(
    val link: LinkedDevicesPairingLink,
    credential: LinkedDevicesCredential,
    deviceName: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    nonce: ByteArray? = null,
) {
    val request: PairRequest
    val requestHash: ByteArray
    val sas: String
    private val deviceSeed: ByteArray = credential.deviceSeed

    init {
        if (link.isExpired(nowSeconds)) throw LinkedDevicesProtocolError.Expired
        LinkedDevicesPairingLink.validateLabel(deviceName)
        if (!credential.hostKey.contentEquals(link.hostKey) || credential.deviceSeed.size != 32) throw LinkedDevicesProtocolError.Invalid("device identity")
        val deviceNonce = nonce ?: LinkedDevicesCrypto.random(32)
        if (deviceNonce.size != 32) throw LinkedDevicesProtocolError.Invalid("device nonce")
        val publicKey = credential.devicePublicKey
        val unsigned = PairRequest(link.invitationId, publicKey, deviceNonce, deviceName, ByteArray(0), ByteArray(0))
        val transcript = LinkedDevicesTranscript.pairRequest(unsigned, link.hostKey)
        request = unsigned.copy(
            mac = LinkedDevicesTranscript.mac(link.pairingSecret, transcript),
            sig = LinkedDevicesCrypto.sign(deviceSeed, transcript),
        )
        requestHash = LinkedDevicesTranscript.hash(transcript)
        sas = LinkedDevicesTranscript.sas(link.pairingSecret, link.invitationId, link.hostKey, publicKey)
    }

    fun verify(answer: PairAnswer, nowSeconds: Long = System.currentTimeMillis() / 1000): LinkedDevicesCredential {
        if (answer.v != 2 || answer.type != "pair_answer" || !answer.hostKey.contentEquals(link.hostKey)) throw LinkedDevicesProtocolError.HostIdentityChanged
        if (!answer.invitationId.contentEquals(request.invitationId) || !answer.deviceKey.contentEquals(request.deviceKey) ||
            !answer.deviceNonce.contentEquals(request.deviceNonce) || !answer.requestHash.contentEquals(requestHash) ||
            answer.deviceId.size != 16 || answer.reconnectCapability.size != 32 || answer.scope != "web-v1" ||
            !LinkedDevicesCrypto.validBasePath(answer.basePath)
        ) throw LinkedDevicesProtocolError.Invalid("pair answer binding")
        if (answer.hostLabel.isNotEmpty()) LinkedDevicesPairingLink.validateLabel(answer.hostLabel)
        val transcript = LinkedDevicesTranscript.pairAnswer(answer)
        if (!LinkedDevicesCrypto.constantTimeEqual(answer.mac, LinkedDevicesTranscript.mac(link.pairingSecret, transcript))) throw LinkedDevicesProtocolError.BadMac
        if (!LinkedDevicesCrypto.verify(answer.sig, link.hostKey, transcript)) throw LinkedDevicesProtocolError.BadSignature
        return LinkedDevicesCredential(
            hostKey = link.hostKey, deviceSeed = deviceSeed, deviceId = B64Url.encode(answer.deviceId),
            reconnectCapability = answer.reconnectCapability, brokerUrl = link.broker, scope = answer.scope,
            basePath = answer.basePath, hostLabel = answer.hostLabel.ifEmpty { link.hostLabel }, pairedAtSeconds = nowSeconds,
        )
    }
}

/** Signed transport handshake: hello → challenge → request(offer) → answer. */
class LinkedDevicesTransportAttempt(
    challenge: TransportChallenge,
    credential: LinkedDevicesCredential,
    offerSdp: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    nonce: ByteArray? = null,
) {
    val request: TransportRequest
    val requestHash: ByteArray
    private val hostKey: ByteArray = credential.hostKey

    init {
        if (challenge.v != 2 || challenge.type != "transport_challenge" || !challenge.hostKey.contentEquals(credential.hostKey)) throw LinkedDevicesProtocolError.HostIdentityChanged
        val deviceIdRaw = credential.deviceId?.let { B64Url.decode(it, 16) }?.takeIf { it.size == 16 }
        val publicKey = credential.devicePublicKey
        if (deviceIdRaw == null || challenge.challengeId.size != 16 || challenge.hostNonce.size != 32 ||
            !challenge.deviceKey.contentEquals(publicKey) || !challenge.deviceId.contentEquals(deviceIdRaw) ||
            nowSeconds - 30 >= challenge.expiresAt
        ) throw LinkedDevicesProtocolError.Invalid("transport challenge")
        if (!LinkedDevicesCrypto.verify(challenge.sig, credential.hostKey, LinkedDevicesTranscript.transportChallenge(challenge))) throw LinkedDevicesProtocolError.BadSignature
        LinkedDevicesCrypto.fingerprint(offerSdp)
        val deviceNonce = nonce ?: LinkedDevicesCrypto.random(32)
        if (deviceNonce.size != 32) throw LinkedDevicesProtocolError.Invalid("transport nonce")
        val unsigned = TransportRequest(challenge.challengeId, challenge.hostNonce, publicKey, deviceNonce, deviceIdRaw, offerSdp, ByteArray(0))
        val transcript = LinkedDevicesTranscript.transportRequest(unsigned, credential.hostKey)
        request = unsigned.copy(sig = LinkedDevicesCrypto.sign(credential.deviceSeed, transcript))
        requestHash = LinkedDevicesTranscript.hash(transcript)
    }

    fun verify(answer: TransportAnswer): String {
        if (answer.v != 2 || answer.type != "transport_answer" || !answer.hostKey.contentEquals(hostKey)) throw LinkedDevicesProtocolError.HostIdentityChanged
        if (!answer.challengeId.contentEquals(request.challengeId) || !answer.hostNonce.contentEquals(request.hostNonce) ||
            !answer.deviceKey.contentEquals(request.deviceKey) || !answer.deviceNonce.contentEquals(request.deviceNonce) ||
            !answer.deviceId.contentEquals(request.deviceId) || !answer.requestHash.contentEquals(requestHash)
        ) throw LinkedDevicesProtocolError.Invalid("transport answer binding")
        if (!LinkedDevicesCrypto.verify(answer.sig, hostKey, LinkedDevicesTranscript.transportAnswer(answer))) throw LinkedDevicesProtocolError.BadSignature
        LinkedDevicesCrypto.fingerprint(answer.answerSdp)
        return answer.answerSdp
    }

    companion object {
        fun hello(credential: LinkedDevicesCredential, nonce: ByteArray? = null): TransportHello {
            val deviceIdRaw = credential.deviceId?.let { B64Url.decode(it, 16) }?.takeIf { it.size == 16 }
            val capability = credential.reconnectCapability?.takeIf { it.size == 32 }
            if (deviceIdRaw == null || capability == null || credential.deviceSeed.size != 32) throw LinkedDevicesProtocolError.Invalid("transport credential")
            val deviceNonce = nonce ?: LinkedDevicesCrypto.random(32)
            if (deviceNonce.size != 32) throw LinkedDevicesProtocolError.Invalid("transport nonce")
            val unsigned = TransportHello(credential.devicePublicKey, deviceIdRaw, capability, deviceNonce, ByteArray(0))
            val transcript = LinkedDevicesTranscript.transportHello(unsigned, credential.hostKey)
            return unsigned.copy(sig = LinkedDevicesCrypto.sign(credential.deviceSeed, transcript))
        }
    }
}
