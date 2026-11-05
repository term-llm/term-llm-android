package com.termllm.app.push

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `window.__termLLMNativePush`, the page-facing half of push, matching the iOS
 * `NativePushBridge` contract (version 1 plus the v2 notification methods).
 *
 * The page enrols a subscription with its own node: it asks this bridge for the
 * Web Push endpoint and keys, then POSTs them to `/v1/push/subscribe` itself. That
 * keeps the node as the only thing that can send, and keeps the relay a dumb,
 * authenticated transport.
 *
 * Hardening from the audit:
 *  * handlers run on a worker, never on the WebView/UI thread — enrollment is a
 *    network call and `runBlocking` behind it must not freeze the page;
 *  * each request is answered exactly once (worker result, failure or a 30s
 *    watchdog), so a hung relay cannot leave the page's promise pending forever;
 *  * the document-start shim refuses to install outside the top frame and outside
 *    this source's mount, and the listener refuses a non-main-frame or foreign
 *    origin message;
 *  * no payload, deep link or credential is ever logged.
 *
 * The route key identifies the source the document belongs to. Pass it explicitly
 * (the holder knows the connection). An origin alone is not an identity: sibling
 * server mounts can share it.
 */
object PushBridge {

    const val JS_NAME = "termllmNativePush"

    /** Long enough for a cold enrollment, short enough that a page is never stuck. */
    private const val HANDLER_TIMEOUT_MS = 30_000L

    private val workers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "push-bridge").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val main = Handler(Looper.getMainLooper())

    fun install(
        webView: WebView,
        origin: String,
        routeKey: String,
        mountPrefix: String? = null,
        handler: (method: String, arguments: String) -> String,
    ) {
        val normalizedOrigin = origin.trimEnd('/')
        if (normalizedOrigin.isBlank() || routeKey.isBlank()) return
        val mount = PushRouting.mountPrefix(mountPrefix)
        // Attach metadata off the UI thread: this reads and writes the route table, and
        // the origin lookup is a file read.
        workers.execute {
            runCatching {
                val application = webView.context.applicationContext
                PushCenter.ensureInit(application)
                PushCenter.attachRoute(routeKey, normalizedOrigin, mount)
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, JS_NAME, setOf(normalizedOrigin)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != normalizedOrigin) {
                reply.postMessage(errorJson("origin not allowed"))
                return@addWebMessageListener
            }
            val body = message.data ?: return@addWebMessageListener
            val request = runCatching { JSONObject(body) }.getOrNull()
            if (request == null) {
                reply.postMessage(errorJson("invalid request"))
                return@addWebMessageListener
            }
            val id = request.optString("id")
            val method = request.optString("method")
            val args = request.optString("arguments").ifBlank { "{}" }
            val answered = AtomicBoolean(false)
            val timeout = Runnable {
                if (answered.compareAndSet(false, true)) {
                    deliver(webView, id, errorEnvelope(id, "the push bridge did not answer in time"))
                }
            }
            main.postDelayed(timeout, HANDLER_TIMEOUT_MS)
            workers.execute {
                val outcome = runCatching { handler(method, args) }
                if (!answered.compareAndSet(false, true)) return@execute
                main.removeCallbacks(timeout)
                // Exactly one envelope shape: a handler's answer always rides in
                // `result` (JSON text the shim parses), and only a bridge-level failure
                // is an `__error`. Never rewrite a handler's JSON as the envelope itself:
                // that dropped `result` and made `subscribe` resolve undefined.
                val envelope = outcome.fold(
                    onSuccess = { result -> resultEnvelope(id, result) },
                    onFailure = { failure -> errorEnvelope(id, failure.message ?: "push bridge failure") },
                )
                main.post { deliver(webView, id, envelope) }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, shim(normalizedOrigin, mount), setOf(normalizedOrigin)) }
        }
    }

    fun uninstall(webView: WebView) {
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "(function(){ try { delete window.__termLLMNativePush } catch (e) {} })();",
                    null,
                )
            }
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(webView, JS_NAME)
                }
            }
        }
    }

    private fun resultEnvelope(id: String, result: String) =
        JSONObject().put("id", id).put("result", result).toString()

    private fun errorEnvelope(id: String, message: String) =
        JSONObject().put("id", id).put("__error", message).toString()

    private fun errorJson(message: String) = JSONObject().put("__error", message).toString()

    private fun deliver(webView: WebView, id: String, payload: String) {
        val script = "window.__termLLMPushReply && window.__termLLMPushReply(" + js(id) + ", " + js(payload) + ");"
        runCatching { webView.evaluateJavascript(script, null) }
    }

    private fun shim(origin: String, mount: String): String = """
        (() => {
          'use strict';
          // Only the top frame of this exact origin and mount gets the bridge: a
          // framed page from the same origin is not the document the user trusts.
          if (window.top !== window) return;
          if (location.origin !== ${js(origin)}) return;
          const mount = ${js(mount)};
          if (mount !== '/') {
            const bare = mount.endsWith('/') ? mount.slice(0, -1) : mount;
            if (location.pathname !== bare && !location.pathname.startsWith(mount)) return;
          }
          if (!window.$JS_NAME) return;
          const pending = new Map();
          let seq = 0;
          const call = (method, args) => new Promise((resolve, reject) => {
            const bridge = window.$JS_NAME;
            if (!bridge) { reject(new Error('push bridge unavailable')); return; }
            const id = 'p' + (++seq);
            pending.set(id, { resolve, reject });
            bridge.postMessage(JSON.stringify({ id, method, arguments: JSON.stringify(args || {}) }));
          });
          window.__termLLMPushReply = (id, payload) => {
            const entry = pending.get(id);
            if (!entry) return;
            pending.delete(id);
            let reply;
            try { reply = JSON.parse(payload); } catch (e) { entry.reject(new Error('bad reply')); return; }
            if (reply && reply.__error) { entry.reject(new Error(reply.__error)); return; }
            // Native answers are JSON text; hand the page the value it expects.
            const value = reply && reply.result;
            if (typeof value !== 'string') { entry.resolve(value); return; }
            try { entry.resolve(JSON.parse(value)); } catch (e) { entry.resolve(value); }
          };
          const emitChange = (state) => {
            try { window.dispatchEvent(new CustomEvent('termllm-native-push-change', { detail: state })); } catch (e) {}
          };
          const bridge = {
            version: 1,
            permission: (scope) => call('permission', { scope }),
            requestPermission: (scope) => call('requestPermission', { scope }),
            getSubscription: (scope) => call('getSubscription', { scope }),
            subscribe: (scope, applicationServerKey) => call('subscribe', { scope, applicationServerKey }),
            unsubscribe: (scope) => call('unsubscribe', { scope }),
            disable: (scope) => call('disable', { scope }),
            notificationCapabilities: () => call('notificationCapabilities', {}),
            notificationPresence: (payload) => call('notificationPresence', payload),
            notificationRead: (payload) => call('notificationRead', payload),
            notificationEnrollment: (payload) => call('notificationEnrollment', payload),
            notificationPendingCleanup: (payload) => call('notificationPendingCleanup', payload),
            notificationCleanupAcknowledged: (payload) => call('notificationCleanupAcknowledged', payload),
          };
          Object.defineProperty(window, '__termLLMNativePush', { value: Object.freeze(bridge), configurable: true });
          // Announce readiness the same way the iOS shell does.
          try { window.dispatchEvent(new CustomEvent('termllm-native-push-change', { detail: { ready: true } })); } catch (e) {}
        })();
    """.trimIndent()

    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
