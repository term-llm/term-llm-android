package com.termllm.app

import com.termllm.app.linked.B64Url
import com.termllm.app.linked.LinkedDevicesCodec
import com.termllm.app.linked.LinkedDevicesCredential
import com.termllm.app.linked.LinkedDevicesCrypto
import com.termllm.app.linked.LinkedDevicesPairingAttempt
import com.termllm.app.linked.LinkedDevicesPairingLink
import com.termllm.app.linked.LinkedDevicesProtocolError
import com.termllm.app.linked.LinkedDevicesTranscript
import com.termllm.app.linked.LinkedDevicesTransportAttempt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Frozen pairing v2 vectors (`scripts/linked-devices/fixtures/vectors_v2.json`, identical to
 * core `internal/pairing/testdata/vectors_v2.json`): transcripts, MAC key, MACs, Ed25519
 * signatures, hashes, SAS and canonical JSON must match byte for byte.
 */
class LinkedDevicesProtocolTest {

    private val v = Json.parseToJsonElement(javaClass.getResourceAsStream("/vectors_v2.json")!!.reader().readText()).jsonObject
    private fun s(key: String) = v.getValue(key).jsonPrimitive.content
    private fun b(key: String) = B64Url.decode(s(key), 256 * 1024) ?: error("vector $key is not canonical base64url")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val hostKey get() = b("host_key")
    private val link get() = LinkedDevicesPairingLink.parse(
        "termllm://pair?v=2&b=https%3A%2F%2Fbroker.example.com%2Fbase%2F&h=" + s("host_key") + "&i=" + s("invitation_id") +
            "&e=1800000000&n=Study+Mac#s=" + s("pairing_secret") + "&c=inv-cap_01"
    )
    private val identity get() = LinkedDevicesCredential(hostKey = hostKey, deviceSeed = b("device_seed"), brokerUrl = link.broker)

    @Test
    fun keysDeriveFromSeedsAndLinkParsesStrictly() {
        assertArrayEquals(hostKey, LinkedDevicesCrypto.publicKey(b("host_seed")))
        assertArrayEquals(b("device_key"), LinkedDevicesCrypto.publicKey(b("device_seed")))
        val parsed = link
        assertEquals("https://broker.example.com/base", parsed.broker)
        assertEquals("Study Mac", parsed.hostLabel)
        assertEquals(1800000000L, parsed.expiresAtSeconds)
        assertEquals("inv-cap_01", parsed.invitationCapability)
        assertArrayEquals(b("pairing_secret"), parsed.pairingSecret)
        assertEquals(B64Url.encode(LinkedDevicesCrypto.sha256(hostKey)), parsed.hostId)
        assertTrue(parsed.isExpired(1800000000L))
        assertTrue(!parsed.isExpired(1799999999L))
        assertTrue(!parsed.redacted().contains(s("pairing_secret")))
        val bad = listOf(
            "termllm://pair?v=2&b=http%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}", // http off loopback
            "termllm://pair?v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000", // no fragment
            "termllm://pair?v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000&x=1#s=${s("pairing_secret")}", // unknown
            "termllm://pair?v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=01#s=${s("pairing_secret")}", // non-canonical expiry
            "termllm://pair?v=2&v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}", // duplicate
            "termllm://pair?v=3&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}", // version
            "termllm://pair/x?v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}", // path
            "termllm://pair?v=2&b=https%3A%2F%2Fbroker.example.com&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}&c=bad%20cap", // capability charset
        )
        for (candidate in bad) {
            try {
                LinkedDevicesPairingLink.parse(candidate)
                fail("accepted: " + candidate)
            } catch (e: LinkedDevicesProtocolError.InvalidLink) {
            }
        }
        // loopback http brokers are allowed (local hosts)
        val local = LinkedDevicesPairingLink.parse("termllm://pair?v=2&b=http%3A%2F%2F127.0.0.1%3A8092&h=${s("host_key")}&i=${s("invitation_id")}&e=1800000000#s=${s("pairing_secret")}")
        assertEquals("http://127.0.0.1:8092", local.broker)
    }

    @Test
    fun pairRequestMatchesTheVectorsByteForByte() {
        val expectedJson = Json.parseToJsonElement(s("pair_request_json")).jsonObject
        val deviceName = expectedJson.getValue("device_name").jsonPrimitive.content
        val attempt = LinkedDevicesPairingAttempt(link, identity, deviceName, nowSeconds = 1799999000L, nonce = b("device_nonce"))
        val transcript = LinkedDevicesTranscript.pairRequest(attempt.request, hostKey)
        assertEquals(hex(b("pair_request_transcript")), hex(transcript))
        assertEquals(hex(b("mac_key")), hex(LinkedDevicesTranscript.macKey(b("pairing_secret"))))
        assertEquals(hex(b("pair_request_mac")), hex(attempt.request.mac))
        assertEquals(hex(b("pair_request_signature")), hex(attempt.request.sig))
        assertEquals(hex(b("pair_request_hash")), hex(attempt.requestHash))
        assertEquals(s("sas"), attempt.sas)
        assertEquals(s("pair_request_json"), LinkedDevicesCodec.encode(attempt.request))
    }

    @Test
    fun pairAnswerVerifiesAndTamperingIsRejected() {
        val expectedJson = Json.parseToJsonElement(s("pair_request_json")).jsonObject
        val attempt = LinkedDevicesPairingAttempt(link, identity, expectedJson.getValue("device_name").jsonPrimitive.content, nowSeconds = 1799999000L, nonce = b("device_nonce"))
        val answer = LinkedDevicesCodec.decodePairAnswer(s("pair_answer_json"))
        assertEquals(hex(b("pair_answer_transcript")), hex(LinkedDevicesTranscript.pairAnswer(answer)))
        assertEquals(hex(b("pair_answer_mac")), hex(answer.mac))
        assertEquals(hex(b("pair_answer_signature")), hex(answer.sig))
        val credential = attempt.verify(answer, nowSeconds = 1799999100L)
        assertEquals(s("device_id"), credential.deviceId)
        assertArrayEquals(b("reconnect_capability"), credential.reconnectCapability)
        assertEquals("web-v1", credential.scope)
        assertTrue(credential.paired)
        // MAC under a different pairing secret, signature by a different host → rejected before any trust.
        try { attempt.verify(answer.copy(mac = ByteArray(32))); fail("bad mac accepted") } catch (e: LinkedDevicesProtocolError.BadMac) {}
        try { attempt.verify(answer.copy(hostLabel = answer.hostLabel + "x")); fail("tampered label accepted") } catch (e: LinkedDevicesProtocolError.BadMac) {}
        try { attempt.verify(answer.copy(hostKey = b("device_key"))); fail("other host accepted") } catch (e: LinkedDevicesProtocolError.HostIdentityChanged) {}
        try { attempt.verify(answer.copy(scope = "admin")); fail("scope accepted") } catch (e: LinkedDevicesProtocolError.Invalid) {}
        // wrong-shaped JSON (extra key) is refused before decoding
        try { LinkedDevicesCodec.decodePairAnswer(s("pair_answer_json").dropLast(1) + ",\"extra\":1}"); fail("extra key accepted") } catch (e: LinkedDevicesProtocolError.Invalid) {}
    }

    @Test
    fun transportHandshakeMatchesTheVectors() {
        val paired = identity.copy(deviceId = s("device_id"), reconnectCapability = b("reconnect_capability"))
        val hello = LinkedDevicesTransportAttempt.hello(paired, nonce = b("transport_hello_nonce"))
        assertEquals(hex(b("transport_hello_transcript")), hex(LinkedDevicesTranscript.transportHello(hello, hostKey)))
        assertEquals(hex(b("transport_hello_signature")), hex(hello.sig))
        assertEquals(s("transport_hello_json"), LinkedDevicesCodec.encode(hello))

        val challenge = LinkedDevicesCodec.decodeTransportChallenge(s("transport_challenge_json"))
        assertEquals(hex(b("transport_challenge_transcript")), hex(LinkedDevicesTranscript.transportChallenge(challenge)))
        assertEquals(hex(b("transport_challenge_signature")), hex(challenge.sig))
        assertEquals(v.getValue("transport_challenge_expires_at").jsonPrimitive.content.toLong(), challenge.expiresAt)
        val attempt = LinkedDevicesTransportAttempt(challenge, paired, s("transport_offer_sdp"), nowSeconds = 1800000000L, nonce = b("transport_device_nonce"))
        assertEquals(hex(b("transport_request_transcript")), hex(LinkedDevicesTranscript.transportRequest(attempt.request, hostKey)))
        assertEquals(hex(b("transport_request_hash")), hex(attempt.requestHash))
        assertEquals(hex(b("transport_request_signature")), hex(attempt.request.sig))
        assertEquals(s("transport_request_json"), LinkedDevicesCodec.encode(attempt.request))

        val answer = LinkedDevicesCodec.decodeTransportAnswer(s("transport_answer_json"))
        assertEquals(hex(b("transport_answer_transcript")), hex(LinkedDevicesTranscript.transportAnswer(answer)))
        assertEquals(hex(b("transport_answer_signature")), hex(answer.sig))
        assertEquals(s("transport_answer_sdp"), attempt.verify(answer))
        try { attempt.verify(answer.copy(answerSdp = answer.answerSdp + "\n")); fail("sdp tamper accepted") } catch (e: LinkedDevicesProtocolError.BadSignature) {}
        // an expired challenge is refused
        try {
            LinkedDevicesTransportAttempt(challenge, paired, s("transport_offer_sdp"), nowSeconds = challenge.expiresAt + 31, nonce = b("transport_device_nonce"))
            fail("expired challenge accepted")
        } catch (e: LinkedDevicesProtocolError.Invalid) {}
    }

    @Test
    fun sdpFingerprintsAreValidatedAndNormalised() {
        assertEquals("sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
            LinkedDevicesCrypto.fingerprint("v=0\r\na=fingerprint:sha-256 aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99\r\n"))
        for (bad in listOf("v=0", "a=fingerprint:sha-256 AA:BB", "a=fingerprint:md5 " + "AA:".repeat(15) + "AA",
            "a=fingerprint:sha-256 " + "AA:".repeat(31) + "AA\na=fingerprint:sha-256 " + "BB:".repeat(31) + "BB")) {
            try { LinkedDevicesCrypto.fingerprint(bad); fail("accepted: " + bad.take(40)) } catch (e: LinkedDevicesProtocolError.Invalid) {}
        }
        assertTrue(LinkedDevicesCrypto.validBasePath("/"))
        assertTrue(LinkedDevicesCrypto.validBasePath("/work"))
        assertTrue(!LinkedDevicesCrypto.validBasePath("/a/../b") && !LinkedDevicesCrypto.validBasePath("work") && !LinkedDevicesCrypto.validBasePath("/x?y"))
    }
}
