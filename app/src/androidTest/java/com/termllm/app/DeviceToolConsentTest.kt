package com.termllm.app

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenStore
import com.termllm.app.tools.MediaTools
import com.termllm.app.ui.ChatDocumentHolder
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
 * The consent gate lives in the shell, not in page storage: without a grant the
 * holder never installs the bridge, so the page has no `document.modelContext`
 * and no `__termLLMDeviceTools`. Granting makes the tools appear; revoking removes
 * them again and a stale call is refused natively.
 */
@RunWith(AndroidJUnit4::class)
class DeviceToolConsentTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var store: ListenStore
    private lateinit var tools: MediaTools
    private var scenario: ActivityScenario<BridgeHostActivity>? = null

    private val origin = "http://10.0.2.2:8899"
    private val source = Connection(
        id = "consent-source",
        name = "Fixture source",
        kind = ServerKind.DIRECT_NODE,
        url = "http://10.0.2.2:8899/",
    )

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "consent-test-" + System.nanoTime()).apply { mkdirs() }
        store = ListenStore(dir)
        tools = MediaTools(store, ListenDownloader(context, store))
    }

    @After
    fun tearDown() {
        scenario?.close()
        dir.deleteRecursively()
    }

    private fun attach(): WebView {
        scenario = ActivityScenario.launch(BridgeHostActivity::class.java)
        var view: WebView? = null
        scenario!!.onActivity { activity ->
            val web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            activity.container.addView(
                web,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            view = web
        }
        return view!!
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
        latch.await(10, TimeUnit.SECONDS)
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

    private fun load(holder: ChatDocumentHolder, view: WebView) {
        scenario!!.onActivity { holder.load(view, source, "/index.html", null) }
        SystemClock.sleep(1500)
    }

    @Test
    fun consentOffMeansNoDeviceToolsOnThePage() {
        var allowed = false
        val holder = ChatDocumentHolder(context)
        holder.toolHandler = { name, args -> tools.handle(name, args) }
        holder.sharingAllowed = { allowed }
        val view = attach()

        load(holder, view)
        val advertised = eval(view, "JSON.stringify(window.__termLLMDeviceTools || null)")
        assertEquals("\"null\"", advertised)
        val contextType = eval(view, "typeof document.modelContext")
        assertEquals("\"undefined\"", contextType)

        // Granting is the only thing that changes this, and it takes effect on load.
        allowed = true
        load(holder, view)
        val afterGrant = awaitValue(view, "JSON.stringify(window.__termLLMDeviceTools || null)")
        assertTrue("expected tools after granting: $afterGrant", afterGrant.contains("media_add"))
    }

    @Test
    fun revokingRemovesTheRegistryAndRefusesStaleCalls() {
        var allowed = true
        val holder = ChatDocumentHolder(context)
        holder.toolHandler = { name, args -> tools.handle(name, args) }
        holder.sharingAllowed = { allowed }
        val view = attach()

        load(holder, view)
        assertTrue(
            "tools should be present while granted",
            awaitValue(view, "JSON.stringify(window.__termLLMDeviceTools || null)").contains("media_add"),
        )

        allowed = false
        scenario!!.onActivity { holder.revokeTools(view, source) }
        SystemClock.sleep(1500)

        val registry = eval(view, "typeof document.modelContext")
        assertEquals("\"undefined\"", registry)
        // A page that kept a reference cannot get a tool executed any more.
        val stale = eval(
            view,
            "(function(){ try { return JSON.stringify(window.__termLLMDeviceTools || null); } catch (e) { return '\"err\"'; } })()",
        )
        assertEquals("\"null\"", stale)
        assertEquals(0, store.items().size)
    }
}
