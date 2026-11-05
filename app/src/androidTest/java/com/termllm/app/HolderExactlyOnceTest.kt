package com.termllm.app

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.ui.ChatDocumentHolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the holder's REAL production guard wiring, not a directly installed bridge. */
@RunWith(AndroidJUnit4::class)
class HolderExactlyOnceTest {
    private fun eval(view: WebView, js: String): String {
        val latch = CountDownLatch(1); var result = "null"
        view.post { view.evaluateJavascript(js) { result = it ?: "null"; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun waitFor(view: WebView, js: String): String {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { val r = eval(view, js); if (r != "null" && r != "false") return r; SystemClock.sleep(100) }
        error("Page did not settle: " + eval(view, js))
    }
    @Test fun holderDispatchesOnceAndRechecksConsentOnEveryRequest() {
        val server = MockWebServer()
        repeat(10) { server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>bridge fixture</body></html>")) }
        server.start()
        val source = Connection("once-" + System.nanoTime(), "Once fixture", ServerKind.DIRECT_NODE, server.url("/ui/").toString())
        val allowed = AtomicBoolean(true); val calls = AtomicInteger()
        var holder: ChatDocumentHolder? = null; var web: WebView? = null
        val activity = ActivityScenario.launch(BridgeHostActivity::class.java)
        try {
            activity.onActivity { host ->
                holder = ChatDocumentHolder(host).also { h ->
                    h.sharingAllowed = { allowed.get() }
                    h.toolHandler = { _, args -> calls.incrementAndGet(); if (args.contains("fail")) error("expected fixture failure"); "{\"ok\":true}" }
                    web = h.attach(host.container, source, "/ui/index.html", null)
                }
            }
            val view = web!!
            waitFor(view, "!!window.__termLLMDeviceTools")
            eval(view, "window.replies=[];window.__termLLMReply=(id,payload)=>replies.push(JSON.parse(payload));termllmDeviceTools.postMessage(JSON.stringify({id:'one',name:'ping',arguments:'{}'}));true")
            waitFor(view, "replies.length===1")
            assertEquals(1, calls.get()); SystemClock.sleep(200); assertEquals("1", eval(view, "replies.length"))
            // Duplicate transport delivery cannot repeat a side effect.
            eval(view, "termllmDeviceTools.postMessage(JSON.stringify({id:'one',name:'ping',arguments:'{}'}));true")
            waitFor(view, "replies.length===2"); assertEquals(1, calls.get())
            allowed.set(false)
            eval(view, "termllmDeviceTools.postMessage(JSON.stringify({id:'revoked',name:'ping',arguments:'{}'}));true")
            waitFor(view, "replies.length===3"); assertEquals(1, calls.get()); assertTrue(eval(view, "JSON.stringify(replies[2])").contains("__error"))
            allowed.set(true)
            eval(view, "termllmDeviceTools.postMessage(JSON.stringify({id:'failure',name:'ping',arguments:'{\"fail\":true}'}));true")
            waitFor(view, "replies.length===4"); assertEquals(2, calls.get()); assertTrue(eval(view, "JSON.stringify(replies[3])").contains("__error"))
        } finally { activity.onActivity { holder?.destroy() }; activity.close(); server.shutdown() }
    }
}
