package com.termllm.app

import kotlinx.coroutines.flow.first
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.push.PushCenter
import com.termllm.app.push.PushCrypto
import com.termllm.app.push.PushIdentity
import com.termllm.app.push.PushNotifications
import com.termllm.app.push.PushPayload
import com.termllm.app.push.PushStreamService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The whole push chain against the container-only relay: enroll this device, send an
 * aes128gcm record the app has to decrypt with its own auth secret, see the
 * notification, check the tap target, prove foreground suppression, and confirm the
 * exact acknowledgement empties the relay's queue.
 */
@RunWith(AndroidJUnit4::class)
class PushEndToEndTest {
    @get:org.junit.Rule val foreground = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    private fun enableForegroundNotifications() {
        lateinit var vm:com.termllm.app.ui.AppViewModel
        foreground.scenario.onActivity { vm=androidx.lifecycle.ViewModelProvider(it)[com.termllm.app.ui.AppViewModel::class.java] }
        kotlinx.coroutines.runBlocking { vm.settingsReady.first { it } }
        instrumentation.runOnMainSync { vm.setNotificationsEnabled(true) }
        instrumentation.waitForIdleSync()
    }


    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val relay = "https://10.0.2.2:8443"
    private val eventPrefix = "e2e-" + System.nanoTime() + "-"
    private val origin = "http://localhost:8080"
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** A P-256 VAPID key this test signs with, so the relay's verification is exercised. */
    private val vapidKeys = com.termllm.app.push.PushCrypto.generateKeyPair()
    private val vapidPublic = com.termllm.app.push.PushCrypto.base64Url(
        com.termllm.app.push.PushCrypto.publicKeyBytes(vapidKeys.public)
    )

    private fun b64(raw: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    /** ES256 JWT with the header/claims a push service expects. */
    private fun vapidToken(audience: String, expiresInSeconds: Long = 3600): String {
        val header = b64("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64(
            ("""{"aud":""" + "\"" + audience + "\"," + """ "exp":""" + (System.currentTimeMillis() / 1000 + expiresInSeconds) +
                """  ,"sub":"mailto:test@example.invalid"}""").toByteArray()
        )
        val signingInput = (header + "." + claims).toByteArray()
        val signer = java.security.Signature.getInstance("SHA256withECDSA")
        signer.initSign(vapidKeys.private)
        signer.update(signingInput)
        val der = signer.sign()
        // DER SEQUENCE of two INTEGERs -> fixed 32-byte r||s for JWS.
        val raw = derToRaw(der)
        return "vapid t=$header.$claims." + b64(raw)
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var index = 2
        if (der[1].toInt() and 0xff > 0x80) index = 3
        val out = ByteArray(64)
        for (part in 0..1) {
            index += 1 // 0x02
            val length = der[index].toInt() and 0xff
            index += 1
            var start = index
            var size = length
            while (size > 32) { start += 1; size -= 1 }
            System.arraycopy(der, start, out, part * 32 + (32 - size), size)
            index += length
        }
        return out
    }

    @Before
    fun setUp() {
        enableForegroundNotifications()
        instrumentation.uiAutomation.executeShellCommand("pm grant " + context.packageName + " android.permission.POST_NOTIFICATIONS").close()
        PushCenter.init(context)
        PushCenter.setRoute(origin, "/")
        PushCenter.disable()
    }

    @After
    fun tearDown() {
        PushStreamService.stop(context)
        PushCenter.disable()
        manager.cancelAll()
    }

    private fun enroll(): String {
        val endpoint = kotlinx.coroutines.runBlocking { PushCenter.enroll(vapidKey = vapidPublic, relayBase = relay) }
        assertNotNull("enrollment must return a stream endpoint", endpoint)
        assertTrue(PushCenter.state.value.enabled)
        return endpoint!!
    }

    private fun send(endpoint: String, payload: PushPayload): String {
        val identity = PushIdentity(context)
        val record = identity.load()
        val secret = identity.authSecret(PushCenter.ROUTE, record)!!
        val sealed = PushCrypto.seal(
            com.termllm.app.push.PushMessageJson.encode(payload).toByteArray(),
            PushCrypto.base64UrlDecode(record.publicKey),
            secret,
        )
        val token = endpoint.substringAfterLast('/')
        val request = Request.Builder()
            .url(relay + "/v1/push/" + token)
            .header("Authorization", vapidToken(audienceFor(endpoint)))
            .header("Content-Encoding", "aes128gcm")
            .header("TTL", "300")
            .header("Urgency", "normal")
            .post(sealed.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals("relay must accept the record: " + response.code, 201, response.code)
            return JSONObject(response.body!!.string()).getString("id")
        }
    }

    /** The node-facing origin the endpoint was minted on (relay validates aud). */
    private fun audienceFor(endpoint: String): String = relay

    private fun queueFor(endpoint: String): List<String> {
        val token = endpoint.substringAfterLast('/')
        val request = Request.Builder().url(relay + "/v1/queue/" + token).get().build()
        client.newCall(request).execute().use { response ->
            val queue = JSONObject(response.body!!.string()).getJSONArray("queue")
            return (0 until queue.length()).map { queue.getString(it) }
        }
    }

    private fun waitForNotification(timeoutMs: Long = 15_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val posted = manager.activeNotifications.any { it.notification.channelId == PushNotifications.ALERTS }
            if (posted) return true
            SystemClock.sleep(300)
        }
        return false
    }

    @Test
    fun enrollDeliverNotifyTapAndAcknowledge() {
        val endpoint = enroll()
        PushStreamService.start(context, endpoint)
        SystemClock.sleep(1500)

        send(
            endpoint,
            PushPayload(
                title = "Test Jarvis",
                body = "The build finished",
                url = "/chat/1",
                sessionId = "s-1",
                outcome = "completed",
                eventId = eventPrefix + "1",
                createdAt = "2026-11-04T12:00:00Z",
            ),
        )
        assertTrue("notification should be posted", waitForNotification())
        val posted = manager.activeNotifications.first { it.notification.channelId == PushNotifications.ALERTS }
        assertEquals("Test Jarvis", posted.notification.extras.getString("android.title"))
        val contentIntent = posted.notification.contentIntent
        assertNotNull(contentIntent)

        // Exact ACK: the relay's queue is empty once the app has handled the message.
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline && queueFor(endpoint).isNotEmpty()) SystemClock.sleep(300)
        assertTrue("relay queue should be acknowledged empty", queueFor(endpoint).isEmpty())

        // A replay of the same event id must not notify again.
        manager.cancelAll()
        send(endpoint, PushPayload(title = "Test Jarvis", body = "duplicate", url = "/chat/1", eventId = eventPrefix + "1"))
        SystemClock.sleep(2500)
        assertFalse("replay must be dropped", waitForNotification(2_000))
    }

    @Test
    fun foregroundPageSuppressesItsOwnNotification() {
        val endpoint = enroll()
        PushStreamService.start(context, endpoint)
        SystemClock.sleep(1500)
        PushCenter.recordPresence(PushCenter.ROUTE, origin, "s-foreground", "doc-1", true, java.time.Instant.now().plusSeconds(60).toString())
        send(endpoint, PushPayload(title = "Suppressed", body = "page is open", url = "/chat/1", sessionId = "s-foreground", eventId = eventPrefix + "2"))
        SystemClock.sleep(3000)
        assertFalse("foreground page suppresses its own alert", waitForNotification(1_500))
        PushCenter.recordPresence(PushCenter.ROUTE, origin, "s-foreground", "doc-1", false, "")
        send(endpoint, PushPayload(title = "Shown", body = "page left", url = "/chat/1", sessionId = "s-foreground", eventId = eventPrefix + "3"))
        assertTrue("once the page is gone the alert shows", waitForNotification())
    }

    @Test
    fun disablingRetiresTheSubscription() {
        val endpoint = enroll()
        PushCenter.disable()
        assertFalse(PushCenter.state.value.enabled)
        val token = endpoint.substringAfterLast('/')
        val request = Request.Builder().url(relay + "/v1/queue/" + token).get().build()
        client.newCall(request).execute().use { response ->
            assertEquals("retired subscription is unknown to the relay", 404, response.code)
        }
    }
}
