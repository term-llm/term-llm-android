package com.termllm.app.push

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native side of `window.__termLLMNativePush`. Every method answers for **one**
 * source — the route this handler was constructed for by the holder — and nothing
 * here exposes relay keys or device secrets to the page.
 *
 * One handler per source is deliberate: the old build guessed the route from "the
 * last page that talked to us" or from a single in-memory origin, which is how two
 * sources with the same scope string ended up sharing a subscription. [routeKey] is
 * bound at install time from the connection that is actually loaded.
 *
 * Handlers run on the push bridge's worker thread (see [PushBridge]), so the
 * `runBlocking` around enrollment never blocks the UI thread. That is also why this
 * class keeps its synchronous contract: the bridge owns reply ordering and timeout.
 */
class PushHandler(
    private val routeKey: String = PushRouting.LEGACY_ROUTE,
    private val origin: String = "",
    private val mountPrefix: String = "/",
    private val capability: (String) -> Boolean = { false },
    private val markRead: (
        source: String,
        sessionId: String,
        storeInstanceId: String,
        attentionSeq: Long,
    ) -> Unit = { _, _, _, _ -> },
) {

    fun handle(method: String, argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val scope = args.optString("scope")
        return when (method) {
            "permission" -> jsonTrue(isPermitted(scope))
            "requestPermission" -> jsonTrue(requestPermissionIfConsented(scope))
            "getSubscription" -> subscriptionOrNull()
            "subscribe" -> {
                val key = args.optString("applicationServerKey")
                val relay = args.optString("relay").takeIf { it.isNotBlank() }
                val endpoint = kotlinx.coroutines.runBlocking {
                    PushCenter.enroll(routeKey = routeKey, vapidKey = key, relayBase = relay)
                }
                if (endpoint != null) {
                    PushStreamService.start(PushCenter.context(), endpoint, routeKey)
                    PushCenter.subscription(routeKey)?.toString() ?: "null"
                } else {
                    throw IllegalStateException(PushCenter.state.value.lastError ?: "enrollment failed")
                }
            }
            "unsubscribe" -> {
                PushCenter.forgetRoute(routeKey)
                "true"
            }
            "disable" -> {
                PushCenter.disableAndForget(routeKey)
                "true"
            }
            "notificationCapabilities" -> JSONObject()
                .put("version", 2)
                .put("window_id", PushCenter.windowId())
                .put("document_id", PushCenter.documentId())
                .toString()
            "notificationPresence" -> {
                PushCenter.recordPresence(
                    routeKey = routeKey,
                    source = args.optString("source").ifBlank { origin },
                    sessionId = args.optString("session_id"),
                    documentId = args.optString("document_id"),
                    active = args.optBoolean("active", false),
                    expiresAt = args.optString("expires_at"),
                )
                "true"
            }
            "notificationRead" -> {
                markRead(
                    args.optString("source").ifBlank { origin },
                    args.optString("session_id"),
                    args.optString("store_instance_id"),
                    args.optLong("attention_seq", 0L),
                )
                "true"
            }
            "notificationEnrollment", "notificationCleanupAcknowledged" -> "true"
            "notificationPendingCleanup" -> JSONArray().toString()
            else -> throw IllegalArgumentException("unknown push method: " + method)
        }
    }

    /** Consent for this route *and* the OS actually allowing a notification. */
    private fun isPermitted(scope: String): Boolean =
        PushCenter.permissionGranted() && (capability(scope) || PushCenter.enabledForRoute(routeKey))

    /**
     * Ask the OS only when this source already has consent: prompting for a
     * notification permission the user has not enabled delivery for is noise, and
     * checking `isPermitted` first would short-circuit the prompt forever once the
     * OS has denied it (the denial is exactly the state that needs asking about).
     */
    private fun requestPermissionIfConsented(scope: String): Boolean {
        val consented = capability(scope) || PushCenter.enabledForRoute(routeKey)
        if (!consented) return false
        if (PushCenter.permissionGranted()) return true
        return runCatching { kotlinx.coroutines.runBlocking { PushCenter.requestNotificationsPermission() } }
            .getOrDefault(false)
    }

    private fun subscriptionOrNull(): String {
        val subscription = PushCenter.subscription(routeKey) ?: return "null"
        return subscription.toString()
    }

    private fun jsonTrue(value: Boolean) = if (value) "true" else "false"
}
