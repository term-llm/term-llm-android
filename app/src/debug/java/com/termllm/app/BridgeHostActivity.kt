package com.termllm.app

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Debug-only host used by instrumentation tests that need a *real, attached*
 * window: WebView document-start scripts, WebMessageListener delivery and
 * onPageFinished callbacks only behave realistically inside a live activity.
 */
class BridgeHostActivity : ComponentActivity() {

    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        container.id = CONTAINER_ID
        setContentView(container)
    }

    companion object {
        const val CONTAINER_ID = 0x7f0f0001
    }
}
