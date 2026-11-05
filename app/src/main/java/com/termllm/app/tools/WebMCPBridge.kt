package com.termllm.app.tools

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Device tools for the term-llm web UI, matching the iOS `WebMCPBridge` contract:
 *
 *  - the page sees `document.modelContext` (installed here because Android's
 *    WebView has no WebMCP) with the native tool specs already registered;
 *  - `window.__termLLMDeviceTools` advertises `{version, app, device, tools}`;
 *  - a call posts `{name, arguments: JSON.stringify(input)}` to the
 *    `termllmDeviceTools` handler and resolves with the native reply.
 *
 * The listener is restricted to the source's exact origin and the main frame.
 */
object WebMCPBridge {

    const val JS_NAME = "termllmDeviceTools"
    private val executions = java.util.concurrent.atomic.AtomicInteger()
    val debugDispatchCount: Int get() = if (com.termllm.app.BuildConfig.DEBUG) executions.get() else 0
    private const val PREFS = "termllm_tools"

    fun toolSpecs(): String = """
      [
        {"name":"media_add","title":"Add audio","description":"Add audio or video to the Listen queue on this Android phone. Each url must point at the media file itself (mp3, m4a, wav, mp4), not a web page. Give a good title and show: they appear on the lock screen. placement: now plays it, next queues it after the current item, end adds it to the bottom.","inputSchema":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"url":{"type":"string"},"title":{"type":"string"},"show":{"type":"string"},"artwork_url":{"type":"string","description":"Image URL for the lock screen."},"kind":{"type":"string"},"placement":{"type":"string","enum":["now","next","end"]}},"required":["url"]}}},"required":["items"]}},
        {"name":"media_list","title":"List Listen library","description":"List the Listen library: up_next (default), history or all, with ids, progress and download state.","inputSchema":{"type":"object","properties":{"filter":{"type":"string","enum":["up_next","history","all"]},"limit":{"type":"integer"}}}},
        {"name":"media_play","title":"Play media","description":"Play an item from the Listen library, resuming where it was left. Without item, resumes the current or next item.","inputSchema":{"type":"object","properties":{"item":{"type":"string"}}}},
        {"name":"media_control","title":"Control playback","description":"Control playback: pause, resume, stop, seek, skip, next, previous, rate, sleep, sleep_at_end, cancel_sleep, hide_player, show_player.","inputSchema":{"type":"object","properties":{"action":{"type":"string"},"position":{"type":"number"},"delta":{"type":"number"},"rate":{"type":"number"},"minutes":{"type":"number"}},"required":["action"]}},
        {"name":"media_now_playing","title":"Now playing","description":"Report what is playing now with position, duration and speed.","inputSchema":{"type":"object","properties":{}}},
        {"name":"ping","title":"Ping Android phone","description":"Check that the term-llm Android app is reachable. Optionally echoes a message.","inputSchema":{"type":"object","properties":{"message":{"type":"string","description":"Optional text to echo back."}},"additionalProperties":false}},
        {"name":"device_info","title":"Android phone status","description":"Report this Android phone's model, Android version, battery level and charging state, battery saver, and whether the term-llm app is in the foreground.","inputSchema":{"type":"object","properties":{},"additionalProperties":false}},
        {"name":"media_manage","title":"Manage Listen library","description":"Manage items: remove, mark_played, mark_unplayed, archive, move, clear_history, download, cancel_download, delete_download.","inputSchema":{"type":"object","properties":{"action":{"type":"string"},"items":{"type":"array","items":{"type":"string"}},"placement":{"type":"string"}},"required":["action"]}}
      ]
    """.trimIndent()

    fun install(
        webView: WebView,
        origin: String,
        device: String,
        handler: (name: String, arguments: String) -> String,
        guard: ((String, String) -> Unit)? = null,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            android.util.Log.w("TermLLM", "WebMessageListener unsupported: device tools disabled")
            return
        }
        val replies = object : LinkedHashMap<String, Pair<String, String>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 128
        }
        WebViewCompat.addWebMessageListener(webView, JS_NAME, setOf(origin)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != origin.trimEnd('/')) {
                reply.postMessage(errorJson("origin not allowed"))
                return@addWebMessageListener
            }
            val body = message.data ?: return@addWebMessageListener
            if (body.length > 65_536) return@addWebMessageListener reply.postMessage(errorJson("request too large"))
            val request = runCatching { JSONObject(body) }.getOrNull()
                ?: return@addWebMessageListener reply.postMessage(errorJson("invalid request"))
            val name = request.optString("name")
            val args = request.optString("arguments").ifBlank { "{}" }
            val callId = request.optString("id")
            if (callId.isBlank() || callId.length > 160) return@addWebMessageListener reply.postMessage(errorJson("invalid call id"))
            val fingerprint = name + "\u0000" + args
            val payload = runCatching {
                // Validate even a repeated request, so revocation cannot replay a success.
                guard?.invoke(name, args)
                val previous = replies[callId]
                if (previous != null) {
                    require(previous.first == fingerprint) { "call id reused with different arguments" }
                    previous.second
                } else {
                    executions.incrementAndGet()
                    val result = runCatching { handler(name, args) }
                        .getOrElse { errorJson("tool failed: " + (it.message ?: "unknown error")) }
                    replies[callId] = fingerprint to result
                    result
                }
            }.getOrElse { errorJson(it.message ?: "request denied") }
            // One reply transport; the shim resolves the matching promise exactly once.
            deliver(webView, callId, payload)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, shim(origin, device), setOf(origin))
        }
    }

    /**
     * Removes this bridge from a WebView: the message listener stops accepting
     * calls and the page's registry is cleared, so nothing survives a revoke.
     */
    fun uninstall(webView: WebView) {
        webView.post {
            runCatching {
                webView.evaluateJavascript(
                    "(function(){ try { delete window.__termLLMDeviceTools; } catch (e) {}" +
                        " try { delete document.modelContext; } catch (e) {}" +
                        " try { window.termllmDeviceTools && window.termllmDeviceTools.removeEventListener && window.termllmDeviceTools.removeEventListener('message', function(){}) } catch (e) {} })();",
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

    private fun errorJson(message: String) = JSONObject().put("__error", message).toString()

    /** Hands a reply back to the page's pending promise. */
    private fun deliver(webView: WebView, id: String, payload: String) {
        val script = "window.__termLLMReply && window.__termLLMReply(" + js(id) + ", " + js(payload) + ");"
        webView.post { runCatching { webView.evaluateJavascript(script, null) } }
    }

    private fun okJson(id: String, result: String) = JSONObject().put("id", id).put("result", JSONObject(result)).toString()

    private fun shim(origin: String, device: String): String = """
        (() => {
          'use strict';
          const ORIGIN = ${js(origin)};
          if (window.top !== window || location.origin !== ORIGIN) return;
          // Consent lives natively and this script cannot be removed from a WebView,
          // so nothing is created until the native message object exists. Removing the
          // bridge (consent revoked) removes that object, and then this document gets
          // no document.modelContext and no device-tool registry at all.
          const specs = ${toolSpecs()};

          const pending = new Map();
          let seq = 0;
          const documentId = Date.now().toString(36) + Math.random().toString(36).slice(2);
          const invoke = (name, input) => new Promise((resolve, reject) => {
            const id = documentId + ':c' + (++seq);
            const bridge = window.$JS_NAME;
            if (!bridge) { reject(new Error('device tools unavailable')); return; }
            pending.set(id, { resolve, reject });
            bridge.postMessage(JSON.stringify({ id, name, arguments: JSON.stringify(input || {}) }));
          });
          const onMessage = (event) => {
            let reply;
            try { reply = JSON.parse(event.data); } catch (e) { return; }
            const entry = pending.get(reply.id);
            if (!entry) return;
            pending.delete(reply.id);
            if (reply.__error) entry.reject(new Error(reply.__error));
            else entry.resolve(reply);
          };
          window.__termLLMReply = (id, payload) => {
            const entry = pending.get(id);
            if (!entry) return;
            let reply;
            try { reply = JSON.parse(payload); } catch (e) { reply = { __error: 'bad reply' }; }
            pending.delete(id);
            if (reply && reply.__error) entry.reject(new Error(reply.__error));
            else entry.resolve(reply);
          };

          let attached = false;
          const attach = () => {
            const bridge = window.$JS_NAME;
            if (!bridge) return false;
            if (!attached) {
              if (typeof bridge.addEventListener === 'function') bridge.addEventListener('message', onMessage);
              try { bridge.onmessage = onMessage; } catch (e) {}
              attached = true;
            }
            return true;
          };

          let installed = false;
          const install = () => {
            if (installed) return true;
            if (!window.$JS_NAME) return false;
            installed = true;
            let context = document.modelContext || navigator.modelContext;
            if (!context) {
              const tools = new Map();
              const events = new EventTarget();
              const changed = () => events.dispatchEvent(new Event('toolchange'));
              context = {
                async registerTool(tool, options = {}) {
                  if (!tool || typeof tool.name !== 'string' || !tool.name || typeof tool.execute !== 'function') {
                    throw new TypeError('registerTool requires a name and an execute function');
                  }
                  if (tools.has(tool.name)) throw new DOMException('Tool "' + tool.name + '" is already registered', 'InvalidStateError');
                  const signal = options && options.signal;
                  if (signal && signal.aborted) return;
                  tools.set(tool.name, tool);
                  changed();
                  if (signal) signal.addEventListener('abort', () => {
                    if (tools.get(tool.name) === tool) { tools.delete(tool.name); changed(); }
                  }, { once: true });
                },
                async getTools() {
                  return [...tools.values()].map((tool) => ({
                    name: tool.name,
                    title: tool.title || '',
                    description: tool.description || '',
                    inputSchema: tool.inputSchema || { type: 'object', properties: {} },
                    annotations: { ...(tool.annotations || {}) },
                    origin: location.origin,
                  })).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
                },
                async executeTool(tool, input = {}, options = {}) {
                  const name = typeof tool === 'string' ? tool : tool && tool.name;
                  const registered = tools.get(name);
                  if (!registered) throw new DOMException('Unknown tool "' + name + '"', 'NotFoundError');
                  return await registered.execute(input || {}, options || {});
                },
                addEventListener: (...args) => events.addEventListener(...args),
                removeEventListener: (...args) => events.removeEventListener(...args),
                dispatchEvent: (event) => events.dispatchEvent(event),
              };
              Object.defineProperty(document, 'modelContext', { value: context, configurable: true });
            }

            Object.defineProperty(window, '__termLLMDeviceTools', {
              value: Object.freeze({
                version: 1,
                app: 'term-llm Android',
                device: ${js(device)},
                tools: Object.freeze(specs.map((spec) => spec.name)),
              }),
              configurable: true,
            });

            for (const spec of specs) {
              context.registerTool({
                ...spec,
                execute: (input) => invoke(spec.name, input),
              }).catch(() => {});
            }
            return true;
          };

          let tries = 0;
          const timer = setInterval(() => {
            const ready = attach() && install();
            if (ready || ++tries > 200) clearInterval(timer);
          }, 50);
        })();
    """.trimIndent()

    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
