package com.termllm.app

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Minimal probe: can this process evaluate JS in an attached WebView at all? */
@RunWith(AndroidJUnit4::class)
class WebViewEvalProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun evaluateSimpleExpression() {
        val scenario = ActivityScenario.launch(BridgeHostActivity::class.java)
        var web: WebView? = null
        val loaded = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = WebView(activity)
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) = loaded.countDown()
            }
            activity.container.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            view.loadDataWithBaseURL("http://10.0.2.2:8899/", "<html><body>hi</body></html>", "text/html", "utf-8", null)
            web = view
        }
        val ok = loaded.await(20, TimeUnit.SECONDS)
        android.util.Log.i("TermLLMEval", "loaded=" + ok)
        SystemClock.sleep(1000)
        val latches = listOf("main-sync", "post")
        for (mode in latches) {
            val latch = CountDownLatch(1)
            var value = "NO-CALLBACK"
            if (mode == "main-sync") {
                instrumentation.runOnMainSync {
                    web!!.evaluateJavascript("1+1") { v -> value = v; latch.countDown() }
                }
            } else {
                web!!.post { web!!.evaluateJavascript("1+1") { v -> value = v; latch.countDown() } }
            }
            latch.await(8, TimeUnit.SECONDS)
            android.util.Log.i("TermLLMEval", mode + " -> " + value)
        }
        scenario.close()
    }
}
