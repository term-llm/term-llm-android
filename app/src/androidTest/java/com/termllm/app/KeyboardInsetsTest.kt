package com.termllm.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.termllm.app.data.Connection
import com.termllm.app.model.ServerRole
import com.termllm.app.ui.AppViewModel
import com.termllm.app.ui.ProbeResult
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real AppRoot + retained WebView + system IME, not a separately padded test view. */
@RunWith(AndroidJUnit4::class)
class KeyboardInsetsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun composerTracksRealImeWithoutDoubleInsetsOrDocumentReload() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val config = Configurator.getInstance()
        val oldIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 0
        val oldHardwareIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        val documents = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/keyboard/") return MockResponse().setResponseCode(204)
                documents.incrementAndGet()
                return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">
                    <meta name="color-scheme" content="light dark">
                    <style>html,body{margin:0;height:100%}textarea{position:fixed;box-sizing:border-box;bottom:0;left:0;width:100%;height:64px;font-size:18px}</style>
                    <p>Keyboard regression fixture</p><textarea id="draft" placeholder="Unsent draft"></textarea>
                """.trimIndent())
            }
        }
        server.start()
        val fixtureUrl = server.url("/keyboard/")
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        var source: Connection? = null
        fun findWeb(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) {
                findWeb(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        fun js(expression: String): String {
            val latch = CountDownLatch(1)
            var value = "null"
            rule.runOnUiThread {
                val web = findWeb(rule.activity.window.decorView)
                if (web == null) latch.countDown() else web.evaluateJavascript(expression) {
                    value = it ?: "null"; latch.countDown()
                }
            }
            assertTrue("real WebView callback", latch.await(5, TimeUnit.SECONDS))
            return value
        }
        // All coordinates are screen pixels, so compare real Android IME bounds
        // with the actual attached WebView, independent of density/navigation mode.
        fun geometry(): IntArray {
            val result = IntArray(5)
            rule.runOnUiThread {
                val root = rule.activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(root)!!
                val web = findWeb(root)!!
                val origin = IntArray(2)
                web.getLocationOnScreen(origin)
                result[0] = origin[1] + web.height
                result[1] = root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                result[2] = if (insets.isVisible(WindowInsetsCompat.Type.ime())) 1 else 0
                result[3] = web.height
                result[4] = web.width
            }
            return result
        }
        fun tapComposer() {
            val bounds = JSONArray(js("(()=>{const r=document.querySelector('#draft').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth]})()"))
            val origin = IntArray(2)
            var scale = 1.0
            rule.runOnUiThread {
                val web = findWeb(rule.activity.window.decorView)!!
                web.getLocationOnScreen(origin)
                scale = web.width / bounds.getDouble(2)
            }
            assertTrue(device.click(origin[0] + (bounds.getDouble(0) * scale).toInt(),
                origin[1] + (bounds.getDouble(1) * scale).toInt()))
        }
        try {
            rule.runOnIdle {
                source = vm.addConnection("Keyboard fixture", ProbeResult(fixtureUrl, ServerRole.DIRECT_NODE), null)
                vm.openChat(source!!.id, "/keyboard/")
            }
            rule.onNode(hasText("Chat") and isSelectable()).performClick()
            rule.waitUntil(15_000) { js("!!document.querySelector('#draft')") == "true" }
            rule.waitForIdle()
            assertEquals("true", js("window.__keyboardSentinel='retained';true"))
            val original = geometry()
            val requests = documents.get()
            repeat(2) { cycle ->
                tapComposer()
                rule.waitUntil(10_000) { geometry()[2] == 1 }
                // Wait for Android/Compose's IME animation to finish. The regression
                // must fail if no owner resizes the destination OR if it pads twice.
                rule.waitUntil(10_000) { val g = geometry(); abs(g[0] - g[1]) <= 4 }
                val shown = geometry()
                assertTrue("IME must shrink the actual WebView", shown[3] < original[3])
                assertTrue("no second navigation-bar/IME gap: ${shown.contentToString()}", abs(shown[1] - shown[0]) <= 4)
                assertEquals("composer is inside resized visible viewport", "true", js("(()=>{const r=document.querySelector('#draft').getBoundingClientRect();return r.top>=0 && r.bottom<=visualViewport.height+1})()"))
                if (cycle == 0) device.executeShellCommand("input text Keyboard%stest%sdraft")
                assertEquals("\"Keyboard test draft\"", js("document.querySelector('#draft').value"))
                android.util.Log.i("KeyboardInsetsTest", "cycle=$cycle IME top=${shown[1]} web bottom=${shown[0]} height=${shown[3]}")
                device.pressBack()
                rule.waitUntil(10_000) { val g = geometry(); g[2] == 0 && abs(g[3] - original[3]) <= 4 }
                assertEquals("draft survives IME dismissal", "\"Keyboard test draft\"", js("document.querySelector('#draft').value"))
                assertEquals("same document survives", "\"retained\"", js("window.__keyboardSentinel"))
                assertEquals("IME must not reload", requests, documents.get())
                assertEquals("composer returns to viewport bottom", "true", js("Math.abs(document.querySelector('#draft').getBoundingClientRect().bottom-innerHeight)<=1"))
            }
        } finally {
            device.pressBack()
            source?.let { connection -> rule.runOnIdle { vm.removeConnection(connection.id) } }
            server.shutdown()
            if (oldHardwareIme == "null") device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $oldHardwareIme")
            config.waitForIdleTimeout = oldIdle
        }
    }
}
