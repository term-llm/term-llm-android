package com.termllm.app

import android.content.res.Configuration
import android.util.TypedValue
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

/** Run in both OS modes: native framework and WebView must agree without forcing CSS. */
@RunWith(AndroidJUnit4::class)
class OsThemeTest {
    @Test fun frameworkAndProductionWebViewFollowOs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        InstrumentationRegistry.getArguments().getString("expectedDark")?.let {
            assertEquals("actual OS configuration", it.toBoolean(), dark)
        }
        val server = MockWebServer()
        val response = MockResponse().setHeader("Content-Type", "text/html").setBody("""
            <!doctype html><meta name="color-scheme" content="light dark">
            <style>body { background:white; color:black } @media(prefers-color-scheme:dark) { body { background:rgb(18,18,18); color:white } }</style>
            <body>Server-owned OS theme fixture<input id="draft"></body>
        """.trimIndent())
        val mainDocuments = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path == "/") {
                    mainDocuments.incrementAndGet()
                    response.clone()
                } else MockResponse().setResponseCode(204)
        }
        server.start()
        val source = Connection("theme-test", "Theme fixture", ServerKind.DIRECT_NODE, server.url("/").toString())
        val scenario = ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java))
        var holder: ChatDocumentHolder? = null
        var web: WebView? = null
        var originalActivity: MainActivity? = null
        var webParent: android.view.ViewGroup? = null
        fun setOsDark(value: Boolean) {
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night " + if (value) "yes" else "no").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        }
        fun eval(script: String): String {
            val latch = CountDownLatch(1)
            var value = "null"
            instrumentation.runOnMainSync {
                web!!.evaluateJavascript(script) { value = it; latch.countDown() }
            }
            assertTrue("WebView callback", latch.await(5, TimeUnit.SECONDS))
            return value
        }
        try {
            scenario.onActivity { activity ->
                originalActivity = activity
                val light = TypedValue()
                assertTrue(activity.theme.resolveAttribute(android.R.attr.isLightTheme, light, true))
                android.util.Log.i("OsThemeTest", "OS dark=$dark framework isLightTheme=${light.data != 0}")
                // AppRoot retains the production holder using APPLICATION context.
                val container = android.widget.FrameLayout(activity)
                webParent = container
                activity.addContentView(container, android.view.ViewGroup.LayoutParams(-1, -1))
                holder = ChatDocumentHolder(context).also { web = it.attach(container, source, "/", null) }
            }
            var result = "null"
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            do {
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    web!!.evaluateJavascript("document.body && document.body.textContent.includes('Server-owned') && JSON.stringify([matchMedia('(prefers-color-scheme: dark)').matches,getComputedStyle(document.body).backgroundColor])") {
                        result = it; latch.countDown()
                    }
                }
                assertTrue("WebView callback", latch.await(5, TimeUnit.SECONDS))
                if (result != "false" && result != "null") break
                android.os.SystemClock.sleep(100)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            android.util.Log.i("OsThemeTest", "OS dark=$dark WebView=$result")
            val values = org.json.JSONArray(org.json.JSONTokener(result).nextValue().toString())
            assertEquals("WebView prefers-color-scheme follows OS", dark, values.getBoolean(0))
            assertEquals("server CSS follows OS", if (dark) "rgb(18, 18, 18)" else "rgb(255, 255, 255)", values.getString(1))
            scenario.onActivity { activity ->
                val light = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.isLightTheme, light, true)
                assertEquals("native framework theme follows OS", !dark, light.data != 0)
            }
            assertEquals("true", eval("window.__themeDocumentSentinel = 'retained'; document.querySelector('#draft').value = 'retained draft'; true"))
            val requests = mainDocuments.get()
            for ((hidden, nextDark) in listOf(false to !dark, false to dark, true to !dark, true to dark)) {
                if (hidden) instrumentation.runOnMainSync { webParent!!.removeView(web) }
                setOsDark(nextDark)
                if (hidden) instrumentation.runOnMainSync { webParent!!.addView(web) }
                val end = android.os.SystemClock.uptimeMillis() + 10_000
                while (eval("matchMedia('(prefers-color-scheme: dark)').matches") != nextDark.toString() &&
                    android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(100)
                assertEquals("live WebView media query", nextDark.toString(), eval("matchMedia('(prefers-color-scheme: dark)').matches"))
                assertEquals("document survives OS change", "\"retained\"", eval("window.__themeDocumentSentinel"))
                assertEquals("edited draft survives OS change", "\"retained draft\"", eval("document.querySelector('#draft').value"))
                assertEquals("OS change must not reload the server document", requests, mainDocuments.get())
                val css = eval("getComputedStyle(document.body).backgroundColor")
                assertEquals("live server CSS", if (nextDark) "\"rgb(18, 18, 18)\"" else "\"rgb(255, 255, 255)\"", css)
                scenario.onActivity { activity ->
                    assertSame("uiMode must not recreate the live chat Activity", originalActivity, activity)
                    val light = TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.isLightTheme, light, true)
                    assertEquals("live native framework theme", !nextDark, light.data != 0)
                }
                android.util.Log.i("OsThemeTest", "live OS dark=$nextDark hidden=$hidden retained=true CSS=$css")
            }
        } finally {
            setOsDark(dark)
            scenario.onActivity { holder?.destroy() }
            scenario.close()
            server.shutdown()
        }
    }
}
