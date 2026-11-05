package com.termllm.app

import com.termllm.app.push.PushCrypto
import com.termllm.app.push.PushDeliveryStore
import com.termllm.app.push.PushMessageDecoder
import com.termllm.app.push.PushMessageJson
import com.termllm.app.push.PushPayload
import com.termllm.app.push.PushRelay
import com.termllm.app.push.PushRouteStore
import com.termllm.app.push.PushRouting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Per-source push routing (hardening row 5). The audit found one global relay, one
 * in-memory origin/mount and a global replay ledger, so two sources could overwrite
 * or cross-deliver each other. These tests pin the replacement rules: routing
 * metadata and the replay ledger are per route, a legacy single-route enrollment is
 * imported once and adopted only when the mapping is unambiguous, and a release
 * build can only talk to an HTTPS relay.
 */
class PushRoutingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "push-routing-" + System.nanoTime()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun originCannotChooseBetweenSiblingMounts() {
        val store = PushRouteStore(File(dir, "siblings.json"))
        store.attach("a", "https://same.example", "/a/")
        store.attach("b", "https://same.example", "/b/")
        assertNull(store.routeForOrigin("https://same.example"))
    }

    @Test
    fun twoSourcesKeepTheSameEventIdSeparately() {
        val store = PushDeliveryStore(File(dir, "delivery.json"))
        store.setEnabled("phone-a", true)
        store.setEnabled("phone-b", true)
        store.record("phone-a", "evt-1", "2026-11-05T01:00:00Z")
        assertTrue(store.seen("phone-a", "evt-1"))
        assertFalse("a colliding id from another source is not a replay", store.seen("phone-b", "evt-1"))
        assertFalse(store.seen("phone-c", "evt-1"))
        store.record("phone-b", "evt-1", "2026-11-05T01:00:01Z")
        assertTrue(store.seen("phone-b", "evt-1"))
        assertEquals("2026-11-05T01:00:00Z", store.route("phone-a").watermark)
        assertEquals("2026-11-05T01:00:01Z", store.route("phone-b").watermark)
    }

    @Test
    fun registrationsArePerRouteWhileLegacyEntriesStillRead() {
        val file = File(dir, "delivery.json")
        file.writeText(
            """{"routes":{"source":{"enabled":true}},"events":["evt-old"],""" +
                """"registration":"https://relay.example/v1/stream/abc"}"""
        )
        val store = PushDeliveryStore(file)
        assertTrue(store.route("source").enabled)
        assertTrue("pre-2.x ledger entries belong to the legacy route", store.seen("source", "evt-old"))
        assertTrue(store.seen("evt-old"))
        assertEquals("https://relay.example/v1/stream/abc", store.registration())
        store.setRegistration("phone-a", "https://relay.example/v1/stream/aaa")
        store.setRegistration("phone-b", "https://relay.example/v1/stream/bbb")
        assertEquals("https://relay.example/v1/stream/aaa", store.registration("phone-a"))
        assertEquals("https://relay.example/v1/stream/bbb", store.registration("phone-b"))
        assertNull(store.registration("phone-c"))
    }

    @Test
    fun routesPersistOriginMountAndRelaySeparately() {
        val file = File(dir, "routes.json")
        val store = PushRouteStore(file)
        store.attach("phone-a", "https://one.example:8443/", "/ui/")
        store.attach("phone-b", "https://two.example", "/other/")
        store.recordEnrollment(
            id = "phone-a",
            endpoint = "https://relay-a.example/v1/stream/a1",
            pushEndpoint = "https://relay-a.example/v1/push/a1",
            deviceSecret = "secret-a",
            relay = "https://relay-a.example",
            vapidKey = "vapid-a",
        )
        store.recordEnrollment(
            id = "phone-b",
            endpoint = "http://localhost:8081/v1/stream/b1",
            pushEndpoint = "http://localhost:8081/v1/push/b1",
            deviceSecret = "secret-b",
            relay = "http://localhost:8081",
            vapidKey = "vapid-b",
        )

        val reopened = PushRouteStore(file)
        assertEquals("https://one.example:8443", reopened.route("phone-a")!!.origin)
        assertEquals("/ui/", reopened.route("phone-a")!!.mountPrefix)
        assertEquals("https://relay-a.example", reopened.route("phone-a")!!.relay)
        assertEquals("secret-a", reopened.route("phone-a")!!.deviceSecret)
        assertEquals("https://two.example", reopened.route("phone-b")!!.origin)
        assertEquals("/other/", reopened.route("phone-b")!!.mountPrefix)
        assertEquals("http://localhost:8081", reopened.route("phone-b")!!.relay)
        assertEquals("secret-b", reopened.route("phone-b")!!.deviceSecret)
        assertTrue(reopened.route("phone-a")!!.enrolled)
        assertTrue(reopened.route("phone-b")!!.enrolled)
        assertEquals("phone-a", reopened.routeForEndpoint("https://relay-a.example/v1/stream/a1")!!.id)
        assertEquals("phone-b", reopened.routeForOrigin("https://two.example")!!.id)
        assertEquals(listOf("phone-a", "phone-b"), reopened.enrolledRoutes { true }.map { it.id })
        assertEquals(listOf("phone-b"), reopened.enrolledRoutes { it == "phone-b" }.map { it.id })
    }

    @Test
    fun changingTheOriginIsReportedSoTheOldSubscriptionIsRetired() {
        val store = PushRouteStore(File(dir, "routes.json"))
        val first = store.attach("phone-a", "https://one.example", "/")
        assertTrue(first.created)
        assertFalse(first.originChanged)
        assertFalse(store.attach("phone-a", "https://one.example/", "/").originChanged)
        assertTrue(
            "a different origin must retire the old subscription",
            store.attach("phone-a", "https://two.example", "/").originChanged,
        )
    }

    @Test
    fun legacyEnrollmentIsImportedOnceAndAdoptedOnlyForASingleSource() {
        val file = File(dir, "routes.json")
        val store = PushRouteStore(file)
        assertTrue(
            store.migrateLegacy(
                enabled = true,
                endpoint = "https://relay.example/v1/stream/old",
                pushEndpoint = "https://relay.example/v1/push/old",
                relay = "https://relay.example",
                deviceSecret = "old-secret",
            )
        )
        val legacy = store.route("source")!!
        assertEquals("the old origin was never persisted, so it is not guessed", "", legacy.origin)
        assertEquals("https://relay.example", legacy.relay)
        assertFalse("migration runs once", store.migrateLegacy(true, "x", "y", "z", "q"))

        assertNull(
            "two sources have no honest mapping to the old global subscription",
            store.adoptLegacy(2, "phone-a", "https://one.example", "/ui/"),
        )
        assertNotNull(store.route("source"))

        val adopted = store.adoptLegacy(1, "phone-a", "https://one.example", "/ui/")
        assertNotNull(adopted)
        assertEquals("old-secret", adopted!!.deviceSecret)
        assertEquals("https://one.example", adopted.origin)
        assertEquals("/ui/", adopted.mountPrefix)
        assertTrue(adopted.adoptedLegacy)
        assertNull("the legacy route is retired by adoption", store.route("source"))

        val reopened = PushRouteStore(file)
        assertEquals("phone-a", reopened.load().legacyAdoptedBy)
        assertNull("adoption happens once", reopened.adoptLegacy(1, "phone-b", "https://two.example", "/"))
    }

    @Test
    fun aLegacyEnrollmentWithoutConsentIsNotResurrected() {
        val store = PushRouteStore(File(dir, "routes.json"))
        assertFalse(
            store.migrateLegacy(false, "https://relay.example/v1/stream/old", "p", "https://relay.example", "s")
        )
        assertNull(store.route("source"))
        assertTrue(store.load().legacyMigrated)
    }

    @Test
    fun relayUrlsAreHttpsInReleaseAndLoopbackOnlyInDebug() {
        assertEquals("https://relay.example", PushRelay.normalize("https://relay.example/", debug = false))
        assertEquals("https://relay.example", PushRelay.normalize("relay.example", debug = false))
        assertEquals("https://relay.example/push", PushRelay.normalize("https://relay.example/push/", debug = false))
        assertNull(
            "plain HTTP is never allowed in a release build",
            PushRelay.normalize("http://10.0.2.2:8081", debug = false),
        )
        assertNull(PushRelay.normalize("http://relay.example", debug = false))
        assertEquals("http://10.0.2.2:8081", PushRelay.normalize("http://10.0.2.2:8081", debug = true))
        assertEquals("http://localhost:9000", PushRelay.normalize("http://localhost:9000", debug = true))
        assertNull(
            "even a debug build refuses a public cleartext relay",
            PushRelay.normalize("http://relay.example", debug = true),
        )
        assertNull(
            "credentials cannot ride inside a relay URL",
            PushRelay.normalize("https://user:pass@relay.example", debug = false),
        )
        assertNull(PushRelay.normalize("ws://relay.example", debug = false))
        assertNull(PushRelay.normalize("", debug = false))
        assertNull("release builds ship no relay", PushRelay.defaultRelay(debug = false))
        assertEquals("http://10.0.2.2:8081", PushRelay.defaultRelay(debug = true))
    }

    @Test
    fun notificationIdsAndGroupsAreScopedToTheRoute() {
        val a = PushRouting.notificationId("phone-a", "evt-1")
        val b = PushRouting.notificationId("phone-b", "evt-1")
        assertNotEquals("same event id, different source", a, b)
        assertTrue(a > 0 && b > 0)
        assertEquals(a, PushRouting.notificationId("phone-a", "evt-1"))
        assertNotEquals(PushRouting.groupKey("phone-a", "s-1"), PushRouting.groupKey("phone-b", "s-1"))
    }

    @Test
    fun mountsCannotMatchASiblingPrefix() {
        assertEquals("/ui/", PushRouting.mountPrefix("ui"))
        assertEquals("/ui/", PushRouting.mountPrefix("/ui"))
        assertEquals("/", PushRouting.mountPrefix(""))
        assertTrue(PushRouting.pathInMount("/ui/chat/1", "/ui"))
        assertTrue(PushRouting.pathInMount("/ui", "/ui"))
        assertFalse("a sibling mount is not inside this one", PushRouting.pathInMount("/uix/chat/1", "/ui"))
        assertTrue(PushRouting.pathInMount("/anything", "/"))
    }

    @Test
    fun deepLinksFollowTheRouteThatOwnsTheMessage() {
        val payload = PushPayload(title = "x", body = "y", url = "/ui/chat/1")
        assertEquals(
            "https://one.example:8443/ui/chat/1",
            PushMessageDecoder.resolve(payload, "https://one.example:8443", "/ui/"),
        )
        assertNull(
            "source B's mount must not accept source A's path",
            PushMessageDecoder.resolve(payload, "https://two.example", "/other/"),
        )
        assertNull(
            "an absolute url is only accepted for its own origin",
            PushMessageDecoder.resolve(
                PushPayload(title = "x", body = "y", url = "https://one.example/ui/chat/1"),
                "https://two.example",
                "/",
            ),
        )
        assertNull(
            "a route with no recorded origin gets no tap target",
            PushMessageDecoder.resolve(payload, "", "/"),
        )
        assertEquals(
            "https://one.example/ui/",
            PushMessageDecoder.resolve(PushPayload(title = "x", body = "y", url = ""), "https://one.example", "/ui/"),
        )
    }

    @Test
    fun theAuthSecretNamesTheRouteThatOwnsTheRecord() {
        val keys = PushCrypto.generateKeyPair()
        val publicBytes = PushCrypto.publicKeyBytes(keys.public)
        val secretA = ByteArray(16) { 1 }
        val secretB = ByteArray(16) { 2 }
        val record = PushCrypto.seal(
            PushMessageJson.encode(
                PushPayload(title = "B only", body = "run finished", url = "/chat/1", eventId = "evt-9")
            ).toByteArray(),
            publicBytes,
            secretB,
        )
        val decoded = PushMessageDecoder.decodeForRoutes(
            record,
            keys.private,
            publicBytes,
            linkedMapOf("phone-a" to secretA, "phone-b" to secretB),
        )
        assertEquals("phone-b", decoded.routeKey)
        assertEquals("B only", decoded.payload.title)
        assertEquals(1, decoded.authSecretIndex)

        val forged = ByteArray(16) { 3 }
        assertTrue(
            "a record no enrolled route can open is refused",
            runCatching {
                PushMessageDecoder.decodeForRoutes(
                    record,
                    keys.private,
                    publicBytes,
                    linkedMapOf("phone-a" to secretA, "phone-c" to forged),
                )
            }.isFailure,
        )
    }

    @Test
    fun aLedgerWithLegacyAndRouteEntriesReadsBothWays() {
        val store = PushDeliveryStore(File(dir, "delivery.json"))
        store.record("phone-a", "evt-new", "2026-11-05T02:00:00Z")
        assertTrue(store.seen("phone-a", "evt-new"))
        assertTrue("the legacy any-route check still sees route entries", store.seen("evt-new"))
        assertFalse(store.seen("phone-b", "evt-new"))
    }

    @Test
    fun theLedgerStaysBoundedAcrossRoutes() {
        val store = PushDeliveryStore(File(dir, "delivery.json"))
        repeat(PushDeliveryStore.LEDGER_CAP + 30) { index ->
            store.record(if (index % 2 == 0) "phone-a" else "phone-b", "evt-" + index, null)
        }
        assertEquals(PushDeliveryStore.LEDGER_CAP, store.load().events.size)
        assertTrue(store.seen("phone-b", "evt-" + (PushDeliveryStore.LEDGER_CAP + 29)))
        assertFalse(store.seen("phone-a", "evt-0"))
    }

    @Test
    fun concurrentWritersNeverCorruptOrDropTheRouteTable() {
        val file = File(dir, "routes.json")
        val store = PushRouteStore(file)
        val threads = (0 until 8).map { worker ->
            Thread {
                repeat(15) { round ->
                    val id = "phone-" + worker
                    store.attach(id, "https://" + id + ".example", "/ui/")
                    store.recordEnrollment(
                        id = id,
                        endpoint = "https://relay.example/v1/stream/" + id + "-" + round,
                        pushEndpoint = "https://relay.example/v1/push/" + id,
                        deviceSecret = "secret-" + id,
                        relay = "https://relay.example",
                        vapidKey = "vapid",
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val reopened = PushRouteStore(file)
        assertEquals(8, reopened.routes().size)
        (0 until 8).forEach { worker ->
            val route = reopened.route("phone-" + worker)!!
            assertEquals("https://phone-" + worker + ".example", route.origin)
            assertEquals("secret-phone-" + worker, route.deviceSecret)
            assertTrue(route.endpoint!!.startsWith("https://relay.example/v1/stream/phone-" + worker + "-"))
        }
    }

    @Test
    fun relayRefusalsExplainThemselves() {
        listOf("", "not a url", "http://10.0.2.2:8081", "https://user:pass@relay.example", "ws://relay.example")
            .forEach { input ->
                assertTrue(
                    "a refused relay must say why: $input",
                    PushRelay.refusal(input, debug = false).isNotBlank(),
                )
            }
        assertTrue(PushRelay.refusal("http://10.0.2.2:8081", debug = false).contains("HTTPS"))
    }

    @Test
    fun scopeStringsDoNotIdentifyARoute() {
        // The page's scope is the node's controller prefix (often ""), never the route.
        assertEquals("/a/b/", PushRouting.mountPrefix("/a/b"))
        assertEquals("/a/b", PushRouting.mountPath("/a/b/"))
        assertEquals("", PushRouting.mountPath("/"))
        assertTrue(PushRouting.pathInMount("/a/b/c", "/a/b/"))
        assertFalse(PushRouting.pathInMount("/a/bc", "/a/b/"))
    }
}
