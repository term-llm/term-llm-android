package com.termllm.app

import com.termllm.app.push.DecodedPush
import com.termllm.app.push.PushCrypto
import com.termllm.app.push.PushDeliveryStore
import com.termllm.app.push.PushMessageDecoder
import com.termllm.app.push.PushPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * The Web Push half of the pipeline: HKDF against RFC 5869, a real aes128gcm record
 * sealed for a subscription and opened with its auth secret, refusal of a foreign
 * secret, route validation for tap targets, the window-id contract from the iOS
 * fixture, and the replay ledger's bound.
 */
class PushCryptoTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(System.getProperty("java.io.tmpdir"), "push-" + System.nanoTime() + ".json")
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = (0x00..0x0c).map { it.toByte() }.toByteArray()
        val info = (0xf0..0xf9).map { it.toByte() }.toByteArray()
        val okm = PushCrypto.hkdf(salt, ikm, info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { String.format("%02x", it) })
    }

    @Test
    fun sealedRecordOpensWithTheSubscriptionSecret() {
        val keys = PushCrypto.generateKeyPair()
        val publicBytes = PushCrypto.publicKeyBytes(keys.public)
        val authSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = """{"title":"Test Jarvis","body":"Run finished","url":"/ui/chat/1","event_id":"evt-1"}"""
        val record = PushCrypto.seal(payload.toByteArray(), publicBytes, authSecret)
        val decoded: DecodedPush = PushMessageDecoder.decode(record, keys.private, publicBytes, listOf(authSecret))
        assertEquals("Test Jarvis", decoded.payload.title)
        assertEquals("evt-1", decoded.payload.eventId)
        assertEquals(0, decoded.authSecretIndex)
    }

    @Test
    fun aForeignSecretCannotOpenTheRecord() {
        val keys = PushCrypto.generateKeyPair()
        val publicBytes = PushCrypto.publicKeyBytes(keys.public)
        val mine = ByteArray(16) { 1 }
        val theirs = ByteArray(16) { 2 }
        val record = PushCrypto.seal("""{"title":"a","body":"b"}""".toByteArray(), publicBytes, mine)
        val failure = runCatching { PushMessageDecoder.decode(record, keys.private, publicBytes, listOf(theirs)) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun theRightSecretIsSelectedFromAList() {
        val keys = PushCrypto.generateKeyPair()
        val publicBytes = PushCrypto.publicKeyBytes(keys.public)
        val first = ByteArray(16) { 9 }
        val second = ByteArray(16) { 7 }
        val record = PushCrypto.seal("""{"title":"second","body":"route two"}""".toByteArray(), publicBytes, second)
        val decoded = PushMessageDecoder.decode(record, keys.private, publicBytes, listOf(first, second))
        assertEquals(1, decoded.authSecretIndex)
        assertEquals("second", decoded.payload.title)
    }

    @Test
    fun tapTargetsMustStayInsideThisOriginAndMount() {
        val origin = "https://mini.example:8443"
        val mount = "/ui/"
        fun payload(url: String) = PushPayload(title = "x", body = "y", url = url)
        assertEquals("https://mini.example:8443/ui/chat/1", PushMessageDecoder.resolve(payload("/ui/chat/1"), origin, mount))
        assertNull("absolute other host", PushMessageDecoder.resolve(payload("https://evil.example/ui/chat/1"), origin, mount))
        assertNull("outside the mount", PushMessageDecoder.resolve(payload("/admin"), origin, mount))
        assertNull("protocol-relative", PushMessageDecoder.resolve(payload("//evil.example/ui/"), origin, mount))
        // A `notify app` payload carries no url: the alert belongs to this source, so it
        // resolves to the mount root instead of being refused.
        assertEquals(origin + mount, PushMessageDecoder.resolve(payload(""), origin, mount))
    }

    @Test
    fun windowIdentityMatchesTheIosContract() {
        assertEquals(
            "native_fgfH80ms_cD7KCrz8oj_BeFAv-Ydh6epT441bs3FEqE",
            PushCrypto.windowId("01234567-89AB-CDEF-0123-456789ABCDEF"),
        )
        assertEquals(
            "native_47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU",
            PushCrypto.windowId(""),
        )
    }

    @Test
    fun theReplayLedgerIsBoundedAndRemembersEvents() {
        val store = PushDeliveryStore(file)
        repeat(PushDeliveryStore.LEDGER_CAP + 25) { index ->
            store.record("route", "evt-" + index, "2026-11-04T10:" + (index % 60).toString().padStart(2, '0') + ":00Z")
        }
        val ledger = store.load()
        assertEquals(PushDeliveryStore.LEDGER_CAP, ledger.events.size)
        assertTrue(store.seen("evt-" + (PushDeliveryStore.LEDGER_CAP + 24)))
        assertFalse("oldest entries fall out of the bounded ledger", store.seen("evt-0"))
        assertEquals("2026-11-04T10:59:00Z", ledger.routes["route"]?.watermark)
    }

    @Test
    fun consentIsOffUntilEnabledAndRetireIsRemembered() {
        val store = PushDeliveryStore(file)
        assertFalse(store.route("route").enabled)
        store.setEnabled("route", true)
        assertTrue(store.route("route").enabled)
        store.retire("route")
        val retired = store.route("route")
        assertFalse(retired.enabled)
        assertTrue(retired.retired)
    }
}
