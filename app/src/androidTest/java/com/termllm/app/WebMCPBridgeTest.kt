package com.termllm.app

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenStore
import com.termllm.app.tools.MediaTools
import com.termllm.app.tools.WebMCPBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The device-tool bridge inside a real, attached Activity window: document-start
 * injection, `document.modelContext` registration, page → native call, native
 * reply resolved back into the page, origin gating and main-frame gating.
 */
@RunWith(AndroidJUnit4::class)
class WebMCPBridgeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var store: ListenStore
    private lateinit var tools: MediaTools
    private var scenario: ActivityScenario<BridgeHostActivity>? = null

    // Served by the container's python http.server (10.0.2.2 = container loopback).
    private val origin = "http://10.0.2.2:8899"

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "bridge-test-" + System.nanoTime()).apply { mkdirs() }
        store = ListenStore(dir)
        tools = MediaTools(store, ListenDownloader(context, store))
    }

    @After
    fun tearDown() {
        scenario?.close()
        dir.deleteRecursively()
    }

    private fun attachWebView(bridgeOrigin: String): WebView {
        scenario = ActivityScenario.launch(BridgeHostActivity::class.java)
        var view: WebView? = null
        val loaded = CountDownLatch(1)
        scenario!!.onActivity { activity ->
            val web = WebView(activity)
            // JavaScript is required for document-start injection and evaluateJavascript
            // (production WebViews enable it in ChatDocumentHolder.create()).
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    android.util.Log.i("TermLLMBridge", "started " + url)
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    android.util.Log.i("TermLLMBridge", "finished " + url)
                    loaded.countDown()
                }
                override fun onReceivedError(
                    v: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    android.util.Log.w("TermLLMBridge", "error " + error?.errorCode + " " + error?.description + " for " + request?.url)
                }
            }
            WebMCPBridge.install(web, bridgeOrigin, "Android phone", { name, args -> tools.handle(name, args) })
            activity.container.addView(
                web,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            android.util.Log.i("TermLLMBridge", "attached w=0 h=0 pending layout")
            web.loadUrl("about:blank")
            web.postDelayed({ web.loadUrl(origin + "/index.html") }, 300)
            view = web
        }
        // A cold WebView renderer can take seconds to produce its first document.
        assertTrue("page never finished loading", loaded.await(30, TimeUnit.SECONDS))
        SystemClock.sleep(800)
        val web = view!!
        instrumentation.runOnMainSync {
            android.util.Log.i("TermLLMBridge", "url=" + web.url + " size=" + web.width + "x" + web.height)
        }
        return web
    }

    private fun eval(view: WebView, script: String): String {
        var result = "null"
        val latch = CountDownLatch(1)
        view.post {
            view.evaluateJavascript(script) { value ->
                result = value ?: "null"
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            android.util.Log.w("TermLLMBridge", "evaluateJavascript timed out for: " + script.take(40))
        }
        return result
    }

    private fun awaitValue(view: WebView, script: String, timeoutMs: Long = 12_000): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = "null"
        while (SystemClock.uptimeMillis() < deadline) {
            last = eval(view, script)
            if (last != "null" && last != "\"\"" && last.isNotBlank()) return last
            SystemClock.sleep(300)
        }
        return last
    }

    @Test
    fun attachedPageSeesBridgeAndCallsNativeMediaAdd() {
        val web = attachWebView(origin)

        val href = awaitValue(web, "location.href")
        assertTrue("page did not load in the attached window: $href", href.contains("index.html"))

        val advertised = awaitValue(web, "JSON.stringify(window.__termLLMDeviceTools || null)")
        assertTrue("page must see __termLLMDeviceTools: $advertised", advertised.contains("media_add"))
        assertTrue("device label must be Android", advertised.contains("Android"))

        // evaluateJavascript does not await promises: stash the async result on window.
        eval(web, "document.modelContext.getTools().then(t => { window.__toolNames = t.map(x => x.name).join(','); }); 'started'")
        val names = awaitValue(web, "window.__toolNames || null")
        assertTrue("native specs must be registered: $names", names.contains("media_add") && names.contains("media_control"))

        eval(
            web,
            """
            window.__bridgeResult = null;
            document.modelContext.executeTool('media_add',
              {items:[{url:'https://example.com/bridge-episode.mp3', title:'Bridge episode', show:'Bridge show', placement:'end'}]})
              .then(r => { window.__bridgeResult = JSON.stringify(r); })
              .catch(e => { window.__bridgeResult = 'ERR:' + e.message; });
            'started'
            """.trimIndent()
        )
        // Diagnostics: what does the page actually see?
        eval(web, """
            window.__dbg = [];
            try {
              window.__dbg.push('ctx=' + (typeof document.modelContext));
              window.__dbg.push('exec=' + (typeof document.modelContext.executeTool));
              window.__dbg.push('bridge=' + (typeof window.termllmDeviceTools));
              window.__dbg.push('post=' + (typeof (window.termllmDeviceTools||{}).postMessage));
              window.__dbg.push('onmsg=' + (typeof (window.termllmDeviceTools||{}).onmessage));
            } catch (e) { window.__dbg.push('ERR ' + e.message); }
            'ok'
        """.trimIndent())
        android.util.Log.i("TermLLMBridge", "dbg=" + awaitValue(web, "window.__dbg ? window.__dbg.join('|') : 'none'"))
        val reply = awaitValue(web, "window.__bridgeResult")
        assertTrue("unexpected bridge reply: $reply", reply.contains("Bridge episode"))
        assertEquals(1, store.items().size)
        assertEquals("Bridge episode", store.items().first().title)
        assertEquals("Bridge show", store.items().first().show)

        // media_control is reachable through the same contract.
        eval(
            web,
            """
            window.__controlResult = null;
            document.modelContext.executeTool('media_now_playing', {})
              .then(r => { window.__controlResult = JSON.stringify(r); })
              .catch(e => { window.__controlResult = 'ERR:' + e.message; });
            'started'
            """.trimIndent()
        )
        val nowPlaying = awaitValue(web, "window.__controlResult")
        assertTrue("now_playing must report player state: $nowPlaying", nowPlaying.contains("playing"))
    }

    @Test
    fun shimLeavesThePageToolsChoiceToThePage() {
        val web = attachWebView(origin)
        // Isolate the persisted fixture origin from earlier runs, then reload so
        // the document-start shim gets a fresh chance to (incorrectly) write it.
        eval(web, "localStorage.removeItem('term_llm_webmcp_default'); 'ok'")
        instrumentation.runOnMainSync { web.reload() }
        SystemClock.sleep(1000)
        // The web UI's per-conversation page-tools opt-in is the user's decision:
        // the shell must not pre-set it just because its tools are available.
        val stored = awaitValue(web, "localStorage.getItem('term_llm_webmcp_default') || 'unset'")
        assertTrue("shell must not write the page-tools default: $stored", stored.contains("unset"))

        eval(web, "localStorage.setItem('term_llm_webmcp_default', 'true'); 'ok'")
        val afterUserChoice = awaitValue(web, "localStorage.getItem('term_llm_webmcp_default')")
        assertTrue("an explicit choice must persist: $afterUserChoice", afterUserChoice.contains("true"))
    }

    @Test
    fun bridgeStaysClosedForAnotherOriginInAnAttachedWindow() {
        val web = attachWebView("http://10.0.2.2:9998")
        val advertised = eval(web, "JSON.stringify(window.__termLLMDeviceTools || null)")
        assertEquals("\"null\"", advertised)
        eval(web, "window.__toolCount = 'no-context'; document.modelContext.getTools().then(t => { window.__toolCount = String(t.length); }); 'started'")
        val toolCount = awaitValue(web, "window.__toolCount || null")
        // Either the shim never ran (no context) or it registered nothing the page can call.
        val trimmed = toolCount.trim('"')
        assertTrue("unexpected tool exposure: $toolCount", trimmed == "no-context" || trimmed == "0")
        assertEquals(0, store.items().size)
    }
}
